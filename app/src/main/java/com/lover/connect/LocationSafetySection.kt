package com.lover.connect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private data class CapturedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float
)

@Composable
fun LocationSafetySection() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(LocationSafetyManager.status(context)) }
    val initialConfig = remember {
        runCatching { SecureLocationConfigStore(context).load() }.getOrDefault(LocationSafetyConfig())
    }
    var radiusText by remember {
        mutableStateOf((initialConfig.zones.firstOrNull()?.radiusMeters ?: 500).toString())
    }
    var reminderKmText by remember {
        mutableStateOf((initialConfig.secondReminderMeters / 1_000).toString())
    }
    var message by remember { mutableStateOf("") }
    var locatingZone by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    fun refresh() {
        status = LocationSafetyManager.status(context)
    }

    LaunchedEffect(Unit) {
        // An APK update or process restart can leave the persisted switch on
        // while Android no longer has the location foreground service alive.
        // Reconcile that split state as soon as the app UI is launched so the
        // callback watchdog actually gets a chance to run.
        LocationSafetyManager.restoreAfterBoot(context)
        delay(500L)
        refresh()
        while (true) {
            delay(2_000L)
            refresh()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refresh()
        message = if (LocationSafetyManager.hasPreciseLocation(context)) {
            "已取得精确定位权限。请继续设置家或工作地点。"
        } else {
            "未取得精确定位；安全围栏不会启动。"
        }
    }

    fun captureZone(id: String, label: String) {
        val radius = radiusText.toIntOrNull()
        if (radius == null || radius !in 200..2_000) {
            message = "围栏半径需为 200–2000 米。"
            return
        }
        if (!LocationSafetyManager.hasPreciseLocation(context)) {
            message = "请先授予精确定位权限。"
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            )
            return
        }
        locatingZone = id
        message = "正在取得当前位置；坐标只会加密保存在本机……"
        captureCurrentPreciseLocation(context) { result ->
            locatingZone = null
            result.onSuccess { fix ->
                if (fix.accuracyMeters > 100f) {
                    message = "当前定位精度不足 100 米，请到开阔处重试。"
                    return@onSuccess
                }
                runCatching {
                    SecureLocationConfigStore(context).upsertZone(
                        SafetyZone(id, label, fix.latitude, fix.longitude, radius)
                    )
                }.onSuccess {
                    message = "$label 已设置；原始坐标已用 Android Keystore 加密。"
                    refresh()
                }.onFailure {
                    message = "保存失败：请先清除无法解密的旧配置，再重新设置。"
                    refresh()
                }
            }.onFailure {
                message = "暂时取不到可靠位置，请确认系统定位已开启后重试。"
            }
        }
    }

    Text("安全位置播报", fontSize = 18.sp)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "家/工作地点坐标只加密保存在手机；对外事件不含经纬度。GPS 失联或精度差不会被当成离开。",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "状态：${locationStatusLabel(status)} · 待发送 ${status.pendingEvents} 条",
                fontSize = 13.sp
            )
            Text(
                "家：${if ("home" in status.configuredZoneIds) "已设置" else "未设置"}　" +
                    "工作：${if ("work" in status.configuredZoneIds) "已设置" else "未设置"}",
                fontSize = 13.sp
            )
        }
    }

    OutlinedButton(
        onClick = {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (status.preciseLocationGranted) "精确定位已允许" else "第 1 步：允许精确定位")
    }

    OutlinedButton(
        onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
            message = "请在“权限→位置信息”中选择“始终允许”，用于重启后自动恢复。"
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (status.backgroundLocationGranted) "始终定位已允许" else "第 2 步：设置“始终允许”")
    }

    OutlinedTextField(
        value = radiusText,
        onValueChange = { radiusText = it.filter(Char::isDigit).take(4) },
        label = { Text("围栏半径（米，默认 500）") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { captureZone("home", "家") },
            enabled = locatingZone == null,
            modifier = Modifier.weight(1f)
        ) { Text(if (locatingZone == "home") "定位中…" else "当前位置设为家") }
        Button(
            onClick = { captureZone("work", "工作") },
            enabled = locatingZone == null,
            modifier = Modifier.weight(1f)
        ) { Text(if (locatingZone == "work") "定位中…" else "设为工作") }
    }

    OutlinedTextField(
        value = reminderKmText,
        onValueChange = { reminderKmText = it.filter(Char::isDigit).take(2) },
        label = { Text("第二次提醒距离（公里，2–10）") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    OutlinedButton(
        onClick = {
            val km = reminderKmText.toIntOrNull()
            if (km == null || km !in 2..10) {
                message = "第二次提醒距离需为 2–10 公里。"
            } else {
                runCatching { SecureLocationConfigStore(context).saveSecondReminderMeters(km * 1_000) }
                    .onSuccess { config ->
                        val home = config.zones.firstOrNull { it.id == "home" }
                        val work = config.zones.firstOrNull { it.id == "work" }
                        val homeToWork = if (home != null && work != null) {
                            GeoMath.distanceMeters(
                                home.latitude,
                                home.longitude,
                                work.latitude,
                                work.longitude
                            )
                        } else null
                        message = if (homeToWork != null && km * 1_000 < homeToWork) {
                            "已保存；提醒距离短于家到工作地点的距离，可能在到达前先提醒一次。"
                        } else "第二次提醒距离已保存。"
                    }
                    .onFailure { message = "配置不可读，请清除旧配置后重新设置。" }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("保存提醒距离") }

    Button(
        onClick = {
            message = when (LocationSafetyManager.handleReportButton(context)) {
                ReportButtonResult.CURRENT_TRIP_QUEUED ->
                    "已为当前外出排队发送报备；送达后会取消后续提醒。"
                ReportButtonResult.CURRENT_TRIP_ALREADY_QUEUED ->
                    "当前外出的报备已经在发送队列中。"
                ReportButtonResult.NEXT_DEPARTURE_ARMED ->
                    "已记录“我已告知联系人”；6 小时内下一次离开时使用一次后自动清除。"
                ReportButtonResult.NEXT_DEPARTURE_CANCELLED ->
                    "已取消下一次离开的免提醒。"
            }
            refresh()
        },
        enabled = !status.currentTripAcknowledged,
        modifier = Modifier.fillMaxWidth()
    ) {
        val activeTrip = status.state == GeofenceState.AWAY ||
            status.state == GeofenceState.RETURN_PENDING
        Text(
            when {
                activeTrip && status.currentTripAcknowledged -> "本次已告知联系人"
                activeTrip -> "我已告知联系人（当前外出）"
                status.reportedOnceArmed -> "取消“下一次免提醒”"
                else -> "我已告知联系人 / 下一次免提醒"
            }
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                val result = if (status.paused) {
                    LocationSafetyManager.resume(context)
                } else LocationSafetyManager.start(context)
                result.onSuccess { message = "安全播报已开启；通知栏会持续显示。" }
                    .onFailure { message = it.message ?: "无法开启安全播报。" }
                refresh()
            },
            enabled = !status.trackingEnabled || status.paused,
            modifier = Modifier.weight(1f)
        ) { Text(if (status.paused) "继续" else "开启") }
        OutlinedButton(
            onClick = {
                LocationSafetyManager.pause(context)
                message = "安全播报已暂停；不会把定位缺失当成离开。"
                refresh()
            },
            enabled = status.trackingEnabled && !status.paused,
            modifier = Modifier.weight(1f)
        ) { Text("暂停") }
        OutlinedButton(
            onClick = {
                LocationSafetyManager.stop(context)
                message = "安全播报已关闭。"
                refresh()
            },
            enabled = status.trackingEnabled,
            modifier = Modifier.weight(1f)
        ) { Text("关闭") }
    }

    OutlinedButton(
        onClick = {
            try {
                context.startActivity(Intent().apply {
                    setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                })
            } catch (_: Exception) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            }
            message = "小米手机还需开启自启动，并把电池策略设为“不限制”。"
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("小米后台保活设置") }

    TextButton(onClick = { showClearDialog = true }, modifier = Modifier.fillMaxWidth()) {
        Text("清除全部安全位置数据")
    }

    if (message.isNotBlank()) {
        Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除安全位置数据？") },
            text = { Text("将关闭跟踪并删除本机加密的家/工作地点坐标、状态与待发送事件。此操作不会影响其他 LoverConnect 功能。") },
            confirmButton = {
                TextButton(onClick = {
                    LocationSafetyManager.clearAll(context)
                    showClearDialog = false
                    message = "安全位置数据已清除。"
                    refresh()
                }) { Text("确认清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }
}

private fun locationStatusLabel(status: LocationSafetyStatus): String = when {
    !status.configReadable -> "加密配置不可读"
    !status.trackingEnabled -> "已关闭"
    status.paused -> "已暂停"
    status.state == GeofenceState.INSIDE -> "安全区域内"
    status.state == GeofenceState.AWAY -> "安全区域外"
    else -> "正在确认"
}

private fun captureCurrentPreciseLocation(
    context: Context,
    callback: (Result<CapturedLocation>) -> Unit
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED) {
        callback(Result.failure(SecurityException("Precise location permission is required")))
        return
    }
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    if (providers.isEmpty()) {
        callback(Result.failure(IllegalStateException("Location services are disabled")))
        return
    }

    val bestCached = providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.filter { location ->
        val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        ageNanos in 0L..120_000_000_000L && location.hasAccuracy()
    }.minByOrNull { it.accuracy }
    if (bestCached != null && bestCached.accuracy <= 100f) {
        callback(Result.success(bestCached.toCaptured()))
        return
    }

    val provider = providers.first()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        manager.getCurrentLocation(
            provider,
            CancellationSignal(),
            ContextCompat.getMainExecutor(context)
        ) { location ->
            if (location == null) callback(Result.failure(IllegalStateException("No location fix")))
            else callback(Result.success(location.toCaptured()))
        }
    } else {
        @Suppress("DEPRECATION")
        manager.requestSingleUpdate(
            provider,
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    callback(Result.success(location.toCaptured()))
                }
                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            },
            null
        )
    }
}

private fun Location.toCaptured() = CapturedLocation(latitude, longitude, accuracy)
