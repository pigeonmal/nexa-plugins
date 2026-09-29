# `@nexa/media-picker`

Permissionless access to the system photo and video picker. Selected content is
copied into the application's cache directory and returned as a local file
URI. The plugin requests no photo-library or storage permission.

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
caches directory. On Android, it launches AndroidX `PickVisualMedia` and
streams the selected `content://` item into app cache before emitting a `file:`
URI. Android prunes cached selections older than seven days when a new item is
copied. Treat returned URIs as temporary app-local references; copy them into
durable app storage if the app needs them to survive cache cleanup.

The fixture app under `tests/demo/app` exercises both selection modes and the
typed success and failure events on each platform.
