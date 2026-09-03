package com.lover.connect

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Cross-device accessibility service.
 *
 * Runtime-confirmed Vivo devices use the conservative passive mode because
 * active callbacks were observed to destabilize their accessibility grant.
 * OPPO keeps conservative lifecycle handling while retaining the active
 * app-lock path verified on real ColorOS hardware. The decision comes only
 * from immutable platform identity, never from an AI-controlled preference.
 */
class LCAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: LCAccessibilityService? = null
    }

    private val devicePolicy by lazy { DeviceCompatibility.currentPolicy() }
    private val interventionMode get() = devicePolicy.appInterventionMode
    private val screenshotExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lc-accessibility-screenshot").apply { isDaemon = true }
    }
    private var lastHandledPackage: String? = null
    private var lastHandledAt = 0L
    private var lockOverlay: View? = null

    private fun diagnostics() =
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        diagnostics().edit()
            .putBoolean("accessibility_connected", true)
            .putLong("accessibility_connected_at", System.currentTimeMillis())
            .putString("accessibility_stability_mode", devicePolicy.accessibilityStabilityMode.name)
            .putString("accessibility_intervention_mode", interventionMode.name)
            .remove("accessibility_last_callback_error")
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Passive metadata is preserved on every device for Little L reports.
        try {
            val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
            if (prefs.getString("current_foreground_package", "") != pkg) {
                prefs.edit()
                    .putString("current_foreground_package", pkg)
                    .putLong("current_foreground_since", System.currentTimeMillis())
                    .apply()
            }
            diagnostics().edit()
                .putLong("accessibility_last_event_at", System.currentTimeMillis())
                .putString("accessibility_last_event_package", pkg)
                .apply()

            // Vivo compatibility is deliberately passive. OPPO and standard
            // Android devices retain the active intervention path.
            if (interventionMode == DeviceCompatibility.AppInterventionMode.OEM_PASSIVE_COMPAT) {
                return
            }

            if (pkg == AppLockManager.RIKKA_PACKAGE) {
                removeLockOverlay()
                return
            }

            if (AppLockManager.isLocked(this, pkg)) {
                val now = System.currentTimeMillis()
                if (pkg == lastHandledPackage && now - lastHandledAt < 1200L) return
                lastHandledPackage = pkg
                lastHandledAt = now
                if (AppLockManager.shouldShowOverlay(this, pkg)) showLockOverlay(pkg)
                else performGlobalAction(GLOBAL_ACTION_HOME)
                showBlockedNotification(pkg)
                return
            }

            // Preserve an already visible full-screen lock across launcher and
            // SystemUI events. Only an explicit unlock/action dismisses it.
            if (lockOverlay != null) return
            if (AppLockManager.shouldFocusToRikka(this, pkg) ||
                AppLockManager.shouldRedirectToRikka(this, pkg)) {
                launchRikkaOrHome()
            }
        } catch (error: Exception) {
            // No vendor-specific callback failure may disable accessibility.
            diagnostics().edit()
                .putLong("accessibility_last_callback_error_at", System.currentTimeMillis())
                .putString("accessibility_last_callback_error", error.javaClass.simpleName)
                .apply()
        }
    }

    override fun onInterrupt() {
        diagnostics().edit()
            .putLong("accessibility_interrupted_at", System.currentTimeMillis())
            .apply()
    }

    override fun onDestroy() {
        removeLockOverlay()
        screenshotExecutor.shutdownNow()
        instance = null
        diagnostics().edit()
            .putBoolean("accessibility_connected", false)
            .putLong("accessibility_destroyed_at", System.currentTimeMillis())
            .apply()
        super.onDestroy()
    }

    fun dismissLockOverlay() {
        removeLockOverlay()
    }

    private fun showLockOverlay(pkg: String) {
        if (interventionMode != DeviceCompatibility.AppInterventionMode.ACTIVE) return
        removeLockOverlay()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            background = GradientDrawable().apply {
                setColor(Color.rgb(25, 22, 35))
                cornerRadius = 0f
            }
        }
        val title = TextView(this).apply {
            text = AppLockManager.getMessage(this@LCAccessibilityService, pkg)
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val detail = TextView(this).apply {
            val until = AppLockManager.getUnlockAt(this@LCAccessibilityService, pkg)
            text = if (until > 0L) {
                "Unlocks at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))}"
            } else {
                "Locked until manually released"
            }
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 36)
        }
        val home = Button(this).apply {
            text = "Back to home"
            setOnClickListener {
                removeLockOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
        val emergency = Button(this).apply {
            text = "Emergency unlock all"
            setOnClickListener {
                AppLockManager.clearAll(this@LCAccessibilityService)
                removeLockOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
        root.addView(title)
        root.addView(detail)
        root.addView(home, LinearLayout.LayoutParams(-1, -2))
        root.addView(emergency, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        windowManager.addView(root, params)
        lockOverlay = root
    }

    private fun removeLockOverlay() {
        val view = lockOverlay ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
        }
        lockOverlay = null
    }

    private fun launchRikkaOrHome() {
        if (interventionMode != DeviceCompatibility.AppInterventionMode.ACTIVE) return
        val launch = packageManager.getLaunchIntentForPackage(AppLockManager.RIKKA_PACKAGE)
        if (launch == null) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            startActivity(launch)
        } catch (_: Exception) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun showBlockedNotification(pkg: String) {
        if (interventionMode != DeviceCompatibility.AppInterventionMode.ACTIVE) return
        val channelId = "lc_app_lock"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "App lock", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val pending = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, UnlockAllReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        manager.notify(
            4102,
            builder.setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("App is locked")
                .setContentText(pkg)
                .setAutoCancel(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Emergency unlock all",
                    pending,
                )
                .build(),
        )
    }

    fun takeScreenshotNow(callback: (String?) -> Unit) {
        diagnostics().edit()
            .putLong("screenshot_last_requested_at", System.currentTimeMillis())
            .apply()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (!ScreenCaptureService.isReady()) {
                diagnostics().edit()
                    .putLong("screenshot_last_failure_at", System.currentTimeMillis())
                    .putString("screenshot_last_failure", "media_projection_consent_required")
                    .apply()
                callback(null)
                return
            }
            ScreenCaptureService.takeScreenshot { base64 ->
                if (base64 != null) {
                    diagnostics().edit()
                        .putLong("screenshot_last_success_at", System.currentTimeMillis())
                        .remove("screenshot_last_failure")
                        .apply()
                } else {
                    diagnostics().edit()
                        .putLong("screenshot_last_failure_at", System.currentTimeMillis())
                        .putString("screenshot_last_failure", "media_projection_capture_failed")
                        .apply()
                }
                callback(base64)
            }
            return
        }

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            hardwareBuffer.close()
                            if (bitmap != null) {
                                val soft = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                bitmap.recycle()
                                val stream = ByteArrayOutputStream()
                                soft.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                                soft.recycle()
                                diagnostics().edit()
                                    .putLong("screenshot_last_success_at", System.currentTimeMillis())
                                    .remove("screenshot_last_failure")
                                    .apply()
                                callback(Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))
                            } else {
                                diagnostics().edit()
                                    .putLong("screenshot_last_failure_at", System.currentTimeMillis())
                                    .putString("screenshot_last_failure", "bitmap_wrap_returned_null")
                                    .apply()
                                callback(null)
                            }
                        } catch (error: Exception) {
                            diagnostics().edit()
                                .putLong("screenshot_last_failure_at", System.currentTimeMillis())
                                .putString("screenshot_last_failure", error.javaClass.simpleName)
                                .apply()
                            callback(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        diagnostics().edit()
                            .putLong("screenshot_last_failure_at", System.currentTimeMillis())
                            .putString("screenshot_last_failure", "android_error_$errorCode")
                            .apply()
                        callback(null)
                    }
                }
            )
        } catch (error: Exception) {
            diagnostics().edit()
                .putLong("screenshot_last_failure_at", System.currentTimeMillis())
                .putString("screenshot_last_failure", error.javaClass.simpleName)
                .apply()
            callback(null)
        }
    }
}
