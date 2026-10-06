# `@nexa/browser`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-SFSafariViewController%20%2F%20Custom%20Tabs-blue.svg)](https://developer.apple.com/documentation/safariservices/sfsafariviewcontroller)

Native in-app web browser modal sheets and external system browser launching.

Backed by Apple `SFSafariViewController` on iOS and AndroidX `CustomTabsIntent` (Chrome Custom Tabs) on Android:
- **`inApp: true`**: Presents a native, sandboxed modal browser with shared cookies and autofill credentials, keeping the user inside your application.
- **`inApp: false`**: Handoffs the link to the user's default external browser (Safari, Chrome, Firefox).

---

## 1. Quick Start

```nexa
plugin "dev.nexa.browser" as Browser

component ExternalLinksScreen() {
    let browser = Browser.SystemBrowser()

    fn openPrivacyPolicy() {
        try {
            await browser.open("https://example.com/privacy", inApp: true)
        } catch Browser.BrowserError as err {
            print("Failed to open browser: \(err)")
        }
    }

    fn openAppStoreReview() {
        try {
            await browser.open("https://apps.apple.com/app/id123456", inApp: false)
        } catch Browser.BrowserError as err {
            print("Failed to launch external browser: \(err)")
        }
    }

    VStack(spacing: 16) {
        Button("Read Privacy Policy (In-App)", action: () => { openPrivacyPolicy() })
        Button("Rate on App Store (External)", action: () => { openAppStoreReview() })
    }
}
```

---

## 2. API Reference

### `SystemBrowser` Native Class

```nexa
native class SystemBrowser {
    init()
}
```

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `open(url: String, inApp: Bool)` | `Void` | Launches target URL. When `inApp` is `true`, opens `SFSafariViewController` / Custom Tab. When `false`, opens default external browser. |

---

### Error Handling (`BrowserError`)

| Variant | Description |
|---|---|
| `invalidURL` | URL string cannot be parsed as a valid HTTP or HTTPS URI |
| `unavailable` | Device lacks an installed browser or web runtime |
| `presentationUnavailable` | Host view controller or Activity is currently unable to present modal sheets |
