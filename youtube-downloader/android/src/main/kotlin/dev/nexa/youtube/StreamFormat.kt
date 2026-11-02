package dev.nexa.youtube

data class StreamFormat(
    val itag: Int,
    val mimeType: String,
    val bitrate: Int,
    val width: Int?,
    val height: Int?,
    val contentLength: Long?,
    val quality: String,
    val fps: Int?,
    val qualityLabel: String?,
    val approxDurationMs: String?,
    val audioSampleRate: Int?,
    val audioChannels: Int?,
    val loudnessDb: Double?,
)
