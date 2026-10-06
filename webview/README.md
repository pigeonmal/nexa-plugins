# `@nexa/webview`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Security Hardened](https://img.shields.io/badge/Security-Origin%20Allowlisted-brightgreen.svg)](https://developer.apple.com/documentation/webkit/wkwebview)

Security-hardened native WebKit and Android WebView component with origin isolation, two-way bidirectional messaging, and safe defaults.

Backed by Apple `WebKit` (`WKWebView`) on iOS and AndroidX `WebView` on Android. JavaScript is disabled by default to prevent unauthorized code execution.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.webview" as WebView

component TermsOfServiceScreen() {
    state currentUrl: String = "https://example.com/terms"
    state isLoading: Bool = true

    VStack {
        WebView.BrowserView(
            url: currentUrl,
            javaScriptEnabled: true,
            allowedMessageOrigins: ["https://example.com"],
            onNavigated: (url) => {
                currentUrl = url
                isLoading = false
            },
            onMessageReceived: (msg) => {
                print("Message from web page: \(msg)")
            },
            onFailed: (err) => {
                print("Failed to load webview: \(err)")
            }
        )
    }
}
```

---

## 2. API Reference

### `BrowserView` Native Component

```nexa
native component BrowserView
```

#### Properties

| Prop | Type | Default | Description |
|---|---|---|---|
| `url` | `String` | — | Target URL to load (must be HTTPS or local app asset) |
| `javaScriptEnabled` | `Bool` | `false` | Security toggle. JavaScript is strictly disabled unless explicitly enabled. |
| `allowedMessageOrigins` | `Array<String>` | `[]` | List of trusted HTTPS origins permitted to send postMessages to the host app |
| `message` | `String?` | `null` | String payload dispatched to the web document via `window.postMessage` |

#### Events

| Event | Payload | Description |
|---|---|---|
| `messageReceived` | `message: String` | Fired when an allowed origin emits `window.webkit.messageHandlers.nexa.postMessage` or Android interface |
| `navigated` | `url: String` | Fired when navigation finishes successfully |
| `failed` | `message: String` | Fired on SSL errors, DNS resolution failures, or HTTP connection aborts |

---

## 3. Bidirectional Web-to-Native Messaging

In your hosted HTML/JavaScript document:

```javascript
// Dispatches message to Nexa host app if origin is in allowedMessageOrigins
if (window.nexa) {
    window.nexa.postMessage(JSON.stringify({ event: "checkout_complete", orderId: "12345" }));
}
```
