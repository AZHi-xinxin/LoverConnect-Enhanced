package com.lover.connect

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Opt-in MediaProjection owner used only when AccessibilityService.takeScreenshot
 * is unavailable (Android 8-10). The system consent result is never persisted.
 */
class ScreenCaptureService : Service() {

    private lateinit var screenshotManager: ScreenshotManager

    companion object {
        private const val CHANNEL_ID = "lc_screen_capture"
        private const val NOTIFICATION_ID = 4103
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        private var instance: ScreenCaptureService? = null

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        fun isReady(): Boolean = instance?.screenshotManager?.isReady() == true

        fun takeScreenshot(callback: (String?) -> Unit) {
            val service = instance
            if (service == null || !service.screenshotManager.isReady()) {
                callback(null)
            } else {
                service.screenshotManager.takeScreenshot(callback)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        screenshotManager = ScreenshotManager(this) {
            getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
                .putBoolean("media_projection_ready", false)
                .putLong("media_projection_stopped_at", System.currentTimeMillis())
                .apply()
            stopSelf()
        }
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = readResultData(intent)
        val diagnostics = getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            diagnostics.edit()
                .putBoolean("media_projection_ready", false)
                .putLong("media_projection_failure_at", System.currentTimeMillis())
                .putString("media_projection_failure", "missing_or_denied_consent")
                .apply()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val ready = screenshotManager.initProjection(resultCode, resultData)
        diagnostics.edit()
            .putBoolean("media_projection_ready", ready)
            .putLong(
                if (ready) "media_projection_authorized_at" else "media_projection_failure_at",
                System.currentTimeMillis(),
            )
            .apply {
                if (ready) remove("media_projection_failure")
                else putString("media_projection_failure", "projection_initialization_failed")
            }
            .apply()

        if (!ready) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readResultData(intent: Intent?): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (::screenshotManager.isInitialized) screenshotManager.release()
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("media_projection_ready", false)
            .putLong("media_projection_service_destroyed_at", System.currentTimeMillis())
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "小L屏幕观察",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("小L屏幕观察已授权")
            .setContentText("Android 10 截屏会话正在运行，点此返回 LoverConnect")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
