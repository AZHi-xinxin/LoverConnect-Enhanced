package com.lover.connect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

object McpServiceController {
    private const val CONTROL_PREFS = "lc_service_control"
    private const val KEY_ENABLED = "mcp_enabled"
    private const val DIAGNOSTICS_PREFS = "lc_diagnostics"
    const val EXTRA_START_TRIGGER = "lc_mcp_start_trigger"

    private fun controlPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(CONTROL_PREFS, Context.MODE_PRIVATE)

    fun hasStoredPreference(context: Context): Boolean =
        controlPrefs(context).contains(KEY_ENABLED)

    fun isEnabled(context: Context): Boolean =
        controlPrefs(context)
            .getBoolean(KEY_ENABLED, McpServiceLifecyclePolicy.DEFAULT_ENABLED)

    fun initializeForInteractiveLaunch(context: Context) {
        if (!hasStoredPreference(context)) setEnabled(context, false)
    }

    fun enableAndStart(context: Context, trigger: String = "user_start"): Boolean {
        setEnabled(context, true)
        cancelLegacyRestartAlarms(context)
        return startIfEnabled(context, trigger)
    }

    fun disableAndStop(context: Context): Boolean {
        val app = context.applicationContext
        setEnabled(app, false)
        cancelLegacyRestartAlarms(app)
        recordStartDecision(app, "user_stop", "disabled")
        return app.stopService(Intent(app, McpService::class.java))
    }

    fun restoreForBroadcast(context: Context, action: String?): Boolean {
        if (!McpServiceLifecyclePolicy.handlesRestoreBroadcast(action)) return false
        if (
            McpServiceLifecyclePolicy.shouldMigrateLegacyEnabled(
                action = action,
                hasStoredPreference = hasStoredPreference(context),
                hasLegacyUseEvidence = hasLegacyUseEvidence(context),
            )
        ) {
            setEnabled(context, true)
            context.getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("mcp_legacy_migrated_at", System.currentTimeMillis())
                .putString("mcp_legacy_migrated_trigger", action)
                .apply()
        }
        cancelLegacyRestartAlarms(context)
        return startIfEnabled(context, action ?: "restore_broadcast")
    }

    fun startIfEnabled(context: Context, trigger: String): Boolean {
        val app = context.applicationContext
        val enabled = isEnabled(app)
        val alreadyRunning = McpService.instance != null
        if (!McpServiceLifecyclePolicy.shouldRequestStart(enabled)) {
            recordStartDecision(app, trigger, "disabled")
            return false
        }

        return try {
            val intent = Intent(app, McpService::class.java)
                .putExtra(EXTRA_START_TRIGGER, trigger)
            ContextCompat.startForegroundService(app, intent)
            recordStartDecision(
                app,
                trigger,
                if (alreadyRunning) "requested_existing" else "requested",
            )
            true
        } catch (error: RuntimeException) {
            recordStartDecision(app, trigger, "failed:${error.javaClass.simpleName}")
            false
        }
    }

    private fun setEnabled(context: Context, enabled: Boolean) {
        check(
            controlPrefs(context)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .commit(),
        ) { "mcp_service_preference_not_persisted" }
    }

    /** Cancel restart alarms created by older builds before the run preference existed. */
    private fun cancelLegacyRestartAlarms(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (requestCode in 0..1) {
            val intent = Intent(app, McpService::class.java)
            val identityFlagSets = intArrayOf(
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_ONE_SHOT,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            for (pendingIntentFlags in identityFlagSets) {
                PendingIntent.getService(app, requestCode, intent, pendingIntentFlags)?.let {
                    alarmManager.cancel(it)
                    it.cancel()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(
                        app,
                        requestCode,
                        intent,
                        pendingIntentFlags,
                    )?.let {
                        alarmManager.cancel(it)
                        it.cancel()
                    }
                }
            }
        }
    }

    private fun hasLegacyUseEvidence(context: Context): Boolean {
        val app = context.applicationContext
        if (McpLocalSecurity.hasStoredEndpointToken(app)) return true
        if (app.getSharedPreferences("lc_config", Context.MODE_PRIVATE).all.isNotEmpty()) return true
        return java.io.File(app.filesDir, "lc_memory.json").exists()
    }

    private fun recordStartDecision(context: Context, trigger: String, result: String) {
        context.getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong("mcp_restore_last_attempt_at", System.currentTimeMillis())
            .putString("mcp_restore_last_trigger", trigger)
            .putString("mcp_restore_last_result", result)
            .apply()
    }
}
