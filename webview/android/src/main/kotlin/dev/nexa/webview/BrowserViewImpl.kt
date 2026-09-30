package dev.nexa.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.Locale

/** A WebView with HTTPS-only navigation and an origin-limited string JS bridge. */
@Composable
public fun BrowserViewImpl(
    url: String,
    javaScriptEnabled: Boolean,
    allowedMessageOrigins: List<String>,
    message: String?,
    onMessageReceived: ((String) -> Unit)? = null,
    onNavigated: ((String) -> Unit)? = null,
    onFailed: ((String) -> Unit)? = null,
) {
    val latestMessageReceived = rememberUpdatedState(onMessageReceived)
    val latestNavigated = rememberUpdatedState(onNavigated)
    val latestFailed = rememberUpdatedState(onFailed)
    val latestMessage = rememberUpdatedState(message)
    val latestJavaScriptEnabled = rememberUpdatedState(javaScriptEnabled)

    AndroidView(
        modifier = Modifier,
        factory = { context ->
            NexaBrowserView(context).apply {
                this.javascriptEnabled = javaScriptEnabled
                lastObservedMessage = message
                settings.javaScriptEnabled = javaScriptEnabled
                settings.domStorageEnabled = javaScriptEnabled
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        if (isAbsoluteHttpsUrl(request.url.toString())) return false
                        if (request.isForMainFrame) {
                            latestFailed.value?.invoke("Only absolute HTTPS navigation is allowed.")
                        }
                        return true
                    }

                    override fun onPageStarted(
                        view: WebView,
                        address: String,
                        favicon: android.graphics.Bitmap?,
                    ) {
                        (view as? NexaBrowserView)?.apply {
                            pageLoaded = false
                            lastSentMessage = null
                        }
                    }

                    override fun onPageFinished(view: WebView, address: String) {
                        val browserView = view as? NexaBrowserView ?: return
                        browserView.pageLoaded = true
                        latestNavigated.value?.invoke(address)
                        dispatchMessage(
                            browserView,
                            latestMessage.value,
                            latestJavaScriptEnabled.value,
                        )
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            latestFailed.value?.invoke(
                                error.description?.toString() ?: "Web page failed to load."
                            )
                        }
                    }
                }
                setAllowedMessageOrigins(
                    this,
                    allowedMessageOrigins,
                    onMessage = { text -> latestMessageReceived.value?.invoke(text) },
                    onFailed = { error -> latestFailed.value?.invoke(error) },
                )
                loadHttpsUrl(this, url, latestFailed.value)
            }
        },
        update = { view ->
            val jsChanged = view.javascriptEnabled != javaScriptEnabled
            val messageChanged = view.lastObservedMessage != message
            view.javascriptEnabled = javaScriptEnabled
            view.lastObservedMessage = message
            if (messageChanged) view.lastSentMessage = null
            view.settings.javaScriptEnabled = javaScriptEnabled
            view.settings.domStorageEnabled = javaScriptEnabled

            val originsChanged = setAllowedMessageOrigins(
                view,
                allowedMessageOrigins,
                onMessage = { text -> latestMessageReceived.value?.invoke(text) },
                onFailed = { error -> latestFailed.value?.invoke(error) },
            )
            if (jsChanged || originsChanged) view.lastSentMessage = null
            if (view.requestedUrl != url || jsChanged || originsChanged) {
                loadHttpsUrl(view, url, latestFailed.value)
            }

            dispatchMessage(view, latestMessage.value, javaScriptEnabled)
        },
        onRelease = { view ->
            if (view.messageListenerInstalled &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            ) {
                WebViewCompat.removeWebMessageListener(view, MESSAGE_BRIDGE_NAME)
            }
            view.stopLoading()
            view.webViewClient = WebViewClient()
            view.destroy()
        },
    )
}

private class NexaBrowserView(context: Context) : WebView(context) {
    var requestedUrl: String? = null
    var javascriptEnabled = false
    var pageLoaded = false
    var lastSentMessage: String? = null
    var lastObservedMessage: String? = null
    var messageOrigins: Set<String> = emptySet()
    var messageListenerInstalled = false
    var lastReportedInvalidMessageOrigins: List<String>? = null
}

