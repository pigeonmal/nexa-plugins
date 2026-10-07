import SafariServices
import UIKit

@MainActor
public final class SystemBrowserImpl: SystemBrowserSpec {
    public init() {}

    public func open(_ rawURL: String, _ inApp: Bool) async throws(BrowserError) {
        guard let url = URL(string: rawURL),
              let scheme = url.scheme?.lowercased() else {
            throw .invalidURL
        }

        if inApp {
            guard (scheme == "http" || scheme == "https"), url.host != nil else {
                throw .invalidURL
            }
            guard let presenter = Self.topViewController() else {
                throw .presentationUnavailable
            }
            presenter.present(SFSafariViewController(url: url), animated: true)
        } else {
            await UIApplication.shared.open(url, options: [:])
        }
    }

    public func openInApp(_ rawURL: String, _ toolbarColor: String?) async throws(BrowserError) {
        guard let url = URL(string: rawURL),
              let scheme = url.scheme?.lowercased(),
              (scheme == "http" || scheme == "https"),
              url.host != nil,
              url.user == nil,
              url.password == nil else {
            throw .invalidURL
        }

        let color = toolbarColor.flatMap(Self.parseHexColor)
        if toolbarColor != nil && color == nil { throw .invalidURL }
        guard let presenter = Self.topViewController() else {
            throw .presentationUnavailable
        }
        let browser = SFSafariViewController(url: url)
        browser.preferredBarTintColor = color
        presenter.present(browser, animated: true)
    }

    private static func parseHexColor(_ value: String) -> UIColor? {
        let digits = value.hasPrefix("#") ? String(value.dropFirst()) : value
        guard digits.count == 6, let rgb = UInt64(digits, radix: 16) else { return nil }
        return UIColor(
            red: CGFloat((rgb >> 16) & 0xff) / 255,
            green: CGFloat((rgb >> 8) & 0xff) / 255,
            blue: CGFloat(rgb & 0xff) / 255,
            alpha: 1
        )
    }

    private static func topViewController() -> UIViewController? {
        let root = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController

        var presenter = root
        while let presented = presenter?.presentedViewController {
            presenter = presented
        }
        return presenter
    }
}
