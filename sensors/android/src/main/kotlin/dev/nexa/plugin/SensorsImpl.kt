package dev.nexa.plugin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.nexa.core.NexaRuntimeCore

public class SensorsImpl : SensorsSpec, SensorEventListener {
    private val context = NexaRuntimeCore.context().applicationContext
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isDisposed = false

    @Volatile
    private var isAccelerometerRunning = false

    @Volatile
    private var isGyroscopeRunning = false

    @Volatile
    private var isBarometerRunning = false

    @Volatile
    private var isPedometerRunning = false

    @Volatile
    private var pedometerBaseline: Long? = null

    @Volatile
    private var pedometerSensor: Sensor? = null

    @Volatile
    private var barometerSensor: Sensor? = null

    @Volatile
    private var detectedSteps = 0L

    @Volatile
    override var onAccelerometerChanged: ((MotionReading) -> Unit)? = null

    @Volatile
    override var onGyroscopeChanged: ((MotionReading) -> Unit)? = null

    @Volatile
    override var onPressureChanged: ((PressureReading) -> Unit)? = null

    @Volatile
    override var onStepsChanged: ((PedometerReading) -> Unit)? = null

    override val accelerometerAvailable: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    override val gyroscopeAvailable: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    override val barometerAvailable: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    override val pedometerAvailable: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null ||
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null

    override suspend fun startAccelerometer(intervalMs: Int) {
        ensureActive()
        validateInterval(intervalMs)
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: throw SensorError.accelerometerUnavailable
        isAccelerometerRunning = true
        if (!sensorManager.registerListener(this, sensor, intervalMs * 1_000, mainHandler)) {
            isAccelerometerRunning = false
            throw SensorError.accelerometerUnavailable
        }
    }

    override fun stopAccelerometer() {
        isAccelerometerRunning = false
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.unregisterListener(this, it)
        }
    }

    override suspend fun startGyroscope(intervalMs: Int) {
        ensureActive()
        validateInterval(intervalMs)
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: throw SensorError.gyroscopeUnavailable
        isGyroscopeRunning = true
        if (!sensorManager.registerListener(this, sensor, intervalMs * 1_000, mainHandler)) {
            isGyroscopeRunning = false
            throw SensorError.gyroscopeUnavailable
        }
    }

    override fun stopGyroscope() {
        isGyroscopeRunning = false
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.unregisterListener(this, it)
        }
    }

    override suspend fun startBarometer(intervalMs: Int) {
        ensureActive()
        validateInterval(intervalMs)
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
            ?: throw SensorError.barometerUnavailable
        barometerSensor = sensor
        isBarometerRunning = true
        if (!sensorManager.registerListener(this, sensor, intervalMs * 1_000, mainHandler)) {
            isBarometerRunning = false
            barometerSensor = null
            throw SensorError.barometerUnavailable
        }
    }

    override fun stopBarometer() {
        isBarometerRunning = false
        barometerSensor?.let { sensorManager.unregisterListener(this, it) }
        barometerSensor = null
    }

    override suspend fun startPedometer() {
        ensureActive()
        if (!hasMotionPermission()) throw SensorError.permissionNotGranted
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
            ?: throw SensorError.pedometerUnavailable
        pedometerSensor = sensor
        pedometerBaseline = null
        detectedSteps = 0L
        isPedometerRunning = true
        if (!sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler)) {
            isPedometerRunning = false
            pedometerSensor = null
            throw SensorError.pedometerUnavailable
        }
    }

    override fun stopPedometer() {
        isPedometerRunning = false
        pedometerBaseline = null
        detectedSteps = 0L
        pedometerSensor?.let { sensorManager.unregisterListener(this, it) }
        pedometerSensor = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (isDisposed) return
        val timestamp = unixTimestamp(event.timestamp)
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> if (isAccelerometerRunning) {
                val values = event.values
                onAccelerometerChanged?.invoke(
                    MotionReading(
                        x = values[0].toDouble(),
                        y = values[1].toDouble(),
                        z = values[2].toDouble(),
                        timestampUnixSeconds = timestamp,
                    ),
                )
            }
            Sensor.TYPE_GYROSCOPE -> if (isGyroscopeRunning) {
                val values = event.values
                onGyroscopeChanged?.invoke(
                    MotionReading(
                        x = values[0].toDouble(),
                        y = values[1].toDouble(),
                        z = values[2].toDouble(),
                        timestampUnixSeconds = timestamp,
                    ),
                )
            }
            Sensor.TYPE_PRESSURE -> if (isBarometerRunning && event.values.isNotEmpty()) {
                onPressureChanged?.invoke(
                    PressureReading(
                        hectopascals = event.values[0].toDouble(),
                        timestampUnixSeconds = timestamp,
                    ),
                )
            }
            Sensor.TYPE_STEP_COUNTER -> if (isPedometerRunning && event.values.isNotEmpty()) {
                val currentSteps = event.values[0].toLong()
                val baseline = pedometerBaseline ?: currentSteps.also { pedometerBaseline = it }
                onStepsChanged?.invoke(
                    PedometerReading(
                        steps = (currentSteps - baseline).coerceAtLeast(0L),
                        timestampUnixSeconds = timestamp,
                    ),
                )
            }
            Sensor.TYPE_STEP_DETECTOR -> if (isPedometerRunning) {
                detectedSteps += 1L
                onStepsChanged?.invoke(
                    PedometerReading(
                        steps = detectedSteps,
                        timestampUnixSeconds = timestamp,
                    ),
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true
        isAccelerometerRunning = false
        isGyroscopeRunning = false
        isBarometerRunning = false
        isPedometerRunning = false
        pedometerBaseline = null
        pedometerSensor = null
        barometerSensor = null
        detectedSteps = 0L
        sensorManager.unregisterListener(this)
        onAccelerometerChanged = null
        onGyroscopeChanged = null
        onPressureChanged = null
        onStepsChanged = null
    }

    private fun ensureActive() {
        if (isDisposed) throw SensorError.disposed
    }

    private fun validateInterval(intervalMs: Int) {
        if (intervalMs !in MIN_INTERVAL_MS..MAX_INTERVAL_MS) {
            throw SensorError.invalidInterval(intervalMs)
        }
    }

    private fun hasMotionPermission(): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun unixTimestamp(sensorTimestampNanos: Long): Double =
        System.currentTimeMillis() / 1_000.0 -
            SystemClock.elapsedRealtimeNanos() / 1_000_000_000.0 +
            sensorTimestampNanos / 1_000_000_000.0

    private companion object {
        const val MIN_INTERVAL_MS = 5
        const val MAX_INTERVAL_MS = 60_000
    }
}
