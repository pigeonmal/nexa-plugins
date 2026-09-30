import LocalAuthentication
import SwiftUI

/// A user-triggered LocalAuthentication control. The system owns the prompt;
/// the view owns and invalidates its LAContext for the visible lifetime.
@MainActor
public struct BiometricButtonImpl: View {
    public let title: String
    public let reason: String
    public let onAuthenticated: (() -> Void)?
    public let onFailed: ((BiometricFailure) -> Void)?

    @State private var session: BiometricPromptSession?
    @State private var isAuthenticating = false

    public init(
        title: String,
        reason: String,
        onAuthenticated: (() -> Void)?,
        onFailed: ((BiometricFailure) -> Void)?
    ) {
        self.title = title
        self.reason = reason
        self.onAuthenticated = onAuthenticated
        self.onFailed = onFailed
    }

    public var body: some View {
        Button(title, action: authenticate)
            .disabled(isAuthenticating)
            .onDisappear(perform: cancelAuthentication)
    }

    private func authenticate() {
        guard !isAuthenticating else { return }
        let authenticationContext = LAContext()
        var availabilityError: NSError?
        guard authenticationContext.canEvaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            error: &availabilityError
        ) else {
            onFailed?(Self.failure(for: availabilityError))
            return
        }

        let promptSession = BiometricPromptSession(
            context: authenticationContext,
            onAuthenticated: onAuthenticated,
            onFailed: onFailed,
            onCompleted: {
                session = nil
                isAuthenticating = false
            }
        )
        session = promptSession
        isAuthenticating = true
        authenticationContext.evaluatePolicy(
            .deviceOwnerAuthenticationWithBiometrics,
            localizedReason: reason
        ) { success, error in
            let errorCode = (error as? LAError)?.code.rawValue
            Task { @MainActor [weak promptSession] in
                promptSession?.complete(success: success, errorCode: errorCode)
            }
        }
    }

    private func cancelAuthentication() {
        session?.cancel()
        session = nil
        isAuthenticating = false
    }

    private static func failure(for error: NSError?) -> BiometricFailure {
        guard let code = error.flatMap({ LAError.Code(rawValue: $0.code) }) else {
            return .unknown
        }
        return failure(for: code)
    }

    fileprivate static func failure(for code: LAError.Code) -> BiometricFailure {
        switch code {
        case .biometryNotAvailable, .biometryNotPaired:
            return .notAvailable
        case .biometryNotEnrolled:
            return .notEnrolled
        case .biometryLockout:
            return .lockout
        case .userCancel:
            return .userCanceled
        case .systemCancel, .appCancel:
            return .systemCanceled
        case .authenticationFailed:
            return .authenticationFailed
        case .passcodeNotSet:
            return .passcodeNotSet
        case .invalidContext:
            return .invalidContext
        default:
            return .unknown
        }
    }
}

/// Keeps non-Sendable LocalAuthentication state isolated on the main actor so
/// the framework's completion callback can return only a Boolean and code.
@MainActor
private final class BiometricPromptSession {
    private let context: LAContext
    private let onAuthenticated: (() -> Void)?
    private let onFailed: ((BiometricFailure) -> Void)?
    private let onCompleted: () -> Void
    private var isActive = true

    init(
        context: LAContext,
        onAuthenticated: (() -> Void)?,
        onFailed: ((BiometricFailure) -> Void)?,
        onCompleted: @escaping () -> Void
    ) {
        self.context = context
        self.onAuthenticated = onAuthenticated
        self.onFailed = onFailed
        self.onCompleted = onCompleted
    }

    func complete(success: Bool, errorCode: Int?) {
        guard isActive else { return }
        isActive = false
        context.invalidate()
        onCompleted()
        if success {
            onAuthenticated?()
        } else if let errorCode, let code = LAError.Code(rawValue: errorCode) {
            onFailed?(BiometricButtonImpl.failure(for: code))
        } else {
            onFailed?(.unknown)
        }
    }

    func cancel() {
        guard isActive else { return }
        isActive = false
        context.invalidate()
    }
}
