# `@nexa/camera`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-AVFoundation%20%2F%20CameraX-orange.svg)](https://developer.apple.com/av-foundation/)

Native camera preview, photo capture, video recording, real-time barcode scanning, and zero-copy raw I420 frame streaming.

Backed by Apple `AVFoundation` + `Vision` on iOS and AndroidX `CameraX` + `ML Kit` on Android.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.camera" as Camera

component BarcodeScannerScreen() {
    state scannedCode: String = ""
    state isScanning: Bool = true
    state facing: Camera.CameraFacing = Camera.CameraFacing.back

    VStack {
        if isScanning {
            Camera.CameraView(
                facing: facing,
                barcodeScanningEnabled: true,
                barcodeFormat: Camera.CameraBarcodeFormat.qr,
                onBarcodeDetected: (barcode) => {
                    scannedCode = barcode.value
                    isScanning = false
                },
                onFailed: (err) => {
                    print("Camera error: \(err)")
                }
            )
        } else {
            VStack(spacing: 16) {
                Text("Scanned QR Code:", size: 14)
                Text(scannedCode, size: 18, weight: "bold")
                Button("Scan Again", action: () => { isScanning = true })
            }
        }
    }
}
```

---

## 2. API Reference

### `CameraView` Native Component

Declarative native camera surface. Leaving composition automatically releases camera hardware sessions and halts recordings.

```nexa
native component CameraView
```

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
- `back`: Standard rear camera lens.
- `front`: Selfie camera lens (mirrored preview).

#### `CameraBarcodeFormat`
Supported symbologies: `aztec`, `codabar`, `code39`, `code93`, `code128`, `dataMatrix`, `ean8`, `ean13`, `itf`, `pdf417`, `qr`, `upcA`, `upcE`.

#### `CameraFrameResolution`
- `vga`: 640x480 resolution (optimized for computer vision / ML inference).
- `hd`: 1280x720 high definition resolution.

#### `CameraBarcode`
| Field | Type | Description |
|---|---|---|
| `value` | `String` | Decoded payload text |
| `format` | `CameraBarcodeFormat` | Symbology of the detected code |

#### `CameraFrame`
Tightly packed raw I420 planar buffer (`Y`, `U`, `V`).
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
