# `dev.nexa.maps`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-MapKit%20%2F%20Google%20Maps-brightgreen.svg)](https://developer.apple.com/documentation/mapkit)

Native interactive map view with zoom-aware marker clustering, custom marker colors, route overlays, camera centering, and pin selection events.

Backed by SwiftUI `Map` / MapKit on iOS and Google Play Services Maps Compose on Android.

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
                markerColor: "#7B1FA2",
                pins: [
                    Maps.MapPin("eiffel", "Eiffel Tower", 48.8584, 2.2945),
                    Maps.MapPin("louvre", "The Louvre", 48.8606, 2.3376),
                    Maps.MapPin("notre-dame", "Notre-Dame", 48.8530, 2.3499),
                ],
                route: [
                    Maps.MapCoordinate(48.8584, 2.2945),
                    Maps.MapCoordinate(48.8606, 2.3376),
                    Maps.MapCoordinate(48.8530, 2.3499),
                ],
                routeColor: "#1565C0",
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
| `markerColor` | `String?` | `nil` | Optional six-digit hex tint for every marker, such as `"#7B1FA2"`. Invalid values use the native red marker color. |
| `route` | `Array<MapCoordinate>?` | `nil` | Optional ordered route. Fewer than two points draw no route. Coordinates outside the valid latitude/longitude ranges are clamped; non-finite coordinates become zero. |
| `routeColor` | `String?` | `nil` | Optional six-digit hex route color. Defaults to blue. |

Nearby pins in the same 64-pixel map grid cell are grouped into a count marker on both platforms. Tapping a cluster zooms toward its center; single pins continue to emit `pinSelected`.

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

### `MapCoordinate`

| Field | Type | Description |
|---|---|---|
| `latitude` | `Float64` | Route latitude in degrees. |
| `longitude` | `Float64` | Route longitude in degrees. |

Android renders the route as a native Google Maps polyline and iOS renders it as a MapKit `MapPolyline`. Both platform map frameworks own and release their map resources with the containing Compose or SwiftUI view lifecycle.

## 3. Android setup

The package manifest reads the Android Maps key from the `NEXA_MAPS_API_KEY` environment variable. Set it in the build environment; the plugin adds it to Android application metadata. Keep the key restricted in Google Cloud Console.

```bash
NEXA_MAPS_API_KEY="your-restricted-key" nexa build --android
```
