package com.lover.connect

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Vivo-compatible accessibility service.
 *
 * This service deliberately keeps only passive foreground-app observation and
 * the screenshot API used by Little L. It performs no app locking, overlay,
 * HOME action, or RikkaHub redirection — those active interventions are
 * paused in Vivo compat mode because they destabilised the accessibility
 * switch on Vivo devices. Keeping foreground observation means Little L can
 * still attach the current app and session duration to reports. The MCP tools
 * lock_app / focus_rikka / redirect_to_rikka report this honestly instead of
 * pretending success.
 */
class LCAccessibilityService : AccessibilityService() {
    companion object { var instance: LCAccessibilityService? = null }

    private fun diagnostics() =
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        diagnostics().edit()
            .putBoolean("accessibility_connected", true)
            .putLong("accessibility_connected_at", System.currentTimeMillis())
            .remove("accessibility_last_callback_error")
            .apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Passive metadata only. This preserves precise Little L reports while
        // excluding every active app-lock/overlay/navigation behavior.
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
        } catch (error: Exception) {
            // A diagnostic accessibility service must never be disabled by an
            // uncaught vendor-specific event callback failure.
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
        instance = null
        diagnostics().edit()
            .putBoolean("accessibility_connected", false)
            .putLong("accessibility_destroyed_at", System.currentTimeMillis())
            .apply()
        super.onDestroy()
    }

    fun dismissLockOverlay() {
        // Compatibility no-op: the Vivo compat variant never creates overlays.
    }

    fun takeScreenshotNow(callback: (String?) -> Unit) {
        diagnostics().edit()
            .putLong("screenshot_last_requested_at", System.currentTimeMillis())
            .apply()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            diagnostics().edit()
                .putLong("screenshot_last_failure_at", System.currentTimeMillis())
                .putString("screenshot_last_failure", "android_version_below_r")
                .apply()
            callback(null)
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Executors.newSingleThreadExecutor(),
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
    }
}
