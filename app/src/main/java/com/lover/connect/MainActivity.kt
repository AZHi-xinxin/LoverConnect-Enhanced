package com.lover.connect

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lover.connect.ui.theme.LoverConnectTheme
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val activityRecognitionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestActivityRecognitionIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestActivityRecognitionIfNeeded()
            }
        } else {
            requestActivityRecognitionIfNeeded()
        }

        setContent {
            LoverConnectTheme {
                MainScreen()
            }
        }
    }

    private fun requestActivityRecognitionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            activityRecognitionPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MainScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
    val scrollState = rememberScrollState()

    var serviceRunning by remember { mutableStateOf(false) }
    var city by remember { mutableStateOf(prefs.getString("city", "") ?: "") }
    var newAnnName by remember { mutableStateOf("") }
    var newAnnDate by remember { mutableStateOf("") }
    var newAnnType by remember { mutableStateOf("countup") }
    var showDatePicker by remember { mutableStateOf(false) }
    var anniversaries by remember { mutableStateOf(loadAnniversaries(prefs)) }
// 小L配置
    var aiName by remember { mutableStateOf(prefs.getString("ai_name", "") ?: "") }
    var userName by remember { mutableStateOf(prefs.getString("user_name", "") ?: "") }
    var relationship by remember { mutableStateOf(prefs.getString("relationship", "") ?: "") }
    var eyesPersonality by remember { mutableStateOf(prefs.getString("eyes_personality", "") ?: "") }
    var eyesInterval by remember { mutableStateOf(prefs.getInt("eyes_interval", 30).toString()) }
    var eyesEnabled by remember { mutableStateOf(prefs.getBoolean("eyes_enabled", false)) }

    // 视觉API配置
    var visionApiUrl by remember { mutableStateOf(prefs.getString("vision_api_url", "") ?: "") }
    var visionApiKey by remember { mutableStateOf(prefs.getString("vision_api_key", "") ?: "") }
    var visionModel by remember { mutableStateOf(prefs.getString("vision_model", "") ?: "") }
    var visionConfigMessage by remember { mutableStateOf("") }

    // 截屏授权

    var memoryMessage by remember { mutableStateOf("") }
    val localMcpEndpoint = remember { McpLocalSecurity.endpoint(context) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val file = java.io.File(context.filesDir, "lc_memory.json")
                if (file.exists()) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(file.readBytes())
                    }
                    memoryMessage = "导出成功！"
                } else {
                    memoryMessage = "记忆库为空，无需导出"
                }
            } catch (e: Exception) {
                memoryMessage = "导出失败：${e.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                JSONObject(content)
                val file = java.io.File(context.filesDir, "lc_memory.json")
                file.writeText(content)
                memoryMessage = "导入成功！"
            } catch (e: Exception) {
                memoryMessage = "导入失败：文件格式不对"
            }
        }
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "LoverConnect",
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "装上就能用的MCP懒人神器",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()
// ===== MCP服务 =====
        Text("MCP服务", fontSize = 18.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                val intent = Intent(context, McpService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                serviceRunning = true
            }) {
                Text("启动服务")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = {
                context.stopService(Intent(context, McpService::class.java))
                serviceRunning = false
            }) {
                Text("停止服务")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("MCP地址（填入RikkaHub）：", fontSize = 12.sp)
                Text(
                    text = localMcpEndpoint,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LoverConnect MCP", localMcpEndpoint))
                }) {
                    Text("复制本机私密 MCP 地址")
                }
                Text(
                    text = "覆盖安装后请把 RikkaHub 的旧 /mcp 地址替换为上方私密地址。它只接受本机连接，请勿把完整地址发给其他人。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        // ===== 权限管理 =====
        Text("权限管理", fontSize = 18.sp)
        Text("点击按钮直接跳转到对应设置页面", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }, modifier = Modifier.fillMaxWidth()) {
            Text("使用情况访问（屏幕时间必需）")
        }

        OutlinedButton(onClick = {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } else {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("电池优化白名单（保活必需）")
        }

        OutlinedButton(onClick = {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            context.startActivity(intent)
        }, modifier = Modifier.fillMaxWidth()) {
            Text("通知权限")
        }

        OutlinedButton(onClick = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }, modifier = Modifier.fillMaxWidth()) {
            Text("体力活动权限（步数）")
        }

        OutlinedButton(onClick = {
            val componentName = ComponentName(context, LockScreenReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "允许LoverConnect锁定屏幕")
            }
            context.startActivity(intent)
        }, modifier = Modifier.fillMaxWidth()) {
            Text("激活设备管理员（锁屏功能必需）")
        }

        OutlinedButton(onClick = {
            try {
                val intent = Intent().apply {
                    setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent().apply {
                        setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("自启动管理（OPPO/小米等国产系统）")
        }
        OutlinedButton(onClick = {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            context.startActivity(intent)
        }, modifier = Modifier.fillMaxWidth()) {
            Text("通知使用权（音乐感知必需）")
        }

        HorizontalDivider()

        DeviceContextSection()

        HorizontalDivider()

        LocationSafetySection()

        HorizontalDivider()

// ===== 小L配置 =====
        Text("小L（屏幕观察）", fontSize = 18.sp)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用小L", fontSize = 14.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = eyesEnabled,
                onCheckedChange = {
                    eyesEnabled = it
                    prefs.edit().putBoolean("eyes_enabled", it).apply()
                }
            )
        }

        OutlinedTextField(
            value = aiName,
            onValueChange = { aiName = it },
            label = { Text("AI名字（如：L、小黑）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = userName,
            onValueChange = { userName = it },
            label = { Text("你的昵称（如：宝宝）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = relationship,
            onValueChange = { relationship = it },
            label = { Text("你们的关系（如：老公、男朋友、闺蜜）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = eyesPersonality,
            onValueChange = { eyesPersonality = it },
            label = { Text("小L人格描述（自由填写）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 5
        )

        OutlinedTextField(
            value = eyesInterval,
            onValueChange = { eyesInterval = it },
            label = { Text("截屏间隔（分钟）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(onClick = {
            prefs.edit()
                .putString("ai_name", aiName.trim())
                .putString("user_name", userName.trim())
                .putString("relationship", relationship.trim())
                .putString("eyes_personality", eyesPersonality.trim())
                .putInt("eyes_interval", eyesInterval.toIntOrNull() ?: 30)
                .apply()
        }) {
            Text("保存小L配置")
        }

// 截屏授权
        OutlinedButton(onClick = {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        }, modifier = Modifier.fillMaxWidth()) {
            Text("开启无障碍服务（小L截屏必需）")
        }


        HorizontalDivider()

// ===== 视觉API配置 =====
        Text("视觉API配置", fontSize = 18.sp)
        Text("小L需要视觉模型来分析截图", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = visionApiUrl,
            onValueChange = { visionApiUrl = it },
            label = { Text("API地址（如：https://api.xxx.com/v1/chat/completions）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = visionApiKey,
            onValueChange = { visionApiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = visionModel,
            onValueChange = { visionModel = it },
            label = { Text("模型名（如：gemini-2.5-flash）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(onClick = {
            val normalizedUrl = visionApiUrl.trim()
            if (!VisionApiEndpointPolicy.isAllowed(normalizedUrl)) {
                visionConfigMessage = "API 地址必须使用 HTTPS；只有本机 127.0.0.1/localhost 可使用 HTTP。"
            } else {
                prefs.edit()
                    .putString("vision_api_url", normalizedUrl)
                    .putString("vision_api_key", visionApiKey.trim())
                    .putString("vision_model", visionModel.trim())
                    .apply()
                visionConfigMessage = "API 配置已安全保存"
            }
        }) {
            Text("保存API配置")
        }
        if (visionConfigMessage.isNotEmpty()) {
            Text(
                visionConfigMessage,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        // ===== 城市设置 =====
        Text("天气城市", fontSize = 18.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = city,
                onValueChange = { city = it },
                label = { Text("城市拼音（如 Beijing）") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                prefs.edit().putString("city", city.trim()).apply()
            }) {
                Text("保存")
            }
        }
        Text(
            text = "使用wttr.in查询，填城市拼音或英文名",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        // ===== 纪念日管理 =====
        Text("纪念日", fontSize = 18.sp)

        if (anniversaries.isEmpty()) {
            Text("暂无纪念日", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val sorted = anniversaries.sortedByDescending { it.pinned }
            LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                itemsIndexed(sorted) { _, ann ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (ann.pinned) {
                                        Text("📌", fontSize = 14.sp)
                                    }
                                    Text(ann.name, fontSize = 14.sp)
                                }
                                Text(
                                    "${ann.date} · ${if (ann.type == "countup") "第 ${calcAnniversaryDays(ann.date, ann.type)} 天" else "还剩 ${calcAnniversaryDays(ann.date, ann.type)} 天"}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                val pinnedCount = anniversaries.count { it.pinned }
                                val idx = anniversaries.indexOfFirst { it.name == ann.name && it.date == ann.date }
                                if (idx >= 0) {
                                    val target = anniversaries[idx]
                                    val updated = anniversaries.toMutableList()
                                    if (target.pinned) {
                                        updated[idx] = target.copy(pinned = false)
                                        anniversaries = updated
                                        saveAnniversaries(prefs, anniversaries)
                                    } else if (pinnedCount < 3) {
                                        updated[idx] = target.copy(pinned = true)
                                        anniversaries = updated
                                        saveAnniversaries(prefs, anniversaries)
                                    } else {
                                        android.widget.Toast.makeText(context, "置顶数已满（最多3个），请先取消一个", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }) {
                                Text(if (ann.pinned) "取消置顶" else "置顶")
                            }
                            TextButton(onClick = {
                                anniversaries = anniversaries.toMutableList().apply {
                                    removeAll { it.name == ann.name && it.date == ann.date }
                                }
                                saveAnniversaries(prefs, anniversaries)
                            }) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }

        Text("添加纪念日：", fontSize = 14.sp)
        OutlinedTextField(
            value = newAnnName,
            onValueChange = { newAnnName = it },
            label = { Text("名称（如：在一起）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newAnnDate,
                onValueChange = { newAnnDate = it },
                label = { Text("日期（2026-7-10 或 2026-07-10 均可）") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedButton(onClick = { showDatePicker = true }) {
                Text("选日期")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = newAnnType == "countup",
                onClick = { newAnnType = "countup" }
            )
            Text("正计时（第X天）", fontSize = 14.sp)
            Spacer(modifier = Modifier.width(16.dp))
            RadioButton(
                selected = newAnnType == "countdown",
                onClick = { newAnnType = "countdown" }
            )
            Text("倒计时（还有X天）", fontSize = 14.sp)
        }
        Button(onClick = {
            val normalized = normalizeDate(newAnnDate)
            if (newAnnName.isNotBlank() && normalized != null) {
                anniversaries = anniversaries + AnniversaryItem(newAnnName.trim(), normalized, newAnnType)
                saveAnniversaries(prefs, anniversaries)
                newAnnName = ""
                newAnnDate = ""
            } else if (newAnnName.isNotBlank() && normalized == null) {
                android.widget.Toast.makeText(context, "日期格式不对，请填如 2026-7-10", android.widget.Toast.LENGTH_SHORT).show()
            }
        }) {
            Text("添加")
        }
        if (showDatePicker) {
            val datePickerState = rememberDatePickerState()
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            newAnnDate = java.time.Instant.ofEpochMilli(millis)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate()
                                .toString()
                        }
                        showDatePicker = false
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("取消") }
                }
            ) {
                DatePicker(state = datePickerState, showModeToggle = false)
            }
        }
        HorizontalDivider()

// ===== 记忆库管理 =====
        Text("记忆库", fontSize = 18.sp)
        Text("导出后可备份到其他位置，换机时导入恢复", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = {
                exportLauncher.launch("lc_memory.json")
            }) {
                Text("导出记忆库")
            }
            OutlinedButton(onClick = {
                importLauncher.launch(arrayOf("application/json"))
            }) {
                Text("导入记忆库")
            }
        }

        if (memoryMessage.isNotEmpty()) {
            Text(memoryMessage, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
// ===== 数据类和工具函数 =====

data class AnniversaryItem(val name: String, val date: String, val type: String, val pinned: Boolean = false)

fun loadAnniversaries(prefs: android.content.SharedPreferences): List<AnniversaryItem> {
    val json = prefs.getString("anniversaries", null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            AnniversaryItem(
                obj.getString("name"),
                obj.getString("date"),
                obj.optString("type", "countdown"),
                obj.optBoolean("pinned", false)
            )
        }
    } catch (_: Exception) { emptyList() }
}

fun saveAnniversaries(prefs: android.content.SharedPreferences, list: List<AnniversaryItem>) {
    val arr = JSONArray()
    list.forEach { item ->
        arr.put(JSONObject().apply {
            put("name", item.name)
            put("date", item.date)
            put("type", item.type)
            put("pinned", item.pinned)
        })
    }
    prefs.edit().putString("anniversaries", arr.toString()).apply()
}

fun normalizeDate(input: String): String? {
    return try {
        val parts = input.trim().split("-")
        if (parts.size != 3) return null
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        val day = parts[2].toInt()
        java.time.LocalDate.of(year, month, day).toString()
    } catch (_: Exception) { null }
}

fun calcAnniversaryDays(date: String, type: String): Long {
    return try {
        val target = java.time.LocalDate.parse(date)
        val today = java.time.LocalDate.now()
        if (type == "countup") {
            java.time.temporal.ChronoUnit.DAYS.between(target, today) + 1
        } else {
            java.time.temporal.ChronoUnit.DAYS.between(today, target)
        }
    } catch (_: Exception) { 0L }
}

