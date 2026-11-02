package dev.nexa.youtube

data class PlaybackData(
    val audioConfig: AudioConfig?,
    val videoDetails: VideoDetails?,
    val playbackTracking: PlaybackTracking?,
    val streamExpiresInSeconds: Int,
    val audioStream: StreamPlayback,
    val videoStream: StreamPlayback?,
    val clientName: String,
    val extractionDurationMs: Double? = null,
    val poTokenDurationMs: Double? = null,
    val sabrStreamingUrl: String? = null,
)
