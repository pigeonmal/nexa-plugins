# `@nexa/sensors`

Typed access to native accelerometer, gyroscope, and pedometer readings on
iOS 17+ and Android 8+.

| Stream | Values | Availability |
|---|---|---|
| Accelerometer | `x`, `y`, `z` in m/s² | Device hardware dependent; no runtime permission |
| Gyroscope | `x`, `y`, `z` in radians/second | Device hardware dependent; no runtime permission |
| Pedometer | Steps since this sensor instance started | Core Motion / Android step-counter or step-detector hardware; motion permission required |

Motion vector and step timestamps are Unix epoch seconds. Sensor intervals are
specified in milliseconds and must be between 5 and 60,000. Events are
delivered on the main actor on iOS and the main looper on Android. Stop streams
when they are not needed, and call `dispose()` when the `Sensors` instance is no
longer used.

Before starting the pedometer, request the cross-platform motion permission.
Android requests `ACTIVITY_RECOGNITION` on Android 10 and later. iOS requests
Core Motion access through `CMPedometer`; the plugin supplies the required
`NSMotionUsageDescription` entry. Accelerometer and gyroscope readings do not
require this permission.

```nx
plugin "dev.nexa.sensors" as Sensors

app Fitness {
    let sensors = Sensors()
    state horizontalAcceleration = 0.0
    state steps: Int64 = 0
    state sensorError = ""

    body {
        OnAppear async {
            sensors.accelerometerChanged { reading ->
                horizontalAcceleration = reading.x
            }
            sensors.stepsChanged { reading ->
                steps = reading.steps
            }

            try {
                await sensors.startAccelerometer(50)
            } catch {
                else { sensorError = "Accelerometer could not start" }
            }

            let permission = await Permissions.request(permission: Motion)
            if permission == PermissionStatus.granted {
                try {
                    await sensors.startPedometer()
                } catch {
                    case Sensors.SensorError.pedometerUnavailable {
                        sensorError = "Pedometer unavailable"
                    }
                    case Sensors.SensorError.permissionNotGranted {
                        sensorError = "Motion permission not granted"
                    }
                    else { sensorError = "Pedometer could not start" }
                }
            }
        }
        OnDisappear {
            sensors.dispose()
        }
        Column {
            Text("Acceleration X: \(horizontalAcceleration) m/s²")
            Text("Steps: \(steps)")
            Text(sensorError)
        }
    }
}
```

The runnable maintainer app is in [`tests/demo/app`](tests/demo/app). Run
`nexa check App.nx`, then `nexa test` to compile the iOS and Android hosts or
`nexa dev` to launch one. Changes to the plugin contract, native implementation,
permissions, or manifest require rebuilding the host; app-level event handlers
remain hot reloadable.
