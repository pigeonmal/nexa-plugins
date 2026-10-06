# `@nexa/biometrics`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Security](https://img.shields.io/badge/Security-Face%20ID%20%2F%20BiometricPrompt-brightgreen.svg)](https://developer.apple.com/documentation/localauthentication)

Secure biometric authentication for iOS and Android.

Backed by Apple `LocalAuthentication` (Face ID / Touch ID) on iOS and AndroidX `BiometricPrompt` on Android.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.biometrics" as Biometrics

component SecureVaultScreen() {
    state isUnlocked: Bool = false
    state errorMessage: String? = null

    VStack(spacing: 24) {
        if isUnlocked {
            Text("Vault Unlocked", size: 20, color: "#34C759")
            Text("Secret documents are now accessible.")
        } else {
            Text("Authentication Required", size: 18, weight: "bold")
            
            Biometrics.BiometricButton(
                title: "Unlock with Face ID",
                reason: "Authenticate to view encrypted credentials",
                onAuthenticated: () => {
                    isUnlocked = true
                    errorMessage = null
                },
                onFailed: (failure) => {
                    errorMessage = "Authentication failed: \(failure)"
                }
            )

            if let err = errorMessage {
                Text(err, color: "#FF3B30", size: 14)
            }
        }
    }
}
```

---

## 2. API Reference

### `BiometricButton` Native Component

User-triggered native authentication control. Initiating authentication requires explicit user tap interaction, ensuring compliance with Apple App Store Review and Android security invariants.

```nexa
native component BiometricButton
```

#### Properties

| Prop | Type | Description |
|---|---|---|
| `title` | `String` | Visual label displayed on the trigger button |
| `reason` | `String` | System dialog subtitle explaining why the biometric check is requested |

#### Events

| Event | Payload | Description |
|---|---|---|
| `authenticated` | — | Fired when Face ID, Touch ID, or fingerprint authentication succeeds |
| `failed` | `error: BiometricFailure` | Fired when biometric check is rejected, canceled, or unavailable |

---

### Data Structures & Enums

#### `BiometricFailure`

| Variant | Description |
|---|---|
| `notAvailable` | Device lacks biometric hardware support |
| `notEnrolled` | Biometric hardware present but no faces or fingerprints are registered in OS |
| `lockout` | Too many failed attempts; OS temporarily disabled biometric sensor |
| `userCanceled` | User explicitly dismissed the biometric prompt |
| `systemCanceled` | System interrupted prompt (e.g., incoming call or app backgrounding) |
| `authenticationFailed` | Biometric match failed (unrecognized face or fingerprint) |
| `passcodeNotSet` | Device has no lock screen PIN or passcode configured |
| `invalidContext` | Underlying native context was destroyed |
| `unknown` | Unspecified native OS authentication error |
