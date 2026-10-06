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
