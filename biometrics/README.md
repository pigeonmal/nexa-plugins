# `dev.nexa.biometrics`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Security](https://img.shields.io/badge/Security-Face%20ID%20%2F%20BiometricPrompt-brightgreen.svg)](https://developer.apple.com/documentation/localauthentication)

Secure biometric authentication for iOS and Android.

Backed by Apple `LocalAuthentication` (Face ID / Touch ID) on iOS and AndroidX `BiometricPrompt` on Android.

---

> **Android minimum API:** 28. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/biometrics" as Biometrics

app BiometricsDemo {
    state authenticated = false
    state failed = false

    body {
        Column(spacing: 12, padding: 20) {
            Text("Biometric authentication")
            Biometrics.BiometricButton(
                title: "Authenticate",
                reason: "Confirm your identity to continue"
            )
                .onAuthenticated {
                    authenticated = true
                    failed = false
                }
                .onFailed { error ->
                    authenticated = false
                    failed = true
                }
            if authenticated {
                Text("Authenticated")
            }
            if failed {
                Text("Authentication was not completed")
            }
        }
    }
}
```

---

## 2. API Reference

### `BiometricButton` control

User-triggered native authentication control. Initiating authentication requires explicit user tap interaction, ensuring compliance with Apple App Store Review and Android security invariants.


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
