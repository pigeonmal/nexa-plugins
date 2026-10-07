# `dev.nexa.sensors`

[![Nexa Plugin](https://img.shields.io/badge/Nexa-Plugin-blue.svg)](https://github.com/pigeonmal/nexa)
[![Native Engine](https://img.shields.io/badge/Engine-CoreMotion%20%2F%20SensorManager-purple.svg)](https://developer.apple.com/documentation/coremotion)

Hardware motion sensors with unified, physical scientific units across iOS and Android.

Backed by Apple `CoreMotion` on iOS and Android `SensorManager` on Android:
- **Accelerometer**: Calibrated in meters per second squared ($m/s^2$).
- **Gyroscope**: Calibrated in radians per second ($rad/s$).
- **Barometer**: Ambient pressure reported in hectopascals ($hPa$), with events throttled to the requested interval.
- **Pedometer**: Step count accumulated during the active session.

---

> **Android minimum API:** 26. Set `android.minSdk` to at least this value in `nexa.config.nx`.

## 1. Quick Start

```nexa
plugin "plugins/sensors" as Sensors

app SensorsDemo {
    let sensors = Sensors()
    state accelerationX = 0.0
    state rotationZ = 0.0
    state pressure: Float64 = 0.0
    state steps: Int64 = 0
    state sensorError = ""

    body {
        OnAppear async {
            sensors.accelerometerChanged { reading ->
                accelerationX = reading.x
            }
            sensors.gyroscopeChanged { reading ->
                rotationZ = reading.z
            }
            sensors.pressureChanged { reading ->
                pressure = reading.hectopascals
            }
            sensors.stepsChanged { reading ->
                steps = reading.steps
            }

            try {
                await sensors.startAccelerometer(50)
                await sensors.startGyroscope(50)
                if sensors.barometerAvailable {
                    await sensors.startBarometer(100)
                }
            } catch {
                case Sensors.SensorError.accelerometerUnavailable {
                    sensorError = "Accelerometer unavailable"
                }
                case Sensors.SensorError.gyroscopeUnavailable {
                    sensorError = "Gyroscope unavailable"
                }
                case Sensors.SensorError.invalidInterval(intervalMs) {
                    sensorError = "Invalid sampling interval: \(intervalMs)"
                }
                case Sensors.SensorError.permissionNotGranted {
                    sensorError = "Motion permission not granted"
                }
                case Sensors.SensorError.disposed {
                    sensorError = "Sensor was disposed"
                }
                else {
                    sensorError = "Motion sensor could not start"
                }
            }

            if await Permissions.request(permission: Motion) == PermissionStatus.granted {
                try {
                    await sensors.startPedometer()
                } catch {
                    case Sensors.SensorError.pedometerUnavailable {
                        sensorError = "Pedometer unavailable"
                    }
                    case Sensors.SensorError.permissionNotGranted {
                        sensorError = "Motion permission not granted"
                    }
                    case Sensors.SensorError.invalidInterval(intervalMs) {
                        sensorError = "Invalid sampling interval: \(intervalMs)"
                    }
                    case Sensors.SensorError.disposed {
                        sensorError = "Sensor was disposed"
                    }
                    else {
                        sensorError = "Pedometer could not start"
                    }
                }
            }
        }
        OnDisappear {
            sensors.dispose()
        }
        Column {
            Text("Acceleration X: \(accelerationX) m/s²")
            Text("Rotation Z: \(rotationZ) rad/s")
            Text("Pressure: \(pressure) hPa")
            Text("Steps: \(steps)")
            Text(sensorError)
        }
    }
}
```

---

## 2. API Reference

### `Sensors` handle

| Constructor | Signature | Description |
|---|---|---|
| `Sensors` | `Sensors()` | Creates a sensor manager for motion and step updates. |

Direct hardware sensor coordinator.


#### Properties

| Property | Type | Access | Description |
|---|---|---|---|
| `accelerometerAvailable` | `Bool` | Read-only | Hardware accelerometer sensor presence check |
| `gyroscopeAvailable` | `Bool` | Read-only | Hardware gyroscope sensor presence check |
| `barometerAvailable` | `Bool` | Read-only | Hardware pressure sensor / Core Motion altimeter availability |
| `pedometerAvailable` | `Bool` | Read-only | Hardware step counter coprocessor presence check |

#### Methods

| Method | Return Type | Description |
|---|---|---|
| `startAccelerometer(intervalMs: Int32)` | `async -> Void throws SensorError` | Starts accelerometer sampling at specified millisecond interval |
| `stopAccelerometer()` | `Void` | Suspends accelerometer sensor updates to conserve battery |
| `startGyroscope(intervalMs: Int32)` | `async -> Void throws SensorError` | Starts gyroscope rotation sampling at specified interval |
| `stopGyroscope()` | `Void` | Suspends gyroscope sensor updates |
| `startBarometer(intervalMs: Int32)` | `async -> Void throws SensorError` | Starts pressure updates in hectopascals, throttled to at least the requested interval |
| `stopBarometer()` | `Void` | Stops pressure updates and releases the altimeter listener |
| `startPedometer()` | `async -> Void throws SensorError` | Initializes step counter and accumulates steps |
| `stopPedometer()` | `Void` | Halts step counter updates |
| `dispose()` | `Void` | Stops all active sensor listeners and unregisters OS delegates |

#### Events

| Event | Payload | Description |
|---|---|---|
| `accelerometerChanged` | `reading: MotionReading` | Emits 3-axis acceleration values in $m/s^2$ |
| `gyroscopeChanged` | `reading: MotionReading` | Emits 3-axis rotational velocity values in $rad/s$ |
| `pressureChanged` | `reading: PressureReading` | Emits ambient pressure in hPa with a Unix timestamp |
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

#### `PressureReading`
| Field | Type | Description |
|---|---|---|
| `hectopascals` | `Float64` | Ambient pressure in hPa. Core Motion's kPa reading is converted to hPa on iOS. |
| `timestampUnixSeconds` | `Float64` | Sensor timestamp as Unix epoch seconds |

---

### Error Handling (`SensorError`)

| Variant | Description |
|---|---|
| `accelerometerUnavailable` | Device lacks accelerometer hardware |
| `gyroscopeUnavailable` | Device lacks gyroscope hardware |
| `barometerUnavailable` | Device lacks a pressure sensor or Core Motion altimeter |
| `pedometerUnavailable` | Device lacks step counter hardware or coprocessor |
| `permissionNotGranted` | Motion, fitness activity, or altimeter permission was denied or restricted |
| `invalidInterval(intervalMs: Int32)` | Sampling interval must be between 5 and 60,000 milliseconds |
| `disposed` | Sensor handle was disposed |
