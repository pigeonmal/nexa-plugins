# Nexa Official Plugins Catalog

[![Nexa](https://img.shields.io/badge/Nexa-AOT%20Transpiler-blue.svg)](https://github.com/pigeonmal/nexa)
[![License: MPL-2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](https://opensource.org/licenses/MPL-2.0)
[![Zero-Cost Native Bridges](https://img.shields.io/badge/Bridge-Zero--Copy%20Typed-purple.svg)](https://github.com/pigeonmal/nexa)

This repository contains the official, strongly-typed native plugin packages for the **Nexa** cross-platform mobile framework.

Every Nexa plugin is defined by a declarative Native Interface Definition (`.nxid`) contract and implemented directly in platform-native **Swift (iOS)**, **Kotlin (Android)**, and **C++20** with zero reflection, zero boxing, and deterministic lifecycle management.

---

## 1. Plugin Catalog & Platform Matrix

| Package | Category | iOS Native Engine | Android Native Engine | Zero-Copy C++ |
|---|---|---|---|---|
| [`@nexa/sqlite`](sqlite) | **Storage** | SQLite3 via `libsqlite3.dylib` | `sqlite3` via Android NDK / Framework | ✅ Core |
| [`@nexa/mmkv`](mmkv) | **Storage** | Tencent MMKV (mmap / AES) | Tencent MMKV (mmap / AES) | ✅ Core |
| [`@nexa/notifications`](notifications) | **System** | Apple `UserNotifications` (UNUserNotificationCenter) | Android `NotificationManager` + FCM | — |
| [`@nexa/biometrics`](biometrics) | **Security** | Apple `LocalAuthentication` (Face ID / Touch ID) | AndroidX `BiometricPrompt` | — |
| [`@nexa/camera`](camera) | **Media** | Apple `AVFoundation` + `Vision` | AndroidX `CameraX` + ML Kit Barcode | ✅ Frame stream |
| [`@nexa/media-picker`](media-picker) | **Media** | Apple `PHPickerViewController` (Permissionless) | AndroidX `ActivityResultContracts.PickVisualMedia` | — |
| [`@nexa/audio-player`](audio-player) | **Media** | Apple `AVPlayer` + `MPNowPlayingInfoCenter` | AndroidX `Media3` (ExoPlayer) + MediaSession | — |
| [`@nexa/video-player`](video-player) | **Media** | Apple `AVPlayer` + `AVPlayerLayer` | AndroidX `Media3` (ExoPlayer) + Cronet HLS/DASH | — |
| [`@nexa/maps`](maps) | **UI Component** | Apple `MapKit` (`MKMapView`) | Google Play Services Maps (`MapView`) | — |
| [`@nexa/webview`](webview) | **UI Component** | Apple `WebKit` (`WKWebView`) | AndroidX `WebView` (Secure origin isolation) | — |
| [`@nexa/websocket`](websocket) | **Network** | Apple `URLSessionWebSocketTask` | Square `OkHttp` WebSocket | — |
| [`@nexa/browser`](browser) | **System** | Apple `SFSafariViewController` | AndroidX Custom Tabs (`CustomTabsIntent`) | — |
| [`@nexa/sensors`](sensors) | **Hardware** | Apple `CoreMotion` (CMMotionManager, CMPedometer) | Android `SensorManager` | — |
| [`@nexa/data-extractor`](data-extractor) | **Intelligence** | Apple `NSDataDetector` | Android `TextClassifier` | — |
| [`@nexa/in-app-purchases`](in-app-purchases) | **Commerce** | Apple `StoreKit 2` (App Store) | Google Play Billing Library 7.x | — |
| [`@nexa/mail-composer`](mail-composer) | **System** | Apple `MessageUI` (`MFMailComposeViewController`) | Android `ACTION_SENDTO` intent chooser | — |

---

## 2. Installing & Using Plugins

### Step 1: Add the Plugin to Your App
Add the plugin package to your Nexa project:

```bash
nexa plugin add @nexa/sqlite
```

Or declare it directly in your `nexa.config.nx` manifest:

```nexa
app MyApp {
    bundleId: "com.example.myapp",
    version: "1.0.0",
    
    plugins: [
        "@nexa/sqlite",
        "@nexa/mmkv",
        "@nexa/notifications"
    ]
}
```

### Step 2: Import and Use in `.nx` Source Code

Plugins are strongly typed. Import the plugin module at the top of your `.nx` file:

```nexa
plugin "dev.nexa.sqlite" as SQLite

component TaskListView() {
    state tasks: Array<Task> = []
    let db = SQLite.Database("tasks", sharedWithWidgets: true)

    onAppear(() => {
        loadTasks()
    })

    fn loadTasks() {
        tasks = try? await db.query<Task>("SELECT id, title, completed FROM tasks") ?? []
    }

    VStack {
        // UI implementation...
    }
}
```

---

## 3. Architecture & Development Guidelines

1. **Zero Runtime Reflection**: All plugin calls are compiled into static native calls.
2. **Explicit Error Handling**: All failable native calls throw strongly-typed errors declared in `.nxid`.
3. **Deterministic Memory Ownership**: Native classes expose explicit `dispose()` methods and attach to lifecycle hooks (`onAppear` / `onDisappear`).
4. **Isolated Native Scaffolding**: Plugins provide their own Xcode framework configurations, Gradle dependencies, and C++ header bindings.
