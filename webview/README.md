# `dev.nexa.webview`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Security Hardened](https://img.shields.io/badge/Security-Origin%20Allowlisted-brightgreen.svg)](https://developer.apple.com/documentation/webkit/wkwebview)

Security-hardened native WebKit and Android WebView component with origin isolation, two-way bidirectional messaging, and safe defaults.

Backed by Apple `WebKit` (`WKWebView`) on iOS and AndroidX `WebView` on Android. JavaScript is disabled by default to prevent unauthorized code execution.

---

> **Android minimum API:** 24. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/webview" as WebView

app WebViewDemo {
    state outgoingMessage = "First Nexa message"
    state incomingMessage = "No message received"
    state currentUrl = ""
    state failure = ""
    state cookieHeader = ""

    body {
        Column {
            WebView.BrowserView(
                url: "https://example.com",
                javaScriptEnabled: true,
                offlineCacheEnabled: true,
                allowedMessageOrigins: ["https://example.com"],
                message: outgoingMessage,
                script: "window.Nexa.postMessage('Page is ready')",
                cookieRequestURL: "https://example.com"
            ).onMessageReceived { value ->
                incomingMessage = value
            }.onNavigated { url ->
                currentUrl = url
            }.onFailed { message ->
                failure = message
            }.onCookiesRead { address, cookies ->
                cookieHeader = cookies
            }

            Button("Send another message") {
                outgoingMessage = "Second Nexa message"
            }
            Text(currentUrl)
            Text(incomingMessage)
            Text(failure)
            Text("Cookie header length: \\(cookieHeader.count)")
        }
    }
}
```

---

## 2. API Reference

### `BrowserView` component

The component loads the requested URL. JavaScript messaging is origin-scoped: only exact HTTPS origins in `allowedMessageOrigins` can use the bridge.

#### Properties

| Prop | Type | Default | Description |
|---|---|---|---|
| `url` | `String` | required | URL loaded by the native web view. |
| `javaScriptEnabled` | `Bool` | `false` | Enables page JavaScript and the messaging bridge. |
| `offlineCacheEnabled` | `Bool` | `false` | Prefers cached responses before the network to support previously loaded content offline. Android uses `LOAD_CACHE_ELSE_NETWORK`; iOS uses `returnCacheDataElseLoad`. |
| `allowedMessageOrigins` | `Array<String>` | required | Exact HTTPS origins allowed to exchange messages. |
| `message` | `String?` | required | Optional host-to-page message. A changed value dispatches a `nexa-message` event on an allowed origin. |
| `script` | `String?` | required | App-authored JavaScript evaluated once after the main frame loads, only when JavaScript is enabled and the page's exact HTTPS origin is allowlisted. A changed script runs once on the current page. |
| `cookieRequestURL` | `String?` | required | Explicit HTTPS URL whose cookies the host requests. Its exact origin must be in `allowedMessageOrigins` and match the currently loaded page. No cookies are read when this prop is null. |

#### Events

| Event | Payload | Description |
|---|---|---|
| `messageReceived` | `message: String` | Page message received from an allowlisted main-frame origin. |
| `navigated` | `url: String` | Navigation completed successfully. |
| `failed` | `message: String` | Navigation or bridge setup failed. |
| `cookiesRead` | `url: String, cookieHeader: String` | Cookie header for the explicit request URL. The callback may return an empty string when the page has no matching cookies. |

## 3. Bidirectional messaging

In the hosted page, send a string from an allowed HTTPS main-frame origin:

```javascript
if (window.Nexa) {
    window.Nexa.postMessage(JSON.stringify({ event: "checkout_complete", orderId: "12345" }));
}

window.addEventListener("nexa-message", (event) => {
    document.querySelector("#status").textContent = String(event.data);
});
```

The Android implementation exposes the same `window.Nexa.postMessage` interface. Keep messages as strings and validate their contents in the app before acting on them.

The host can run app-authored JavaScript in the loaded main frame. Scripts run only with JavaScript enabled and only on an exact allowed origin. Cookie access is a separate, explicit native request: the URL must be HTTPS, its exact origin must be allowlisted, and it must match the loaded page's origin. iOS reads matching WebKit cookies from the app's website data store; Android reads the WebView cookie store. Do not render or log cookie values. Store credentials using the platform secure-storage API.

## 4. Native mapping and lifecycle

| Platform | UI and cookie APIs | Lifecycle |
|---|---|---|
| iOS | `WKWebView`, `WKUserContentController`, and `WKHTTPCookieStore` | The SwiftUI representable owns the WebKit view. Navigation stops and the message handler is removed when the view is dismantled. |
| Android | Android `WebView`, AndroidX `WebViewCompat`, and `CookieManager` | `AndroidView` owns and destroys the view. Its listener is removed before `WebView.destroy()`. |

Only exact HTTPS origins are accepted. Messages and cookie requests from a subframe or an unlisted origin are ignored. The plugin does not send cookies to a service; shared `.nx` code decides whether returned data is valid and how to store it.
