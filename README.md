# Nexa Official Plugins

![Nexa](https://img.shields.io/badge/Nexa-AOT%20Transpiler-blue.svg) ![License](https://img.shields.io/badge/License-MPL--2.0-brightgreen.svg)

This repository is the official local package collection for Nexa apps. Each package declares its public API in `native.nxid` and implements it for supported native platforms.

**Quick start:** From a Nexa project with this repository checked out as `plugins/`, reference a package by its local path and use its typed API. Biometrics requires Android API 28 or later, so set `android.minSdk` to at least `28` in `nexa.config.nx`.

```nexa
plugin "plugins/biometrics" as Biometrics

app VaultUnlock {
    state authenticated: Bool = false
    state failed: Bool = false

    body {
        Column(spacing: 12, padding: 20) {
            Text("Unlock the private vault", fontSize: 22, fontWeight: Bold)
            Biometrics.BiometricButton(
                title: "Authenticate",
                reason: "Confirm your identity to view saved credentials"
            )
                .onAuthenticated {
                    authenticated = true
                    failed = false
                }
                .onFailed { error ->
                    authenticated = false
                    failed = true
                }
            if authenticated { Text("Vault unlocked") }
            if failed { Text("Authentication was not completed") }
        }
    }
}
```

The package path is relative to the `.nx` source file. There is no package registry or `nexa plugin add` command. See the [plugin authoring and integration guide](../docs/plugins.md) for local dependency resolution and native package development.

## Package catalog

| Package | Use | iOS min | Android minSdk | Native implementation |
|---|---|---:|---:|---|
| [audio-player](audio-player) | Background audio playback and transport controls | 17.0 | 26 | AVPlayer; Android Media3 |
| [biometrics](biometrics) | User-triggered biometric authentication | 13.0 | 28 | LocalAuthentication; AndroidX BiometricPrompt |
| [browser](browser) | In-app browser presentation and external URL handoff | 16.0 | 23 | SFSafariViewController; Android Custom Tabs |
| [camera](camera) | Camera preview, photo/video capture, barcode and frame events | 17.0 | 23 | AVFoundation/Vision; CameraX/ML Kit |
| [data-extractor](data-extractor) | On-device date, phone, URL, email, and address detection | 13.0 | 28 | NSDataDetector; Android TextClassifier |
| [in-app-purchases](in-app-purchases) | Store product, purchase, restore, and transaction flows | 17.0 | 23 | StoreKit 2; Google Play Billing |
| [mail-composer](mail-composer) | Native email composition | 16.0 | 23 | MessageUI; Android mail intent chooser |
| [maps](maps) | Native map view with typed pins and selection events | 17.0 | 23 | MapKit; Google Maps SDK |
| [media-picker](media-picker) | System image and video selection | 16.0 | 23 | PHPicker; Android Photo Picker |
| [mmkv](mmkv) | Typed persistent key-value storage | 13.0 | 21 | Tencent MMKV on both platforms |
| [notifications](notifications) | Local scheduling and remote notification events | 13.0 | 23 | UserNotifications; Android NotificationManager/Firebase |
| [sensors](sensors) | Accelerometer, gyroscope, and pedometer readings | 17.0 | 26 | CoreMotion; Android SensorManager |
| [sqlite](sqlite) | Typed SQLite queries, migrations, and reactive signals | 13.0 | 23 | SQLite on iOS and Android |
| [video-player](video-player) | Native video playback and player views | 17.0 | 23 | AVPlayer; Android Media3 |
| [websocket](websocket) | Typed text and binary WebSocket messaging | 13.0 | 21 | URLSessionWebSocketTask; OkHttp |
| [webview](webview) | Embedded web content and origin-scoped messages | 17.0 | 24 | WebKit; Android WebView |

Each package README links to its checked contract and describes its complete public API. Native C++ output is available only for compatible contract shapes; a Swift/Kotlin package does not imply a C++ implementation.

The `iOS min` and `Android minSdk` columns are the values each `plugin.config.nx` declares. An app's `android.minSdk` must be at least the highest plugin minimum, and generation raises the iOS deployment target to the highest plugin minimum, so raising a manifest floor changes what an app needs. The `docs-freshness` CI job fails if this table disagrees with a manifest.

## Package commands

| Command | Purpose |
|---|---|
| `nexa plugin init dev.example.sensor --out plugins/sensor --name Sensor` | Scaffold a native package. |
| `nexa plugin init dev.example.formatters --out plugins/formatters --kind pure` | Scaffold a Nexa-only package. |
| `nexa plugin check plugins/sensor` | Validate the manifest, contract, sources, and configured native checks. |
| `nexa plugin generate plugins/sensor --target swift --out generated/swift` | Generate a contract binding; `kotlin` and compatible `cpp` targets are also available. |

`nexa plugin check` validates the declared package surface. Native build checks require the corresponding platform toolchain and package dependencies.
