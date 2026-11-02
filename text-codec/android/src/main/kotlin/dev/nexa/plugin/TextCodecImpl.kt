package dev.nexa.plugin

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

public class TextCodecImpl : TextCodecSpec {
    override fun decodeBase64Utf8(encoded: String): String? {
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}
