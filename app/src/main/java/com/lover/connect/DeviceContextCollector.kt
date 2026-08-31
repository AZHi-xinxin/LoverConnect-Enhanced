package com.lover.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

class DeviceContextCollector(context: Context) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val store = DeviceContextStore(appContext)
    private var started = false
    private var receiverRegistered = false
    private var sensorsRegistered = false
    private var motionAverage = 0f
    private var hasGravitySensor = false
    private var hasLinearAccelerationSensor = false
    private val fallbackEstimator = GravityMotionEstimator()
    private val lastWrittenAt = mutableMapOf<String, Long>()
    private val lastWrittenValue = mutableMapOf<String, String>()
    private val pendingValue = mutableMapOf<String, String>()
    private val pendingCount = mutableMapOf<String, Int>()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!DeviceContextSettings.isEnabled(appContext)) return
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    store.recordObservation("screen", "on", source = "system_broadcast")
                    registerSensors()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    store.recordObservation("screen", "off", source = "system_broadcast")
                    unregisterSensors()
                }
                Intent.ACTION_USER_PRESENT ->
                    store.recordObservation("screen", "unlocked", source = "system_broadcast")
                Intent.ACTION_POWER_CONNECTED ->
                    store.recordObservation("power", "connected", source = "system_broadcast")
                Intent.ACTION_POWER_DISCONNECTED ->
                    store.recordObservation("power", "disconnected", source = "system_broadcast")
            }
        }
    }

    fun start() {
        if (!started) {
            started = true
        }
        refresh()
    }

    fun refresh() {
        if (!started) return
        if (!DeviceContextSettings.isEnabled(appContext)) {
            unregisterSensors()
            unregisterStateReceiver()
            return
        }
        registerStateReceiver()
        val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        store.recordObservation("screen", if (power.isInteractive) "on" else "off", source = "live_system_state")
        if (power.isInteractive) registerSensors() else unregisterSensors()
    }

    fun stop() {
        unregisterSensors()
        if (receiverRegistered) {
            unregisterStateReceiver()
        }
        started = false
    }

    private fun registerStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(stateReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterStateReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(stateReceiver) }
        receiverRegistered = false
    }

    private fun registerSensors() {
        if (sensorsRegistered || !DeviceContextSettings.isEnabled(appContext)) return
        hasGravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null
        hasLinearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null
        val types = buildList {
            if (hasGravitySensor) add(Sensor.TYPE_GRAVITY)
            if (hasLinearAccelerationSensor) add(Sensor.TYPE_LINEAR_ACCELERATION)
            if (!hasGravitySensor || !hasLinearAccelerationSensor) add(Sensor.TYPE_ACCELEROMETER)
            add(Sensor.TYPE_LIGHT)
            add(Sensor.TYPE_PROXIMITY)
        }.distinct()
        var registeredAny = false
        types.forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                registeredAny = sensorManager.registerListener(
                    this,
                    sensor,
                    SAMPLE_PERIOD_US,
                    MAX_BATCH_LATENCY_US,
                ) || registeredAny
            }
        }
        sensorsRegistered = registeredAny
    }

    private fun unregisterSensors() {
        if (sensorsRegistered) sensorManager.unregisterListener(this)
        sensorsRegistered = false
        motionAverage = 0f
        fallbackEstimator.reset()
        lastWrittenAt.clear()
        lastWrittenValue.clear()
        pendingValue.clear()
        pendingCount.clear()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !DeviceContextSettings.isEnabled(appContext)) return
        val now = sensorWallClockMs(event)
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                if (event.values.size < 3) return
                writeThrottled(
                    "pose",
                    DeviceContextLogic.classifyPose(event.values[0], event.values[1], event.values[2]),
                    now,
                    POSE_WRITE_INTERVAL_MS,
                )
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (event.values.size < 3) return
                val magnitude = DeviceContextLogic.motionMagnitude(event.values[0], event.values[1], event.values[2])
                motionAverage = if (motionAverage == 0f) magnitude else motionAverage * 0.75f + magnitude * 0.25f
                writeThrottled(
                    "motion",
                    DeviceContextLogic.classifyMotion(motionAverage),
                    now,
                    MOTION_WRITE_INTERVAL_MS,
                )
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (event.values.size < 3) return
                val estimate = fallbackEstimator.add(event.values[0], event.values[1], event.values[2])
                if (!hasGravitySensor) {
                    writeThrottled(
                        "pose",
                        DeviceContextLogic.classifyPose(
                            estimate.gravityX,
                            estimate.gravityY,
                            estimate.gravityZ,
                        ),
                        now,
                        POSE_WRITE_INTERVAL_MS,
                    )
                }
                if (!hasLinearAccelerationSensor && estimate.linearMotionMagnitude != null) {
                    val magnitude = estimate.linearMotionMagnitude
                    motionAverage = if (motionAverage == 0f) magnitude else
                        motionAverage * 0.75f + magnitude * 0.25f
                    writeThrottled(
                        "motion",
                        DeviceContextLogic.classifyMotion(motionAverage),
                        now,
                        MOTION_WRITE_INTERVAL_MS,
                    )
                }
            }
            Sensor.TYPE_LIGHT -> writeThrottled(
                "light",
                DeviceContextLogic.classifyLight(event.values.firstOrNull() ?: -1f),
                now,
                LIGHT_WRITE_INTERVAL_MS,
            )
            Sensor.TYPE_PROXIMITY -> {
                val distance = event.values.firstOrNull() ?: return
                val nearThreshold = minOf(event.sensor.maximumRange, 5f)
                writeThrottled(
                    "proximity",
                    if (distance < nearThreshold) "near" else "far",
                    now,
                    PROXIMITY_WRITE_INTERVAL_MS,
                )
            }
        }
    }

    private fun writeThrottled(kind: String, value: String, nowMs: Long, intervalMs: Long) {
        val lastAt = lastWrittenAt[kind] ?: 0L
        val changed = lastWrittenValue[kind] != value
        if (changed) {
            if (pendingValue[kind] == value) {
                pendingCount[kind] = (pendingCount[kind] ?: 0) + 1
            } else {
                pendingValue[kind] = value
                pendingCount[kind] = 1
            }
            if ((pendingCount[kind] ?: 0) < STABLE_SAMPLE_COUNT) return
            if (lastAt > 0L && nowMs - lastAt < MIN_TRANSITION_INTERVAL_MS) return
        } else {
            pendingValue.remove(kind)
            pendingCount.remove(kind)
            if (nowMs - lastAt < intervalMs) return
        }
        store.recordObservation(kind, value, nowMs)
        lastWrittenAt[kind] = nowMs
        lastWrittenValue[kind] = value
        pendingValue.remove(kind)
        pendingCount.remove(kind)
    }

    private fun sensorWallClockMs(event: SensorEvent): Long {
        val ageNanos = (SystemClock.elapsedRealtimeNanos() - event.timestamp).coerceAtLeast(0L)
        return System.currentTimeMillis() - ageNanos / 1_000_000L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val SAMPLE_PERIOD_US = 1_000_000
        private const val MAX_BATCH_LATENCY_US = 5_000_000
        private const val POSE_WRITE_INTERVAL_MS = 15_000L
        private const val MOTION_WRITE_INTERVAL_MS = 10_000L
        private const val LIGHT_WRITE_INTERVAL_MS = 30_000L
        private const val PROXIMITY_WRITE_INTERVAL_MS = 15_000L
        private const val STABLE_SAMPLE_COUNT = 2
        private const val MIN_TRANSITION_INTERVAL_MS = 10_000L
    }
}
