package dev.nexa.youtube

import android.util.Base64

/**
 * Encodes SABR requests (VideoPlaybackAbrRequest) and decodes UMP media parts.
 */
object SabrProtoCodec {

    /**
     * Decodes a UMP MediaHeader (Part 20) payload into a SabrMediaHeader object.
     */
    fun decodeMediaHeader(payload: ByteArray): SabrMediaHeader {
        val reader = ProtoReader(payload)
        var itag = 0
        var videoId: String? = null
        var sequenceNumber = 0L
        var isInitSegment = false
        var contentLength = 0L
        var startDataRange = 0L

        while (reader.hasMore()) {
            val (fieldNumber, wireType) = reader.readTag() ?: break
            when (fieldNumber) {
                1 -> videoId = reader.readString()
                2 -> itag = reader.readVarint().toInt()
                4 -> sequenceNumber = reader.readVarint()
                5 -> startDataRange = reader.readVarint()
                6 -> isInitSegment = reader.readVarint() != 0L
                7 -> contentLength = reader.readVarint()
                else -> reader.skipField(wireType)
            }
        }

        return SabrMediaHeader(
            itag = itag,
            videoId = videoId,
            sequenceNumber = sequenceNumber,
            isInitSegment = isInitSegment,
            contentLength = contentLength,
            startDataRange = startDataRange,
        )
    }

    /**
     * Builds a binary SABR request payload (VideoPlaybackAbrRequest) for an itag.
     */
    fun buildAbrRequest(
        selectedItag: Int,
        ustreamerConfigBase64: String?,
        poToken: String?,
        sequenceNumber: Long = 0L,
        bufferedMediaTimeMs: Long = 0L,
    ): ByteArray {
        val root = ProtoWriter()

        // Field 1: ClientAbrState message
        val abrState = ProtoWriter()
            .writeInt32(1, selectedItag)
            .writeInt64(2, bufferedMediaTimeMs)
            .writeInt64(3, sequenceNumber)
        root.writeMessage(1, abrState)

        // Field 2: Raw ustreamer config bytes
        if (!ustreamerConfigBase64.isNullOrBlank()) {
            val ustreamerBytes = runCatching {
                Base64.decode(ustreamerConfigBase64, Base64.DEFAULT)
            }.getOrNull()
            if (ustreamerBytes != null && ustreamerBytes.isNotEmpty()) {
                root.writeBytes(2, ustreamerBytes)
            }
        }

        // Field 3: PoToken integrity
        if (!poToken.isNullOrBlank()) {
            root.writeString(3, poToken)
        }

        return root.toByteArray()
    }

    /**
     * Demuxes a sequence of UMP parts into sequential media chunks.
     */
    fun demuxParts(parts: Iterable<SabrPart>): List<SabrChunk> {
        val chunks = mutableListOf<SabrChunk>()
        var currentHeader: SabrMediaHeader? = null

        for (part in parts) {
            when (part.type) {
                SabrPart.TYPE_MEDIA_HEADER -> {
                    currentHeader = runCatching { decodeMediaHeader(part.payload) }.getOrNull()
                }
                SabrPart.TYPE_MEDIA -> {
                    chunks.add(SabrChunk(currentHeader, part.payload, isEnd = false))
                }
                SabrPart.TYPE_MEDIA_END -> {
                    chunks.add(SabrChunk(currentHeader, ByteArray(0), isEnd = true))
                }
            }
        }

        return chunks
    }
}
