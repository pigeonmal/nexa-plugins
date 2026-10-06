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

    body {
        Column {
            WebView.BrowserView(
                url: "https://example.com",
                javaScriptEnabled: true,
                allowedMessageOrigins: ["https://example.com"],
                message: outgoingMessage,
            ).onMessageReceived { value ->
                incomingMessage = value
            }.onNavigated { url ->
                currentUrl = url
            }.onFailed { message ->
                failure = message
            }

            Button("Send another message") {
                outgoingMessage = "Second Nexa message"
            }
            Text(currentUrl)
            Text(incomingMessage)
            Text(failure)
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
| `allowedMessageOrigins` | `Array<String>` | required | Exact HTTPS origins allowed to exchange messages. |
| `message` | `String?` | required | Optional host-to-page message. A changed value dispatches a `nexa-message` event on an allowed origin. |

#### Events

| Event | Payload | Description |
|---|---|---|
| `messageReceived` | `message: String` | Page message received from an allowlisted main-frame origin. |
| `navigated` | `url: String` | Navigation completed successfully. |
| `failed` | `message: String` | Navigation or bridge setup failed. |

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
