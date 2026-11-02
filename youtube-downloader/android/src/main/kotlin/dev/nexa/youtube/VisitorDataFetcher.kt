package dev.nexa.youtube

import org.json.JSONArray
import org.json.JSONTokener
import kotlinx.coroutines.CancellationException

/**
 * High-performance visitorData fetcher based on InnerTubeX's sw.js_data strategy.
 * Fetches visitorData in <100ms without doing a heavy player API extraction.
 */
object VisitorDataFetcher {
    private const val VISITOR_DATA_URL = "https://www.youtube.com/sw.js_data"
    private const val FALLBACK_MUSIC_URL = "https://music.youtube.com"
    private val VISITOR_DATA_REGEX = Regex(""""(?:VISITOR_DATA|visitorData)"\s*:\s*"([^"]+)"""")

    suspend fun fetchVisitorData(userAgent: String): String? {
        // Fast path: sw.js_data
        val fastResult = try {
            val response = YoutubeNetwork.fetch(
                url = VISITOR_DATA_URL,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json,text/plain,*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                ),
                maxResponseBytes = 2L * 1024L * 1024L,
            )
            if (response.statusCode in 200..299) parseServiceWorkerVisitorData(response.text) else null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
        if (!fastResult.isNullOrBlank()) return fastResult

        // Fallback path: music.youtube.com regex scrape
        return try {
            val response = YoutubeNetwork.fetch(
                url = FALLBACK_MUSIC_URL,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9",
                ),
                maxResponseBytes = 8L * 1024L * 1024L,
            )
            if (response.statusCode in 200..299) {
                VISITOR_DATA_REGEX.find(response.text)?.groupValues?.getOrNull(1)
            } else null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseServiceWorkerVisitorData(responseText: String): String? = runCatching {
        val jsonText = responseText.substringAfter('\n').trimStart()
        val tokener = JSONTokener(jsonText)
        val root = JSONArray(tokener)
        // Structure: root[0][2][0][0][13] is visitorData string
        val arr0 = root.getJSONArray(0)
        val arr2 = arr0.getJSONArray(2)
        val inner0 = arr2.getJSONArray(0)
        val item0 = inner0.getJSONArray(0)
        val visitorData = item0.getString(13)
        visitorData.takeIf { it.isNotBlank() }
    }.getOrNull()
}
