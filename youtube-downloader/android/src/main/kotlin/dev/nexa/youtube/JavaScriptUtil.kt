package dev.nexa.youtube

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Converts YouTube's BotGuard challenge into the object expected by the local JS runner. */
internal fun parseChallengeData(raw: String): String {
    val scrambled = JSONArray(raw)
    val challenge = if (scrambled.length() > 1 && scrambled.opt(1) is String) {
        JSONArray(descramble(scrambled.getString(1)))
    } else {
        scrambled.optJSONArray(0) ?: throw PoTokenException("Invalid BotGuard challenge")
    }

    val interpreterJavascript = JSONObject()
    val safe = firstString(challenge.optJSONArray(1))
    val trusted = firstString(challenge.optJSONArray(2))
    interpreterJavascript.put(
        "privateDoNotAccessOrElseSafeScriptWrappedValue",
        safe ?: JSONObject.NULL,
    )
    interpreterJavascript.put(
        "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue",
        trusted ?: JSONObject.NULL,
    )

    return JSONObject().apply {
        put("messageId", challenge.getString(0))
        put("interpreterJavascript", interpreterJavascript)
        put("interpreterHash", challenge.getString(3))
        put("program", challenge.getString(4))
        put("globalName", challenge.getString(5))
        put("clientExperimentsStateBlob", challenge.getString(7))
    }.toString()
}

internal fun parseIntegrityTokenData(raw: String): Pair<String, Long> {
    val data = JSONArray(raw)
    return base64ToU8(data.getString(0)) to data.getLong(1)
}

internal fun stringToU8(identifier: String): String =
    "new Uint8Array([" + identifier.toByteArray().joinToString(",") { (it.toInt() and 0xff).toString() } + "])"

internal fun u8ToBase64(poToken: String): String {
    val bytes = poToken.split(',').filter { it.isNotBlank() }.map { it.toInt().toByte() }.toByteArray()
    // This is the URL-safe representation expected by YouTube's web token API:
    // URL-safe Base64 without line breaks or padding.
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private fun firstString(array: JSONArray?): String? {
    if (array == null) return null
    for (index in 0 until array.length()) {
        val value = array.opt(index)
        if (value is String) return value
    }
    return null
}

private fun descramble(value: String): String {
    val bytes = base64Bytes(value).map { (it.toInt() + 97).toByte() }.toByteArray()
    return bytes.toString(Charsets.UTF_8)
}

private fun base64ToU8(value: String): String =
    "new Uint8Array([" + base64Bytes(value).joinToString(",") { (it.toInt() and 0xff).toString() } + "])"

private fun base64Bytes(value: String): ByteArray {
    val normalized = value.replace('-', '+').replace('_', '/').replace('.', '=')
    return runCatching { Base64.decode(normalized, Base64.DEFAULT) }
        .getOrElse { throw PoTokenException("Cannot decode BotGuard data") }
}
