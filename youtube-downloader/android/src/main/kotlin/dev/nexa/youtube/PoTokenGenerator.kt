package dev.nexa.youtube

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The two visitor-bound tokens used by YouTube's web playback clients. */
internal data class WebPoTokenResult(
    val playerRequestPoToken: String,
    val streamingDataPoToken: String,
)

/**
 * Owns one short-lived BotGuard WebView and mints visitor-bound tokens.
 *
 * The first token minted for a WebView session is the streaming-data token. A
 * video-bound token is generated only after that token exists, matching the
 * token binding expected by YouTube's TVHTML5_SIMPLY and WEB profiles.
 */
internal class PoTokenGenerator(context: Context) {
    private val applicationContext = context.applicationContext
    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private val webViewBadImplementation = AtomicBoolean(false)
    private val lock = Mutex()
    private var sessionKey: String? = null
    private var streamingPoToken: String? = null
    private var webView: PoTokenWebView? = null
    private var syncedCookieNames: Set<String> = emptySet()
    private val playerTokenCache = LinkedHashMap<String, CachedToken>(MAX_CACHED_PLAYER_TOKENS)
    private val mintLock = Mutex()

    suspend fun getWebClientPoToken(
        videoId: String,
        visitorData: String,
        cookie: String? = null,
        poTokenBinding: PoTokenBinding = PoTokenBinding.VIDEO_ID,
    ): WebPoTokenResult? {
        if (!webViewSupported || webViewBadImplementation.get() || visitorData.isBlank()) return null
        return try {
            mintLock.withLock {
                withTimeout(POTOKEN_TIMEOUT_MS) {
                    getWebClientPoTokenInternal(videoId, visitorData, cookie, forceRecreate = false, poTokenBinding = poTokenBinding)
                }
            }
        } catch (_: TimeoutCancellationException) {
            clear()
            null
        } catch (_: BadWebViewException) {
            webViewBadImplementation.set(true)
            clear()
            null
        } catch (_: PoTokenException) {
            // A failed challenge or expired renderer is recoverable. The next
            // extraction gets a fresh WebView and can fall back to a client
            // that does not require BotGuard if YouTube is still unavailable.
            clear()
            null
        } catch (error: CancellationException) {
            throw error
        }
    }

    suspend fun prewarm(visitorData: String, cookie: String? = null) {
        if (!webViewSupported || webViewBadImplementation.get() || visitorData.isBlank()) return
        runCatching {
            mintLock.withLock {
                withTimeout(POTOKEN_TIMEOUT_MS) {
                    val key = visitorData + "\u0000" + cookie.orEmpty()
                    lock.withLock {
                        val current = webView
                        val shouldRecreate = current == null || current.isExpired ||
                            current.isDead || sessionKey != key || streamingPoToken.isNullOrBlank()
                        if (shouldRecreate) {
                            withContext(Dispatchers.Main) { current?.close() }
                            webView = null
                            streamingPoToken = null
                            sessionKey = null
                            syncYoutubeCookies(cookie)
                            val created = PoTokenWebView.getNewPoTokenGenerator(applicationContext)
                            val streaming = try {
                                created.generatePoToken(visitorData)
                            } catch (error: Throwable) {
                                created.close()
                                throw error
                            }
                            webView = created
                            streamingPoToken = streaming
                            sessionKey = key
                        }
                    }
                }
            }
        }
    }

    suspend fun close() = clear()

