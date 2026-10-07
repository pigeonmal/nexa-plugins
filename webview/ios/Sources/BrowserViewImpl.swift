import Foundation
import SwiftUI
import WebKit

/// A WebKit view with HTTPS-only navigation and an opt-in string message bridge.
@MainActor
public struct BrowserViewImpl: UIViewRepresentable {
    public let url: String
    public let javaScriptEnabled: Bool
    public let offlineCacheEnabled: Bool
    public let allowedMessageOrigins: [String]
    public let message: String?
    public let onMessageReceived: ((String) -> Void)?
    public let onNavigated: ((String) -> Void)?
    public let onFailed: ((String) -> Void)?

    public init(
        url: String,
        javaScriptEnabled: Bool,
        offlineCacheEnabled: Bool,
        allowedMessageOrigins: [String],
        message: String?,
        onMessageReceived: ((String) -> Void)?,
        onNavigated: ((String) -> Void)?,
        onFailed: ((String) -> Void)?
    ) {
        self.url = url
        self.javaScriptEnabled = javaScriptEnabled
        self.offlineCacheEnabled = offlineCacheEnabled
        self.allowedMessageOrigins = allowedMessageOrigins
        self.message = message
        self.onMessageReceived = onMessageReceived
        self.onNavigated = onNavigated
        self.onFailed = onFailed
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(
            javaScriptEnabled: javaScriptEnabled,
            offlineCacheEnabled: offlineCacheEnabled,
            allowedMessageOrigins: allowedMessageOrigins,
            message: message,
            onMessageReceived: onMessageReceived,
            onNavigated: onNavigated,
            onFailed: onFailed
        )
    }

    public func makeUIView(context: Context) -> WKWebView {
        context.coordinator.reportInvalidMessageOriginsIfNeeded()
        let controller = WKUserContentController()
        controller.add(context.coordinator, name: "nexaBridge")
        if let script = Self.bridgeUserScript(for: context.coordinator.allowedMessageOrigins) {
            controller.addUserScript(script)
        }

        let configuration = WKWebViewConfiguration()
        configuration.userContentController = controller
        configuration.defaultWebpagePreferences.allowsContentJavaScript = javaScriptEnabled

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = context.coordinator
        context.coordinator.load(url, in: webView)
        return webView
    }

    public func updateUIView(_ webView: WKWebView, context: Context) {
        let coordinator = context.coordinator
        let javaScriptChanged = coordinator.javaScriptEnabled != javaScriptEnabled
        let cachePolicyChanged = coordinator.offlineCacheEnabled != offlineCacheEnabled
        coordinator.javaScriptEnabled = javaScriptEnabled
        coordinator.offlineCacheEnabled = offlineCacheEnabled
        coordinator.updateMessage(message)
        coordinator.onMessageReceived = onMessageReceived
        coordinator.onNavigated = onNavigated
        coordinator.onFailed = onFailed
        let originsChanged = coordinator.setAllowedMessageOrigins(allowedMessageOrigins)
        coordinator.reportInvalidMessageOriginsIfNeeded()
        if originsChanged {
            let controller = webView.configuration.userContentController
            controller.removeAllUserScripts()
            if let script = Self.bridgeUserScript(for: coordinator.allowedMessageOrigins) {
                controller.addUserScript(script)
            }
        }
        if javaScriptChanged || originsChanged || cachePolicyChanged { coordinator.requestedURL = nil }
        coordinator.load(url, in: webView)
        coordinator.sendCurrentMessage(to: webView)
    }

