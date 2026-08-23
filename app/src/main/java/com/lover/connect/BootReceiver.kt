package com.lover.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build



class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, McpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            LocationSafetyManager.restoreAfterBoot(context)
        }
    }
}
