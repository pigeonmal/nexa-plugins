package dev.nexa.youtube

import dev.nexa.core.NexaRuntimeCore

/** Typed adapter around Mazica's shared YouTube stream resolver. */
public class YoutubeDownloaderImpl : YoutubeDownloaderSpec {
    private var disposed = false

    init {
        val context = NexaRuntimeCore.context().applicationContext
        YoutubeNetwork.configure(context)
        YoutubeExtractor.configure(context)
    }

    override suspend fun extractYoutubeStream(options: YoutubeExtractStreamOptions): YoutubePlaybackData {
        checkActive()
        val videoID = options.videoID.trim()
        if (!VIDEO_ID_PATTERN.matches(videoID)) throw YoutubeDownloaderError.invalidVideoID
        if (options.authenticatedOnly && options.cookie.isNullOrBlank()) {
            throw YoutubeDownloaderError.cookieRequired
        }

        val quality = when (options.audioQuality) {
            YoutubeAudioQuality.high -> AudioQuality.HIGH
            YoutubeAudioQuality.low -> AudioQuality.LOW
            YoutubeAudioQuality.auto -> AudioQuality.AUTO
        }
        val videoQuality = options.videoQuality?.let(VideoQuality::fromHeight)
        val playback = try {
            YoutubeExtractor.extractAsync(
                videoId = videoID,
                playlistId = options.playlistID,
                audioQuality = quality,
                videoQuality = videoQuality,
                isMetered = false,
                cookie = options.cookie,
                forceVisitorData = options.visitorData,
                authenticatedOnly = options.authenticatedOnly,
                targetClientName = options.clientName,
            )
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val message = error.message ?: "YouTube stream extraction failed"
            if (message.contains("no playable audio", ignoreCase = true)) {
                throw YoutubeDownloaderError.noPlayableAudio(options.clientName ?: "automatic")
            }
            throw YoutubeDownloaderError.invalidResponse(message)
        }
        return playback.toNexaPlaybackData()
    }

    override suspend fun generatePoToken(videoID: String, cookie: String?, visitorData: String?): String {
        checkActive()
        val normalizedID = videoID.trim()
        if (!VIDEO_ID_PATTERN.matches(normalizedID)) throw YoutubeDownloaderError.invalidVideoID
        return try {
            YoutubeExtractor.generatePoTokenAsync(normalizedID, cookie, visitorData)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw YoutubeDownloaderError.tokenUnavailable(error.message ?: "PoToken generation failed")
        }
    }

    override fun reportPlaybackFailure(videoID: String, clientName: String, cookie: String?, visitorData: String?) {
        if (!disposed) YoutubeExtractor.recordPlaybackFailure(videoID, clientName, cookie, visitorData)
    }

    override fun clearCache(videoID: String?) {
        if (!disposed) YoutubeExtractor.clearCachedPlayback(videoID)
    }

    override fun dispose() {
        disposed = true
    }

    private fun checkActive() {
        if (disposed) throw YoutubeDownloaderError.invalidResponse("YoutubeDownloader is disposed")
    }

    private companion object {
        val VIDEO_ID_PATTERN = Regex("^[A-Za-z0-9_-]{11}$")
    }
}

private fun dev.nexa.youtube.PlaybackData.toNexaPlaybackData() = YoutubePlaybackData(
    audioConfig = audioConfig?.let { YoutubeAudioConfig(it.loudnessDb, it.perceptualLoudnessDb) },
    videoDetails = videoDetails?.let {
        YoutubeVideoDetails(it.videoId, it.title, it.author, it.channelId, it.lengthSeconds, it.musicVideoType, it.viewCount)
    },
    playbackTracking = playbackTracking?.let {
        YoutubePlaybackTracking(it.videostatsPlaybackUrl, it.videostatsWatchtimeUrl, it.atrUrl)
    },
    streamExpiresInSeconds = streamExpiresInSeconds,
    audioStream = audioStream.toNexaStreamPlayback(),
    videoStream = videoStream?.toNexaStreamPlayback(),
    clientName = clientName,
    extractionDurationMs = extractionDurationMs ?: 0.0,
    poTokenDurationMs = poTokenDurationMs,
    sabrStreamingURL = sabrStreamingUrl,
)

private fun dev.nexa.youtube.StreamPlayback.toNexaStreamPlayback() = YoutubeStreamPlayback(
    format = YoutubeStreamFormat(
        itag = format.itag,
        mimeType = format.mimeType,
        bitrate = format.bitrate.toLong(),
        width = format.width,
        height = format.height,
        contentLength = format.contentLength,
        quality = format.quality,
        fps = format.fps,
        qualityLabel = format.qualityLabel,
        audioSampleRate = format.audioSampleRate,
        audioChannels = format.audioChannels,
        loudnessDb = format.loudnessDb,
    ),
    streamURL = streamUrl,
    requestHeaders = requestHeaders,
    rangeChunkSizeBytes = rangeChunkSizeBytes,
    isHLS = isHls,
    isSABR = isSabr,
    sabrUstreamerConfig = sabrUstreamerConfig,
)
