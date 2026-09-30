import CoreMotion
import Foundation

@MainActor
public final class SensorsImpl: SensorsSpec {
    private let motionManager = CMMotionManager()
    private let pedometer = CMPedometer()

    private var isDisposed = false
    private var isAccelerometerRunning = false
    private var isGyroscopeRunning = false
    private var isPedometerRunning = false

    public var onAccelerometerChanged: ((MotionReading) -> Void)?
    public var onGyroscopeChanged: ((MotionReading) -> Void)?
    public var onStepsChanged: ((PedometerReading) -> Void)?

    public var accelerometerAvailable: Bool { motionManager.isAccelerometerAvailable }
    public var gyroscopeAvailable: Bool { motionManager.isGyroAvailable }
    public var pedometerAvailable: Bool { CMPedometer.isStepCountingAvailable() }

    public init() {}

    public func startAccelerometer(_ intervalMs: Int32) async throws(SensorError) {
        try ensureActive()
        try validateInterval(intervalMs)
        guard accelerometerAvailable else {
            throw .accelerometerUnavailable
        }

        motionManager.accelerometerUpdateInterval = Double(intervalMs) / 1_000
        isAccelerometerRunning = true
        motionManager.startAccelerometerUpdates(to: .main) { [weak self] data, _ in
            guard let self, let data else { return }
            MainActor.assumeIsolated {
                guard self.isAccelerometerRunning, !self.isDisposed else { return }
                let acceleration = data.acceleration
                self.onAccelerometerChanged?(
                    MotionReading(
                        x: acceleration.x * 9.80665,
                        y: acceleration.y * 9.80665,
                        z: acceleration.z * 9.80665,
                        timestampUnixSeconds: Self.unixTimestamp(data.timestamp)
                    )
                )
            }
        }
    }

    public func stopAccelerometer() {
        guard isAccelerometerRunning else { return }
        isAccelerometerRunning = false
        motionManager.stopAccelerometerUpdates()
    }

    public func startGyroscope(_ intervalMs: Int32) async throws(SensorError) {
        try ensureActive()
        try validateInterval(intervalMs)
        guard gyroscopeAvailable else {
            throw .gyroscopeUnavailable
        }

        motionManager.gyroUpdateInterval = Double(intervalMs) / 1_000
        isGyroscopeRunning = true
        motionManager.startGyroUpdates(to: .main) { [weak self] data, _ in
            guard let self, let data else { return }
            MainActor.assumeIsolated {
                guard self.isGyroscopeRunning, !self.isDisposed else { return }
                let rotation = data.rotationRate
                self.onGyroscopeChanged?(
                    MotionReading(
                        x: rotation.x,
                        y: rotation.y,
                        z: rotation.z,
                        timestampUnixSeconds: Self.unixTimestamp(data.timestamp)
                    )
                )
            }
        }
    }

    public func stopGyroscope() {
        guard isGyroscopeRunning else { return }
        isGyroscopeRunning = false
        motionManager.stopGyroUpdates()
    }

    public func startPedometer() async throws(SensorError) {
        try ensureActive()
        guard pedometerAvailable else {
            throw .pedometerUnavailable
        }
        guard CMPedometer.authorizationStatus() == .authorized else {
            throw .permissionNotGranted
        }

        isPedometerRunning = true
        pedometer.startUpdates(from: Date()) { [weak self] data, _ in
            guard let self, let data else { return }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                MainActor.assumeIsolated {
                    guard self.isPedometerRunning, !self.isDisposed else { return }
                    self.onStepsChanged?(
                        PedometerReading(
                            steps: data.numberOfSteps.int64Value,
                            timestampUnixSeconds: data.endDate.timeIntervalSince1970
                        )
                    )
                }
            }
        }
    }

    public func stopPedometer() {
        guard isPedometerRunning else { return }
        isPedometerRunning = false
        pedometer.stopUpdates()
    }

    public func dispose() {
        guard !isDisposed else { return }
        stopAccelerometer()
        stopGyroscope()
        stopPedometer()
        isDisposed = true
        onAccelerometerChanged = nil
        onGyroscopeChanged = nil
        onStepsChanged = nil
    }

    private func ensureActive() throws(SensorError) {
        guard !isDisposed else { throw .disposed }
    }

    private func validateInterval(_ intervalMs: Int32) throws(SensorError) {
        guard (5...60_000).contains(intervalMs) else {
            throw .invalidInterval(intervalMs: intervalMs)
        }
    }

    private static func unixTimestamp(_ uptimeTimestamp: TimeInterval) -> Double {
        Date().timeIntervalSince1970 - ProcessInfo.processInfo.systemUptime + uptimeTimestamp
    }
}
