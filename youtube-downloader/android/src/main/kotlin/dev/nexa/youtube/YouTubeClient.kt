package dev.nexa.youtube

import org.json.JSONObject

/**
 * YouTube InnerTube client profile representation matching InnerTubeX specification.
 */
data class YouTubeClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null,
    val platform: String? = null,
    val friendlyName: String? = null,
    val loginSupported: Boolean = false,
    val loginRequired: Boolean = false,
    val useSignatureTimestamp: Boolean = false,
    val isEmbedded: Boolean = false,
    val useWebPoTokens: Boolean = false,
    val requirePoToken: Boolean = false,
    val recommendPoToken: Boolean = false,
    val poTokenBinding: PoTokenBinding = PoTokenBinding.VIDEO_ID,
    val includeUserAgentInContext: Boolean = false,
    val useMusicPlayerEndpoint: Boolean = false,
    val skipPlayerResponseValidation: Boolean = false,
) {
    fun playerEndpoint(): String =
        when {
            useMusicPlayerEndpoint || clientName == "WEB_REMIX" ->
                "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
            clientName == "MWEB" ->
                "https://m.youtube.com/youtubei/v1/player?prettyPrint=false"
            else ->
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        }

    fun buildContext(visitorData: String?): JSONObject {
        val clientJson = JSONObject().apply {
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            put("hl", "en")
            put("gl", "US")
            visitorData?.takeIf { it.isNotBlank() }?.let { put("visitorData", it) }
            if (includeUserAgentInContext) {
                put("userAgent", userAgent)
            }
            osName?.let { put("osName", it) }
            osVersion?.let { put("osVersion", it) }
            deviceMake?.let { put("deviceMake", it) }
            deviceModel?.let { put("deviceModel", it) }
            androidSdkVersion?.let { put("androidSdkVersion", it) }
            platform?.let { put("platform", it) }
        }

        val context = JSONObject()
        context.put("client", clientJson)
        if (isEmbedded) {
            val thirdParty = JSONObject()
            thirdParty.put("embedUrl", "https://www.google.com")
            context.put("thirdParty", thirdParty)
        }
        return context
    }
}
