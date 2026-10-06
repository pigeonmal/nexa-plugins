# `dev.nexa.audio-player`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-AVPlayer%20%2F%20Media3-blue.svg)](https://developer.apple.com/documentation/avfoundation/avplayer)

Native background audio playback, lock screen metadata (`MPNowPlayingInfoCenter` / `MediaSession`), and hardware media control routing for iOS and Android.

Backed by Apple `AVPlayer` on iOS and AndroidX `Media3` (ExoPlayer) on Android.

---

> **Android minimum API:** 26. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/audio-player" as AudioPlayer

app AudioPlayerDemo {
    let player = AudioPlayer()
    let trackUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
    state stateChanged = false
    state failureMessage = ""
    state ended = false

    body {
        OnAppear async {
            player.stateChanged { current ->
                stateChanged = true
            }
            player.ended {
                ended = true
            }
            player.updateMetadata("SoundHelix Song 1", "SoundHelix", "AudioPlayer demo")
            try {
                await player.prepare(trackUrl)
                player.play()
            } catch {
                case AudioPlayer.AudioPlayerError.invalidUrl {
                    failureMessage = "The track URL is invalid."
                }
                case AudioPlayer.AudioPlayerError.playbackFailed(message) {
                    failureMessage = message
                }
            }
        }
        Column(spacing: 12) {
            Text("AudioPlayer demo")
            if stateChanged {
                Text("Playback state changed")
            }
            Text("Position: \(player.currentTime) / \(player.duration)")
            Button("Pause") {
                player.pause()
            }
            Button("Resume") {
                player.play()
            }
            Button("Seek to start") {
                player.seek(0.0)
            }
            Button("Set volume to 50%") {
                player.volume = 0.5
            }
            if ended {
                Text("Playback ended")
            }
            if failureMessage != "" {
                Text(failureMessage)
            }
        }
    }
}
```

---

## 2. API Reference

### `AudioPlayer` handle

App-wide background audio playback manager.

| Constructor | Signature | Description |
|---|---|---|
| `AudioPlayer` | `AudioPlayer()` | Creates a player and registers native media controls. |


#### Properties

| Property | Type | Access | Description |
|---|---|---|---|
| `state` | `AudioPlaybackState` | Read-only | Current lifecycle state of the playback engine |
| `duration` | `Float64` | Read-only | Total media duration in seconds (`0.0` if unknown or live stream) |
| `currentTime` | `Float64` | Read-only | Current playback head position in seconds |
| `volume` | `Float64` | Read-write | Playback volume scaling factor (`0.0` to `1.0`) |

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `prepare(url: String)` | `async -> Void throws AudioPlayerError` | Loads remote URL or local `file://` path. Asynchronously buffers initial stream. |
| `updateMetadata(title: String, artist: String?, album: String?)` | `Void` | Updates OS lock screen, control center, and notification media notifications. |
| `play()` | `Void` | Resumes or starts audio playback. |
| `pause()` | `Void` | Suspends audio playback. |
| `seek(position: Float64)` | `Void` | Moves playback position head to specified timestamp in seconds. |
| `dispose()` | `Void` | Halts playback, tears down audio session, and releases system media controls. |

#### Events

| Event | Payload | Description |
|---|---|---|
| `stateChanged` | `state: AudioPlaybackState` | Fired on playback state transitions (e.g., buffering, playing, paused) |
| `ended` | — | Fired when playback reaches the end of the media stream |

---

### Data Structures & Enums

#### `AudioPlaybackState`

| State | Description |
|---|---|
| `idle` | Player created but no media prepared |
| `preparing` | Fetching headers and initial audio buffers |
| `ready` | Media ready for immediate playback |
| `playing` | Audio actively rendering through speakers/headphones |
| `paused` | Playback suspended by user or system interruption |
| `ended` | Playback reached end of stream |
| `failed` | Unrecoverable network or decoding error |

---

### Error Handling (`AudioPlayerError`)

| Variant | Description |
|---|---|
| `invalidUrl` | Malformed URL string or unsupported URL protocol |
| `playbackFailed(message: String)` | Network error, missing codec, or audio hardware routing failure |
