package dev.nexa.youtube

import org.json.JSONObject
import java.security.SecureRandom

internal object PlayerRequest {
    private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    private val SECURE_RANDOM = SecureRandom()
    private const val NONCE_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    suspend fun execute(
        client: YouTubeClient,
        videoId: String,
        playlistId: String?,
        cookie: String?,
        visitorData: String?,
        poToken: String?,
        signatureTimestamp: Long? = null,
    ): ParsedPlayerResponse {
        val requestBody = JSONObject().apply {
            put("videoId", videoId)
            put("context", client.buildContext(visitorData))
            put("contentCheckOk", true)
            put("racyCheckOk", true)

            if (!client.useMusicPlayerEndpoint) {
                put("videoCheckOk", true)
            }

            playlistId?.takeIf { it.isNotBlank() }?.let {
                put("playlistId", it)
            }

            if (client.useSignatureTimestamp || client.isEmbedded || signatureTimestamp != null) {
                val contentPlaybackContext = JSONObject()
                if (!client.useMusicPlayerEndpoint) {
                    contentPlaybackContext.put("html5Preference", "HTML5_PREF_WANTS")
                }
                if (client.useSignatureTimestamp && signatureTimestamp != null) {
                    contentPlaybackContext.put("signatureTimestamp", signatureTimestamp)
                }
                val playbackContext = JSONObject()
                playbackContext.put("contentPlaybackContext", contentPlaybackContext)
                put("playbackContext", playbackContext)
            }

            if (client.isEmbedded) {
                val thirdParty = JSONObject()
                thirdParty.put("embedUrl", "https://www.youtube.com/embed/$videoId")
                put("thirdParty", thirdParty)
            }

            poToken?.takeIf { it.isNotBlank() }?.let {
                val dimensions = JSONObject()
                dimensions.put("poToken", it)
                put("serviceIntegrityDimensions", dimensions)
            }
        }

        val (origin, referer) = when {
            client.useMusicPlayerEndpoint || client.clientName == "WEB_REMIX" ->
                "https://music.youtube.com" to "https://music.youtube.com/"
            client.clientName == "MWEB" ->
                "https://m.youtube.com" to "https://m.youtube.com/"
            else ->
                "https://www.youtube.com" to "https://www.youtube.com/"
        }
        val requestReferer = if (client.isEmbedded) "https://www.reddit.com/" else referer

        val headers = linkedMapOf(
            "Content-Type" to JSON_MEDIA_TYPE,
            "Accept" to "*/*",
            "User-Agent" to client.userAgent,
            "X-Goog-Api-Format-Version" to "1",
            "X-YouTube-Client-Name" to client.clientId,
            "X-YouTube-Client-Version" to client.clientVersion,
            "Origin" to origin,
            "X-Origin" to origin,
            "Referer" to requestReferer,
            "Accept-Language" to "en-US,en;q=0.9",
        )

        visitorData?.takeIf { it.isNotBlank() }?.let {
            headers["X-Goog-Visitor-Id"] = it
        }
        if (client.loginSupported && !cookie.isNullOrBlank()) {
            headers["Cookie"] = cookie
            headers["X-Goog-AuthUser"] = "0"
            val sapisid = extractSapisid(cookie)
            if (!sapisid.isNullOrBlank()) {
                val currentTime = System.currentTimeMillis() / 1000
                val hash = sha1("$currentTime $sapisid $origin")
                headers["Authorization"] = "SAPISIDHASH ${currentTime}_$hash"
            }
        }

        val response = YoutubeNetwork.fetch(
            url = client.playerEndpoint(),
            method = "POST",
            headers = headers,
            body = requestBody.toString().toByteArray(Charsets.UTF_8),
            timeoutMillis = 25_000L,
            maxResponseBytes = 8L * 1024L * 1024L,
        )
        if (response.statusCode !in 200..299) {
            throw IllegalStateException(
                "YouTube ${client.clientName} HTTP ${response.statusCode}: ${response.text.take(200)}",
            )
        }
        return PlayerResponseParser.parse(client, response.text)
    }

    private fun extractSapisid(cookie: String): String? {
        val match = Regex("""(?:^|;\s*)(?:__Secure-3PAPISID|SAPISID)=([^;]+)""").find(cookie)
        return match?.groupValues?.get(1)
    }

    private fun sha1(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
