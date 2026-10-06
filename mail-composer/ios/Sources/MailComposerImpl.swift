import MessageUI
import UIKit

@MainActor
public final class MailComposerImpl: NSObject, MailComposerSpec, @preconcurrency MFMailComposeViewControllerDelegate {
    public var onCompleted: ((MailComposerResult) -> Void)?
    public var available: Bool { MFMailComposeViewController.canSendMail() }
    public var deviceInfo: String {
        let device = UIDevice.current
        return "\(device.model) / iOS \(device.systemVersion)"
    }

    public override init() {
        super.init()
    }

    public func present(
        _ recipients: [String],
        _ subject: String,
        _ body: String
    ) async throws(MailComposerError) {
        guard MFMailComposeViewController.canSendMail() else {
            throw .unavailable
        }
        guard let presenter = Self.topViewController() else {
            throw .presentationUnavailable
        }

        let composer = MFMailComposeViewController()
        composer.setToRecipients(recipients)
        composer.setSubject(subject)
        composer.setMessageBody(body, isHTML: false)
        composer.mailComposeDelegate = self
        presenter.present(composer, animated: true)
    }

    public func mailComposeController(
        _ controller: MFMailComposeViewController,
        didFinishWith result: MFMailComposeResult,
        error: Error?
    ) {
        let completion: MailComposerResult = switch result {
        case .sent: .sent
        case .saved: .saved
        case .cancelled: .cancelled
        case .failed: .failed
        @unknown default: .failed
        }
        controller.dismiss(animated: true) { [weak self] in
            self?.onCompleted?(completion)
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
