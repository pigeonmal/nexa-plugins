package dev.nexa.youtube

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale

private data class PlaybackCacheKey(
    val videoId: String,
    val playlistId: String?,
    val audioQuality: AudioQuality,
    val videoQuality: VideoQuality?,
    val isMetered: Boolean,
    val cookieFingerprint: String,
    val visitorDataFingerprint: String,
    val authenticatedOnly: Boolean,
)

private data class CachedPlayback(
    val data: PlaybackData,
    val expiresAtElapsedRealtime: Long,
)

private data class ClientHealthKey(
    val videoId: String,
    val cookieFingerprint: String,
    val visitorDataFingerprint: String,
    val authenticatedOnly: Boolean,
)

private data class FailedClient(
    val clientName: String,
    val expiresAtElapsedRealtime: Long,
)

private data class CachedVisitorData(
    val value: String,
    val expiresAtElapsedRealtime: Long,
)

/**
 * Clean, high-performance YouTube playback extractor inspired by InnerTubeX.
 * Provides anonymous-first extraction with authenticated cookie fallback.
 */
object YoutubeExtractor {
    private const val TAG = "YoutubeExtractor"
    private const val CACHE_EXPIRY_MARGIN_MS = 30_000L
    private const val FAILED_CLIENT_TTL_MS = 5 * 60 * 1000L
    private const val MAX_CACHED_PLAYBACKS = 32
    private const val MAX_FAILED_CLIENTS = 64
    private const val VISITOR_DATA_CACHE_TTL_MS = 30 * 60 * 1000L
    private const val TV_CONFIG_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private const val RANGE_CHUNK_SIZE_BYTES = 10L * 1024L * 1024L
    private const val PUBLIC_WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackCache = object : LinkedHashMap<PlaybackCacheKey, CachedPlayback>(
        MAX_CACHED_PLAYBACKS,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlaybackCacheKey, CachedPlayback>?) =
            size > MAX_CACHED_PLAYBACKS
    }
    private val failedClients = object : LinkedHashMap<ClientHealthKey, FailedClient>(
        MAX_FAILED_CLIENTS,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ClientHealthKey, FailedClient>?) =
            size > MAX_FAILED_CLIENTS
    }
    private val visitorDataCache = HashMap<String, CachedVisitorData>()

    @Volatile private var poTokenGenerator: PoTokenGenerator? = null
    @Volatile private var isConfigured = false
    @Volatile private var isWarmingUp = false
    @Volatile private var tvSignatureTimestamp: Long? = null
    @Volatile private var tvSignatureTimestampExpiresAt = 0L

    private val VIDEO_ID_PATTERN = Regex("^[a-zA-Z0-9_-]{11}$")
    private val PLAYLIST_ID_PATTERN = Regex("^[a-zA-Z0-9_-]{2,}$")

    @Synchronized
    fun configure(context: Context) {
        if (!isConfigured) {
            YoutubeNetwork.configure(context.applicationContext)
            poTokenGenerator = PoTokenGenerator(context.applicationContext)
            isConfigured = true
        }
    }

    fun warmUp() {
        if (isWarmingUp) return
        isWarmingUp = true
        warmUpScope.launch {
            runCatching {
                resolveTvSignatureTimestamp()
                val visitorData = fetchVisitorData("dQw4w9WgXcQ", null)
                if (!visitorData.isNullOrBlank()) {
                    poTokenGenerator?.prewarm(visitorData, null)
                }
            }
        }
    }

    fun peekCache(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        videoQuality: VideoQuality?,
        isMetered: Boolean,
        cookie: String?,
        forceVisitorData: String?,
        authenticatedOnly: Boolean = false,
    ): PlaybackData? {
        val normalizedVideoId = videoId.trim()
        if (!VIDEO_ID_PATTERN.matches(normalizedVideoId)) return null
        val normalizedCookie = normalizeCookie(cookie)
        val normalizedVisitorData = forceVisitorData?.trim().takeUnless { it.isNullOrEmpty() }

        val cacheKey = PlaybackCacheKey(
            videoId = normalizedVideoId,
            playlistId = playlistId,
            audioQuality = audioQuality,
            videoQuality = videoQuality,
            isMetered = isMetered,
            cookieFingerprint = fingerprint(normalizedCookie),
            visitorDataFingerprint = fingerprint(normalizedVisitorData),
            authenticatedOnly = authenticatedOnly,
        )

        return getCachedPlayback(cacheKey)?.copy(extractionDurationMs = 0.0)
    }

    fun extract(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        videoQuality: VideoQuality?,
        isMetered: Boolean,
        cookie: String?,
        forceVisitorData: String?,
        authenticatedOnly: Boolean = false,
        bypassCache: Boolean = false,
        targetClientName: String? = null,
    ): PlaybackData = runBlocking(Dispatchers.IO) {
        extractAsync(
            videoId = videoId,
            playlistId = playlistId,
            audioQuality = audioQuality,
            videoQuality = videoQuality,
            isMetered = isMetered,
            cookie = cookie,
            forceVisitorData = forceVisitorData,
            authenticatedOnly = authenticatedOnly,
            bypassCache = bypassCache,
            targetClientName = targetClientName,
        )
    }

    fun extractWithClient(
        clientName: String,
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality = AudioQuality.AUTO,
        videoQuality: VideoQuality? = null,
        cookie: String? = null,
        forceVisitorData: String? = null,
    ): PlaybackData = extract(
        videoId = videoId,
        playlistId = playlistId,
        audioQuality = audioQuality,
        videoQuality = videoQuality,
        isMetered = false,
        cookie = cookie,
        forceVisitorData = forceVisitorData,
        bypassCache = true,
        targetClientName = clientName,
    )

    suspend fun extractAsync(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        videoQuality: VideoQuality?,
        isMetered: Boolean,
        cookie: String?,
        forceVisitorData: String?,
        authenticatedOnly: Boolean = false,
        bypassCache: Boolean = false,
        targetClientName: String? = null,
    ): PlaybackData {
        val startedAtNanos = System.nanoTime()
        val normalizedVideoId = videoId.trim()
        require(VIDEO_ID_PATTERN.matches(normalizedVideoId)) { "Invalid YouTube video ID" }
        val normalizedCookie = normalizeCookie(cookie)
        val normalizedVisitorData = forceVisitorData?.trim().takeUnless { it.isNullOrEmpty() }
        require(!authenticatedOnly || !normalizedCookie.isNullOrBlank()) {
            "Authenticated YouTube extraction requires a cookie"
        }

        val cacheKey = PlaybackCacheKey(
            videoId = normalizedVideoId,
            playlistId = playlistId,
            audioQuality = audioQuality,
            videoQuality = videoQuality,
            isMetered = isMetered,
            cookieFingerprint = fingerprint(normalizedCookie),
            visitorDataFingerprint = fingerprint(normalizedVisitorData),
            authenticatedOnly = authenticatedOnly,
        )

        // Fix for "Sometimes JS total duration is < than native duration":
        // On cache hit, return the actual elapsed time of this call (<1ms) instead of stale cold duration
        if (!bypassCache && targetClientName.isNullOrBlank()) {
            getCachedPlayback(cacheKey)?.let { cached ->
                val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000.0
                return cached.copy(extractionDurationMs = elapsedMs)
            }
        }

        val excluded = failedClientsFor(cacheKey)
        val clients = if (!targetClientName.isNullOrBlank()) {
            val all = ClientCatalog.anonymousClients + ClientCatalog.authenticatedClients
            val target = targetClientName.trim()
            val found = when {
                target.equals("VISIONOS_0_1", ignoreCase = true) -> listOf(VisionOsClient.VISIONOS_0_1)
                target.equals("VISIONOS", ignoreCase = true) -> listOf(VisionOsClient.VISIONOS)
                target.equals("ANDROID_VR_1_65_10", ignoreCase = true) -> listOf(AndroidVrClient.ANDROID_VR_1_65_10)
                target.equals("ANDROID_VR_1_61_48", ignoreCase = true) -> listOf(AndroidVrClient.ANDROID_VR_1_61_48)
                target.equals("ANDROID_VR_1_43_32", ignoreCase = true) -> listOf(AndroidVrClient.ANDROID_VR_1_43_32)
                target.equals("ANDROID_VR", ignoreCase = true) -> listOf(
                    AndroidVrClient.ANDROID_VR_1_65_10,
                    AndroidVrClient.ANDROID_VR_1_61_48,
                    AndroidVrClient.ANDROID_VR_1_43_32,
                )
                target.equals("ANDROID", ignoreCase = true) -> listOf(AndroidClient.ANDROID)
                target.equals("IPADOS", ignoreCase = true) -> listOf(IosClient.IPADOS)
                target.equals("IOS", ignoreCase = true) -> listOf(IosClient.IOS)
                target.equals("TVHTML5_SIMPLY", ignoreCase = true) -> listOf(TvSimplyClient.TVHTML5_SIMPLY)
                target.equals("TVHTML5_EMBEDDED", ignoreCase = true) -> listOf(TvSimplyClient.TVHTML5_EMBEDDED)
                target.equals("TVHTML5", ignoreCase = true) -> listOf(TvHtml5Client.TVHTML5)
                target.equals("TVHTML5_DOWNGRADED", ignoreCase = true) -> listOf(TvDowngradedClient.TVHTML5_DOWNGRADED)
                target.equals("WEB_REMIX", ignoreCase = true) -> listOf(WebRemixClient.WEB_REMIX)
                target.equals("WEB", ignoreCase = true) -> listOf(WebClient.WEB)
                target.equals("MWEB", ignoreCase = true) -> listOf(MWebClient.MWEB)
                else -> all.filter {
                    it.clientName.equals(target, ignoreCase = true) ||
                    (it.friendlyName?.replace(" ", "_")?.replace(".", "_")?.equals(target, ignoreCase = true) == true) ||
                    (it.friendlyName?.equals(target, ignoreCase = true) == true)
                }
            }
            if (found.isEmpty()) throw IllegalArgumentException("Unknown YouTube client: $targetClientName")
            found
        } else {
            ClientCatalog.getClients(normalizedCookie, authenticatedOnly, excluded)
        }
        var lastError: Throwable? = null

        for (client in clients) {
            val clientStart = System.nanoTime()
            try {
                val requestVisitorData = normalizedVisitorData ?: fetchVisitorData(normalizedVideoId, normalizedCookie)

                val needsWebPoTokens = (client.requirePoToken || client.recommendPoToken || client.useWebPoTokens) && client.useWebPoTokens
                val poTokens = if (needsWebPoTokens) {
                    val visitor = requestVisitorData ?: fetchVisitorData(normalizedVideoId, normalizedCookie)
                    if (visitor != null) {
                        try {
                            poTokenGenerator?.getWebClientPoToken(
                                normalizedVideoId,
                                visitor,
                                normalizedCookie,
                                client.poTokenBinding,
                            )
                        } catch (e: Throwable) {
                            if (client.requirePoToken) throw e
                            null
                        }
                    } else null
                } else null

                if (client.requirePoToken && poTokens == null && poTokenGenerator != null) {
                    throw IllegalStateException("YouTube ${client.clientName} requires visitorData/PoToken")
                }

                val sigTimestamp = if (client.useSignatureTimestamp) resolveTvSignatureTimestamp() else null

                val parsed = PlayerRequest.execute(
                    client = client,
                    videoId = normalizedVideoId,
                    playlistId = playlistId,
                    cookie = if (client.loginSupported) normalizedCookie else null,
                    visitorData = requestVisitorData,
                    poToken = poTokens?.playerRequestPoToken,
                    signatureTimestamp = sigTimestamp,
                )

                parsed.visitorData?.let { cacheVisitorData(normalizedCookie, it) }

                val audioCandidate = StreamSelector.selectAudio(parsed.candidates, audioQuality)
                    ?: throw IllegalStateException("${client.clientName} returned no playable audio stream")

                val videoCandidate = videoQuality?.let { vq ->
                    StreamSelector.selectVideo(parsed.candidates, vq, isMetered)
                        ?: throw IllegalStateException("${client.clientName} returned no video for $vq")
                }

                val totalElapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000.0
                val playback = toPlaybackData(
                    client = client,
                    parsed = parsed,
                    audio = audioCandidate,
                    video = videoCandidate,
                    totalElapsedMs = totalElapsedMs,
                    streamingDataPoToken = poTokens?.streamingDataPoToken,
                )

                if (targetClientName.isNullOrBlank()) {
                    clearSuccessfulClient(cacheKey, client.clientName)
                    cachePlayback(cacheKey, playback)
                }
                try { Log.d(TAG, "${client.clientName} succeeded in ${elapsedMillis(clientStart)}ms; total=${totalElapsedMs.toInt()}ms") } catch (_: Throwable) {}
                return playback
            } catch (error: Throwable) {
                lastError = error
                try { Log.w(TAG, "${client.clientName} failed in ${elapsedMillis(clientStart)}ms: ${error.message}") } catch (_: Throwable) {}
            }
        }

        throw lastError ?: IllegalStateException("No YouTube playback client returned a stream")
    }

    fun generatePoToken(
        videoId: String,
        cookie: String? = null,
        preferredVisitorData: String? = null,
    ): String = runBlocking(Dispatchers.IO) {
        generatePoTokenAsync(videoId, cookie, preferredVisitorData)
    }

    suspend fun generatePoTokenAsync(
        videoId: String,
        cookie: String? = null,
        preferredVisitorData: String? = null,
    ): String {
        val visitorData = preferredVisitorData?.trim().takeUnless { it.isNullOrEmpty() }
            ?: fetchVisitorData(videoId, cookie)
            ?: throw IllegalStateException("Unable to obtain YouTube visitor data")
        return checkNotNull(poTokenGenerator) {
            "YoutubeExtractor must be configured before generating a PoToken"
        }.getWebClientPoToken(videoId, visitorData, normalizeCookie(cookie))
            ?.playerRequestPoToken
            ?: throw IllegalStateException("WebView BotGuard token generation is unavailable")
    }

    fun recordPlaybackFailure(
        videoId: String,
        clientName: String,
        cookie: String? = null,
        visitorData: String? = null,
        authenticatedOnly: Boolean = false,
    ) {
        val key = ClientHealthKey(
            videoId = videoId.trim(),
            cookieFingerprint = fingerprint(normalizeCookie(cookie)),
            visitorDataFingerprint = fingerprint(visitorData?.trim()),
            authenticatedOnly = authenticatedOnly,
        )
        synchronized(failedClients) {
            failedClients[key] = FailedClient(clientName, monotonicNowMillis() + FAILED_CLIENT_TTL_MS)
        }
        clearCachedPlayback(videoId)
    }

    fun clearCachedPlayback(videoId: String? = null) {
        synchronized(playbackCache) {
            if (videoId == null) {
                playbackCache.clear()
            } else {
                playbackCache.entries.removeIf { it.key.videoId == videoId }
            }
        }
    }

    fun hasSupportedAuthCookie(cookie: String?): Boolean =
        normalizeCookie(cookie)?.split(';')?.any { pair ->
            val name = pair.substringBefore('=', "").trim()
            val value = pair.substringAfter('=', "").trim()
            value.isNotEmpty() && (name == "SAPISID" || name == "__Secure-3PAPISID")
        } == true

    fun isYoutubeBotChallenge(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            val message = current.message?.lowercase(Locale.ROOT).orEmpty()
            if (
                message.contains("sign in to confirm") ||
                message.contains("confirm you're not a bot") ||
                message.contains("confirm you’re not a bot") ||
                message.contains("not a bot")
            ) return true
            current = current.cause
        }
        return false
    }

    private fun toPlaybackData(
        client: YouTubeClient,
        parsed: ParsedPlayerResponse,
        audio: StreamCandidate,
        video: StreamCandidate?,
        totalElapsedMs: Double,
        streamingDataPoToken: String?,
    ): PlaybackData {
        val audioHeaders = buildStreamHeaders(client)
        val audioStream = StreamPlayback(
            format = toStreamFormat(audio, parsed.audioConfig),
            streamUrl = audio.url.withStreamingPoToken(streamingDataPoToken),
            requestHeaders = audioHeaders,
            rangeChunkSizeBytes = RANGE_CHUNK_SIZE_BYTES,
            isHls = audio.isHls,
            isSabr = audio.isSabr,
            sabrUstreamerConfig = parsed.sabrBootstrap?.videoPlaybackUstreamerConfig,
        )
        val videoStream = video?.let { v ->
            StreamPlayback(
                format = toStreamFormat(v, null),
                streamUrl = v.url.withStreamingPoToken(streamingDataPoToken),
                requestHeaders = buildStreamHeaders(client),
                rangeChunkSizeBytes = RANGE_CHUNK_SIZE_BYTES,
                isHls = v.isHls,
                isSabr = v.isSabr,
                sabrUstreamerConfig = parsed.sabrBootstrap?.videoPlaybackUstreamerConfig,
            )
        }

        return PlaybackData(
            audioConfig = parsed.audioConfig,
            videoDetails = parsed.videoDetails,
            playbackTracking = parsed.playbackTracking,
            streamExpiresInSeconds = parsed.expiresInSeconds,
            audioStream = audioStream,
            videoStream = videoStream,
            clientName = client.clientName,
            extractionDurationMs = totalElapsedMs,
            poTokenDurationMs = null,
            sabrStreamingUrl = parsed.sabrBootstrap?.serverAbrStreamingUrl,
        )
    }

    private fun toStreamFormat(candidate: StreamCandidate, audioConfig: AudioConfig?): StreamFormat =
        StreamFormat(
            itag = candidate.itag,
            mimeType = candidate.mimeType,
            bitrate = candidate.bitrate,
            width = candidate.width,
            height = candidate.height,
            contentLength = candidate.contentLength,
            quality = candidate.quality,
            fps = candidate.fps,
            qualityLabel = candidate.qualityLabel,
            approxDurationMs = candidate.approxDurationMs,
            audioSampleRate = candidate.audioSampleRate,
            audioChannels = candidate.audioChannels,
            loudnessDb = audioConfig?.loudnessDb,
        )

    private fun buildStreamHeaders(client: YouTubeClient): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = client.userAgent
        if (client.useMusicPlayerEndpoint) {
            headers["Origin"] = "https://music.youtube.com"
            headers["Referer"] = "https://music.youtube.com/"
        } else {
            headers["Origin"] = "https://www.youtube.com"
            headers["Referer"] = "https://www.youtube.com/"
        }
        return headers
    }

    private fun String.withStreamingPoToken(streamingPoToken: String?): String {
        if (streamingPoToken.isNullOrBlank()) return this
        return runCatching {
            val uri = Uri.parse(this)
            if (uri.getQueryParameter("pot") != null) this
            else uri.buildUpon().appendQueryParameter("pot", streamingPoToken).build().toString()
        }.getOrDefault(this)
    }

    private suspend fun fetchVisitorData(videoId: String, cookie: String?): String? {
        val cacheKey = fingerprint(normalizeCookie(cookie))
        synchronized(visitorDataCache) {
            visitorDataCache[cacheKey]?.let { cached ->
                if (cached.expiresAtElapsedRealtime > monotonicNowMillis()) return cached.value
                visitorDataCache.remove(cacheKey)
            }
        }

        // Fast InnerTubeX strategy: sw.js_data
        val fastResult = VisitorDataFetcher.fetchVisitorData(PUBLIC_WEB_USER_AGENT)
        if (!fastResult.isNullOrBlank()) {
            cacheVisitorData(cookie, fastResult)
            return fastResult
        }

        // Fallback: fast player call with visionOS
        return try {
            PlayerRequest.execute(
                client = ClientCatalog.anonymousClients.first(),
                videoId = videoId,
                playlistId = null,
                cookie = null,
                visitorData = null,
                poToken = null,
            ).visitorData?.also { cacheVisitorData(cookie, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private fun cacheVisitorData(cookie: String?, visitorData: String) {
        val value = visitorData.trim().takeIf { it.isNotEmpty() } ?: return
        synchronized(visitorDataCache) {
            visitorDataCache[fingerprint(normalizeCookie(cookie))] = CachedVisitorData(
                value,
                monotonicNowMillis() + VISITOR_DATA_CACHE_TTL_MS,
            )
        }
    }

    private suspend fun resolveTvSignatureTimestamp(): Long? {
        val now = monotonicNowMillis()
        if (tvSignatureTimestamp != null && tvSignatureTimestampExpiresAt > now) {
            return tvSignatureTimestamp
        }
        val resolved = try {
            val headers = mapOf("User-Agent" to "Mozilla/5.0")
            val body = YoutubeNetwork.text("https://www.youtube.com/iframe_api", headers)
                .replace("\\/", "/")
            val playerId = Regex("""/s/player/([a-zA-Z0-9_-]+)/""").find(body)?.groupValues?.getOrNull(1)
            val script = playerId?.let {
                YoutubeNetwork.text(
                    "https://www.youtube.com/s/player/$it/player_ias.vflset/en_US/base.js",
                    headers,
                    maxResponseBytes = 16L * 1024L * 1024L,
                )
            }
            script?.let { Regex("""signatureTimestamp:(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        if (resolved != null) {
            tvSignatureTimestamp = resolved
            tvSignatureTimestampExpiresAt = now + TV_CONFIG_CACHE_TTL_MS
            return resolved
        }
        return (tvSignatureTimestamp ?: 20697L).also {
            if (tvSignatureTimestamp == null) {
                tvSignatureTimestamp = it
                tvSignatureTimestampExpiresAt = now + TV_CONFIG_CACHE_TTL_MS
            }
        }
    }

    private fun getCachedPlayback(key: PlaybackCacheKey): PlaybackData? {
        val now = monotonicNowMillis()
        synchronized(playbackCache) {
            val cached = playbackCache[key] ?: return null
            if (cached.expiresAtElapsedRealtime > now + CACHE_EXPIRY_MARGIN_MS) {
                return cached.data
            }
            playbackCache.remove(key)
            return null
        }
    }

    private fun cachePlayback(key: PlaybackCacheKey, data: PlaybackData) {
        synchronized(playbackCache) {
            playbackCache[key] = CachedPlayback(
                data = data,
                expiresAtElapsedRealtime = monotonicNowMillis() + (data.streamExpiresInSeconds * 1000L),
            )
        }
    }

    private fun failedClientsFor(key: PlaybackCacheKey): Set<String> {
        val healthKey = ClientHealthKey(
            videoId = key.videoId,
            cookieFingerprint = key.cookieFingerprint,
            visitorDataFingerprint = key.visitorDataFingerprint,
            authenticatedOnly = key.authenticatedOnly,
        )
        val now = monotonicNowMillis()
        synchronized(failedClients) {
            val failed = failedClients[healthKey] ?: return emptySet()
            if (failed.expiresAtElapsedRealtime > now) return setOf(failed.clientName)
            failedClients.remove(healthKey)
            return emptySet()
        }
    }

    private fun clearSuccessfulClient(key: PlaybackCacheKey, clientName: String) {
        val healthKey = ClientHealthKey(
            videoId = key.videoId,
            cookieFingerprint = key.cookieFingerprint,
            visitorDataFingerprint = key.visitorDataFingerprint,
            authenticatedOnly = key.authenticatedOnly,
        )
        synchronized(failedClients) {
            failedClients.remove(healthKey)
        }
    }

    private fun normalizeCookie(cookie: String?): String? = cookie
        ?.split(';')
        ?.asSequence()
        ?.mapNotNull { pair ->
            val trimmed = pair.trim()
            val separator = trimmed.indexOf('=')
            if (separator <= 0) null else trimmed
        }
        ?.joinToString("; ")
        ?.takeIf { it.isNotEmpty() }

    private fun fingerprint(value: String?): String =
        value?.takeIf { it.isNotBlank() }?.let { sha256(it) } ?: "none"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun monotonicNowMillis(): Long = System.nanoTime() / 1_000_000L
    private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L
}
