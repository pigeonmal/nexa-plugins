# `dev.nexa.maps`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-MapKit%20%2F%20Google%20Maps-brightgreen.svg)](https://developer.apple.com/documentation/mapkit)

Native interactive vector map view with annotations, centering, and pin selection events.

Backed by Apple `MapKit` (`MKMapView`) on iOS and Google Play Services Maps (`MapView`) on Android.

---

> **Android minimum API:** 23. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/maps" as Maps

app MapsConformance {
    state selectedPin: String = ""

    body {
        Stack(alignment: Center, background: "#111111") {
            Maps.MapView(
                centerLatitude: 48.8584,
                centerLongitude: 2.2945,
                pins: [
                    Maps.MapPin("eiffel", "Eiffel Tower", 48.8584, 2.2945),
                    Maps.MapPin("louvre", "The Louvre", 48.8606, 2.3376),
                    Maps.MapPin("notre-dame", "Notre-Dame", 48.8530, 2.3499),
                ],
            ).onPinSelected { id ->
                selectedPin = id
                Log.info(message: "MAP_PIN_SELECTED:\(id)")
            }

            Column(spacing: 4, padding: 16) {
                Spacer()
                Row(padding: 10, background: "#CC111111") {
                    Text(
                        if selectedPin == "" { "Tap a map pin" } else { "Selected \(selectedPin)" },
                        color: "#FFFFFF",
                        fontSize: 16,
                        fontWeight: Semibold,
                    )
                }
            }
        }
    }
}
```

---

## 2. API Reference

### `MapView` component

The center coordinates and pins are required. `zoom` defaults to `12.0`.

#### Properties

| Prop | Type | Default | Description |
|---|---|---|---|
| `centerLatitude` | `Float64` | required | Center latitude in degrees. |
| `centerLongitude` | `Float64` | required | Center longitude in degrees. |
| `zoom` | `Float64` | `12.0` | Initial camera zoom. |
| `pins` | `Array<MapPin>` | required | Typed map pins to display. |

#### Event

| Event | Payload | Description |
|---|---|---|
| `pinSelected` | `id: String` | Fires when the user selects a pin. |

### `MapPin`

| Field | Type | Description |
|---|---|---|
| `id` | `String` | Stable identifier returned by `pinSelected`. |
| `title` | `String` | Pin title shown by the native map. |
| `latitude` | `Float64` | Pin latitude in degrees. |
| `longitude` | `Float64` | Pin longitude in degrees. |

## 3. Android setup

The package manifest reads the Android Maps key from the `NEXA_MAPS_API_KEY` environment variable. Set it in the build environment; the plugin adds it to Android application metadata. Keep the key restricted in Google Cloud Console.

```bash
NEXA_MAPS_API_KEY="your-restricted-key" nexa build --android
```
