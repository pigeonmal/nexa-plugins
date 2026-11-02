import UIKit

private final class ShareSheetItemSource: NSObject, UIActivityItemSource, @unchecked Sendable {
    private let text: String
    private let subject: String

    init(text: String, subject: String) {
        self.text = text
        self.subject = subject
    }

    func activityViewControllerPlaceholderItem(_ activityViewController: UIActivityViewController) -> Any {
        text
    }

    func activityViewController(
        _ activityViewController: UIActivityViewController,
        itemForActivityType activityType: UIActivity.ActivityType?
    ) -> Any? {
        text
    }

    func activityViewController(
        _ activityViewController: UIActivityViewController,
        subjectForActivityType activityType: UIActivity.ActivityType?
    ) -> String {
        subject
    }
}

@MainActor
public final class ShareSheetImpl: ShareSheetSpec {
    public required init() {}

    public func shareText(_ text: String, _ subject: String?) async throws(ShareSheetError) {
        try present(text: text, subject: subject)
    }

    public func shareLink(_ url: String, _ title: String?) async throws(ShareSheetError) {
        guard !url.isEmpty else { throw .presentationUnavailable }
        try present(text: url, subject: title)
    }

    private func present(text: String, subject: String?) throws(ShareSheetError) {
        guard let presenter = Self.topViewController() else {
            throw .presentationUnavailable
        }
        let item: Any = if let subject {
            ShareSheetItemSource(text: text, subject: subject)
        } else {
            text
        }
        let controller = UIActivityViewController(activityItems: [item], applicationActivities: nil)
        if let popover = controller.popoverPresentationController {
            popover.sourceView = presenter.view
            popover.sourceRect = CGRect(
                x: presenter.view.bounds.midX,
                y: presenter.view.bounds.midY,
                width: 0,
                height: 0
            )
            popover.permittedArrowDirections = []
        }
        presenter.present(controller, animated: true)
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
