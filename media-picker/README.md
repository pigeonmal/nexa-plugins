# `dev.nexa.media-picker`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Permissionless](https://img.shields.io/badge/Permission-Zero%20Permission%20Required-brightgreen.svg)](https://developer.apple.com/documentation/photokit/phpickerviewcontroller)

Permissionless system photo and video picker for iOS and Android.

Leverages Apple `PHPickerViewController` on iOS and AndroidX `ActivityResultContracts.PickVisualMedia` on Android. Runs out-of-process in system UI, eliminating the need to request intrusive `READ_MEDIA_IMAGES` or `NSPhotoLibraryUsageDescription` permissions.

---

> **Android minimum API:** 23. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/media-picker" as MediaPicker

app MediaPickerDemo {
    state imageUri = ""
    state videoUri = ""
    state errorMessage = ""

    body {
        Column {
            MediaPicker.MediaPickerControl(isVideo: false) {
                Text("Choose image")
            }.onPicked { uri ->
                imageUri = uri
            }.onFailed { message ->
                errorMessage = message
            }

            MediaPicker.MediaPickerControl(isVideo: true) {
                Text("Choose video")
            }.onPicked { uri ->
                videoUri = uri
            }.onFailed { message ->
                errorMessage = message
            }

            Text(imageUri)
            Text(videoUri)
            Text(errorMessage)
        }
    }
}
```

---

## 2. API Reference

### `MediaPickerControl` control

Wraps any child component (`content`) and presents the native picker when tapped.


#### Properties

| Prop | Type | Default | Description |
|---|---|---|---|
| `isVideo` | `Bool` | **Required** | Filters the picker: `true` selects videos (`public.movie`), `false` selects photos (`public.image`). There is no default; the compiler rejects `MediaPickerControl` without it. |

#### Events

| Event | Payload | Description |
|---|---|---|
| `picked` | `uri: String` | Fired when selection completes. Media is copied to the app cache directory and returns a local `file://` URI. |
| `failed` | `message: String` | Fired when copying or presenting the selected media fails. Cancellation produces no selected URI. |

---

## 3. Platform Architecture

| Platform | Underlying API | Permission Invariant |
|---|---|---|
| **iOS** | `PHPickerViewController` | Requires **zero** Info.plist permissions; user grants access only to the selected asset |
| **Android** | `PickVisualMedia` Activity Contract | System Photo Picker via Google Play Services / Android 13+; requires **no runtime permissions** |
