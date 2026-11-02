package dev.nexa.youtube

import android.content.Context
import dev.nexa.core.NexaNetwork
import org.chromium.net.CronetEngine
import java.util.concurrent.Executor

/** Routes extractor requests through the same app-wide Cronet engine as Nexa HTTP. */
internal object YoutubeNetwork {
    @Volatile
    private var applicationContext: Context? = null

    fun configure(context: Context) {
        applicationContext = context.applicationContext
    }

    fun cronetEngine(): CronetEngine = NexaNetwork.cronetEngine(requireContext())

    fun cronetExecutor(): Executor = NexaNetwork.cronetExecutor()

    suspend fun fetch(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMillis: Long = 30_000L,
        maxResponseBytes: Long = 16L * 1024L * 1024L,
    ): dev.nexa.core.NexaNetworkResponse {
        val context = checkNotNull(applicationContext) {
            "YoutubeNetwork must be configured before making requests"
        }
        return NexaNetwork.fetch(
            context = context,
            url = url,
            method = method,
            body = body,
            headers = headers,
            timeoutMillis = timeoutMillis,
            cachePolicy = "Default",
            followRedirects = true,
            maxResponseBytes = maxResponseBytes,
        )
    }

    suspend fun text(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxResponseBytes: Long = 16L * 1024L * 1024L,
    ): String {
        val response = fetch(url, headers = headers, maxResponseBytes = maxResponseBytes)
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("YouTube HTTP ${response.statusCode}")
        }
        return response.text
    }

    private fun requireContext(): Context = checkNotNull(applicationContext) {
        "YoutubeNetwork must be configured before making requests"
    }
}
