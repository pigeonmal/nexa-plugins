# `@nexa/maps`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-MapKit%20%2F%20Google%20Maps-brightgreen.svg)](https://developer.apple.com/documentation/mapkit)

Native interactive vector map view with annotations, centering, and pin selection events.

Backed by Apple `MapKit` (`MKMapView`) on iOS and Google Play Services Maps (`MapView`) on Android.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.maps" as Maps

component StoreLocatorScreen() {
    state selectedStoreId: String? = null
    state storePins: Array<Maps.MapPin> = [
        Maps.MapPin(id: "store_1", title: "Downtown Flagship", latitude: 37.7749, longitude: -122.4194),
        Maps.MapPin(id: "store_2", title: "Mission Branch", latitude: 37.7599, longitude: -122.4148)
    ]

    VStack {
        Maps.MapView(
            centerLatitude: 37.7749,
            centerLongitude: -122.4194,
            zoom: 13.0,
            pins: storePins,
            onPinSelected: (id) => {
                selectedStoreId = id
            }
        )

        if let id = selectedStoreId {
            HStack {
                Text("Selected Location: \(id)", weight: "bold")
            }
            .padding(16)
        }
    }
}
```

---

## 2. API Reference

### `MapView` Native Component

```nexa
native component MapView
```

#### Properties

| Prop | Type | Default | Description |
|---|---|---|---|
| `centerLatitude` | `Float64` | — | Initial coordinate latitude center in degrees |
| `centerLongitude` | `Float64` | — | Initial coordinate longitude center in degrees |
| `zoom` | `Float64` | `12.0` | Camera zoom level (`1.0` is entire globe, `20.0` is street level) |
| `pins` | `Array<MapPin>` | `[]` | List of typed point annotations placed on the map |

#### Events

| Event | Payload | Description |
|---|---|---|
| `pinSelected` | `id: String` | Fired when user taps any pin annotation marker |

---

### Data Structures

#### `MapPin`
| Field | Type | Description |
|---|---|---|
| `id` | `String` | Unique identifier for marker callback disambiguation |
| `title` | `String` | Text callout label displayed above the marker pin |
| `latitude` | `Float64` | Geographic latitude in degrees |
| `longitude` | `Float64` | Geographic longitude in degrees |

---

## 3. Platform Setup

For Android builds, provide your Google Maps API key in your Android manifest or `nexa.config.nx`:

```nexa
app MyApp {
    android: {
        manifestPlaceholders: {
            "googleMapsApiKey": "AIzaSy..."
        }
    }
}
```
