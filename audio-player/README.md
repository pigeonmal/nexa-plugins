# `dev.nexa.audio-player`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-AVPlayer%20%2F%20Media3-blue.svg)](https://developer.apple.com/documentation/avfoundation/avplayer)

Native background audio playback, lock screen metadata (`MPNowPlayingInfoCenter` / `MediaSession`), play/pause/seek/track-skip media controls, and session-owned sleep timers for iOS and Android. Remote bytes use Nexa's shared network engine: Cronet on Android and URLSession on iOS.

Backed by Apple `AVPlayer` on iOS and AndroidX `Media3` (ExoPlayer) on Android. A custom iOS resource loader uses Nexa's shared URLSession; Android uses Media3's Cronet data source with the app's shared Cronet engine, request executor, and cache configuration.

---

> **Minimum OS versions:** Android API 24 and iOS 16.0. Media3 core audio playback supports API 23 and newer; the package keeps its declared Android floor at API 24 to match Mazica's compatibility target.

## 1. Quick Start

```nexa
plugin "plugins/audio-player" as AudioPlayer

app AudioPlayerDemo {
    let player = AudioPlayer()
    let trackUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
    state stateChanged = false
    state failureMessage = ""
    state ended = false
    state equalizerUnavailable = false

    body {
        OnAppear async {
            player.stateChanged { current ->
                stateChanged = true
            }
            player.ended {
                ended = true
            }
            player.skipToNext {
                failureMessage = "The app should advance its playback queue."
            }
            player.skipToPrevious {
                failureMessage = "The app should move back in its playback queue."
            }
            player.updateMetadata("SoundHelix Song 1", "SoundHelix", "AudioPlayer demo")
            try {
                await player.prepare(AudioPlayer.AudioSource(
                    url: trackUrl,
                    headers: [:],
                    loudnessDb: null,
                    isHLS: false
                ))
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
        Column(gap: 12) {
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
            Button("Open system equalizer") {
                equalizerUnavailable = !player.openSystemEqualizer()
            }
            if equalizerUnavailable {
                Text("No system equalizer panel is available on this device.")
            }
            Button("Set volume to 50%") {
                player.volume = 0.5
            }
            Button("Set playback speed to 1.25x") {
                player.playbackSpeed = 1.25
            }
            Button("Mute") {
                player.muted = true
            }
            Button("Unmute") {
                player.muted = false
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
| `isSleepTimerActive` | `Bool` | Read-only | Whether a timed pause or end-of-current-track pause is armed |
| `sleepTimerRemainingSeconds` | `Int64` | Read-only | Seconds until a timed pause; `0` for no timed pause or end-of-track mode |
| `sleepTimerPauseAtTrackEnd` | `Bool` | Read-only | Whether playback will pause when the current item ends |
| `volume` | `Float64` | Read-write | Playback volume scaling factor (`0.0` to `1.0`) |
| `playbackSpeed` | `Float64` | Read-write | Playback rate, clamped to `0.25`–`3.0` on both platforms |
| `muted` | `Bool` | Read-write | Mutes this app's audio output while preserving the selected volume |

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `prepare(source: AudioSource)` | `async -> Void throws AudioPlayerError` | Stops the current item as soon as replacement preparation begins, then loads the remote URL or local `file://` path, forwarding HTTP headers, optional source loudness metadata, and HLS mode. Remote requests use Nexa's shared Cronet or URLSession engine; local files stay on the native file path. |
| `configure(skipSilence: Bool, audioNormalization: Bool, pauseOnMute: Bool)` | `Void` | Applies persisted playback preferences. Android uses Media3 silence skipping and LoudnessEnhancer; iOS applies loudness metadata through AVAudioMix and pauses/resumes when the device output volume reaches zero. AVPlayer has no native silence-skipping control. |
| `openSystemEqualizer()` | `Bool` | Opens the active Android Media3 audio session in the device's system audio-effects panel. Returns `false` if Android has no active session or no panel can be launched. Returns `false` on iOS because iOS exposes no public system equalizer panel for third-party apps. |
| `updateMetadata(title: String, artist: String?, album: String?)` | `Void` | Updates OS lock screen, control center, and notification media notifications. |
| `play()` | `Void` | Resumes or starts audio playback. |
| `pause()` | `Void` | Suspends audio playback. |
| `seek(position: Float64)` | `Void` | Moves playback position head to specified timestamp in seconds. |
| `startSleepTimer(minutes: Int32)` | `Void` | Replaces the current timer and pauses after the requested positive number of minutes. Values less than or equal to zero clear the timer. |
| `startSleepTimerAtTrackEnd()` | `Void` | Replaces the current timer and pauses after the current media item ends. |
| `clearSleepTimer()` | `Void` | Cancels either sleep-timer mode without pausing playback. |
| `dispose()` | `Void` | Releases this player handle; the shared audio session stops when its final handle is released. |

#### Events

| Event | Payload | Description |
|---|---|---|
| `stateChanged` | `state: AudioPlaybackState` | Fired on playback state transitions (e.g., buffering, playing, paused) |
| `ended` | — | Fired when playback reaches the end of the media stream |
| `skipToNext` | — | Fired when a lock-screen or system media control requests the next app-managed queue item. Playback pauses immediately; the app owns queue advancement and prepares the next stream. |
| `skipToPrevious` | — | Fired when a lock-screen or system media control requests the previous app-managed queue item. Playback pauses immediately; the app owns queue selection and prepares the stream. |
| `sleepTimerEnded` | — | Fired when a timed pause expires or the current item ends in end-of-track mode. When end-of-track mode fires, `ended` is suppressed so app queue logic does not automatically start another item. |
| `progressChanged` | `position: Float64, duration: Float64` | Fired once per second while playback is active |

Sleep timers are owned by the live native audio session. They are not persisted across operating-system process termination. The remaining-time property is monotonic during playback and returns whole seconds rounded up.

---

### Data Structures & Enums

#### `AudioSource`

| Field | Type | Required | Default / nullability | Description |
|---|---|---:|---|---|
| `url` | `String` | Yes | No default; non-null | Remote `http`/`https` URL or local `file` URL. |
| `headers` | `Map<String, String>` | Yes | No default; non-null | HTTP request headers required by the media provider; pass an empty map when none are needed. |
| `loudnessDb` | `Float64?` | Yes | No default; nullable | Optional integrated loudness used for volume normalization; pass `null` when unavailable. |
| `isHLS` | `Bool` | Yes | No default; non-null | Selects native HLS playback on Android; AVPlayer infers HLS from the URL on iOS. |

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

---

## 3. Third-party source

The Android progressive range reader is adapted from `react-native-pure-music` by pigeonmal under the MIT License. The full license notice is included in `THIRD_PARTY_LICENSE_PURE_MUSIC_MIT.txt`.
