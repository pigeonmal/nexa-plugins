import Foundation
import MapKit
import SwiftUI

/// An interactive Apple MapKit map with typed, tappable pins.
@MainActor
public struct MapViewImpl: View {
    public let centerLatitude: Double
    public let centerLongitude: Double
    public let zoom: Double
    public let pins: [MapPin]
    public let markerColor: String?
    public let route: [MapCoordinate]?
    public let routeColor: String?
    public let onPinSelected: ((String) -> Void)?

    @State private var cameraPosition: MapCameraPosition
    @State private var clusterZoom: Double

    public init(
        centerLatitude: Double,
        centerLongitude: Double,
        zoom: Double,
        pins: [MapPin],
        markerColor: String? = nil,
        route: [MapCoordinate]? = nil,
        routeColor: String? = nil,
        onPinSelected: ((String) -> Void)?
    ) {
        self.centerLatitude = centerLatitude
        self.centerLongitude = centerLongitude
        self.zoom = zoom
        self.pins = pins
        self.markerColor = markerColor
        self.route = route
        self.routeColor = routeColor
        self.onPinSelected = onPinSelected
        let initialZoom = zoom.isFinite ? min(max(zoom, 0), 22) : 12
        _cameraPosition = State(
            initialValue: .region(
                Self.region(
                    latitude: centerLatitude,
                    longitude: centerLongitude,
                    zoom: initialZoom
                )
            )
        )
        _clusterZoom = State(initialValue: initialZoom)
    }

    private var cameraInputs: [Double] {
        [centerLatitude, centerLongitude, zoom]
    }

    public var body: some View {
        let pinGroups = Self.clusteredPins(pins, zoom: clusterZoom)
        Map(position: $cameraPosition) {
            if let route, route.count >= 2 {
                MapPolyline(coordinates: route.map(Self.coordinate(for:)))
                    .stroke(Self.color(from: routeColor, fallback: Color.blue), lineWidth: 5)
            }
            ForEach(pinGroups, id: \.id) { group in
                if group.pins.count == 1, let pin = group.pins.first {
                    Annotation(pin.title, coordinate: Self.coordinate(for: pin), anchor: .bottom) {
                        Button {
                            onPinSelected?(pin.id)
                        } label: {
                            Image(systemName: "mappin.circle.fill")
                                .font(.title)
                                .foregroundStyle(Self.color(from: markerColor, fallback: Color.red))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(pin.title)
                    }
                } else {
                    Annotation("\(group.pins.count) locations", coordinate: group.coordinate, anchor: .center) {
                        Button {
                            cameraPosition = .region(
                                Self.region(
                                    latitude: group.latitude,
                                    longitude: group.longitude,
                                    zoom: min(22, max(0, zoom + 2))
                                )
                            )
                        } label: {
                            Text(String(group.pins.count))
                                .font(.caption.bold())
                                .foregroundStyle(.white)
                                .frame(width: 40, height: 40)
                                .background(Self.color(from: markerColor, fallback: Color.red), in: Circle())
                                .overlay(Circle().stroke(.white, lineWidth: 2))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("\(group.pins.count) map locations. Tap to zoom in.")
                    }
                }
            }
        }
        .onChange(of: cameraInputs) { _, _ in
            clusterZoom = zoom.isFinite ? min(max(zoom, 0), 22) : 12
            cameraPosition = .region(
                Self.region(
                    latitude: centerLatitude,
                    longitude: centerLongitude,
                    zoom: zoom
                )
            )
        }
        .onMapCameraChange(frequency: .onEnd) { context in
            clusterZoom = Self.zoom(for: context.region)
        }
    }

    private static func coordinate(for pin: MapPin) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(
            latitude: normalized(pin.latitude, minimum: -90, maximum: 90),
            longitude: normalized(pin.longitude, minimum: -180, maximum: 180)
        )
    }

    private static func region(
        latitude: Double,
        longitude: Double,
        zoom: Double
    ) -> MKCoordinateRegion {
        let safeZoom = zoom.isFinite ? min(max(zoom, 0), 22) : 12
        let span = 360 / pow(2, safeZoom)
        return MKCoordinateRegion(
            center: CLLocationCoordinate2D(
                latitude: normalized(latitude, minimum: -90, maximum: 90),
                longitude: normalized(longitude, minimum: -180, maximum: 180)
            ),
            span: MKCoordinateSpan(
                latitudeDelta: min(180, max(0.00001, span)),
                longitudeDelta: min(360, max(0.00001, span))
            )
        )
    }

    private static func zoom(for region: MKCoordinateRegion) -> Double {
        let longitudeSpan = region.span.longitudeDelta
        guard longitudeSpan.isFinite, longitudeSpan > 0 else { return 12 }
        return min(max(log2(360 / longitudeSpan), 0), 22)
    }

    private static func normalized(_ value: Double, minimum: Double, maximum: Double) -> Double {
        guard value.isFinite else { return 0 }
        return min(max(value, minimum), maximum)
    }

    private static func coordinate(for point: MapCoordinate) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(
            latitude: normalized(point.latitude, minimum: -90, maximum: 90),
            longitude: normalized(point.longitude, minimum: -180, maximum: 180)
        )
    }

    private static func clusteredPins(_ pins: [MapPin], zoom: Double) -> [MapPinGroup] {
        let safeZoom = zoom.isFinite ? min(max(zoom, 0), 22) : 12
        let pixelsPerWorld = 256 * pow(2, safeZoom)
        var buckets: [ClusterCell: [MapPin]] = [:]
        for pin in pins {
            let point = coordinate(for: pin)
            let latitude = min(max(point.latitude, -85.05112878), 85.05112878) * .pi / 180
            let worldX = (point.longitude + 180) / 360 * pixelsPerWorld
            let worldY = (0.5 - log((1 + sin(latitude)) / (1 - sin(latitude))) / (4 * .pi)) * pixelsPerWorld
            let cell = ClusterCell(x: Int(floor(worldX / 64)), y: Int(floor(worldY / 64)))
            buckets[cell, default: []].append(pin)
        }

        return buckets.values.map { members in
            let ordered = members.sorted { $0.id < $1.id }
            let points = ordered.map(coordinate(for:))
            let latitude = points.map(\.latitude).reduce(0, +) / Double(points.count)
            let longitudeX = points.map { cos($0.longitude * .pi / 180) }.reduce(0, +)
            let longitudeY = points.map { sin($0.longitude * .pi / 180) }.reduce(0, +)
            let longitude = atan2(longitudeY, longitudeX) * 180 / .pi
            return MapPinGroup(
                id: ordered.map(\.id).joined(separator: "|"),
                pins: ordered,
                latitude: latitude,
                longitude: longitude
            )
        }
        .sorted { $0.id < $1.id }
    }

    private static func color(from value: String?, fallback: Color) -> Color {
        guard let value else { return fallback }
        let digits = value.hasPrefix("#") ? String(value.dropFirst()) : value
        guard digits.count == 6, let rgb = UInt64(digits, radix: 16) else { return fallback }
        return Color(
            .sRGB,
            red: Double((rgb >> 16) & 0xff) / 255,
            green: Double((rgb >> 8) & 0xff) / 255,
            blue: Double(rgb & 0xff) / 255,
            opacity: 1
        )
    }
}

private struct ClusterCell: Hashable {
    let x: Int
    let y: Int
}

private struct MapPinGroup: Identifiable {
    let id: String
    let pins: [MapPin]
    let latitude: Double
    let longitude: Double

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}