    public static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        webView.stopLoading()
        webView.navigationDelegate = nil
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "nexaBridge")
    }

    @MainActor
    public final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        fileprivate var javaScriptEnabled: Bool
        fileprivate var offlineCacheEnabled: Bool
        fileprivate var allowedMessageOrigins: Set<String>
        fileprivate var invalidMessageOrigins: [String]?
        fileprivate var reportedInvalidMessageOrigins: [String]?
        fileprivate var message: String?
        fileprivate var onMessageReceived: ((String) -> Void)?
        fileprivate var onNavigated: ((String) -> Void)?
        fileprivate var onFailed: ((String) -> Void)?
        fileprivate var requestedURL: String?
        fileprivate var pageLoaded = false
        fileprivate var lastSentMessage: String?

        fileprivate init(
            javaScriptEnabled: Bool,
            offlineCacheEnabled: Bool,
            allowedMessageOrigins: [String],
            message: String?,
            onMessageReceived: ((String) -> Void)?,
            onNavigated: ((String) -> Void)?,
            onFailed: ((String) -> Void)?
        ) {
            self.javaScriptEnabled = javaScriptEnabled
            self.offlineCacheEnabled = offlineCacheEnabled
            let normalizedOrigins = allowedMessageOrigins.compactMap(BrowserViewImpl.normalizeHttpsOrigin)
            let hasInvalidOrigins = normalizedOrigins.count != allowedMessageOrigins.count ||
                Set(normalizedOrigins).count != allowedMessageOrigins.count
            self.allowedMessageOrigins = hasInvalidOrigins ? [] : Set(normalizedOrigins)
            self.invalidMessageOrigins = hasInvalidOrigins ? allowedMessageOrigins : nil
            self.message = message
            self.onMessageReceived = onMessageReceived
            self.onNavigated = onNavigated
            self.onFailed = onFailed
        }

        fileprivate func load(_ address: String, in webView: WKWebView) {
            guard requestedURL != address else { return }
            requestedURL = address
            guard BrowserViewImpl.hasValidHttpsAuthority(address),
                  let components = URLComponents(string: address),
                  components.scheme?.lowercased() == "https",
                  components.host?.isEmpty == false,
                  components.user == nil,
                  components.password == nil,
                  let target = components.url
            else {
                webView.stopLoading()
                pageLoaded = false
                lastSentMessage = nil
                reportFailure("WebView requires an absolute HTTPS URL.")
                return
            }

            pageLoaded = false
            lastSentMessage = nil
            var request = URLRequest(url: target)
            request.cachePolicy = offlineCacheEnabled ? .returnCacheDataElseLoad : .useProtocolCachePolicy
            webView.load(request)
        }

        fileprivate func sendCurrentMessage(to webView: WKWebView) {
            guard javaScriptEnabled,
                  pageLoaded,
                  let message,
                  message != lastSentMessage,
                  let origin = BrowserViewImpl.normalizedSecurityOrigin(webView.url),
                  allowedMessageOrigins.contains(origin)
            else { return }

            lastSentMessage = message
            let literal = BrowserViewImpl.javaScriptStringLiteral(message)
            webView.evaluateJavaScript(
                "window.dispatchEvent(new MessageEvent('nexa-message', { data: \(literal) }));"
            )
        }

        public func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            guard message.name == "nexaBridge",
                  message.frameInfo.isMainFrame,
                  let text = message.body as? String,
                  let origin = BrowserViewImpl.normalizedSecurityOrigin(message.frameInfo.securityOrigin),
                  allowedMessageOrigins.contains(origin)
            else { return }
            onMessageReceived?(text)
        }

        public func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            preferences: WKWebpagePreferences,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy, WKWebpagePreferences) -> Void
        ) {
            guard let target = navigationAction.request.url,
                  BrowserViewImpl.hasValidHttpsAuthority(target.absoluteString),
                  target.scheme?.lowercased() == "https",
                  target.host?.isEmpty == false,
                  target.user == nil,
                  target.password == nil
            else {
                if navigationAction.targetFrame?.isMainFrame != false {
                    reportFailure("Only absolute HTTPS navigation is allowed.")
                }
                decisionHandler(.cancel, preferences)
                return
            }
            preferences.allowsContentJavaScript = javaScriptEnabled
            decisionHandler(.allow, preferences)
        }

        public func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationResponse: WKNavigationResponse,
            decisionHandler: @escaping @MainActor (WKNavigationResponsePolicy) -> Void
        ) {
            guard let target = navigationResponse.response.url,
                  BrowserViewImpl.hasValidHttpsAuthority(target.absoluteString),
                  target.scheme?.lowercased() == "https",
                  target.host?.isEmpty == false,
                  target.user == nil,
                  target.password == nil
            else {
                if navigationResponse.isForMainFrame {
                    reportFailure("Only absolute HTTPS navigation is allowed.")
                }
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        fileprivate func setAllowedMessageOrigins(_ origins: [String]) -> Bool {
            let normalizedOrigins = origins.compactMap(BrowserViewImpl.normalizeHttpsOrigin)
            let hasInvalidOrigins = normalizedOrigins.count != origins.count ||
                Set(normalizedOrigins).count != origins.count
            invalidMessageOrigins = hasInvalidOrigins ? origins : nil
            let updated = hasInvalidOrigins ? Set<String>() : Set(normalizedOrigins)
            guard updated != allowedMessageOrigins else { return false }
            allowedMessageOrigins = updated
            lastSentMessage = nil
            return true
        }

        fileprivate func reportInvalidMessageOriginsIfNeeded() {
            guard let invalidMessageOrigins else {
                reportedInvalidMessageOrigins = nil
                return
            }
            guard reportedInvalidMessageOrigins != invalidMessageOrigins else { return }
            reportedInvalidMessageOrigins = invalidMessageOrigins
            reportFailure("Message origins must be unique, exact HTTPS origins without paths or wildcards.")
        }

        fileprivate func reportFailure(_ message: String) {
            Task { @MainActor [weak self] in
                self?.onFailed?(message)
            }
        }

        fileprivate func updateMessage(_ message: String?) {
            guard self.message != message else { return }
            self.message = message
            lastSentMessage = nil
        }

        public func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            pageLoaded = false
            lastSentMessage = nil
        }

        public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            pageLoaded = true
            if let address = webView.url?.absoluteString {
                onNavigated?(address)
            }
            sendCurrentMessage(to: webView)
        }

        public func webView(
            _ webView: WKWebView,
            didFailProvisionalNavigation navigation: WKNavigation!,
            withError error: Error
        ) {
            pageLoaded = false
            onFailed?(error.localizedDescription)
        }

        public func webView(
            _ webView: WKWebView,
            didFail navigation: WKNavigation!,
            withError error: Error
        ) {
            pageLoaded = false
            onFailed?(error.localizedDescription)
        }
    }

    private static func javaScriptStringLiteral(_ value: String) -> String {
        guard let data = try? JSONEncoder().encode(value),
              let literal = String(data: data, encoding: .utf8)
        else { return "\"\"" }
        return literal
    }

    private static func bridgeUserScript(for origins: Set<String>) -> WKUserScript? {
        guard !origins.isEmpty,
              let data = try? JSONEncoder().encode(origins.sorted()),
              let originList = String(data: data, encoding: .utf8)
        else { return nil }
        let source = "if (\(originList).includes(window.location.origin)) { window.Nexa = window.Nexa || {}; window.Nexa.postMessage = function(message) { window.webkit.messageHandlers.nexaBridge.postMessage(String(message)); }; }"
        return WKUserScript(source: source, injectionTime: .atDocumentStart, forMainFrameOnly: true)
    }

    fileprivate static func normalizeHttpsOrigin(_ value: String) -> String? {
        guard hasValidHttpsAuthority(value),
              let components = URLComponents(string: value),
              components.scheme?.lowercased() == "https",
              let host = components.host?.lowercased(), !host.isEmpty,
              components.user == nil,
              components.password == nil,
              components.path.isEmpty || components.path == "/",
              components.query == nil,
              components.fragment == nil
        else { return nil }
        if let port = components.port {
            guard (1...65_535).contains(port) else { return nil }
            if port != 443 { return "https://\(host):\(port)" }
        }
        return "https://\(host)"
    }

    private static func hasValidHttpsAuthority(_ value: String) -> Bool {
        guard let separator = value.range(of: "://"),
              value[..<separator.lowerBound].lowercased() == "https"
        else { return false }
        let authorityStart = separator.upperBound
        let authorityEnd = value[authorityStart...].firstIndex(where: { "/?#".contains($0) }) ?? value.endIndex
        let authority = value[authorityStart..<authorityEnd]
        guard !authority.isEmpty, !authority.contains("@") else { return false }

        let portText: Substring
        if authority.first == "[" {
            guard let hostEnd = authority.firstIndex(of: "]"), hostEnd > authority.startIndex else {
                return false
            }
            let suffix = authority[authority.index(after: hostEnd)...]
            if suffix.isEmpty { return true }
            guard suffix.first == ":" else { return false }
            portText = suffix.dropFirst()
        } else {
            guard let separator = authority.lastIndex(of: ":") else { return true }
            guard authority[..<separator].firstIndex(of: ":") == nil else { return false }
            portText = authority[authority.index(after: separator)...]
        }
        guard !portText.isEmpty,
              portText.utf8.allSatisfy({ $0 >= 48 && $0 <= 57 }),
              let port = Int(portText),
              (1...65_535).contains(port)
        else { return false }
        return true
    }

    fileprivate static func normalizedSecurityOrigin(_ url: URL?) -> String? {
        guard let url,
              url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(), !host.isEmpty
        else { return nil }
        if let port = url.port, port != 443 { return "https://\(host):\(port)" }
        return "https://\(host)"
    }

    fileprivate static func normalizedSecurityOrigin(_ origin: WKSecurityOrigin) -> String? {
        guard origin.protocol.lowercased() == "https",
              !origin.host.isEmpty
        else { return nil }
        if origin.port > 0, origin.port != 443 {
            return "https://\(origin.host.lowercased()):\(origin.port)"
        }
        return "https://\(origin.host.lowercased())"
    }
}
