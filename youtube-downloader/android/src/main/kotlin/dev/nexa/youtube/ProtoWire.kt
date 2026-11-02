package dev.nexa.youtube

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Lightweight, zero-dependency Protobuf wire-format encoder and decoder.
 * Supports Varint, Length-Delimited, 32-bit, and 64-bit fields required by YouTube UMP/SABR.
 */
class ProtoWriter {
    private val buffer = ByteArrayOutputStream()

    fun writeVarint(value: Long): ProtoWriter {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                buffer.write(v.toInt())
                return this
            } else {
                buffer.write(((v.toInt() and 0x7F) or 0x80))
                v = v ushr 7
            }
        }
    }

    fun writeTag(fieldNumber: Int, wireType: Int): ProtoWriter {
        writeVarint(((fieldNumber shl 3) or wireType).toLong())
        return this
    }

    fun writeInt32(fieldNumber: Int, value: Int): ProtoWriter {
        writeTag(fieldNumber, WIRE_TYPE_VARINT)
        writeVarint(value.toLong())
        return this
    }

    fun writeInt64(fieldNumber: Int, value: Long): ProtoWriter {
        writeTag(fieldNumber, WIRE_TYPE_VARINT)
        writeVarint(value)
        return this
    }

    fun writeBool(fieldNumber: Int, value: Boolean): ProtoWriter {
        writeTag(fieldNumber, WIRE_TYPE_VARINT)
        writeVarint(if (value) 1L else 0L)
        return this
    }

    fun writeString(fieldNumber: Int, value: String): ProtoWriter {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint(bytes.size.toLong())
        buffer.write(bytes)
        return this
    }

    fun writeBytes(fieldNumber: Int, value: ByteArray): ProtoWriter {
        writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint(value.size.toLong())
        buffer.write(value)
        return this
    }

    fun writeMessage(fieldNumber: Int, message: ProtoWriter): ProtoWriter {
        val bytes = message.toByteArray()
        writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED)
        writeVarint(bytes.size.toLong())
        buffer.write(bytes)
        return this
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()

    companion object {
        const val WIRE_TYPE_VARINT = 0
        const val WIRE_TYPE_64BIT = 1
        const val WIRE_TYPE_LENGTH_DELIMITED = 2
        const val WIRE_TYPE_32BIT = 5
    }
}

class ProtoReader(private val bytes: ByteArray) {
    private var offset = 0

    fun hasMore(): Boolean = offset < bytes.size

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (offset < bytes.size && shift < 64) {
            val b = bytes[offset++].toLong()
            result = result or ((b and 0x7FL) shl shift)
            if ((b and 0x80L) == 0L) return result
            shift += 7
        }
        return result
    }

    fun readTag(): Pair<Int, Int>? {
        if (!hasMore()) return null
        val tag = readVarint().toInt()
        val wireType = tag and 0x07
        val fieldNumber = tag ushr 3
        return fieldNumber to wireType
    }

    fun readBytes(): ByteArray {
        val length = readVarint().toInt()
        val end = (offset + length).coerceAtMost(bytes.size)
        val chunk = bytes.copyOfRange(offset, end)
        offset = end
        return chunk
    }

    fun readString(): String = String(readBytes(), StandardCharsets.UTF_8)

    fun skipField(wireType: Int) {
        when (wireType) {
            ProtoWriter.WIRE_TYPE_VARINT -> readVarint()
            ProtoWriter.WIRE_TYPE_64BIT -> offset = (offset + 8).coerceAtMost(bytes.size)
            ProtoWriter.WIRE_TYPE_LENGTH_DELIMITED -> {
                val len = readVarint().toInt()
                offset = (offset + len).coerceAtMost(bytes.size)
            }
            ProtoWriter.WIRE_TYPE_32BIT -> offset = (offset + 4).coerceAtMost(bytes.size)
            else -> offset = bytes.size
        }
    }

    companion object {
        fun readVarint(input: InputStream): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                val byteRead = input.read()
                if (byteRead == -1) break
                result = result or ((byteRead.toLong() and 0x7FL) shl shift)
                if ((byteRead and 0x80) == 0) return result
                shift += 7
            }
            return result
        }
    }
}
