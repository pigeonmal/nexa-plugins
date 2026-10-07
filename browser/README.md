# `dev.nexa.browser`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-SFSafariViewController%20%2F%20Custom%20Tabs-blue.svg)](https://developer.apple.com/documentation/safariservices/sfsafariviewcontroller)

Native in-app web browser modal sheets and external system browser launching.

Backed by Apple `SFSafariViewController` on iOS and AndroidX `CustomTabsIntent` (Chrome Custom Tabs) on Android:
- **`inApp: true`**: Presents a native, sandboxed modal browser with shared cookies and autofill credentials, keeping the user inside your application.
- **`inApp: false`**: Handoffs the link to the user's default external browser (Safari, Chrome, Firefox).

---

> **Android minimum API:** 23. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/browser" as Browser

app PrivacyLinks {
    let browser = Browser.SystemBrowser()
    state status: String = "Choose a privacy link"
    state openTask: TaskHandle? = null

    body {
        Column(spacing: 12) {
            Text(status)
            Button("Open privacy policy") {
                Task.launch(handle: openTask, executor: TaskExecutor.Main) {
                    try {
                        await browser.openInApp("https://www.mozilla.org/privacy/", "#1E88E5")
                        status = "Privacy policy opened"
                    } catch {
                        status = "Could not open the browser"
                    }
                }
            }
        }
    }
}
```

---

## 2. API Reference

### `SystemBrowser` handle

| Constructor | Signature | Description |
|---|---|---|
| `SystemBrowser` | `SystemBrowser()` | Creates a browser launcher handle. |


#### Methods

| Method | Return Type | Description |
|---|---|---|
| `open(url: String, inApp: Bool)` | `async -> Void throws BrowserError` | Launches target URL. When `inApp` is `true`, opens `SFSafariViewController` / Custom Tab. When `false`, opens default external browser. |
| `openInApp(url: String, toolbarColor: String?)` | `async -> Void throws BrowserError` | Opens an HTTP(S) URL in Safari / Custom Tabs with an optional six-digit hex toolbar tint. `null` keeps the system default; malformed colors throw `invalidURL`. |

---

### Error Handling (`BrowserError`)

| Variant | Description |
|---|---|
| `invalidURL` | URL string cannot be parsed as a valid HTTP or HTTPS URI |
| `unavailable` | Device lacks an installed browser or web runtime |
| `presentationUnavailable` | Host view controller or Activity is currently unable to present modal sheets |
