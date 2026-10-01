# `@nexa/camera`

Lifecycle-scoped native camera preview, photo and video capture, barcode
scanning, and an optional throttled I420 image stream for Nexa apps.

```nx
plugin "dev.nexa.camera" as Camera

app CaptureDemo {
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

The camera opens only while `CameraView` is in the UI tree and after the
platform permission is granted. Its default preview frame is 4:3 and follows
the available width so surrounding controls can remain in a normal layout.
`photoRequestId` is edge-triggered: increment it once for each still capture.
Set `recording` to start a movie and back to
`false` to finish it. Video output is saved to the app's cache directory and
returned as a local file URI. Audio recording is opt-in with `recordAudio` and
requests microphone permission only when recording is requested. Photos and
videos are not written to the user's media library.

Barcode scanning uses one selected format (the example selects `qr`) so the
scanner can avoid searching every format. The Android bundled ML Kit model is available
offline on first use. iOS uses AVFoundation's metadata output. Duplicate
continuous detections are throttled by the native implementation.

Set `imageStreamEnabled` only when consuming image data. Frames are capped by
`maxFramesPerSecond` (1–30, default 10); analysis uses VGA by default and can
be raised to HD for small or distant barcodes. Each frame emits one tightly
packed I420 `Bytes` buffer: Y, U, and V planes in that order, with chroma
dimensions rounded up for odd image sizes. `rotationDegrees` describes how the
consumer should rotate the pixels. The platform capture buffer is copied once
into the event value and is released immediately; there is no retained queue,
frame pool exposed to Nexa code, or unbounded backpressure. CameraX drops stale
analysis frames when its analyzer is busy.

The preview owns its session and tears down analysis, capture, and recording
when it leaves the composition. Photo capture is unavailable while recording;
stop the recording before requesting a still image. Stop recording while the
view remains mounted to receive its finalized video URI; leaving the screen
stops an active recording as part of teardown. Runtime permission denials and
native capture failures arrive through `onFailed` as typed `CameraError`
values.

## Platform requirements

- iOS 17 or later, using the system AVFoundation framework. The plugin adds
  camera and microphone privacy usage descriptions.
- Android API 23 or later, using CameraX 1.6.2 and the bundled ML Kit barcode
  model 17.3.0. The plugin adds `CAMERA` and `RECORD_AUDIO` permissions.

The maintainer app under `tests/demo/app` exercises permission handling,
preview, photo/video requests, barcode callbacks, throttled frame events, and
teardown. Native contract, dependency, permission, and implementation changes
require a host rebuild; app event handlers remain hot reloadable.
