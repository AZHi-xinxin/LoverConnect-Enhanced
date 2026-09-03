package com.lover.connect

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DeviceContextSection() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(DeviceContextSettings.CONFIG_PREFS, Context.MODE_PRIVATE)
    var enabled by remember { mutableStateOf(prefs.getBoolean(DeviceContextSettings.KEY_ENABLED, false)) }
    var notificationSummaries by remember {
        mutableStateOf(prefs.getBoolean(DeviceContextSettings.KEY_NOTIFICATION_SUMMARIES, false))
    }
    var notificationText by remember {
        mutableStateOf(prefs.getBoolean(DeviceContextSettings.KEY_NOTIFICATION_TEXT, false))
    }
    var status by remember { mutableStateOf("") }

    Text("设备情境（实验性）", fontSize = 18.sp)
    Text(
        "默认关闭。开启后，只在本机短时记录手机姿态、移动、光线、屏幕等设备事实；不会把“手机平放”擅自解释成“你睡着了”。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingSwitchRow("启用设备情境", enabled) { value ->
        enabled = value
        prefs.edit().putBoolean(DeviceContextSettings.KEY_ENABLED, value).apply()
        if (!value) DeviceContextStore(context).clearEphemeral()
        if (value) McpServiceController.enableAndStart(context, "device_context_enabled")
        McpService.refreshDeviceContextCollection()
        status = when {
            !value -> "设备情境已停止采集"
            McpService.instance == null -> "设备情境已配置，服务启动后开始采集"
            else -> "设备情境已开启"
        }
    }
    SettingSwitchRow("保留通知摘要（30分钟）", notificationSummaries, enabled) { value ->
        notificationSummaries = value
        if (!value) notificationText = false
        prefs.edit()
            .putBoolean(DeviceContextSettings.KEY_NOTIFICATION_SUMMARIES, value)
            .putBoolean(DeviceContextSettings.KEY_NOTIFICATION_TEXT, if (value) notificationText else false)
            .apply()
        if (!value) DeviceContextStore(context).clearNotifications()
    }
    Text(
        "摘要默认关闭。开启后会保存应用名、通知类别和时间；内容仍不会保存。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingSwitchRow(
        "保存经基础遮盖的通知文字",
        notificationText,
        enabled && notificationSummaries,
    ) { value ->
        notificationText = value
        prefs.edit().putBoolean(DeviceContextSettings.KEY_NOTIFICATION_TEXT, value).apply()
        if (!value) DeviceContextStore(context).stripNotificationText()
    }
    Text(
        "若开启文字，链接、邮箱和六位以上长数字会先做基础遮盖，文本最长保留160字；姓名、地址、短验证码等仍可能出现，请只在你接受该隐私范围时开启。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) { Text("通知读取权限") }
        Button(
            onClick = {
                DeviceContextStore(context).clearEphemeral()
                status = "短时情境已清空，开关设置保留"
            },
            modifier = Modifier.weight(1f),
        ) { Text("清空短时情境") }
    }
    if (status.isNotEmpty()) {
        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
    Text(
        "传递方式：普通状态可在下一次对话时交给已接入的 AI 客户端；重要事件仍可用哨兵主动唤醒。静默上下文本身不会主动发起对话。",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
