# `@nexa/browser`

Opens URLs in the system browser or in a browser surface presented by the app.

```nx
plugin "dev.nexa.browser" as Browser

app BrowserExample {
    let browser = Browser.SystemBrowser()

    body {
        Button("Open in app") {
            try {
                await browser.open("https://example.com", true)
            } catch {
                case Browser.BrowserError.invalidURL {}
                case Browser.BrowserError.unavailable {}
                case Browser.BrowserError.presentationUnavailable {}
            }
        }
    }
}
```

On iOS, `inApp: true` presents `SFSafariViewController`; `false` asks the system
to open the URL in the user's browser. On Android, `true` opens a Custom Tab and
`false` launches the system URL handler. The in-app presentation uses the native
browser interface, not an embedded WebView. URLs must be absolute. In-app browser
URLs must use HTTP or HTTPS.

Apple API reference: [SFSafariViewController](https://developer.apple.com/documentation/safariservices/sfsafariviewcontroller/).