    private suspend fun getWebClientPoTokenInternal(
        videoId: String,
        visitorData: String,
        cookie: String?,
        forceRecreate: Boolean,
        poTokenBinding: PoTokenBinding = PoTokenBinding.VIDEO_ID,
    ): WebPoTokenResult {
        val key = visitorData + "\u0000" + cookie.orEmpty()
        val (generator, streamingToken, recreated) = lock.withLock {
            val current = webView
            val shouldRecreate = forceRecreate || current == null || current.isExpired ||
                current.isDead || sessionKey != key || streamingPoToken.isNullOrBlank()
            if (shouldRecreate) {
                withContext(Dispatchers.Main) { current?.close() }
                webView = null
                streamingPoToken = null
                sessionKey = null
                syncYoutubeCookies(cookie)
                val created = PoTokenWebView.getNewPoTokenGenerator(applicationContext)
                val streaming = try {
                    // This must be the first token created in this BotGuard session.
                    created.generatePoToken(visitorData)
                } catch (error: Throwable) {
                    created.close()
                    throw error
                }
                webView = created
                streamingPoToken = streaming
                sessionKey = key
            }
            Triple(
                checkNotNull(webView),
                checkNotNull(streamingPoToken),
                shouldRecreate,
            )
        }

        if (poTokenBinding == PoTokenBinding.VISITOR_DATA) {
            // For VISITOR_DATA-bound clients (e.g. TVHTML5_SIMPLY), YouTube binds the player request
            // to visitorData rather than videoId.
            return WebPoTokenResult(
                playerRequestPoToken = streamingToken,
                streamingDataPoToken = streamingToken,
            )
        }

        val tokenKey = "$key\u0000$videoId"
        lock.withLock {
            playerTokenCache[tokenKey]?.takeIf { it.expiresAtElapsedRealtime > android.os.SystemClock.elapsedRealtime() }
                ?.let { cached ->
                    return WebPoTokenResult(cached.token, streamingToken)
                }
            playerTokenCache.remove(tokenKey)
        }

        val playerToken = try {
            generator.generatePoToken(videoId)
        } catch (error: Throwable) {
            if (recreated) throw error
            return getWebClientPoTokenInternal(videoId, visitorData, cookie, forceRecreate = true, poTokenBinding = poTokenBinding)
        }
        lock.withLock {
            if (sessionKey == key) {
                playerTokenCache[tokenKey] = CachedToken(
                    playerToken,
                    android.os.SystemClock.elapsedRealtime() + PLAYER_TOKEN_CACHE_TTL_MS,
                )
            }
        }
        return WebPoTokenResult(
            playerRequestPoToken = playerToken,
            streamingDataPoToken = streamingToken,
        )
    }

    private suspend fun clear() {
        lock.withLock {
            val current = webView
            webView = null
            streamingPoToken = null
            sessionKey = null
            playerTokenCache.clear()
            withContext(Dispatchers.Main) { current?.close() }
            clearSyncedCookies()
        }
    }

    private fun syncYoutubeCookies(cookie: String?) {
        val pairs = cookie.orEmpty()
            .split(';')
            .asSequence()
            .map(String::trim)
            .mapNotNull { pair ->
                val name = pair.substringBefore('=', "").trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toList()
        runCatching {
            val cookieManager = CookieManager.getInstance()
            syncedCookieNames
                .subtract(pairs.mapTo(mutableSetOf()) { it.first })
                .forEach { name ->
                    cookieManager.setCookie(
                        "https://www.youtube.com",
                        "$name=; Max-Age=0; Path=/; Secure",
                    )
                }
            pairs.forEach { (name, value) ->
                cookieManager.setCookie(
                    "https://www.youtube.com",
                    "$name=$value; Path=/; Secure",
                )
            }
            syncedCookieNames = pairs.mapTo(mutableSetOf()) { it.first }
            cookieManager.flush()
        }
    }

    private fun clearSyncedCookies() {
        if (syncedCookieNames.isEmpty()) return
        runCatching {
            val cookieManager = CookieManager.getInstance()
            syncedCookieNames.forEach { name ->
                cookieManager.setCookie(
                    "https://www.youtube.com",
                    "$name=; Max-Age=0; Path=/; Secure",
                )
            }
            cookieManager.flush()
            syncedCookieNames = emptySet()
        }
    }

    private companion object {
        private data class CachedToken(val token: String, val expiresAtElapsedRealtime: Long)
        private const val MAX_CACHED_PLAYER_TOKENS = 16
        private const val PLAYER_TOKEN_CACHE_TTL_MS = 5 * 60 * 1000L
        const val POTOKEN_TIMEOUT_MS = 8_000L
    }
}
