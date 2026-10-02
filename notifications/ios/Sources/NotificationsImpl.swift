import Foundation
import UIKit
import UserNotifications

@MainActor
private final class WeakNotificationsReference {
    weak var value: NotificationsImpl?

    init(_ value: NotificationsImpl) {
        self.value = value
    }
}

@MainActor
private final class NotificationsRemoteHub {
    static let shared = NotificationsRemoteHub()

    private var observers: [WeakNotificationsReference] = []
    private var pendingReceived: [RemoteNotification] = []
    private var pendingOpened: [RemoteNotification] = []
    private var pendingTokens: [String] = []
    private var tokenWaiters: [CheckedContinuation<String, NotificationError>] = []
    private var currentToken: String?

    func add(_ owner: NotificationsImpl) {
        pruneObservers()
        if !observers.contains(where: { $0.value === owner }) {
            observers.append(WeakNotificationsReference(owner))
        }
    }

    func register() async throws(NotificationError) -> String {
        return try await withCheckedThrowingContinuation { continuation in
            tokenWaiters.append(continuation)
            UIApplication.shared.registerForRemoteNotifications()
        }
    }

    func didRegister(deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        guard !token.isEmpty else {
            didFailToRegister()
            return
        }

        let previous = currentToken
        currentToken = token
        if previous != token {
            tokenChanged(token)
        }
        let waiters = tokenWaiters
        tokenWaiters.removeAll(keepingCapacity: true)
        for waiter in waiters {
            waiter.resume(returning: token)
        }
    }

    func didFailToRegister() {
        let waiters = tokenWaiters
        tokenWaiters.removeAll(keepingCapacity: true)
        for waiter in waiters {
            waiter.resume(throwing: .remoteRegistrationUnavailable)
        }
    }

    func receive(_ notification: RemoteNotification) {
        dispatch(notification, opened: false)
    }

    func open(_ notification: RemoteNotification) {
        dispatch(notification, opened: true)
    }

    func tokenChanged(_ token: String) {
        let interested = liveObservers().filter { $0.onRemoteTokenChanged != nil }
        guard !interested.isEmpty else {
            if pendingTokens.count == 20 {
                pendingTokens.removeFirst()
            }
            pendingTokens.append(token)
            return
        }
        for owner in interested {
            owner.onRemoteTokenChanged?(token)
        }
    }

    private func dispatch(_ notification: RemoteNotification, opened: Bool) {
        let owners = liveObservers()
        let interested = owners.filter {
            opened ? $0.onRemoteNotificationOpened != nil : $0.onRemoteNotificationReceived != nil
        }
        guard !interested.isEmpty else {
            let pending = opened ? pendingOpened : pendingReceived
            if pending.count == 20 {
                if opened {
                    pendingOpened.removeFirst()
                } else {
                    pendingReceived.removeFirst()
                }
            }
            if opened {
                pendingOpened.append(notification)
            } else {
                pendingReceived.append(notification)
            }
            return
        }
        for owner in interested {
            if opened {
                owner.onRemoteNotificationOpened?(notification)
            } else {
                owner.onRemoteNotificationReceived?(notification)
            }
        }
    }

    func deliverPendingNotifications(to owner: NotificationsImpl, opened: Bool) {
        if opened {
            guard owner.onRemoteNotificationOpened != nil else { return }
            let pending = pendingOpened
            pendingOpened.removeAll(keepingCapacity: true)
            for notification in pending {
                owner.onRemoteNotificationOpened?(notification)
            }
        } else {
            guard owner.onRemoteNotificationReceived != nil else { return }
            let pending = pendingReceived
            pendingReceived.removeAll(keepingCapacity: true)
            for notification in pending {
                owner.onRemoteNotificationReceived?(notification)
            }
        }
    }

    func deliverPendingTokens(to owner: NotificationsImpl) {
        guard owner.onRemoteTokenChanged != nil else { return }
        let pending = pendingTokens
        pendingTokens.removeAll(keepingCapacity: true)
        for token in pending {
            owner.onRemoteTokenChanged?(token)
        }
    }

    private func liveObservers() -> [NotificationsImpl] {
        pruneObservers()
        return observers.compactMap(\.value)
    }

    private func pruneObservers() {
        observers.removeAll { $0.value == nil }
    }
}

@MainActor
public final class NotificationsImpl: NotificationsSpec {
    private let center = UNUserNotificationCenter.current()
    private let identifiersKey = "dev.nexa.notifications.local.identifiers"
    private let requestPrefix = "dev.nexa.notifications.local."

    public var onRemoteNotificationReceived: ((RemoteNotification) -> Void)? {
        didSet {
            NotificationsRemoteHub.shared.deliverPendingNotifications(to: self, opened: false)
        }
    }

    public var onRemoteNotificationOpened: ((RemoteNotification) -> Void)? {
        didSet {
            NotificationsRemoteHub.shared.deliverPendingNotifications(to: self, opened: true)
        }
    }

    public var onRemoteTokenChanged: ((String) -> Void)? {
        didSet {
            NotificationsRemoteHub.shared.deliverPendingTokens(to: self)
        }
    }

    public init() {
        NotificationsRemoteHub.shared.add(self)
    }

