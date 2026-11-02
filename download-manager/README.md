# Download Manager Plugin

| **Package** | `dev.nexa.download-manager` | **Platforms** | iOS 16+ · Android API 24+ | **Schema** | 2 |

This plugin owns cancellable audio downloads and local file packages. Direct files retain native background-transfer recovery; finite HLS playlists are downloaded through Nexa's shared URLSession on iOS and Cronet engine on Android.

## Quick start

```nx
plugin "plugins/download-manager" as Downloads

class OfflineAudioService {
    static let manager: Downloads.DownloadManager = Downloads.DownloadManager()

    static async fn savePlaylist(trackID: String, manifestURL: String, headers: Map<String, String>) -> String? {
        try {
            let result = await OfflineAudioService.manager.startHls(
                trackID,
                manifestURL,
                "mazica-\(trackID)",
                headers,
                null
            )
            if result.state == Downloads.DownloadState.completed {
                return result.localPath
            }
        } catch {
            return null
        }
        return null
    }
}
```

## Typed API

### Data types

| Type | Fields | Meaning |
|---|---|---|
| `DownloadState` | `queued`, `downloading`, `completed`, `failed`, `cancelled` | Persistent transfer state. |
| `DownloadSnapshot` | `id: String`, `state: DownloadState`, `receivedBytes: Int64`, `expectedBytes: Int64`, `localPath: String`, `error: String` | Current transfer state and local result. `expectedBytes` is zero when the server does not provide a total. |
| `DownloadManagerError` | `invalidIdentifier`, `invalidURL`, `destinationUnavailable`, `downloadFailed(message: String)` | Typed validation, storage, or transfer failures. |

### `DownloadManager`

| Member | Signature | Behavior |
|---|---|---|
| `init` | `DownloadManager()` | Creates a manager bound to the app's private music directory. |
| `start` | `async fn start(id: String, url: String, fileName: String, headers: Map<String, String>) -> DownloadSnapshot throws DownloadManagerError` | Starts or resumes observation of a direct HTTP(S) file. The Android system download service and iOS background URLSession own transfer recovery. |
| `startHls` | `async fn startHls(id: String, url: String, packageName: String, headers: Map<String, String>, expectedBytes: Int64?) -> DownloadSnapshot throws DownloadManagerError` | Downloads a finite HLS audio playlist and its referenced resources into `<packageName>.hls/index.m3u8`. Manifest, segment, byte-range, initialization-map, and AES-128 key URIs are rewritten to local package files. |
| `status` | `async fn status(id: String) -> DownloadSnapshot? throws DownloadManagerError` | Reads live or persisted transfer state. |
| `completedPath` | `async fn completedPath(id: String) -> String? throws DownloadManagerError` | Returns a local file path only when the completed output still exists. For HLS, this is the local `.m3u8` playlist. |
| `adoptExistingDownloads` | `async fn adoptExistingDownloads(ids: Array<String>) throws DownloadManagerError` | Imports completed source files named by track ID; intended for one-time app migration. |
| `cancel` | `fn cancel(id: String)` | Cancels a transfer and removes its partial output. |
| `remove` | `async fn remove(id: String) -> Bool throws DownloadManagerError` | Cancels the transfer, deletes the completed file or entire HLS package, and clears its record. |
| `progress` | `event progress(id: String, receivedBytes: Int64, expectedBytes: Int64)` | Emits direct transfer byte progress and HLS progress after each resource completes. |
| `completed` | `event completed(snapshot: DownloadSnapshot)` | Emits a completed direct file or HLS package. |
| `failed` | `event failed(id: String, message: String)` | Emits a failed transfer with its diagnostic message. |
| `dispose` | `fn dispose()` | Releases event callbacks and cancels active HLS work. OS-owned direct transfers retain their platform-managed state. |

## Native behavior and limits

Android writes HLS resources through Nexa's shared Cronet engine. iOS uses Nexa's shared URLSession. Each resource streams to a temporary file, so media bytes do not accumulate in memory. Resources download sequentially to bound network concurrency and memory; progress callbacks arrive after each resource. Returned packages contain a playlist plus referenced media files.

HLS support is for finite VOD playlists. Live, low-latency, delta-update, and DRM encryption methods are rejected. AES-128 keys are copied into the private package. A process interruption restarts an HLS package from the beginning; direct downloads retain background-task recovery. Callers should use `expectedBytes` only as a progress estimate because HLS package bytes can include playlist and key overhead.
