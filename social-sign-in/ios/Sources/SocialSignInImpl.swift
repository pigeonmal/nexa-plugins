import AuthenticationServices
import CryptoKit
import GoogleSignIn
import UIKit

@MainActor
public final class SocialSignInImpl: SocialSignInSpec {
    private let googleServerClientID: String
    private let googleIOSClientID: String?
    private var activeAppleFlow: AppleSignInFlow?

    public required init(_ googleServerClientID: String, _ googleIOSClientID: String?) {
        self.googleServerClientID = googleServerClientID
        self.googleIOSClientID = googleIOSClientID
        if let googleIOSClientID, !googleIOSClientID.isEmpty {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(
                clientID: googleIOSClientID,
                serverClientID: googleServerClientID
            )
        }
    }

    public func signIn(_ provider: SocialProvider) async throws(SocialSignInError) -> SocialCredential {
        switch provider {
        case .google:
            return try await signInWithGoogle()
        case .apple:
            return try await signInWithApple()
        }
    }

    private func signInWithGoogle() async throws(SocialSignInError) -> SocialCredential {
        guard !googleServerClientID.isEmpty,
              let googleIOSClientID,
              !googleIOSClientID.isEmpty else {
            throw .appClientNotConfigured
        }
        guard let presenter = Self.topViewController() else {
            throw .presentationUnavailable
        }

        GIDSignIn.sharedInstance.configuration = GIDConfiguration(
            clientID: googleIOSClientID,
            serverClientID: googleServerClientID
        )
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
            guard let idToken = result.user.idToken?.tokenString, !idToken.isEmpty else {
                throw SocialSignInError.invalidCredential
            }
            let profile = result.user.profile
            return SocialCredential(
                provider: .google,
                idToken: idToken,
                authorizationCode: "",
                nonce: "",
                email: profile?.email ?? "",
                displayName: profile?.name ?? "",
                photoURL: profile?.imageURL(withDimension: 256)?.absoluteString ?? ""
            )
        } catch let error as SocialSignInError {
            throw error
        } catch {
            throw .signInFailed
        }
    }

    private func signInWithApple() async throws(SocialSignInError) -> SocialCredential {
        guard Self.presentationAnchor() != nil else {
            throw .presentationUnavailable
        }
        let flow = AppleSignInFlow()
        activeAppleFlow = flow
        defer { activeAppleFlow = nil }

        do {
            let result = try await flow.perform()
            guard let tokenData = result.identityToken,
                  let idToken = String(data: tokenData, encoding: .utf8),
                  !idToken.isEmpty else {
                throw SocialSignInError.invalidCredential
            }
            let authorizationCode = result.authorizationCode
                .flatMap { String(data: $0, encoding: .utf8) } ?? ""
            guard !authorizationCode.isEmpty else {
                throw SocialSignInError.invalidCredential
            }
            let name = result.fullName.map { PersonNameComponentsFormatter().string(from: $0) } ?? ""
            return SocialCredential(
                provider: .apple,
                idToken: idToken,
                authorizationCode: authorizationCode,
                nonce: flow.rawNonce,
                email: result.email ?? "",
                displayName: name,
                photoURL: ""
            )
        } catch let error as ASAuthorizationError where error.code == .canceled {
            throw .userCanceled
        } catch let error as SocialSignInError {
            throw error
        } catch {
            throw .signInFailed
        }
    }

    public func signOut(_ provider: SocialProvider) async {
        if provider == .google {
            GIDSignIn.sharedInstance.signOut()
        }
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        guard var controller = scene?.windows.first(where: \.isKeyWindow)?.rootViewController else {
            return nil
        }
        while let presented = controller.presentedViewController {
            controller = presented
        }
        return controller
    }

    fileprivate static func presentationAnchor() -> ASPresentationAnchor? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }?
            .windows.first(where: \.isKeyWindow)
    }
}

@MainActor
private final class AppleSignInFlow: NSObject, ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding {
    let rawNonce = UUID().uuidString + UUID().uuidString
    private var controller: ASAuthorizationController?
    private var continuation: CheckedContinuation<ASAuthorizationAppleIDCredential, Error>?

    func perform() async throws -> ASAuthorizationAppleIDCredential {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let request = ASAuthorizationAppleIDProvider().createRequest()
            request.requestedScopes = [.fullName, .email]
            request.nonce = Self.sha256(rawNonce)

            let controller = ASAuthorizationController(authorizationRequests: [request])
            controller.delegate = self
            controller.presentationContextProvider = self
            self.controller = controller
            controller.performRequests()
        }
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        SocialSignInImpl.presentationAnchor() ?? UIWindow(frame: .zero)
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else {
            finish(.failure(SocialSignInError.invalidCredential))
            return
        }
        finish(.success(credential))
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        finish(.failure(error))
    }

    private func finish(_ result: Result<ASAuthorizationAppleIDCredential, Error>) {
        controller = nil
        continuation?.resume(with: result)
        continuation = nil
    }

    private static func sha256(_ value: String) -> String {
        let digest = SHA256.hash(data: Data(value.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}

@MainActor
public final class SocialSignInAppDelegate: NSObject, UIApplicationDelegate {
    public func application(
        _ application: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        GIDSignIn.sharedInstance.handle(url)
    }
}
