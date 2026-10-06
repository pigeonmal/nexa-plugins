# `@nexa/media-picker`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Permissionless](https://img.shields.io/badge/Permission-Zero%20Permission%20Required-brightgreen.svg)](https://developer.apple.com/documentation/photokit/phpickerviewcontroller)

Permissionless system photo and video picker for iOS and Android.

Leverages Apple `PHPickerViewController` on iOS and AndroidX `ActivityResultContracts.PickVisualMedia` on Android. Runs out-of-process in system UI, eliminating the need to request intrusive `READ_MEDIA_IMAGES` or `NSPhotoLibraryUsageDescription` permissions.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.media-picker" as MediaPicker

component ProfileAvatarScreen() {
    state avatarUri: String? = null

    VStack(spacing: 20) {
        if let uri = avatarUri {
            Image(url: uri, width: 120, height: 120)
                .cornerRadius(60)
        } else {
            Icon(system: "person", size: 80, tint: "#8E8E93")
        }

        MediaPicker.MediaPickerControl(
            isVideo: false,
            onPicked: (uri) => {
                avatarUri = uri
            },
            onFailed: (err) => {
                print("Picker failed: \(err)")
            }
        ) {
            Button("Choose New Photo")
        }
    }
}
```

---

## 2. API Reference

### `MediaPickerControl` Native Component

Wraps any child component (`content`) and presents the native picker when tapped.

```nexa
native component MediaPickerControl
```

#### Properties

| Prop | Type | Default | Description |
|---|---|---|---|
| `isVideo` | `Bool` | `false` | When `true`, filters picker for videos (`public.movie`). When `false`, filters for photos (`public.image`). |

#### Events

| Event | Payload | Description |
|---|---|---|
| `picked` | `uri: String` | Fired when selection completes. Media is copied to the app cache directory and returns a local `file://` URI. |
| `failed` | `message: String` | Fired if the user cancels or an error occurs during file copying. |

---

## 3. Platform Architecture

| Platform | Underlying API | Permission Invariant |
|---|---|---|
| **iOS** | `PHPickerViewController` | Requires **zero** Info.plist permissions; user grants access only to the selected asset |
| **Android** | `PickVisualMedia` Activity Contract | System Photo Picker via Google Play Services / Android 13+; requires **no runtime permissions** |
