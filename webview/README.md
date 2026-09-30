# `@nexa/webview`

A native WebKit / Android WebView component for HTTPS pages. JavaScript is
disabled by default. The component blocks non-HTTPS top-level navigation,
disallows local file and content access on Android, and does not enable multiple
windows or mixed content. JavaScript messages are limited to explicit, exact
HTTPS origins supplied by the app.

```nx
plugin "dev.nexa.webview" as WebView

app Browser {
    state outgoing = "hello from Nexa"
    state incoming = ""
    state currentUrl = ""
    state failure = ""

    body {
        Column {
            WebView.BrowserView(
                url: "https://example.com",
                javaScriptEnabled: true,
                allowedMessageOrigins: ["https://example.com"],
                message: outgoing,
            ).onMessageReceived { value ->
                incoming = value
            }.onNavigated { url ->
                currentUrl = url
            }.onFailed { message ->
                failure = message
            }

            Button("Send message") {
                outgoing = "updated from Nexa"
            }
            Text(currentUrl)
            Text(incoming)
            Text(failure)
        }
    }
}
```

With JavaScript enabled, a page at an allowlisted origin can receive each
changed `message` value as a `MessageEvent` named `nexa-message`, whose `data`
field is a string. Only messages from allowlisted main-frame origins reach
`onMessageReceived`. A page can send a string back through the same bridge name
on both platforms:

```javascript
window.addEventListener("nexa-message", (event) => {
  document.querySelector("#status").textContent = event.data;
});

window.Nexa.postMessage("hello from the page");
```

Only absolute HTTPS URLs are accepted, including redirects and in-page
top-level navigation. Each allowed message origin must be an exact origin such
as `https://app.example.com` or `https://app.example.com:8443`; paths, wildcards,
and non-HTTPS schemes are rejected. JavaScript access to `window.Nexa` exists
only when the app opts in with `javaScriptEnabled: true` and the current page's
origin is allowlisted. The bridge transports strings and exposes no native
device operations. Android uses AndroidX WebKit's origin-gated message API and
declares the `INTERNET` permission; iOS links the system WebKit framework. The
demo app under `tests/demo/app` exercises the typed component and callbacks.

## Platform requirements

- iOS 17 or later, using the system WebKit framework.
- Android API 24 or later, using AndroidX WebKit `1.17.1` and the system
  Android WebView. The `INTERNET` permission is added by the plugin manifest.
- Android JavaScript messaging requires a system WebView that supports
  `WEB_MESSAGE_LISTENER`; older WebView versions report the limitation through
  `onFailed` and do not fall back to the unrestricted legacy JavaScript bridge.
