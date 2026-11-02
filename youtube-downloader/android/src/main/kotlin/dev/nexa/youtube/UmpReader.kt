package dev.nexa.youtube

import java.io.EOFException
import java.io.InputStream

/**
 * Parses binary Universal Media Protocol (UMP) streams into discrete SabrPart objects.
 */
object UmpReader {

    /**
     * Reads a single UMP part from an input stream. Returns null at end-of-stream.
     */
    fun readPart(input: InputStream): SabrPart? {
        val firstByte = input.read()
        if (firstByte == -1) return null

        // Parse partType varint with the first byte already consumed
        var partType = (firstByte and 0x7F).toLong()
        var shift = 7
        if ((firstByte and 0x80) != 0) {
            while (shift < 32) {
                val b = input.read()
                if (b == -1) throw EOFException("Unexpected EOF reading UMP part type")
                partType = partType or ((b.toLong() and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
        }

        // Parse payloadLength varint
        val payloadLength = ProtoReader.readVarint(input)
        if (payloadLength < 0 || payloadLength > 100 * 1024 * 1024) {
            throw IllegalStateException("Invalid UMP payload length: $payloadLength")
        }

        val payload = ByteArray(payloadLength.toInt())
        var totalRead = 0
        while (totalRead < payload.size) {
            val count = input.read(payload, totalRead, payload.size - totalRead)
            if (count == -1) {
                throw EOFException("Unexpected EOF reading UMP payload: expected ${payload.size}, got $totalRead")
            }
            totalRead += count
        }

        return SabrPart(partType.toInt(), payload)
    }

    /**
     * Parses all UMP parts from an in-memory byte buffer.
     */
    fun parseParts(bytes: ByteArray): List<SabrPart> {
        val input = bytes.inputStream()
        val parts = mutableListOf<SabrPart>()
        while (true) {
            val part = readPart(input) ?: break
            parts.add(part)
        }
        return parts
    }
}
