# `dev.nexa.media-picker`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Permissionless](https://img.shields.io/badge/Permission-Zero%20Permission%20Required-brightgreen.svg)](https://developer.apple.com/documentation/photokit/phpickerviewcontroller)

Permissionless system photo and video picker for iOS and Android.

Leverages SwiftUI `PhotosPicker` on iOS and AndroidX `PickVisualMedia` / `PickMultipleVisualMedia` contracts on Android. Runs in system picker UI, eliminating the need to request `READ_MEDIA_IMAGES` or `NSPhotoLibraryUsageDescription` permissions.

---

> **Android minimum API:** 23. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/media-picker" as MediaPicker

app MediaPickerDemo {
    state imageUris: Array<String> = []
    state videoUris: Array<String> = []
    state errorMessage = ""

    body {
        Column {
            MediaPicker.MediaPickerControl(isVideo: false, selectionLimit: 8) {
                Text("Choose image")
            }.onPicked { uris ->
                imageUris = uris
            }.onFailed { message ->
                errorMessage = message
            }

            MediaPicker.MediaPickerControl(isVideo: true, selectionLimit: 1) {
                Text("Choose video")
            }.onPicked { uris ->
                videoUris = uris
            }.onFailed { message ->
                errorMessage = message
            }

            Text("Selected images: \(imageUris.count)")
            Text("Selected videos: \(videoUris.count)")
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
| `selectionLimit` | `Int32` | `0` | Maximum number of assets to select. `0` uses the platform picker default; `1` selects one asset; values above `1` cap multi-selection. Negative values fire `failed`. Older Android document-picker fallbacks may not enforce a positive cap. |

#### Events

| Event | Payload | Description |
|---|---|---|
| `picked` | `uris: Array<String>` | Fired when selection completes. Every selected asset is copied to the app cache directory and returned as a local `file://` URI in picker order. The source encoding and image metadata, including EXIF orientation, are retained for metadata-aware decoders. If any copy fails or the control leaves composition during copying, partial copies are removed and `failed` is fired for non-cancellation errors. |
| `failed` | `message: String` | Fired when copying or presenting the selected media fails. Cancellation produces no selected URIs. |

---

## 3. Platform Architecture

| Platform | Underlying API | Permission Invariant |
|---|---|---|
| **iOS** | SwiftUI `PhotosPicker` with `.current` encoding and `PhotosPickerItem` transfer loading | Requires **zero** Info.plist permissions; the user grants access only to the selected assets |
| **Android** | `PickVisualMedia` for one asset; `PickMultipleVisualMedia` for multiple assets; copies original bytes | System Photo Picker when available, with AndroidX fallback; requires **no runtime permissions** |
