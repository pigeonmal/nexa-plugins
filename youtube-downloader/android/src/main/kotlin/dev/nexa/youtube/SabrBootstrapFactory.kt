package dev.nexa.youtube

import org.json.JSONObject

/**
 * Extracts SABR bootstrap parameters from an InnerTube player response.
 */
object SabrBootstrapFactory {

    fun fromPlayerResponse(root: JSONObject): SabrBootstrap? {
        val streamingData = root.optJSONObject("streamingData") ?: return null
        val serverAbrStreamingUrl = streamingData.optString("serverAbrStreamingUrl")
            .takeIf { it.isNotBlank() } ?: return null

        val ustreamerConfig = root.optJSONObject("playerConfig")
            ?.optJSONObject("mediaCommonConfig")
            ?.optJSONObject("mediaUstreamerRequestConfig")
            ?.optString("videoPlaybackUstreamerConfig")
            ?.takeIf { it.isNotBlank() }

        val candidateItags = mutableListOf<Int>()
        val formats = streamingData.optJSONArray("adaptiveFormats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val itag = f.optInt("itag")
                if (itag > 0) candidateItags.add(itag)
            }
        }

        val durationSeconds = root.optJSONObject("videoDetails")
            ?.optString("lengthSeconds")
            ?.toLongOrNull()
        val durationMs = durationSeconds?.times(1000L)

        return SabrBootstrap(
            serverAbrStreamingUrl = serverAbrStreamingUrl,
            videoPlaybackUstreamerConfig = ustreamerConfig,
            candidateItags = candidateItags,
            durationMs = durationMs,
        )
    }
}
