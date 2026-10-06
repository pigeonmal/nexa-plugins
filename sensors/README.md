# `@nexa/sensors`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-CoreMotion%20%2F%20SensorManager-purple.svg)](https://developer.apple.com/documentation/coremotion)

Hardware motion sensors with unified, physical scientific units across iOS and Android.

Backed by Apple `CoreMotion` on iOS and Android `SensorManager` on Android:
- **Accelerometer**: Calibrated in meters per second squared ($m/s^2$).
- **Gyroscope**: Calibrated in radians per second ($rad/s$).
- **Pedometer**: Step count accumulated during the active session.

---

## 1. Quick Start

```nexa
plugin "dev.nexa.sensors" as Sensors

component StepTrackerScreen() {
    let sensors = Sensors.Sensors()
    state currentSteps: Int64 = 0
    state isTracking: Bool = false

    onAppear(() => {
        sensors.onStepsChanged((reading) => {
            currentSteps = reading.steps
        })
    })

    onDisappear(() => {
        sensors.stopPedometer()
        sensors.dispose()
    })

    fn toggleTracking() {
        if isTracking {
            sensors.stopPedometer()
            isTracking = false
        } else {
            try {
                await sensors.startPedometer()
                isTracking = true
            } catch Sensors.SensorError as err {
                print("Failed to start pedometer: \(err)")
            }
        }
    }

    VStack(spacing: 20) {
        Text("Steps Today", size: 16)
        Text("\(currentSteps)", size: 36, weight: "bold")
        Button(isTracking ? "Pause Tracking" : "Start Tracking", action: () => { toggleTracking() })
    }
}
```

---

## 2. API Reference

### `Sensors` Native Class

Direct hardware sensor coordinator.

```nexa
native class Sensors {
    init()
}
```

#### Properties

| Property | Type | Access | Description |
|---|---|---|---|
| `accelerometerAvailable` | `Bool` | Read-only | Hardware accelerometer sensor presence check |
| `gyroscopeAvailable` | `Bool` | Read-only | Hardware gyroscope sensor presence check |
| `pedometerAvailable` | `Bool` | Read-only | Hardware step counter coprocessor presence check |

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `startAccelerometer(intervalMs: Int32)` | `Void` | Starts accelerometer sampling at specified millisecond interval |
| `stopAccelerometer()` | `Void` | Suspends accelerometer sensor updates to conserve battery |
| `startGyroscope(intervalMs: Int32)` | `Void` | Starts gyroscope rotation sampling at specified interval |
| `stopGyroscope()` | `Void` | Suspends gyroscope sensor updates |
| `startPedometer()` | `Void` | Initializes step counter and accumulates steps |
| `stopPedometer()` | `Void` | Halts step counter updates |
| `dispose()` | `Void` | Stops all active sensor listeners and unregisters OS delegates |

#### Events

| Event | Payload | Description |
|---|---|---|
| `accelerometerChanged` | `reading: MotionReading` | Emits 3-axis linear acceleration values in $m/s^2$ |
| `gyroscopeChanged` | `reading: MotionReading` | Emits 3-axis rotational velocity values in $rad/s$ |
| `stepsChanged` | `reading: PedometerReading` | Emits cumulative steps taken during the active sensor session |

---

### Data Structures

#### `MotionReading`
| Field | Type | Description |
|---|---|---|
| `x` | `Float64` | Acceleration ($m/s^2$) or rotation ($rad/s$) along X axis |
| `y` | `Float64` | Acceleration ($m/s^2$) or rotation ($rad/s$) along Y axis |
| `z` | `Float64` | Acceleration ($m/s^2$) or rotation ($rad/s$) along Z axis |
| `timestampUnixSeconds` | `Float64` | System sensor timestamp as Unix epoch seconds |

#### `PedometerReading`
| Field | Type | Description |
|---|---|---|
| `steps` | `Int64` | Accumulated step count |
| `timestampUnixSeconds` | `Float64` | System sensor timestamp as Unix epoch seconds |

---

### Error Handling (`SensorError`)

| Variant | Description |
|---|---|
| `accelerometerUnavailable` | Device lacks accelerometer hardware |
| `gyroscopeUnavailable` | Device lacks gyroscope hardware |
| `pedometerUnavailable` | Device lacks step counter hardware or coprocessor |
| `permissionNotGranted` | Motion/fitness activity permission was denied by user |
| `invalidInterval(intervalMs: Int32)` | Interval must be greater than zero |
| `disposed` | Sensor handle was disposed |
