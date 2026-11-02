package dev.nexa.plugin

import android.content.Context
import dev.nexa.core.NexaNetwork
import java.io.File
import java.net.URI
import java.util.Locale

/** Materializes finite audio HLS playlists through Nexa's shared Cronet engine. */
internal class HlsPackageDownloader(private val context: Context) {
    data class Result(val playlist: File, val receivedBytes: Long)

    suspend fun download(
        sourceUrl: String,
        destination: File,
        headers: Map<String, String>,
        onProgress: (Long) -> Unit,
    ): Result {
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw IllegalStateException("HLS destination is unavailable")
        }
        val state = DownloadState()
        val playlist = materialize(sourceUrl, destination, headers, state, depth = 0, onProgress)
        return Result(playlist, state.receivedBytes)
    }

    private suspend fun materialize(
        sourceUrl: String,
        destination: File,
        headers: Map<String, String>,
        state: DownloadState,
        depth: Int,
        onProgress: (Long) -> Unit,
    ): File {
        require(depth <= MAX_PLAYLIST_DEPTH) { "HLS playlist nesting is too deep" }
        val playlistResponse = NexaNetwork.fetch(
            context = context,
            url = sourceUrl,
            headers = headers,
            timeoutMillis = PLAYLIST_TIMEOUT_MS,
            cachePolicy = "Reload",
            maxResponseBytes = MAX_PLAYLIST_BYTES,
        )
        require(playlistResponse.statusCode in 200..299) { "HLS playlist HTTP ${playlistResponse.statusCode}" }
        val lines = playlistResponse.text.trimStart('\uFEFF').lines()
        require(lines.firstOrNull()?.trim() == "#EXTM3U") { "The HLS response is not a playlist" }

        val variant = selectAudioVariant(lines, sourceUrl)
        if (variant != null) {
            return materialize(variant, destination, headers, state, depth + 1, onProgress)
        }
        require(lines.any { it.trim() == "#EXT-X-ENDLIST" }) { "Live HLS playlists cannot be saved for offline playback" }

        var pendingRange: String? = null
        val rangeTracker = ByteRangeTracker()
        val outputLines = ArrayList<String>(lines.size)
        var resourceIndex = 0
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-PART:") || trimmed.startsWith("#EXT-X-PRELOAD-HINT:") ||
                    trimmed.startsWith("#EXT-X-SKIP:") || trimmed.startsWith("#EXT-X-RENDITION-REPORT:") ->
                    throw IllegalStateException("Low-latency or delta HLS playlists are not supported")
                trimmed.startsWith("#EXT-X-BYTERANGE:") -> {
                    pendingRange = trimmed.substringAfter(':').trim()
                }
                trimmed.startsWith("#EXT-X-KEY:") || trimmed.startsWith("#EXT-X-SESSION-KEY:") -> {
                    val method = attribute(trimmed, "METHOD")?.uppercase(Locale.ROOT)
                    if (method != null && method != "NONE" && method != "AES-128") {
                        throw IllegalStateException("Encrypted HLS method $method is not supported")
                    }
                    val uri = attribute(trimmed, "URI")
                    if (uri == null) {
                        outputLines.add(line)
                    } else {
                        val remote = resolve(sourceUrl, uri)
                        val localName = resourceName(++resourceIndex, extensionFor(remote, "key"))
                        downloadResource(remote, File(destination, localName), headers, null)
                        state.receivedBytes += File(destination, localName).length()
                        onProgress(state.receivedBytes)
                        outputLines.add(replaceUri(line, localName))
                    }
                }
                trimmed.startsWith("#EXT-X-MAP:") -> {
                    val uri = attribute(trimmed, "URI")
                        ?: throw IllegalStateException("HLS initialization map has no URI")
                    val remote = resolve(sourceUrl, uri)
                    val range = attribute(trimmed, "BYTERANGE")
                    val byteRange = range?.let { rangeTracker.resolve(it, remote) }
                    val localName = resourceName(++resourceIndex, extensionFor(remote, "mp4"))
                    val target = File(destination, localName)
                    downloadResource(remote, target, headers, byteRange)
                    byteRange?.let { require(target.length() == it.length) { "HLS initialization range was not honored" } }
                    state.receivedBytes += target.length()
                    onProgress(state.receivedBytes)
                    var rewritten = replaceUri(line, localName)
                    if (byteRange != null) rewritten = replaceAttribute(rewritten, "BYTERANGE", "${byteRange.length}@0")
                    outputLines.add(rewritten)
                }
                trimmed.isEmpty() || trimmed.startsWith("#") -> outputLines.add(line)
                else -> {
                    val remote = resolve(sourceUrl, trimmed)
                    val byteRange = pendingRange?.let { rangeTracker.resolve(it, remote) }
                    pendingRange = null
                    val localName = resourceName(++resourceIndex, extensionFor(remote, "bin"))
                    val target = File(destination, localName)
                    downloadResource(remote, target, headers, byteRange)
                    byteRange?.let { require(target.length() == it.length) { "HLS segment range was not honored" } }
                    state.receivedBytes += target.length()
                    onProgress(state.receivedBytes)
                    outputLines.add(localName)
                }
            }
        }
        require(pendingRange == null) { "HLS byte range has no media segment" }
        val localPlaylist = File(destination, "index.m3u8")
        localPlaylist.writeText(outputLines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        return localPlaylist
    }

    private suspend fun downloadResource(
        sourceUrl: String,
        destination: File,
        headers: Map<String, String>,
        byteRange: ByteRange?,
    ) {
        val requestHeaders = headers.toMutableMap()
        if (byteRange != null) {
            requestHeaders["Range"] = "bytes=${byteRange.start}-${byteRange.start + byteRange.length - 1}"
            requestHeaders["Accept-Encoding"] = "identity"
        }
        val completed = NexaNetwork.download(
            context = context,
            url = sourceUrl,
            destinationPath = destination.absolutePath,
            headers = requestHeaders,
            timeoutMillis = RESOURCE_TIMEOUT_MS,
            cachePolicy = "Reload",
            maxResponseBytes = MAX_RESOURCE_BYTES,
        )
        require(completed && destination.isFile) { "HLS resource download failed" }
    }

    private fun selectAudioVariant(lines: List<String>, sourceUrl: String): String? {
        val audioRenditions = lines.asSequence()
            .filter { it.trim().startsWith("#EXT-X-MEDIA:") }
            .filter { attribute(it, "TYPE")?.equals("AUDIO", ignoreCase = true) == true }
            .mapNotNull { line -> attribute(line, "URI")?.let { line to it } }
            .toList()
        if (audioRenditions.isNotEmpty()) {
            val selected = audioRenditions.firstOrNull { attribute(it.first, "DEFAULT")?.equals("YES", true) == true }
                ?: audioRenditions.first()
            return resolve(sourceUrl, selected.second)
        }

        val variants = ArrayList<Variant>()
        for (index in lines.indices) {
            val line = lines[index].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val uri = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") } ?: continue
            val codecs = attribute(line, "CODECS").orEmpty().lowercase(Locale.ROOT)
            val audioOnly = listOf("mp4a", "opus", "vorbis").any(codecs::contains) &&
                listOf("avc", "vp9", "av01", "hev1", "hvc1").none(codecs::contains)
            variants.add(Variant(
                url = resolve(sourceUrl, uri.trim()),
                audioOnly = audioOnly,
                bandwidth = attribute(line, "AVERAGE-BANDWIDTH")?.toLongOrNull()
                    ?: attribute(line, "BANDWIDTH")?.toLongOrNull() ?: 0L,
            ))
        }
        return variants.maxWithOrNull(compareBy<Variant> { it.audioOnly }.thenBy { it.bandwidth })?.url
    }

    private fun resolve(baseUrl: String, reference: String): String {
        val resolved = URI(baseUrl).resolve(reference)
        require(resolved.scheme == "https" || resolved.scheme == "http") { "Unsupported HLS resource URL" }
        return resolved.toString()
    }

    private fun attribute(line: String, name: String): String? {
        val pattern = Regex("(?:^|[:,])\\s*${Regex.escape(name)}=(?:\"([^\"]*)\"|([^,]*))", RegexOption.IGNORE_CASE)
        val match = pattern.find(line) ?: return null
        return (match.groups[1]?.value ?: match.groups[2]?.value)?.trim()
    }

    private fun replaceUri(line: String, localName: String): String =
        Regex("URI=\"[^\"]*\"", RegexOption.IGNORE_CASE).replaceFirst(line, "URI=\"$localName\"")

    private fun replaceAttribute(line: String, name: String, value: String): String =
        Regex("(?<=[,:])\\s*${Regex.escape(name)}=(?:\"[^\"]*\"|[^,]*)", RegexOption.IGNORE_CASE)
            .replaceFirst(line, "$name=$value")

    private fun extensionFor(url: String, fallback: String): String {
        val candidate = runCatching { URI(url).path.substringAfterLast('.').lowercase(Locale.ROOT) }.getOrDefault("")
        return candidate.takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) } ?: fallback
    }

    private fun resourceName(index: Int, extension: String): String =
        "resource-${index.toString().padStart(6, '0')}.$extension"

    private data class Variant(val url: String, val audioOnly: Boolean, val bandwidth: Long)
    private data class ByteRange(val length: Long, val start: Long)

    private class DownloadState {
        var receivedBytes: Long = 0L
    }

    private class ByteRangeTracker {
        private var lastUrl: String? = null
        private var nextOffset = 0L

        fun resolve(value: String, url: String): ByteRange {
            val parts = value.trim().split('@', limit = 2)
            val length = parts.first().toLongOrNull()?.takeIf { it > 0L }
                ?: throw IllegalStateException("Invalid HLS byte range")
            val start = if (parts.size == 2) {
                parts[1].toLongOrNull()?.takeIf { it >= 0L }
                    ?: throw IllegalStateException("Invalid HLS byte range offset")
            } else {
                require(lastUrl == url) { "HLS byte range omitted its initial offset" }
                nextOffset
            }
            lastUrl = url
            nextOffset = start + length
            return ByteRange(length, start)
        }
    }

    private companion object {
        const val MAX_PLAYLIST_DEPTH = 4
        const val MAX_PLAYLIST_BYTES = 4L * 1024L * 1024L
        const val MAX_RESOURCE_BYTES = 128L * 1024L * 1024L
        const val PLAYLIST_TIMEOUT_MS = 30_000L
        const val RESOURCE_TIMEOUT_MS = 120_000L
    }
}
