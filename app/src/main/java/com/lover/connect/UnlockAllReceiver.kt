package com.lover.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class UnlockAllReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AppLockManager.clearAll(context)
        LCAccessibilityService.instance?.dismissLockOverlay()
        Toast.makeText(context, "All app locks have been removed", Toast.LENGTH_LONG).show()
    }
}
