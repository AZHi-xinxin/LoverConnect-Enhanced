package com.lover.connect

import android.content.ComponentName
import android.content.Context
import android.app.Notification
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MusicListenerService : NotificationListenerService() {
    private val contextExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32),
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        if (sbn.packageName == "me.rerere.rikkahub") return
        if (!DeviceContextSettings.notificationSummariesEnabled(this)) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val notificationKey = sbn.key
        val appPackage = sbn.packageName
        val category = notification.category
        val flags = notification.flags
        val postedAt = sbn.postTime
        // Do not even copy notification text into the work queue unless the
        // separate text consent is on at capture time. The store re-checks it
        // under lock before retaining anything.
        val captureText = DeviceContextSettings.notificationTextEnabled(this)
        val rawTitle = if (captureText) {
            notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        } else null
        val rawText = if (captureText) {
            (
                notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: notification.extras?.getCharSequence(Notification.EXTRA_TEXT)
                )?.toString()
        } else null
        runCatching { contextExecutor.execute {
            if (!DeviceContextSettings.notificationSummariesEnabled(this)) return@execute
            val appLabel = runCatching {
                val info = packageManager.getApplicationInfo(appPackage, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(appPackage)
            DeviceContextStore(this).recordNotification(
                notificationKey = notificationKey,
                appPackage = appPackage,
                appLabel = appLabel,
                category = category,
                title = rawTitle.orEmpty(),
                text = rawText.orEmpty(),
                ongoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
                postedAtMs = postedAt,
                observedAtMs = System.currentTimeMillis(),
            )
        } }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { DeviceContextStore(this).removeNotification(it) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("notification_listener_connected", true)
            .putLong("notification_listener_connected_at", System.currentTimeMillis())
            .apply()
    }

    override fun onListenerDisconnected() {
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("notification_listener_connected", false)
            .putLong("notification_listener_disconnected_at", System.currentTimeMillis())
            .apply()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        contextExecutor.shutdownNow()
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("notification_listener_connected", false)
            .apply()
        super.onDestroy()
    }

    companion object {
        fun getNowPlaying(context: Context): String {
            return try {
                val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val componentName = ComponentName(context, MusicListenerService::class.java)
                val controllers = msm.getActiveSessions(componentName)

                if (controllers.isEmpty()) return "未在播放音乐"

                val controller = controllers[0]
                val metadata = controller.metadata ?: return "未在播放音乐"
                val state = controller.playbackState

                val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "未知"
                val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "未知"
                val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""

                val playing = if (state?.state == android.media.session.PlaybackState.STATE_PLAYING) "播放中" else "已暂停"

                val info = StringBuilder()
                info.append("$title - $artist")
                if (album.isNotEmpty()) info.append("（$album）")
                info.append(" [$playing]")

                // 尝试获取app名称
                val pkg = controller.packageName
                val appName = try {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) { pkg }

                info.append(" - $appName")
                info.toString()
            } catch (e: Exception) {
                "获取播放信息失败：${e.message}"
            }
        }
    }
}
