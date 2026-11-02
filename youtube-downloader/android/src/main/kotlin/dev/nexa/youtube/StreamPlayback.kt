package dev.nexa.youtube

data class StreamPlayback(
    val format: StreamFormat,
    val streamUrl: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val rangeChunkSizeBytes: Long = 0L,
    val isHls: Boolean = false,
    val isSabr: Boolean = false,
    val sabrUstreamerConfig: String? = null,
)
