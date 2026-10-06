# `@nexa/video-player`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-AVPlayer%20%2F%20Media3%20(ExoPlayer)-red.svg)](https://developer.apple.com/documentation/avfoundation/avplayer)

Native video playback surface with hardware acceleration, forward stream preloading, HLS/DASH streaming, and custom controls.

Backed by Apple `AVPlayer` + `AVPlayerLayer` on iOS and AndroidX `Media3` (ExoPlayer) with optional Google Cronet networking on Android.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.video-player" as Video

component FeedVideoPlayer(videoUrl: String) {
    let player = Video.VideoPlayer()

    onAppear(() => {
        try {
            await player.prepare(videoUrl)
            player.looping = true
            player.play()
        } catch Video.PlayerError as err {
            print("Video error: \(err)")
        }
    })

    onDisappear(() => {
        player.dispose()
    })

    VStack {
        Video.VideoView(
            player: player,
            controls: true,
            softwareDecodingEnabled: true
        )
    }
}
```

---

## 2. API Reference

### `VideoPlayer` Native Class

Controls media decoding, playback state, and forward preloading queues.

```nexa
native class VideoPlayer {
    init()
}
```

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
| `prepare(url: String)` | `Void` | Initializes video pipeline with target URL or local file path |
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

### `VideoView` Native Component

Visual display surface hosting the native video render layer.

```nexa
native component VideoView
```

#### Properties

| Prop | Type | Default | Description |
|---|---|---|---|
| `player` | `VideoPlayer` | — | Required player instance binding |
| `controls` | `Bool` | `true` | Enables platform-native overlay playback controls |
| `softwareDecodingEnabled` | `Bool` | `true` | Allows software fallback if hardware HEVC/H.264 decoders are saturated |

---

### Data Structures & Enums

#### `PlayerState`
- `idle`: Uninitialized player.
- `preparing`: Buffering video manifest and initial frames.
- `ready`: Ready for smooth playback.
- `playing`: Actively decoding and displaying video.
- `paused`: Suspended.
- `ended`: Completed playback.
- `failed`: Decoding or network failure.

#### `PlayerOptions`
| Field | Type | Default | Description |
|---|---|---|---|
| `autoplay` | `Bool` | `false` | Automatically begin playing upon ready state |
| `volume` | `Float64` | `1.0` | Initial volume factor |

---

### Error Handling (`PlayerError`)

| Variant | Description |
|---|---|
| `invalidUrl` | Malformed or unreachable URL |
| `decodingFailed(message: String)` | Unsupported video format, DRM failure, or hardware codec crash |
