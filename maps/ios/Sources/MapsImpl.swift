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
    public let onPinSelected: ((String) -> Void)?

    @State private var cameraPosition: MapCameraPosition

    public init(
        centerLatitude: Double,
        centerLongitude: Double,
        zoom: Double,
        pins: [MapPin],
        onPinSelected: ((String) -> Void)?
    ) {
        self.centerLatitude = centerLatitude
        self.centerLongitude = centerLongitude
        self.zoom = zoom
        self.pins = pins
        self.onPinSelected = onPinSelected
        _cameraPosition = State(
            initialValue: .region(
                Self.region(
                    latitude: centerLatitude,
                    longitude: centerLongitude,
                    zoom: zoom
                )
            )
        )
    }

    private var cameraInputs: [Double] {
        [centerLatitude, centerLongitude, zoom]
    }

    public var body: some View {
        Map(position: $cameraPosition) {
            ForEach(pins, id: \.id) { pin in
                Annotation(pin.title, coordinate: Self.coordinate(for: pin), anchor: .bottom) {
                    Button {
                        onPinSelected?(pin.id)
                    } label: {
                        Image(systemName: "mappin.circle.fill")
                            .font(.title)
                            .foregroundStyle(.red)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(pin.title)
                }
            }
        }
        .onChange(of: cameraInputs) { _, _ in
            cameraPosition = .region(
                Self.region(
                    latitude: centerLatitude,
                    longitude: centerLongitude,
                    zoom: zoom
                )
            )
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

    private static func normalized(_ value: Double, minimum: Double, maximum: Double) -> Double {
        guard value.isFinite else { return 0 }
        return min(max(value, minimum), maximum)
    }
}
