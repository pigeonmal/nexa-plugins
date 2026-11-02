# Nexa Social Sign-In Plugin

| **Package** | `dev.nexa.social-sign-in` | **Version** | `0.1.0` |
|---|---|---|---|
| **Platforms** | iOS 16+ and Android API 23+ | **Providers** | Google (iOS + Android), Apple (iOS) |

One typed Nexa API returns provider credentials without moving product logic into Swift or Kotlin. The iOS implementation uses Google Sign-In and Authentication Services; Android uses Credential Manager for Google.

## Quick start

```nexa
plugin "plugins/social-sign-in" as Social

app SignInExample {
    let identity = Social.SocialSignIn(
        googleServerClientID: "YOUR_WEB_SERVER_CLIENT_ID.apps.googleusercontent.com",
        googleIOSClientID: "YOUR_IOS_CLIENT_ID.apps.googleusercontent.com"
    )

    async fn googleIDToken() -> String {
        try {
            let credential = await identity.signIn(provider: Social.SocialProvider.google)
            return credential.idToken
        } catch {
            case Social.SocialSignInError.appClientNotConfigured { return "" }
            case Social.SocialSignInError.presentationUnavailable { return "" }
            case Social.SocialSignInError.credentialUnavailable { return "" }
            case Social.SocialSignInError.providerUnavailable { return "" }
            case Social.SocialSignInError.invalidCredential { return "" }
            case Social.SocialSignInError.userCanceled { return "" }
            case Social.SocialSignInError.signInFailed { return "" }
        }
    }
}
```

## Platform support

| Provider | iOS | Android | Credential returned |
|---|---|---|---|
| Google | Google Sign-In SDK | Android Credential Manager | ID token, email and profile hints |
| Apple | Authentication Services | Unsupported (`providerUnavailable`) | ID token, single-use authorization code, raw nonce, email and first-use name |

Apple sign-in is supported on iOS only. Android does not start an Apple web authorization flow and returns the typed `providerUnavailable` error.

## Configuration

| Constructor parameter | Type | Default | Meaning |
|---|---|---|---|
| `googleServerClientID` | `String` | Required | Existing Google Web OAuth client ID used as the backend ID-token audience on both platforms. |
| `googleIOSClientID` | `String?` | Required for iOS Google sign-in | Google OAuth client registered as an iOS app for the app's exact bundle ID. Android ignores it. |

The Web client ID can be the server audience; Google native iOS still requires an iOS-type OAuth client and its reversed client ID URL scheme. The app's Nexa `deepLinks` must contain that callback scheme. Configure both IDs in the same Google Cloud project.

Apple iOS sign-in uses the app's bundle ID and the `com.apple.developer.applesignin` entitlement contributed by this plugin. Enable Sign in with Apple for the matching App ID in the Apple Developer account and configure the app's signing team.

## Public API

### `SocialSignIn`

| Member | Signature | Behavior |
|---|---|---|
| Constructor | `SocialSignIn(googleServerClientID: String, googleIOSClientID: String?)` | Configures Google token audiences. |
| Sign in | `async signIn(provider: SocialProvider) -> SocialCredential throws SocialSignInError` | Presents the selected native provider and returns server-verifiable credentials. |
| Sign out | `async signOut(provider: SocialProvider)` | Clears Google provider session state. Apple credentials are revoked by the app's server; local sign-out is a no-op. |

### `SocialProvider`

| Case | Meaning |
|---|---|
| `google` | Google account authorization. |
| `apple` | Sign in with Apple. |

### `SocialCredential`

| Field | Type | Meaning |
|---|---|---|
| `provider` | `SocialProvider` | Provider that issued this credential. |
| `idToken` | `String` | Signed identity token to validate on the app server. |
| `authorizationCode` | `String` | Provider authorization code when returned; Apple codes are single-use. |
| `nonce` | `String` | Raw Apple nonce used to bind the request to its ID token; empty for Google. |
| `email` | `String` | Provider email hint; may be empty or omitted by the provider. |
| `displayName` | `String` | Provider display name; Apple returns it only on first authorization. |
| `photoURL` | `String` | Provider profile image URL; empty when unavailable. |

### `SocialSignInError`

| Case | Meaning |
|---|---|
| `appClientNotConfigured` | Required Google client identifier is missing. |
| `presentationUnavailable` | No foreground native presenter is available. |
| `credentialUnavailable` | The platform could not provide a provider credential. |
| `providerUnavailable` | The selected provider is not implemented on this platform. |
| `invalidCredential` | The native provider response lacks a valid ID token or required code. |
| `userCanceled` | The user dismissed or canceled authorization. |
| `signInFailed` | The provider authorization request failed. |

## Native mapping and data handling

| Platform | Implementation | Runtime behavior |
|---|---|---|
| iOS | GoogleSignIn 10.x and AuthenticationServices | Presents system-owned provider UI on the main actor. Apple ID tokens include a SHA-256 hashed nonce; the raw nonce is returned for server verification. |
| Android | Credential Manager and Google ID | Presents the platform credential selector; app product logic stays in `.nx`. |

Validate the ID token signature, issuer, expiry, audience and nonce on a trusted server. Do not use `email`, `displayName`, or `photoURL` as proof of identity. Apple returns the user's full name only on the first authorization, so persist it after server validation.
