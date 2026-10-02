package dev.nexa.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

/// An interactive Google map with typed, tappable pins.
@Composable
public fun MapViewImpl(
    centerLatitude: Double,
    centerLongitude: Double,
    zoom: Double,
    pins: List<MapPin>,
    onPinSelected: ((String) -> Unit)? = null,
) {
    val initialTarget = coordinate(centerLatitude, centerLongitude)
    val initialZoom = normalizedZoom(zoom)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialTarget, initialZoom)
    }

    LaunchedEffect(centerLatitude, centerLongitude, zoom) {
        cameraPositionState.position = CameraPosition.fromLatLngZoom(
            coordinate(centerLatitude, centerLongitude),
            normalizedZoom(zoom),
        )
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
    ) {
        pins.forEach { pin ->
            key(pin.id) {
                val markerPosition = coordinate(pin.latitude, pin.longitude)
                val markerState = remember(pin.id, pin.latitude, pin.longitude) {
                    MarkerState(position = markerPosition)
                }
                Marker(
                    state = markerState,
                    title = pin.title,
                    onClick = {
                        onPinSelected?.invoke(pin.id)
                        true
                    },
                )
            }
        }
    }
}

private fun coordinate(latitude: Double, longitude: Double): LatLng {
    val safeLatitude = if (latitude.isFinite()) latitude.coerceIn(-90.0, 90.0) else 0.0
    val safeLongitude = if (longitude.isFinite()) longitude.coerceIn(-180.0, 180.0) else 0.0
    return LatLng(safeLatitude, safeLongitude)
}

private fun normalizedZoom(zoom: Double): Float =
    if (zoom.isFinite()) zoom.coerceIn(0.0, 22.0).toFloat() else 12.0f
