# `dev.nexa.camera`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-AVFoundation%20%2F%20CameraX-orange.svg)](https://developer.apple.com/av-foundation/)

Native camera preview, photo capture, video recording, real-time barcode scanning, and throttled raw I420 frame events.

Backed by Apple `AVFoundation` + `Vision` on iOS and AndroidX `CameraX` + `ML Kit` on Android.

---

> **Android minimum API:** 23. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/camera" as Camera

app CameraDemo {
    state photoRequestId: Int32 = 0
    state recording = false
    state imageStreamEnabled = false
    state photoUri = ""
    state videoUri = ""
    state barcodeValue = ""
    state frameSequence: Int64 = 0
    state cameraFailure = ""

    body {
        Column(spacing: 12, padding: 16) {
            Camera.CameraView(
                facing: Camera.CameraFacing.back,
                photoRequestId: photoRequestId,
                recording: recording,
                imageStreamEnabled: imageStreamEnabled,
                barcodeScanningEnabled: true,
                barcodeFormat: Camera.CameraBarcodeFormat.qr,
                frameResolution: Camera.CameraFrameResolution.vga,
                maxFramesPerSecond: 10,
            )
                .onPhotoCaptured { uri -> photoUri = uri }
                .onVideoCaptured { uri -> videoUri = uri }
                .onBarcodeDetected { barcode -> barcodeValue = barcode.value }
                .onFrameAvailable { frame -> frameSequence = frame.sequence }
                .onFailed { error -> cameraFailure = "Camera operation failed" }

            Button("Take photo") { photoRequestId += 1 }
            Button("Toggle video recording") {
                recording = !recording
            }
            Button("Toggle image stream") {
                imageStreamEnabled = !imageStreamEnabled
            }
            Text(photoUri)
            Text(videoUri)
            Text(barcodeValue)
            Text("Frame: \(frameSequence)")
            Text(cameraFailure)
        }
    }
}
```

---

## 2. API Reference

### `CameraView` component

Declarative native camera surface. Leaving composition automatically releases camera hardware sessions and halts recordings.


#### Properties

| Prop | Type | Default | Description |
|---|---|---|---|
| `facing` | `CameraFacing` | — | Camera sensor selection (`back` or `front`) |
| `photoRequestId` | `Int32` | `0` | Increment this value to trigger a high-resolution still capture |
| `recording` | `Bool` | `false` | Set to `true` to start video recording; set to `false` to finish |
| `recordAudio` | `Bool` | `false` | Includes audio track in video recording (requires microphone permission) |
| `imageStreamEnabled` | `Bool` | `false` | Enables real-time raw frame delivery via `frameAvailable` |
| `barcodeScanningEnabled` | `Bool` | `false` | Enables on-device computer vision barcode detection |
| `barcodeFormat` | `CameraBarcodeFormat` | — | Target barcode symbology filter |
| `frameResolution` | `CameraFrameResolution` | — | Resolution preset for video and frame streaming (`vga` or `hd`) |
| `maxFramesPerSecond` | `Int32` | `10` | Frame rate throttle for `frameAvailable` events |

#### Events

| Event | Payload | Description |
|---|---|---|
| `photoCaptured` | `uri: String` | Fired when still photo finishes saving; returns local file URI |
| `videoCaptured` | `uri: String` | Fired when video recording stops and finalizes; returns local MP4 URI |
| `barcodeDetected` | `barcode: CameraBarcode` | Fired when a barcode matching `barcodeFormat` is decoded |
| `frameAvailable` | `frame: CameraFrame` | Emits raw I420 pixel buffers at throttled frame rate |
| `failed` | `error: CameraError` | Fired on permission denial, hardware unavailability, or recording failure |

---

### Data Structures & Enums

#### `CameraFacing`

| Case | Description |
|---|---|
| `back` | Standard rear camera lens. |
| `front` | Selfie camera lens; the preview is mirrored. |

#### `CameraBarcodeFormat`

| Case | Barcode format |
|---|---|
| `aztec` | Aztec |
| `codabar` | Codabar |
| `code39` | Code 39 |
| `code93` | Code 93 |
| `code128` | Code 128 |
| `dataMatrix` | Data Matrix |
| `ean8` | EAN-8 |
| `ean13` | EAN-13 |
| `itf` | Interleaved 2 of 5 |
| `pdf417` | PDF417 |
| `qr` | QR Code |
| `upcA` | UPC-A |
| `upcE` | UPC-E |

#### `CameraFrameResolution`

| Case | Resolution | Notes |
|---|---:|---|
| `vga` | 640 × 480 | Lower-bandwidth frame stream. |
| `hd` | 1280 × 720 | Higher-detail frame stream. |

#### `CameraBarcode`
| Field | Type | Description |
|---|---|---|
| `value` | `String` | Decoded payload text |
| `format` | `CameraBarcodeFormat` | Symbology of the detected code |

#### `CameraFrame`
Tightly packed raw I420 planar buffer (`Y`, `U`, `V`). The native capture frame is released when its callback returns; process each event promptly.
| Field | Type | Description |
|---|---|---|
| `sequence` | `Int64` | Monotonically increasing frame counter |
| `width` | `Int32` | Width in pixels |
| `height` | `Int32` | Height in pixels |
| `rotationDegrees` | `Int32` | Orientation metadata relative to upright display (`0`, `90`, `180`, `270`) |
| `presentationTimeNanoseconds` | `Int64` | Native hardware timestamp |
| `pixels` | `Bytes` | Raw I420 byte buffer |

---

### Error Handling (`CameraError`)

| Variant | Description |
|---|---|
| `cameraPermissionDenied` | User rejected camera access permission prompt |
| `microphonePermissionDenied` | User rejected microphone access during audio recording |
| `cameraUnavailable` | Camera sensor is in use by another app or absent |
| `captureFailed(message: String)` | Error capturing or encoding still image |
| `recordingFailed(message: String)` | Error writing or muxing video container |
| `invalidFrameRate(frameRate: Int32)` | Requested frame rate exceeds hardware capability |