    public func scheduleLocal(
        _ identifier: String,
        _ title: String,
        _ body: String,
        _ delaySeconds: Int64
    ) async throws(NotificationError) {
        guard !identifier.isEmpty else { throw .invalidIdentifier }
        guard delaySeconds >= 0 else { throw .invalidDelay }

        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .authorized, .provisional, .ephemeral:
            break
        case .notDetermined, .denied:
            throw .permissionDenied
        @unknown default:
            throw .permissionDenied
        }

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default

        let trigger: UNNotificationTrigger? = delaySeconds == 0
            ? nil
            : UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(delaySeconds), repeats: false)
        let request = UNNotificationRequest(
            identifier: requestPrefix + identifier,
            content: content,
            trigger: trigger
        )

        do {
            try await center.add(request)
        } catch {
            throw .schedulerUnavailable
        }

        var identifiers = Set(UserDefaults.standard.stringArray(forKey: identifiersKey) ?? [])
        identifiers.insert(identifier)
        UserDefaults.standard.set(identifiers.sorted(), forKey: identifiersKey)
    }

    public func isLocalPending(_ identifier: String) async throws(NotificationError) -> Bool {
        guard !identifier.isEmpty else { throw .invalidIdentifier }
        let requests = await center.pendingNotificationRequests()
        return requests.contains { $0.identifier == requestPrefix + identifier }
    }

    public func cancelLocal(_ identifier: String) {
        let requestIdentifier = requestPrefix + identifier
        center.removePendingNotificationRequests(withIdentifiers: [requestIdentifier])
        center.removeDeliveredNotifications(withIdentifiers: [requestIdentifier])
        updateTrackedIdentifiers { $0.remove(identifier) }
    }

    public func cancelAllLocal() {
        let identifiers = UserDefaults.standard.stringArray(forKey: identifiersKey) ?? []
        let requestIdentifiers = identifiers.map { requestPrefix + $0 }
        center.removePendingNotificationRequests(withIdentifiers: requestIdentifiers)
        center.removeDeliveredNotifications(withIdentifiers: requestIdentifiers)
        UserDefaults.standard.removeObject(forKey: identifiersKey)
    }

    public func registerRemote() async throws(NotificationError) -> String {
        // APNs registration is independent of notification presentation
        // authorization. Apps may need the token for silent/data pushes even
        // when the user has disabled visible notifications.
        return try await NotificationsRemoteHub.shared.register()
    }

    private func updateTrackedIdentifiers(_ update: (inout Set<String>) -> Void) {
        var identifiers = Set(UserDefaults.standard.stringArray(forKey: identifiersKey) ?? [])
        update(&identifiers)
        UserDefaults.standard.set(identifiers.sorted(), forKey: identifiersKey)
    }
}

@MainActor
public final class NotificationsAppDelegate: NSObject, UIApplicationDelegate, @preconcurrency UNUserNotificationCenterDelegate {
    public func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        if let userInfo = launchOptions?[.remoteNotification] as? [AnyHashable: Any] {
            NotificationsRemoteHub.shared.open(Self.notification(from: userInfo))
        }
        return true
    }

    public func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        NotificationsRemoteHub.shared.didRegister(deviceToken: deviceToken)
    }

    public func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        NotificationsRemoteHub.shared.didFailToRegister()
    }

    public func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        NotificationsRemoteHub.shared.receive(Self.notification(from: userInfo))
        completionHandler(.newData)
    }

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        if Self.isRemote(notification) {
            NotificationsRemoteHub.shared.receive(Self.notification(from: notification))
        }
        if #available(iOS 14.0, *) {
            completionHandler([.banner, .list, .sound, .badge])
        } else {
            completionHandler([.alert, .sound, .badge])
        }
    }

    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if Self.isRemote(response.notification) {
            NotificationsRemoteHub.shared.open(Self.notification(from: response.notification))
        }
        completionHandler()
    }

    private static func isRemote(_ notification: UNNotification) -> Bool {
        notification.request.content.userInfo["aps"] != nil
    }

    private static func notification(from notification: UNNotification) -> RemoteNotification {
        let content = notification.request.content
        return RemoteNotification(
            identifier: notification.request.identifier,
            title: content.title,
            body: content.body,
            data: stringValues(content.userInfo)
        )
    }

    private static func notification(from userInfo: [AnyHashable: Any]) -> RemoteNotification {
        let aps = userInfo["aps"] as? [String: Any]
        let alert = aps?["alert"] as? [String: Any]
        let title = alert?["title"] as? String ?? (aps?["alert"] as? String ?? "")
        let body = alert?["body"] as? String ?? ""
        let identifier = userInfo["gcm.message_id"] as? String
            ?? userInfo["google.message_id"] as? String
            ?? ""
        return RemoteNotification(
            identifier: identifier,
            title: title,
            body: body,
            data: stringValues(userInfo)
        )
    }

    private static func stringValues(_ values: [AnyHashable: Any]) -> [String: String] {
        values.reduce(into: [String: String]()) { result, entry in
            guard let key = entry.key as? String, let value = entry.value as? String, key != "aps" else {
                return
            }
            result[key] = value
        }
    }
}
