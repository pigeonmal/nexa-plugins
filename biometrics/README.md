# `@nexa/biometrics`

User-triggered biometric authentication through iOS LocalAuthentication and
Android's system BiometricPrompt.

```nexa
plugin "dev.nexa.biometrics" as Biometrics

app BiometricDemo {
    state authenticated = false
    state failed = false

    body {
        Column(spacing: 12) {
            Biometrics.BiometricButton(
                title: "Unlock",
                reason: "Confirm your identity to continue"
            )
                .onAuthenticated { authenticated = true }
                .onFailed { error -> failed = true }

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

`BiometricButton` starts the system prompt only after the user taps it. Its
`onAuthenticated` event reports success. `onFailed` carries a
`BiometricFailure` enum, including unavailable hardware, missing enrollment,
lockout, user or system cancellation, and invalid context. The Android prompt
remains open after an unrecognized scan so the user can retry. Leaving the
component cancels an active prompt.

iOS uses the device's enrolled Face ID or Touch ID through
`LAContext.deviceOwnerAuthenticationWithBiometrics`; the plugin contributes
`NSFaceIDUsageDescription` to the host Info.plist. Android uses the platform
`android.hardware.biometrics.BiometricPrompt`, requires API 28+, and declares
`USE_BIOMETRIC`. Set the app's Android `minSdk` to at least 28 when using this
plugin; Nexa rejects a plugin minimum above the app minimum.

The plugin does not store biometric data or treat biometrics as a replacement
for the app's account credentials.
