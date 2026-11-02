package dev.nexa.youtube

internal data class StreamCandidate(
    val url: String,
    val itag: Int,
    val mimeType: String,
    val codecs: String?,
    val bitrate: Int,
    val averageBitrate: Int,
    val width: Int?,
    val height: Int?,
    val contentLength: Long?,
    val quality: String,
    val qualityLabel: String?,
    val fps: Int?,
    val approxDurationMs: String?,
    val audioSampleRate: Int?,
    val audioChannels: Int?,
    val isAudio: Boolean,
    val isVideo: Boolean,
    val isHls: Boolean = false,
    val isSabr: Boolean = false,
)
