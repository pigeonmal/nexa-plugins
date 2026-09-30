# `@nexa/video-player`

Native video playback for Nexa. The plugin exposes independent `VideoPlayer`
instances, typed playback state and errors, an `ended` event, and a `VideoView`
with native playback controls.

| Platform | Playback engine | Adaptive formats |
|---|---|---|
| iOS | AVPlayer | HLS |
| Android | Media3 ExoPlayer with Nexa's LGPL-only FFmpeg fallback | HLS and DASH |

On iOS, playback uses Apple's AVPlayer and its native decoder. On Android,
`softwareDecodingEnabled: false` disables the FFmpeg extension renderer; the
default is `true`. The FFmpeg renderer is appended after Media3's platform
renderer, so it is selected only when the platform renderer cannot handle the
stream. Android uses an LGPL-only FFmpeg 9.0.2 build with GPL, LGPLv3, and
nonfree components disabled. The decoder is packaged as separate replaceable
shared libraries; its source archive, configure checks, and notices are under
[`android/ffmpeg-decoder`](android/ffmpeg-decoder). Android uses the app-packaged
Cronet provider for media requests and shares its Cronet engine between player
instances.

The FFmpeg fallback uses the upstream LGPL 2.1-or-later license, not NextLib or
GPL FFmpeg components. The wrapper code is Apache-2.0. App distributors that
ship the Android decoder binaries must include the required notices and make
the corresponding FFmpeg source and build information available. The exact
unmodified FFmpeg source archive and build recipe are included under
`android/ffmpeg-decoder`; see its third-party notices and the
[FFmpeg licensing guidance](https://ffmpeg.org/legal.html).

On Android 8.0 and later, PiP starts when the user backgrounds the app while
exactly one attached video player is actively playing. Android 12 and later use
the system's automatic home gesture transition; Android 8.0 through 11 enter
PiP from the activity's user-leave callback. PiP stays disabled while playback
is paused, the view is not visible, or the device does not advertise PiP support.
The host activity opts into PiP only when this plugin is reachable.

Android uses Media3 1.11.1, the Media3 Cronet data source, and app-packaged
Cronet. HLS and DASH manifests should use supported media sample and container
formats for the target devices. The iOS plugin uses AVPlayer and has no
third-party playback dependency.

## Maintainer validation app

The internal two-player app in [`tests/demo/app/App.nx`](tests/demo/app/App.nx)
is used to validate setup, typed errors, independent controls, event
subscriptions, disposal, and Android PiP. It is a plugin test fixture, not a
curated Nexa app example.

App-level plugin calls, supported properties, event handlers, and component
arguments hot reload through Nexa's generated DevRuntime adapters. Changes to
the plugin contract, native implementation, dependencies, or host configuration
require rebuilding the host.

## Current platform boundary

The Android host enters PiP only while one visible player is actively playing.
Apps should make the player the primary content of the screen during playback;
the system resizes the host activity as a whole. The iOS system player exposes
its native playback controls and PiP. Background audio session configuration
is outside this video plugin's current contract.
