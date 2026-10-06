# `dev.nexa.video-player`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-AVPlayer%20%2F%20Media3%20(ExoPlayer)-red.svg)](https://developer.apple.com/documentation/avfoundation/avplayer)

Native video playback surface with hardware acceleration, forward stream preloading, HLS/DASH streaming, and custom controls.

Backed by Apple `AVPlayer` + `AVPlayerLayer` on iOS and AndroidX `Media3` (ExoPlayer) with optional Google Cronet networking on Android.

---

> **Android minimum API:** 23. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/video-player" as VideoPlayer

component PlayerSurface(player: VideoPlayer.VideoPlayer, title: String) {
    body {
        PlayerMedia(player: player, title: title)
    }
}

component PlayerMedia(player: VideoPlayer.VideoPlayer, title: String) {
    state tapped = false

    body {
        Pressable() {
            VideoPlayer.VideoView(player: player) {
                Column {
                    Text(title)
                    if tapped {
                        Text("Tapped")
                    }
                }
            }
        }.onTap {
            tapped = true
        }
    }
}

app VideoPlayerDemo {
    let player1 = VideoPlayer()
    let player2 = VideoPlayer()
    state loadFailed = false
    state loadError = ""
    state firstEnded = false
    state secondEnded = false
    state secondTapped = false

    body {
        OnAppear async {
            player1.ended { firstEnded = true }
            player2.ended { secondEnded = true }
            try {
                await player1.prepare("https://media.w3.org/2010/05/sintel/trailer.mp4")
                player1.play()
                await player2.prepare("https://media.w3.org/2010/05/sintel/trailer.mp4")
            } catch {
                case VideoPlayer.PlayerError.invalidUrl {
                    loadFailed = true
                }
                case VideoPlayer.PlayerError.decodingFailed(message) {
                    loadFailed = true
                    loadError = message
                }
            }
        }
        OnDisappear {
            player1.dispose()
            player2.dispose()
        }
        Column {
            PlayerSurface(player: player1, title: "First player")
            Pressable() {
                VideoPlayer.VideoView(player: player2, controls: false, softwareDecodingEnabled: false) {
                    Text("Second player")
                }
            }.onTap {
                secondTapped = true
            }
            Button("Play first player") {
                player1.play()
            }
            Button("Pause first player") {
                player1.pause()
            }
            Button("Play second player") {
                player2.play()
            }
            Button("Pause second player") {
                player2.pause()
            }
            Button("Set first volume") {
                player1.volume = 0.5
            }
            Button("Set second volume") {
                player2.volume = 0.25
            }
            if loadFailed {
                Text(loadError)
            }
            if firstEnded {
                Text("First player ended")
            }
            if secondEnded {
                Text("Second player ended")
            }
            if secondTapped {
                Text("Second view tapped")
            }
        }
    }
}
```

---

## 2. API Reference

### `VideoPlayer` handle

Controls media decoding, playback state, and forward preloading queues; call `dispose()` when the app no longer uses the player.

| Constructor | Signature | Description |
|---|---|---|
| `VideoPlayer` | `VideoPlayer()` | Creates a video player handle. |


#### Properties

| Property | Type | Access | Description |
|---|---|---|---|
| `state` | `PlayerState` | Read-only | Current state of playback engine |
| `duration` | `Float64` | Read-only | Duration of loaded video in seconds |
| `volume` | `Float64` | Read-write | Audio volume scaling factor (`0.0` to `1.0`) |
| `looping` | `Bool` | Read-write | When `true`, automatically seeks to `0.0` on completion |

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `prepare(url: String)` | `async -> Void throws PlayerError` | Initializes the video pipeline from a URL or local file path. |
| `preload(url: String, index: Int32)` | `Void` | Buffers subsequent video into forward cache queue at specific index |
| `setPreloadPosition(index: Int32)` | `Void` | Advances active playback queue to preloaded index |
| `play()` | `Void` | Starts or resumes video rendering |
| `pause()` | `Void` | Pauses video rendering |
| `seek(position: Float64)` | `Void` | Seeks playback head to specified second timestamp |
| `dispose()` | `Void` | Frees video decoder, surfaces, and active network connections |

#### Events

| Event | Description |
|---|---|
| `ended` | Fired when non-looping video finishes playing to the end |

---

### `VideoView` component

Visual display surface hosting the native video render layer.


#### Properties

| Prop | Type | Default | Description |
|---|---|---|---|
| `player` | `VideoPlayer` | — | Required player instance binding |
| `controls` | `Bool` | `true` | Enables platform-native overlay playback controls |
| `softwareDecodingEnabled` | `Bool` | `true` | Allows software fallback if hardware HEVC/H.264 decoders are saturated |

---

### Data Structures & Enums

#### `PlayerState`

| Case | Description |
|---|---|
| `idle` | Player has no prepared media. |
| `preparing` | Loading the video and initial frames. |
| `ready` | Ready for playback. |
| `playing` | Actively rendering video. |
| `paused` | Playback is suspended. |
| `ended` | Playback reached the end. |
| `failed` | A playback or decoding failure occurred. |

#### `PlayerOptions`
| Field | Type | Default | Description |
|---|---|---|---|
| `autoplay` | `Bool` | `false` | Automatically begin playing upon ready state |
| `volume` | `Float64` | `1.0` | Initial volume factor |

---

### Error Handling (`PlayerError`)

| Variant | Description |
|---|---|
| `invalidUrl` | URL is malformed or does not use HTTP or HTTPS. |
| `decodingFailed(message: String)` | Loading or decoding failed; the message contains platform details. |
