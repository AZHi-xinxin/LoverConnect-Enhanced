package com.lover.connect

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalTime

object AppLockManager {
    private const val PREFS = "lc_config"
    private const val KEY_LOCKED_APPS = "locked_apps"
    private const val KEY_FOCUS_ENABLED = "focus_rikka_enabled"
    private const val KEY_FOCUS_PACKAGES = "focus_rikka_packages"
    private const val KEY_REDIRECT_PACKAGES = "redirect_rikka_packages"
    private const val KEY_REDIRECT_WINDOW = "redirect_rikka_window"
    const val RIKKA_PACKAGE = "me.rerere.rikkahub"

    private val exactDenyList = setOf(
        "com.lover.connect", RIKKA_PACKAGE, "com.android.settings", "com.android.systemui",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.phone", "com.google.android.dialer", "com.android.vending",
        "com.google.android.gms", "com.google.android.apps.maps",
        "com.google.android.apps.healthdata", "com.google.android.apps.walletnfcrel",
        "com.tailscale.ipn"
    )

    private val deniedFragments = listOf(
        "launcher", "settings", "systemui", "permissioncontroller", "packageinstaller",
        "dialer", "phone", "telecom", "wallet", "payment", "alipay", "tenpay",
        "maps", "navigation", "health", "medical", "authenticator", "password",
        "vpn", "tailscale"
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun untilKey(pkg: String) = "lock_until_$pkg"
    private fun messageKey(pkg: String) = "lock_message_$pkg"
    private fun overlayKey(pkg: String) = "lock_overlay_$pkg"

    fun getLockedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LOCKED_APPS, emptySet())?.toSet() ?: emptySet()

    fun isPermanentlyDenied(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase()
        return normalized in exactDenyList || deniedFragments.any(normalized::contains)
    }

    fun lock(
        context: Context,
        packageName: String,
        durationMinutes: Int = 0,
        message: String? = null,
        showOverlay: Boolean = false
    ): Result<Set<String>> {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return Result.failure(IllegalArgumentException("Package name cannot be empty"))
        if (isPermanentlyDenied(pkg)) {
            return Result.failure(IllegalArgumentException("This app is permanently protected from locking"))
        }
        if (durationMinutes !in 0..10080) {
            return Result.failure(IllegalArgumentException("duration_minutes must be between 0 and 10080"))
        }
        val updated = getLockedApps(context).toMutableSet().apply { add(pkg) }
        val editor = prefs(context).edit().putStringSet(KEY_LOCKED_APPS, updated)
            .putBoolean(overlayKey(pkg), showOverlay)
        if (!message.isNullOrBlank()) editor.putString(messageKey(pkg), message.take(80))
        else editor.remove(messageKey(pkg))
        if (durationMinutes > 0) {
            val unlockAt = System.currentTimeMillis() + durationMinutes * 60_000L
            editor.putLong(untilKey(pkg), unlockAt)
            scheduleUnlock(context, pkg, unlockAt)
        } else editor.remove(untilKey(pkg))
        editor.apply()
        return Result.success(updated)
    }

    fun unlock(context: Context, packageName: String): Set<String> {
        val pkg = packageName.trim()
        val updated = getLockedApps(context).toMutableSet().apply { remove(pkg) }
        prefs(context).edit().putStringSet(KEY_LOCKED_APPS, updated)
            .remove(untilKey(pkg)).remove(messageKey(pkg)).remove(overlayKey(pkg)).apply()
        cancelUnlock(context, pkg)
        return updated
    }

    fun isLocked(context: Context, packageName: String): Boolean {
        if (packageName !in getLockedApps(context)) return false
        val unlockAt = prefs(context).getLong(untilKey(packageName), 0L)
        if (unlockAt > 0L && System.currentTimeMillis() >= unlockAt) {
            unlock(context, packageName)
            return false
        }
        return true
    }

    fun getUnlockAt(context: Context, packageName: String): Long =
        prefs(context).getLong(untilKey(packageName), 0L)

    fun getMessage(context: Context, packageName: String): String =
        prefs(context).getString(messageKey(packageName), null) ?: "This app is locked"

    fun shouldShowOverlay(context: Context, packageName: String): Boolean =
        prefs(context).getBoolean(overlayKey(packageName), false)

    fun clearAll(context: Context) {
        val packages = getLockedApps(context)
        val editor = prefs(context).edit().remove(KEY_LOCKED_APPS)
        packages.forEach {
            editor.remove(untilKey(it)).remove(messageKey(it)).remove(overlayKey(it))
            cancelUnlock(context, it)
        }
        editor.apply()
    }

    fun configureFocus(context: Context, enabled: Boolean, packages: Set<String>): Result<Unit> {
        val safe = packages.map(String::trim).filter(String::isNotEmpty).filterNot(::isPermanentlyDenied).toSet()
        if (enabled && safe.isEmpty()) {
            return Result.failure(IllegalArgumentException("At least one safe entertainment package is required"))
        }
        prefs(context).edit().putBoolean(KEY_FOCUS_ENABLED, enabled)
            .putStringSet(KEY_FOCUS_PACKAGES, safe).apply()
        return Result.success(Unit)
    }

    fun shouldFocusToRikka(context: Context, packageName: String): Boolean =
        prefs(context).getBoolean(KEY_FOCUS_ENABLED, false) &&
            packageName in (prefs(context).getStringSet(KEY_FOCUS_PACKAGES, emptySet()) ?: emptySet())

    fun configureRedirect(context: Context, packages: Set<String>, timeWindow: String?): Result<Unit> {
        val safe = packages.map(String::trim).filter(String::isNotEmpty).filterNot(::isPermanentlyDenied).toSet()
        if (!timeWindow.isNullOrBlank() && !isValidWindow(timeWindow)) {
            return Result.failure(IllegalArgumentException("time_window must use HH:mm-HH:mm"))
        }
        prefs(context).edit().putStringSet(KEY_REDIRECT_PACKAGES, safe)
            .putString(KEY_REDIRECT_WINDOW, timeWindow.orEmpty()).apply()
        return Result.success(Unit)
    }

    fun shouldRedirectToRikka(context: Context, packageName: String): Boolean {
        val packages = prefs(context).getStringSet(KEY_REDIRECT_PACKAGES, emptySet()) ?: emptySet()
        if (packageName !in packages) return false
        val window = prefs(context).getString(KEY_REDIRECT_WINDOW, "").orEmpty()
        return window.isNotBlank() && isNowInWindow(window)
    }

    private fun isValidWindow(value: String): Boolean = try {
        val parts = value.split("-", limit = 2)
        parts.size == 2 && LocalTime.parse(parts[0]) != null && LocalTime.parse(parts[1]) != null
    } catch (_: Exception) { false }

    private fun isNowInWindow(value: String): Boolean = try {
        val parts = value.split("-", limit = 2)
        val start = LocalTime.parse(parts[0])
        val end = LocalTime.parse(parts[1])
        val now = LocalTime.now()
        if (start <= end) now >= start && now <= end else now >= start || now <= end
    } catch (_: Exception) { false }

    private fun scheduleUnlock(context: Context, pkg: String, unlockAt: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = unlockPendingIntent(context, pkg)
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, unlockAt, pending)
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, unlockAt, pending)
        }
    }

    private fun cancelUnlock(context: Context, pkg: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(unlockPendingIntent(context, pkg))
    }

    private fun unlockPendingIntent(context: Context, pkg: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            pkg.hashCode(),
            Intent(context, AppUnlockReceiver::class.java).putExtra("package_name", pkg),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
