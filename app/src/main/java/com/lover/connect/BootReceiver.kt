package com.lover.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (!McpServiceLifecyclePolicy.handlesRestoreBroadcast(action)) return

        McpServiceController.restoreForBroadcast(context, action)
        LocationSafetyManager.restoreAfterBoot(context)
    }
}
