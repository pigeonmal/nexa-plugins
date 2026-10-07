package dev.nexa.maps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.graphics.Color as AndroidColor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch

/// An interactive Google map with typed, tappable pins.
@Composable
public fun MapViewImpl(
    centerLatitude: Double,
    centerLongitude: Double,
    zoom: Double,
    pins: List<MapPin>,
    markerColor: String? = null,
    route: List<MapCoordinate>? = null,
    routeColor: String? = null,
    onPinSelected: ((String) -> Unit)? = null,
) {
    val initialTarget = coordinate(centerLatitude, centerLongitude)
    val initialZoom = normalizedZoom(zoom)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialTarget, initialZoom)
    }
    val clusterZoom = cameraPositionState.position.zoom.toInt().toDouble()
    val pinGroups = remember(pins, clusterZoom) { clusterPins(pins, clusterZoom) }
    val coroutineScope = rememberCoroutineScope()

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
        val resolvedRoute = route.orEmpty().map { coordinate(it.latitude, it.longitude) }
        if (resolvedRoute.size >= 2) {
            Polyline(
                points = resolvedRoute,
                color = routeColor?.let(::parseColor) ?: Color(0xFF1565C0),
                width = 5f,
            )
        }
        pinGroups.forEach { group ->
            key(group.id) {
                val markerPosition = coordinate(group.latitude, group.longitude)
                val markerState = remember(group.id, group.latitude, group.longitude) {
                    MarkerState(position = markerPosition)
                }
                if (group.pins.size == 1) {
                    val pin = group.pins[0]
                    Marker(
                        state = markerState,
                        title = pin.title,
                        icon = markerColor?.let(::markerDescriptor),
                        onClick = {
                            onPinSelected?.invoke(pin.id)
                            true
                        },
                    )
                } else {
                    MarkerComposable(
                        group.id,
                        state = markerState,
                        title = "${group.pins.size} locations",
                        onClick = {
                            coroutineScope.launch {
                                val nextZoom = (cameraPositionState.position.zoom + 2f).coerceAtMost(22f)
                                cameraPositionState.animate(
                                    CameraUpdateFactory.newLatLngZoom(markerPosition, nextZoom),
                                )
                            }
                            true
                        },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(markerColor?.let(::parseColor) ?: Color(0xFFE53935), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Text(
                                text = group.pins.size.toString(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun parseColor(value: String): Color {
    val parsed = runCatching { AndroidColor.parseColor(value) }.getOrDefault(AndroidColor.BLUE)
    return Color(parsed)
}

private fun markerDescriptor(value: String) =
    BitmapDescriptorFactory.defaultMarker(
        FloatArray(3).let { hsv ->
            AndroidColor.colorToHSV(
                runCatching { AndroidColor.parseColor(value) }.getOrDefault(AndroidColor.RED),
                hsv,
            )
            hsv[0]
        },
    )

private data class MapPinGroup(
    val id: String,
    val pins: List<MapPin>,
    val latitude: Double,
    val longitude: Double,
)

private data class ClusterCell(val x: Long, val y: Long)

/** Groups markers occupying the same 64-pixel Web Mercator grid cell. */
private fun clusterPins(pins: List<MapPin>, zoom: Double): List<MapPinGroup> {
    val safeZoom = if (zoom.isFinite()) zoom.coerceIn(0.0, 22.0) else 12.0
    val pixelsPerWorld = 256.0 * Math.pow(2.0, safeZoom)
    val buckets = LinkedHashMap<ClusterCell, MutableList<MapPin>>()
    for (pin in pins) {
        val point = coordinate(pin.latitude, pin.longitude)
        val latitudeRadians = Math.toRadians(point.latitude.coerceIn(-85.05112878, 85.05112878))
        val worldX = (point.longitude + 180.0) / 360.0 * pixelsPerWorld
        val worldY = (0.5 - Math.log((1.0 + Math.sin(latitudeRadians)) / (1.0 - Math.sin(latitudeRadians)) /
            (4.0 * Math.PI))) * pixelsPerWorld
        val cell = ClusterCell(
            x = Math.floor(worldX / 64.0).toLong(),
            y = Math.floor(worldY / 64.0).toLong(),
        )
        buckets.getOrPut(cell) { mutableListOf() }.add(pin)
    }
    return buckets.values.map { members ->
        val ordered = members.sortedBy { it.id }
        val coordinates = ordered.map { coordinate(it.latitude, it.longitude) }
        val latitude = coordinates.map { it.latitude }.average()
        val longitudeRadians = coordinates.map { Math.toRadians(it.longitude) }
        val sinLongitude = longitudeRadians.fold(0.0) { total, value -> total + Math.sin(value) }
        val cosLongitude = longitudeRadians.fold(0.0) { total, value -> total + Math.cos(value) }
        MapPinGroup(
            id = ordered.joinToString("|") { it.id },
            pins = ordered,
            latitude = latitude,
            longitude = Math.toDegrees(Math.atan2(sinLongitude, cosLongitude)),
        )
    }.sortedBy { it.id }
}

private fun coordinate(latitude: Double, longitude: Double): LatLng {
    val safeLatitude = if (latitude.isFinite()) latitude.coerceIn(-90.0, 90.0) else 0.0
    val safeLongitude = if (longitude.isFinite()) longitude.coerceIn(-180.0, 180.0) else 0.0
    return LatLng(safeLatitude, safeLongitude)
}

private fun normalizedZoom(zoom: Double): Float =
    if (zoom.isFinite()) zoom.coerceIn(0.0, 22.0).toFloat() else 12.0f
