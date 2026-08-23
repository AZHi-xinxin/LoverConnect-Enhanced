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

class LCAccessibilityService : AccessibilityService() {
    companion object { var instance: LCAccessibilityService? = null }

    private var lastHandledPackage: String? = null
    private var lastHandledAt = 0L
    private var lockOverlay: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Record the current foreground app and the start of its continuous
        // foreground session for structured sentinel events.
        val activityPrefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        if (activityPrefs.getString("current_foreground_package", "") != pkg) {
            activityPrefs.edit()
                .putString("current_foreground_package", pkg)
                .putLong("current_foreground_since", System.currentTimeMillis())
                .apply()
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

        // A visible lock overlay must survive launcher, notification and SystemUI
        // window events. It is dismissed only by an explicit unlock/action.
        if (lockOverlay != null) return
        if (AppLockManager.shouldFocusToRikka(this, pkg) ||
            AppLockManager.shouldRedirectToRikka(this, pkg)) {
            launchRikkaOrHome()
        }
    }

    private fun showLockOverlay(pkg: String) {
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
            text = if (until > 0L) "Unlocks at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))}" else "Locked until manually released"
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
            android.graphics.PixelFormat.TRANSLUCENT
        )
        windowManager.addView(root, params)
        lockOverlay = root
    }

    fun dismissLockOverlay() {
        removeLockOverlay()
    }

    private fun removeLockOverlay() {
        val view = lockOverlay ?: return
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view) }
        catch (_: Exception) { }
        lockOverlay = null
    }

    private fun launchRikkaOrHome() {
        val launch = packageManager.getLaunchIntentForPackage(AppLockManager.RIKKA_PACKAGE)
        if (launch == null) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { startActivity(launch) } catch (_: Exception) { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    private fun showBlockedNotification(pkg: String) {
        val channelId = "lc_app_lock"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "App lock", NotificationManager.IMPORTANCE_HIGH))
        }
        val pending = PendingIntent.getBroadcast(
            this, 0, Intent(this, UnlockAllReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(this, channelId)
        else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        manager.notify(4102, builder.setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("App is locked").setContentText(pkg).setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Emergency unlock all", pending).build())
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        removeLockOverlay()
        super.onDestroy()
        instance = null
    }

    fun takeScreenshotNow(callback: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { callback(null); return }
        takeScreenshot(Display.DEFAULT_DISPLAY, Executors.newSingleThreadExecutor(), object : TakeScreenshotCallback {
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
                        callback(Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))
                    } else callback(null)
                } catch (_: Exception) { callback(null) }
            }
            override fun onFailure(errorCode: Int) { callback(null) }
        })
    }
}
