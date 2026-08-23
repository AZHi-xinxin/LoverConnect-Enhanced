package com.lover.connect

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.UUID

class LocationTrackingService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var runtimeStore: LocationSafetyRuntimeStore
    private lateinit var eventStore: LocationSafetyEventStore
    private var machine: GeofenceStateMachine? = null
    private var snapshot = GeofenceSnapshot()
    private var foregroundStarted = false
    private var locationDegradedQueued = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackWatchdog = LocationCallbackWatchdog()
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (runtimeStore.isTrackingEnabled() && !runtimeStore.isPaused()) {
                val nowElapsed = SystemClock.elapsedRealtime()
                if (callbackWatchdog.shouldReregister(nowElapsed)) {
                    runtimeStore.recordRejectedSample("raw_callback_timeout")
                    updateNotification("定位回调超时，正在自动恢复")
                    requestLocationUpdates(recovery = true)
                }
                mainHandler.postDelayed(this, LocationSamplingPolicy.WATCHDOG_CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runtimeStore = LocationSafetyRuntimeStore(this)
        eventStore = LocationSafetyEventStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseTracking()
            ACTION_STOP -> stopTracking()
            ACTION_START, null -> startTrackingIfAllowed()
        }
        return START_STICKY
    }

    private fun startTrackingIfAllowed() {
        if (!runtimeStore.isTrackingEnabled() || runtimeStore.isPaused()) {
            stopSelf()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            runtimeStore.setPaused(true)
            stopSelf()
            return
        }

        val config = try {
            SecureLocationConfigStore(this).load()
        } catch (_: LocationConfigUnavailableException) {
            runtimeStore.setPaused(true)
            stopSelf()
            return
        }
        if (config.zones.isEmpty()) {
            runtimeStore.setPaused(true)
            stopSelf()
            return
        }

        machine = GeofenceStateMachine(config)
        snapshot = runtimeStore.loadSnapshot()
        startLocationForeground(buildNotification("正在等待安全定位"))
        requestLocationUpdates(recovery = false)
        scheduleCallbackWatchdog()
        LocationSafetyUploader.trigger(this)
    }

    private fun requestLocationUpdates(recovery: Boolean) {
        val attemptedAt = System.currentTimeMillis()
        callbackWatchdog.onRegistrationAttempt(SystemClock.elapsedRealtime())
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            runtimeStore.recordRegistrationAttempt(
                at = attemptedAt,
                providers = emptySet(),
                error = "missing_fine_location_permission",
                recovery = recovery
            )
            return
        }
        runCatching { locationManager.removeUpdates(this) }
        val requestedProviders = linkedSetOf<String>()
        val registrationErrors = mutableListOf<String>()
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            if (runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        LocationSamplingPolicy.INTERVAL_MS,
                        LocationSamplingPolicy.MIN_DISTANCE_METERS,
                        this,
                        Looper.getMainLooper()
                    )
                    requestedProviders += provider
                } catch (error: Exception) {
                    registrationErrors += "$provider:${error.javaClass.simpleName}"
                }
            }
        }
        runtimeStore.recordRegistrationAttempt(
            at = attemptedAt,
            providers = requestedProviders,
            error = registrationErrors.takeIf { it.isNotEmpty() }?.joinToString(","),
            recovery = recovery
        )
        if (requestedProviders.isEmpty()) {
            updateNotification("请开启系统定位服务")
            queueDiagnosticEvent(LocationSafetyEventType.LOCATION_DEGRADED)
        }
    }

    private fun scheduleCallbackWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(
            watchdogRunnable,
            LocationSamplingPolicy.WATCHDOG_CHECK_INTERVAL_MS
        )
    }

    override fun onLocationChanged(location: Location) {
        val observedAt = System.currentTimeMillis()
        callbackWatchdog.onRawCallback(SystemClock.elapsedRealtime())
        runtimeStore.recordRawCallback(observedAt)
        if (!location.hasAccuracy()) {
            runtimeStore.recordRejectedSample("missing_accuracy")
            return
        }
        val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (ageNanos < 0L || ageNanos > MAX_LOCATION_AGE_NANOS) {
            runtimeStore.recordRejectedSample("stale_location")
            return
        }
        val stateMachine = machine
        if (stateMachine == null) {
            runtimeStore.recordRejectedSample("state_machine_unavailable")
            return
        }
        val sample = LocationSample(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            observedAt = observedAt
        )
        val transition = stateMachine.process(snapshot, sample)
        if (!transition.accepted) {
            runtimeStore.recordRejectedSample("state_machine_rejected")
            return
        }
        runtimeStore.recordAcceptedSample(observedAt)
        locationDegradedQueued = false
        snapshot = transition.snapshot
        runtimeStore.saveSnapshot(snapshot)

        var queuedAny = false
        transition.events.forEach { rawEvent ->
            if (rawEvent.type == LocationSafetyEventType.DEPARTED) {
                runtimeStore.clearAcknowledgedSession()
            }
            val event = if (rawEvent.type == LocationSafetyEventType.DEPARTED) {
                rawEvent.copy(reportedOverride = runtimeStore.consumeReportedOnce(rawEvent.occurredAt))
            } else rawEvent
            queuedAny = eventStore.enqueue(event) || queuedAny
            if (event.type == LocationSafetyEventType.ARRIVED) {
                eventStore.compactUnsentTrip(event.awaySessionId)
            }
        }
        if (queuedAny) LocationSafetyUploader.trigger(this)
        updateNotification(statusText(snapshot))
    }

    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) {
        locationDegradedQueued = false
        if (runtimeStore.isTrackingEnabled() && !runtimeStore.isPaused()) {
            requestLocationUpdates(recovery = true)
            scheduleCallbackWatchdog()
        }
    }

    override fun onProviderDisabled(provider: String) {
        updateNotification("定位暂不可用；不会据此判断离开")
        val anyProviderEnabled = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).any { candidate ->
            runCatching { locationManager.isProviderEnabled(candidate) }.getOrDefault(false)
        }
        if (!anyProviderEnabled) queueDiagnosticEvent(LocationSafetyEventType.LOCATION_DEGRADED)
    }

    private fun pauseTracking() {
        if (!runtimeStore.isPaused()) {
            queueDiagnosticEvent(LocationSafetyEventType.TRACKING_PAUSED)
        }
        runtimeStore.setPaused(true)
        locationManager.removeUpdates(this)
        mainHandler.removeCallbacks(watchdogRunnable)
        if (!foregroundStarted) startLocationForeground(buildNotification("安全定位已暂停"))
        else updateNotification("安全定位已暂停")
    }

    private fun queueDiagnosticEvent(type: LocationSafetyEventType) {
        if (type == LocationSafetyEventType.LOCATION_DEGRADED && locationDegradedQueued) return
        val config = runCatching { SecureLocationConfigStore(this).load() }.getOrNull()
        val zoneId = snapshot.originZoneId ?: snapshot.currentZoneId ?: "unknown"
        val zoneLabel = config?.zones?.firstOrNull { it.id == zoneId }?.label ?: "安全区域"
        val queued = eventStore.enqueue(
            LocationSafetyEvent(
                type = type,
                awaySessionId = snapshot.awaySessionId ?: UUID.randomUUID().toString(),
                zoneId = zoneId,
                zoneLabel = zoneLabel,
                occurredAt = System.currentTimeMillis()
            )
        )
        if (type == LocationSafetyEventType.LOCATION_DEGRADED && queued) {
            locationDegradedQueued = true
        }
        if (queued) LocationSafetyUploader.trigger(this)
    }

    private fun stopTracking() {
        runtimeStore.setTracking(enabled = false, paused = false)
        locationManager.removeUpdates(this)
        mainHandler.removeCallbacks(watchdogRunnable)
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun statusText(value: GeofenceSnapshot): String = when (value.state) {
        GeofenceState.UNKNOWN -> "正在校准安全区域"
        GeofenceState.INSIDE -> "安全区域内：${value.currentZoneId ?: "已配置区域"}"
        GeofenceState.EXIT_PENDING -> "正在确认是否离开（防漂移）"
        GeofenceState.AWAY -> "已离开安全区域"
        GeofenceState.RETURN_PENDING -> "正在确认安全到达"
    }

    private fun startLocationForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "安全位置播报",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "位置监控开启时持续显示；不上传原始经纬度"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pause = PendingIntent.getService(
            this,
            1,
            Intent(this, LocationTrackingService::class.java).setAction(ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("LoverConnect 安全播报")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "暂停", pause)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stop)
            .build()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(watchdogRunnable)
        locationManager.removeUpdates(this)
        eventStore.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.lover.connect.location.START"
        const val ACTION_PAUSE = "com.lover.connect.location.PAUSE"
        const val ACTION_STOP = "com.lover.connect.location.STOP"
        private const val CHANNEL_ID = "lc_location_safety"
        private const val NOTIFICATION_ID = 4203
        private const val MAX_LOCATION_AGE_NANOS = 300_000_000_000L
    }
}
