# `@nexa/video-player`

Native video playback for Nexa. The plugin exposes independent `VideoPlayer`
instances, typed playback state and errors, an `ended` event, and a `VideoView`
with native playback controls.

| Platform | Playback engine | Adaptive formats |
|---|---|---|
| iOS | AVPlayer | HLS |
| Android | Media3 ExoPlayer | HLS and DASH |

Android includes only the Media3 playback, HLS, DASH, and UI modules required by
this package. HLS and DASH manifests should use supported media sample and
container formats for the target devices.

## Example

The self-contained two-player Nexa app in [`tests/demo/app/App.nx`](tests/demo/app/App.nx)
shows setup, typed error handling, independent controls, event subscriptions,
and disposal. From that directory, use `nexa check App.nx`, then `nexa test`
for native project compilation or `nexa dev` to launch the development host.

App-level plugin calls, supported properties, event handlers, and component
arguments hot reload through Nexa's generated DevRuntime adapters. Changes to
the plugin contract, native implementation, dependencies, or host configuration
require rebuilding the host.

## Current platform boundary

The Android activity manifest does not yet opt the generated host into
Picture-in-Picture, so PiP is not available through this package on Android.
The iOS system player exposes its native playback controls. Background audio
session configuration is outside this video plugin's current contract.