/** Replaces the origin-gated listener only when its allowlist changes. */
private fun setAllowedMessageOrigins(
    view: NexaBrowserView,
    origins: List<String>,
    onMessage: (String) -> Unit,
    onFailed: (String) -> Unit,
): Boolean {
    val normalizedValues = origins.map(::normalizeHttpsOrigin)
    val hasInvalidOrigins = normalizedValues.any { it == null } ||
        normalizedValues.mapNotNull { it }.toSet().size != origins.size
    val normalized = if (hasInvalidOrigins) {
        emptySet()
    } else {
        normalizedValues.mapNotNull { it }.toSet()
    }
    if (hasInvalidOrigins) {
        val invalidOrigins = origins.toList()
        if (view.lastReportedInvalidMessageOrigins != invalidOrigins) {
            reportFailure(
                view,
                onFailed,
                "Message origins must be unique, exact HTTPS origins without paths or wildcards.",
            )
            view.lastReportedInvalidMessageOrigins = invalidOrigins
        }
    } else {
        view.lastReportedInvalidMessageOrigins = null
    }
    if (normalized == view.messageOrigins) return false

    if (view.messageListenerInstalled &&
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    ) {
        WebViewCompat.removeWebMessageListener(view, MESSAGE_BRIDGE_NAME)
    }
    view.messageListenerInstalled = false
    view.messageOrigins = normalized
    if (normalized.isEmpty()) return true

    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        reportFailure(
            view,
            onFailed,
            "This Android System WebView does not support secure JavaScript messaging.",
        )
        return true
    }

    try {
        WebViewCompat.addWebMessageListener(
            view,
            MESSAGE_BRIDGE_NAME,
            normalized,
        ) { _, webMessage, sourceOrigin, isMainFrame, _ ->
            val source = normalizeHttpsOrigin(sourceOrigin.toString())
            if (isMainFrame && source != null && source in normalized &&
                webMessage.type == WebMessageCompat.TYPE_STRING
            ) {
                webMessage.data?.let(onMessage)
            }
        }
        view.messageListenerInstalled = true
    } catch (_: UnsupportedOperationException) {
        reportFailure(
            view,
            onFailed,
            "This Android System WebView does not support secure JavaScript messaging.",
        )
    }
    return true
}

private fun normalizeHttpsOrigin(value: String): String? {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    val uriHost = uri.host?.takeIf(String::isNotBlank) ?: return null
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
    if (uri.path.orEmpty().isNotEmpty() && uri.path != "/") return null
    if (!hasValidHttpsAuthority(uri.encodedAuthority)) return null

    val host = canonicalHttpsHost(uriHost)
    return if (uri.port == -1 || uri.port == 443) {
        "https://$host"
    } else {
        if (uri.port !in 1..65535) return null
        "https://$host:${uri.port}"
    }
}

private fun isAbsoluteHttpsUrl(value: String): Boolean = normalizeHttpsUrl(value) != null

private fun reportFailure(view: WebView, onFailed: (String) -> Unit, message: String) {
    view.post { onFailed(message) }
}

private fun normalizeHttpsUrl(value: String): Uri? {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    if (
        !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
        uri.userInfo != null || !hasValidHttpsAuthority(uri.encodedAuthority)
    ) return null
    return uri
}

private fun hasValidHttpsAuthority(authority: String?): Boolean {
    if (authority.isNullOrEmpty() || authority.contains('@')) return false
    val portText = when {
        authority.startsWith('[') -> {
            val hostEnd = authority.indexOf(']')
            if (hostEnd <= 1) return false
            val suffix = authority.substring(hostEnd + 1)
            if (suffix.isEmpty()) return true
            if (!suffix.startsWith(':')) return false
            suffix.substring(1)
        }
        else -> {
            val separator = authority.lastIndexOf(':')
            if (separator < 0) return true
            if (authority.indexOf(':') != separator) return false
            authority.substring(separator + 1)
        }
    }
    if (portText.isEmpty() || portText.any { it !in '0'..'9' }) return false
    val port = portText.toIntOrNull() ?: return false
    return port in 1..65_535
}

private fun canonicalHttpsHost(host: String): String {
    val normalized = host.lowercase(Locale.ROOT)
    return if (normalized.contains(':') &&
        !(normalized.startsWith('[') && normalized.endsWith(']'))
    ) {
        "[$normalized]"
    } else {
        normalized
    }
}

private fun httpsOriginOf(value: String): String? {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    val uriHost = uri.host?.takeIf(String::isNotBlank) ?: return null
    val host = canonicalHttpsHost(uriHost)
    return if (uri.port == -1 || uri.port == 443) {
        "https://$host"
    } else {
        "https://$host:${uri.port}"
    }
}

private fun loadHttpsUrl(view: NexaBrowserView, url: String, onFailed: ((String) -> Unit)?) {
    view.requestedUrl = url
    view.stopLoading()
    view.pageLoaded = false
    view.lastSentMessage = null
    val parsed = normalizeHttpsUrl(url)
    if (parsed == null) {
        if (onFailed != null) {
            reportFailure(view, onFailed, "WebView requires an absolute HTTPS URL.")
        }
        return
    }
    view.loadUrl(parsed.toString())
}

private fun dispatchMessage(view: NexaBrowserView, message: String?, enabled: Boolean) {
    val currentOrigin = view.url?.let(::httpsOriginOf)
    if (!enabled || !view.pageLoaded || message == null || message == view.lastSentMessage ||
        currentOrigin !in view.messageOrigins
    ) return
    view.lastSentMessage = message
    val quotedMessage = JSONObject.quote(message)
    view.evaluateJavascript(
        "window.dispatchEvent(new MessageEvent('nexa-message', { data: $quotedMessage }));",
        null,
    )
}

private const val MESSAGE_BRIDGE_NAME = "Nexa"
