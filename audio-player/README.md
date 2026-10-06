# `@nexa/audio-player`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-AVPlayer%20%2F%20Media3-blue.svg)](https://developer.apple.com/documentation/avfoundation/avplayer)

Native background audio playback, lock screen metadata (`MPNowPlayingInfoCenter` / `MediaSession`), and hardware media control routing for iOS and Android.

Backed by Apple `AVPlayer` on iOS and AndroidX `Media3` (ExoPlayer) on Android.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.audio-player" as Audio

component MusicPlayerScreen() {
    let player = Audio.AudioPlayer()
    state isPlaying: Bool = false
    state currentTrack: String = "No track loaded"

    onAppear(() => {
        loadSong("https://example.com/audio/podcast.mp3")
    })

    onDisappear(() => {
        player.dispose()
    })

    fn loadSong(url: String) {
        try {
            await player.prepare(url)
            player.updateMetadata(title: "Deep Dive Podcast", artist: "Nexa Team", album: "Engineering")
            currentTrack = "Deep Dive Podcast"
        } catch Audio.AudioPlayerError as err {
            print("Failed to load song: \(err)")
        }
    }

    fn togglePlayback() {
        if isPlaying {
            player.pause()
            isPlaying = false
        } else {
            player.play()
            isPlaying = true
        }
    }

    VStack(spacing: 20) {
        Text(currentTrack, size: 18, weight: "bold")
        Button(isPlaying ? "Pause" : "Play", action: () => { togglePlayback() })
    }
}
```

---

## 2. API Reference

### `AudioPlayer` Native Class

App-wide background audio playback manager.

```nexa
native class AudioPlayer {
    init()
}
```

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
| `prepare(url: String)` | `Void` | Loads remote URL or local `file://` path. Asynchronously buffers initial stream. |
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
