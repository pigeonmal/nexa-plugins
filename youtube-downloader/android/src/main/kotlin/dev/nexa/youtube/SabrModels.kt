package dev.nexa.youtube

/**
 * Information needed to initiate a SABR streaming session with YouTube.
 */
data class SabrBootstrap(
    val serverAbrStreamingUrl: String,
    val videoPlaybackUstreamerConfig: String?,
    val candidateItags: List<Int>,
    val durationMs: Long?,
)

/**
 * Parsed UMP Part representation.
 */
data class SabrPart(
    val type: Int,
    val payload: ByteArray,
) {
    companion object {
        const val TYPE_ONESIE_HEADER = 10
        const val TYPE_MEDIA_HEADER = 20
        const val TYPE_MEDIA = 21
        const val TYPE_MEDIA_END = 22
        const val TYPE_LIVE_METADATA = 31
        const val TYPE_NEXT_REQUEST_POLICY = 36
        const val TYPE_STREAM_PROTECTION_STATUS = 37
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SabrPart) return false
        return type == other.type && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Metadata extracted from a UMP MEDIA_HEADER (Part 20).
 */
data class SabrMediaHeader(
    val itag: Int,
    val videoId: String? = null,
    val sequenceNumber: Long = 0L,
    val isInitSegment: Boolean = false,
    val contentLength: Long = 0L,
    val startDataRange: Long = 0L,
)

/**
 * Media segment or chunk emitted during SABR stream demuxing.
 */
data class SabrChunk(
    val header: SabrMediaHeader?,
    val data: ByteArray,
    val isEnd: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SabrChunk) return false
        return header == other.header && data.contentEquals(other.data) && isEnd == other.isEnd
    }

    override fun hashCode(): Int {
        var result = header?.hashCode() ?: 0
        result = 31 * result + data.contentHashCode()
        result = 31 * result + isEnd.hashCode()
        return result
    }
}
