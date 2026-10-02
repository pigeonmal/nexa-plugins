# `@nexa/maps`

Native interactive maps for Nexa apps: Apple MapKit on iOS and Google Maps on
Android. `MapView` accepts a center coordinate, a zoom level, and a typed list
of pins. Selecting a pin emits its `id` through `onPinSelected`.

```nexa
plugin "dev.nexa.maps" as Maps

Maps.MapView(
    centerLatitude: 48.8584,
    centerLongitude: 2.2945,
    pins: [
        Maps.MapPin("eiffel", "Eiffel Tower", 48.8584, 2.2945),
        Maps.MapPin("louvre", "The Louvre", 48.8606, 2.3376),
    ],
) onPinSelected { pinId ->
    Log.info(message: "MAP_PIN_SELECTED:\(pinId)")
}
```

Pin IDs must be unique. `zoom` defaults to `12` and accepts values from 0 to
22; values outside that range are clamped by the native implementation.

## Android API key

Google Maps for Android requires a Google Cloud API key with the Maps SDK for
Android enabled. The generated manifest reads it from `NEXA_MAPS_API_KEY`; set
that environment variable when generating or building the Android app:

```sh
NEXA_MAPS_API_KEY="your-key" nexa dev --android
```

The key is not stored in the plugin package or app source. Restrict it in
Google Cloud to the app's Android package name and signing certificate. The
plugin requests internet access and declares the Google Maps key metadata.
Apple MapKit does not require an app API key.

## Platform versions

- iOS 17 or later (MapKit)
- Android API 23 or later (Google Maps Compose 8.6.0, using Maps SDK 20.0.0)

## Conformance

```sh
nexa plugin check plugins/maps
(cd plugins/maps/tests/conformance/app && nexa check)
(cd plugins/maps/tests/conformance/app && nexa dev --ios --once)
(cd plugins/maps/tests/conformance/app && NEXA_MAPS_API_KEY="your-key" nexa dev --android --once)
```

The iOS pin-selection runtime check uses UI automation and Simulator logs only:

```sh
NEXA_IOS_SIMULATOR_ID="<booted-simulator-id>" plugins/maps/tests/acceptance/ios-map-pin-selection.sh
```

If `NEXA_IOS_SIMULATOR_ID` is omitted, the script selects the first booted
iOS Simulator. Android tile and pin-selection acceptance still requires a
configured Maps SDK key and a device image with Google Play services.
