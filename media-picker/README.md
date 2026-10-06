# `@nexa/media-picker`

Permissionless access to the system photo and video picker. Selected content is
copied into the application's private storage and returned as a local file URI.
The plugin requests no photo-library or storage permission.

```nx
plugin "dev.nexa.media-picker" as MediaPicker

app MediaPickerExample {
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

On iOS, the plugin uses `PhotosPicker` and copies imported files into the app's
documents directory. On Android, it launches AndroidX `PickVisualMedia` and
streams the selected `content://` item into private files before emitting a
`file:` URI. The returned local file references remain available after app
restarts.

The fixture app under `tests/demo/app` exercises both selection modes and the
typed success and failure events on each platform.
