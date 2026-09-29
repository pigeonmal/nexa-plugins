# `@nexa/audio-player`

One app-wide native audio session for Nexa. It streams URLs through AVPlayer on
iOS and Media3 ExoPlayer on Android, with system playback controls and track
metadata.

| Platform | Background playback | System controls |
|---|---|---|
| iOS 17+ | `AVAudioSession` playback category and the generated `audio` background mode | Lock Screen and Control Center through `MPNowPlayingInfoCenter` and remote commands |
| Android 8+ | Media3 `MediaSessionService` with the `mediaPlayback` foreground service type | Media notification, Android System media controls, and compatible external controllers |

The plugin exposes one shared app session. Multiple `AudioPlayer` handles
control that same active stream; disposing any handle stops and clears the
session. Call `prepare` and `play` while the app is foregrounded on Android so
the operating system can transition playback into its foreground service.

The native platform decoders determine supported formats. Android includes
Media3's HLS module. URLs may use `http`, `https`, or `file` schemes.

## Example

The runnable app in [`tests/demo/app/App.nx`](tests/demo/app/App.nx) exercises
preparation, metadata, playback controls, state events, and typed failures. Its
sample track is a public MP3; replace the URL with your own stream when needed.
From that directory, run `nexa check App.nx`, then `nexa test` to compile both
native hosts or `nexa dev` to launch a development host.

The plugin manifest adds the iOS background audio mode, Android service
declaration, and Android foreground service permissions to generated hosts.
Changes to the plugin contract, native implementation, dependencies, or host
configuration require rebuilding the host.
