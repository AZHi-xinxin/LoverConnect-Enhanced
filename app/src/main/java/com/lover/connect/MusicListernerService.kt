package com.lover.connect

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MusicListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

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
