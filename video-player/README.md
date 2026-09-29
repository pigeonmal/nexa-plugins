# `@nexa/video-player`

Native video playback for Nexa. The plugin exposes independent `VideoPlayer`
instances, typed playback state and errors, an `ended` event, and a `VideoView`
with native playback controls.

| Platform | Playback engine | Adaptive formats |
|---|---|---|
| iOS | AVPlayer | HLS |
| Android | Media3 ExoPlayer with NextLib FFmpeg fallback | HLS and DASH |

On iOS, playback uses Apple's AVPlayer and its native decoder. On Android,
`softwareDecodingEnabled: false` disables the NextLib FFmpeg fallback; the
default is `true`. Android uses the app-packaged Cronet provider for media
requests and shares its Cronet engine between player instances.

On Android 8.0 and later, PiP starts when the user backgrounds the app while
exactly one attached video player is actively playing. Android 12 and later use
the system's automatic home gesture transition; Android 8.0 through 11 enter
PiP from the activity's user-leave callback. PiP stays disabled while playback
is paused, the view is not visible, or the device does not advertise PiP support.
The host activity opts into PiP only when this plugin is reachable.

Android uses Media3 1.11.1, NextLib 1.11.1-0.16.0, the Media3 Cronet data
source, and app-packaged Cronet. HLS and DASH manifests should use supported
media sample and container formats for the target devices.

NextLib is GPL-3.0 licensed. Apps that ship its Android dependency must comply
with its license. The iOS plugin has no third-party playback dependency.

## Example

The self-contained two-player Nexa app in [`tests/demo/app/App.nx`](tests/demo/app/App.nx)
shows setup, typed error handling, independent controls, event subscriptions,
and disposal. The first player starts after preparation so Android PiP can be
tried by sending the app to the background while it is playing. From that
directory, use `nexa check App.nx`, then `nexa test` for native project
compilation or `nexa dev` to launch the development host.

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
