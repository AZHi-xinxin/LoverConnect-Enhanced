package com.lover.connect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class AppUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pkg = intent?.getStringExtra("package_name") ?: return
        AppLockManager.unlock(context, pkg)
        LCAccessibilityService.instance?.dismissLockOverlay()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "lc_app_lock"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "App lock", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val label = try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { pkg }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, channelId)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        }
        manager.notify(pkg.hashCode(), builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("App unlocked").setContentText(label).setAutoCancel(true).build())
    }
}
