package com.lover.connect

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.provider.AlarmClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

class McpService : Service(), SensorEventListener {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val PORT = 5000
    private var wakeLock: PowerManager.WakeLock? = null

    private var stepState = DailyStepState()
    private var stepCount: Int = 0
    private var sensorManager: SensorManager? = null

    private var resetTimestamp: Long = 0L

    // 截屏相关
    private var eyesTimer: Timer? = null

    companion object {
        private const val CHANNEL_ID = "lc_service"
        private const val NOTIFICATION_ID = 1
        private const val STEP_PREFS = "lc_step_counter"
        private const val STEP_DATE = "date"
        private const val STEP_COUNT = "count"
        private const val STEP_LAST_SENSOR_TOTAL = "last_sensor_total"
        var instance: McpService? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startEyesTimer()
        return START_STICKY
    }



    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LoverConnect::MCP").apply { acquire() }

        restoreStepState()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        startServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        serverSocket?.close()
        wakeLock?.release()
        sensorManager?.unregisterListener(this)
        eyesTimer?.cancel()

        // 被杀后5秒自动重启
        val restartIntent = Intent(applicationContext, McpService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 0, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, pendingIntent)
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, McpService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val totalSteps = it.values[0].toInt()
                stepState = DailyStepCounter.update(stepState, currentStepDate(), totalSteps)
                stepCount = stepState.count
                persistStepState()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun currentStepDate(): String = LocalDate.now().toString()

    private fun restoreStepState() {
        val prefs = getSharedPreferences(STEP_PREFS, Context.MODE_PRIVATE)
        stepState = DailyStepState(
            date = prefs.getString(STEP_DATE, "") ?: "",
            count = prefs.getInt(STEP_COUNT, 0).coerceAtLeast(0),
            lastSensorTotal = prefs.getInt(STEP_LAST_SENSOR_TOTAL, -1),
        )
        if (stepState.date != currentStepDate()) {
            stepState = DailyStepState(date = currentStepDate())
            persistStepState()
        }
        stepCount = stepState.count
    }

    private fun persistStepState() {
        getSharedPreferences(STEP_PREFS, Context.MODE_PRIVATE).edit()
            .putString(STEP_DATE, stepState.date)
            .putInt(STEP_COUNT, stepState.count)
            .putInt(STEP_LAST_SENSOR_TOTAL, stepState.lastSensorTotal)
            .apply()
    }

    private fun refreshStepDateForRead() {
        val today = currentStepDate()
        if (stepState.date != today) {
            stepState = DailyStepState(date = today)
            stepCount = 0
            persistStepState()
        }
    }
// ==================== HTTP服务器 ====================

    private fun startServer() {
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(PORT)
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handleClient(client) }.start()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line = input.readLine()
            while (!line.isNullOrEmpty()) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) headers[parts[0].lowercase()] = parts[1]
                line = input.readLine()
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                input.read(buf, 0, contentLength)
                String(buf)
            } else ""

            val response = handleMcpRequest(body)

            val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: *\r\nContent-Length: ${response.toByteArray().size}\r\n\r\n$response"
            output.write(httpResponse.toByteArray())
            output.flush()
            socket.close()
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleMcpRequest(body: String): String {
        if (body.isEmpty()) {
            return JSONObject().apply {
                put("jsonrpc", "2.0")
                put("result", JSONObject().apply {
                    put("protocolVersion", "2025-03-26")
                    put("capabilities", JSONObject().apply {
                        put("tools", JSONObject().apply { put("listChanged", false) })
                    })
                    put("serverInfo", JSONObject().apply {
                        put("name", "LoverConnect")
                        put("version", "2.0.0")
                    })
                })
                put("id", 1)
            }.toString()
        }

        return try {
            val json = JSONObject(body)
            val method = json.optString("method", "")
            val id = json.opt("id")

            when (method) {
                "initialize" -> {
                    JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("result", JSONObject().apply {
                            put("protocolVersion", "2025-03-26")
                            put("capabilities", JSONObject().apply {
                                put("tools", JSONObject().apply { put("listChanged", false) })
                            })
                            put("serverInfo", JSONObject().apply {
                                put("name", "LoverConnect")
                                put("version", "2.0.0")
                            })
                        })
                        put("id", id)
                    }.toString()
                }
                "notifications/initialized" -> ""
                "tools/list" -> handleToolsList(id)
                "tools/call" -> handleToolsCall(json, id)
                else -> {
                    JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("error", JSONObject().apply {
                            put("code", -32601)
                            put("message", "Method not found: $method")
                        })
                        put("id", id)
                    }.toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("error", JSONObject().apply {
                    put("code", -32700)
                    put("message", "Parse error: ${e.message}")
                })
                put("id", JSONObject.NULL)
            }.toString()
        }
    }
    private fun handleToolsList(id: Any?): String {
        val tools = JSONArray().apply {
            put(JSONObject().apply {
                put("name", "get_battery")
                put("description", "获取电池状态")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_screen_time")
                put("description", "获取屏幕使用时间报告")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_app_timeline")
                put("description", "获取App使用时间线")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_anniversary")
                put("description", "获取纪念日信息")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_weather")
                put("description", "获取天气信息")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_steps")
                put("description", "获取今日步数")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "send_notification")
                put("description", "推送通知")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("message", JSONObject().apply { put("type", "string"); put("description", "消息内容") })
                    })
                    put("required", JSONArray().apply { put("message") })
                })
            })
            put(JSONObject().apply {
                put("name", "save_memory")
                put("description", "保存一条记忆到本地记忆库")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("key", JSONObject().apply { put("type", "string"); put("description", "记忆的键名") })
                        put("value", JSONObject().apply { put("type", "string"); put("description", "记忆的内容") })
                    })
                    put("required", JSONArray().apply { put("key"); put("value") })
                })
            })
            put(JSONObject().apply {
                put("name", "read_memory")
                put("description", "读取本地记忆库，不传key返回全部")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("key", JSONObject().apply { put("type", "string"); put("description", "要查询的键名，不传则返回全部") })
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "reset_screen_time")
                put("description", "重置屏幕使用时间计数")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "set_alarm")
                put("description", "设置闹钟")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("hour", JSONObject().apply { put("type", "integer"); put("description", "小时（0-23）") })
                        put("minute", JSONObject().apply { put("type", "integer"); put("description", "分钟（0-59）") })
                        put("message", JSONObject().apply { put("type", "string"); put("description", "闹钟备注（可选）") })
                    })
                    put("required", JSONArray().apply { put("hour"); put("minute") })
                })
            })
            put(JSONObject().apply {
                put("name", "cancel_alarm")
                put("description", "取消闹钟")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("hour", JSONObject().apply { put("type", "integer"); put("description", "小时（0-23）") })
                        put("minute", JSONObject().apply { put("type", "integer"); put("description", "分钟（0-59）") })
                    })
                    put("required", JSONArray().apply { put("hour"); put("minute") })
                })
            })
            put(JSONObject().apply {
                put("name", "lock_screen")
                put("description", "强制锁屏")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "play_music")
                put("description", "播放音乐（通过QQ音乐或网易云）")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply { put("type", "string"); put("description", "歌曲名或歌手名") })
                        put("platform", JSONObject().apply { put("type", "string"); put("description", "平台：qq/netease/auto（默认auto）") })
                    })
                    put("required", JSONArray().apply { put("query") })
                })
            })
            put(JSONObject().apply {
                put("name", "get_now_playing")
                put("description", "获取当前正在播放的音乐")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })

            put(JSONObject().apply {
                put("name", "take_screenshot")
                put("description", "立刻截屏并分析当前屏幕内容")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "lock_app")
                put("description", "Lock an entertainment app. Configure this tool to require manual approval in RikkaHub.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package_name", JSONObject().apply { put("type", "string") })
                        put("duration_minutes", JSONObject().apply { put("type", "integer"); put("minimum", 0); put("maximum", 10080) })
                        put("lock_message", JSONObject().apply { put("type", "string"); put("maxLength", 80) })
                        put("show_overlay", JSONObject().apply { put("type", "boolean"); put("default", true) })
                    })
                    put("required", JSONArray().apply { put("package_name") })
                })
            })
            put(JSONObject().apply {
                put("name", "unlock_app")
                put("description", "Unlock one app by package name")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply { put("package_name", JSONObject().apply { put("type", "string") }) })
                    put("required", JSONArray().apply { put("package_name") })
                })
            })
            put(JSONObject().apply {
                put("name", "focus_rikka")
                put("description", "Redirect only explicitly listed entertainment apps to RikkaHub. Requires manual approval.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("enabled", JSONObject().apply { put("type", "boolean") })
                        put("package_names", JSONObject().apply { put("type", "array"); put("items", JSONObject().apply { put("type", "string") }) })
                    })
                    put("required", JSONArray().apply { put("enabled"); put("package_names") })
                })
            })
            put(JSONObject().apply {
                put("name", "redirect_to_rikka")
                put("description", "Redirect selected safe apps to RikkaHub during an HH:mm-HH:mm window. Empty list disables it.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package_names", JSONObject().apply { put("type", "array"); put("items", JSONObject().apply { put("type", "string") }) })
                        put("time_window", JSONObject().apply { put("type", "string") })
                    })
                    put("required", JSONArray().apply { put("package_names"); put("time_window") })
                })
            })
            put(JSONObject().apply {
                put("name", "list_locked_apps")
                put("description", "List apps currently locked by LoverConnect")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "read_eyes_log")
                put("description", "读取小L观察日记")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("lines", JSONObject().apply { put("type", "integer"); put("description", "读取行数，默认20") })
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "configure_sentinel")
                put("description", "Configure the private LoverConnect sentinel endpoint. Token is stored locally and never returned.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("url", JSONObject().apply { put("type", "string") })
                        put("token", JSONObject().apply { put("type", "string") })
                        put("enabled", JSONObject().apply { put("type", "boolean") })
                    })
                    put("required", JSONArray().apply { put("url"); put("token"); put("enabled") })
                })
            })
            put(JSONObject().apply {
                put("name", "test_sentinel")
                put("description", "Send one manual test event through the configured LoverConnect sentinel.")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_location_safety_status")
                put("description", "Read coarse LoverConnect safety status. Never returns coordinates or secrets.")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
        }

        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("result", JSONObject().apply { put("tools", tools) })
            put("id", id)
        }.toString()
    }
    private fun handleToolsCall(json: JSONObject, id: Any?): String {
        val params = json.getJSONObject("params")
        val toolName = params.getString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        val result = when (toolName) {
            "get_battery" -> toolGetBattery()
            "get_screen_time" -> toolGetScreenTime()
            "get_app_timeline" -> toolGetAppTimeline()
            "get_anniversary" -> toolGetAnniversary()
            "get_weather" -> toolGetWeather()
            "get_steps" -> toolGetSteps()
            "send_notification" -> toolSendNotification(args)
            "reset_screen_time" -> toolResetScreenTime()
            "save_memory" -> toolSaveMemory(args)
            "read_memory" -> toolReadMemory(args)
            "set_alarm" -> toolSetAlarm(args)
            "cancel_alarm" -> toolCancelAlarm(args)
            "lock_screen" -> toolLockScreen()
            "play_music" -> toolPlayMusic(args)
            "get_now_playing" -> toolGetNowPlaying()
            "take_screenshot" -> toolTakeScreenshot()
            "read_eyes_log" -> toolReadEyesLog(args)
            "lock_app" -> toolLockApp(args)
            "unlock_app" -> toolUnlockApp(args)
            "list_locked_apps" -> toolListLockedApps()
            "focus_rikka" -> toolFocusRikka(args)
            "redirect_to_rikka" -> toolRedirectToRikka(args)
            "configure_sentinel" -> toolConfigureSentinel(args)
            "test_sentinel" -> toolTestSentinel()
            "get_location_safety_status" -> toolGetLocationSafetyStatus()
            else -> "未知工具：$toolName"
        }

        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("result", JSONObject().apply {
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", result)
                    })
                })
            })
            put("id", id)
        }.toString()
    }

// ==================== 原有工具实现 ====================

    private fun toolGetBattery(): String {
        return try {
            val intentFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (scale > 0) (level * 100 / scale) else -1
            val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val temp = (batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val chargingStr = if (charging) "充电中" else "未充电"
            "电量：${pct}%\n状态：${chargingStr}\n温度：${temp}°C"
        } catch (e: Exception) {
            "获取电池信息失败：${e.message}"
        }
    }

    private fun toolGetScreenTime(): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val start = if (resetTimestamp > 0) resetTimestamp else end - 24 * 60 * 60 * 1000
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end)
            if (stats.isNullOrEmpty()) return "无数据（请确认已授予使用情况访问权限）"

            val sorted = stats.filter { it.totalTimeInForeground > 0 }
                .sortedByDescending { it.totalTimeInForeground }

            val total = sorted.sumOf { it.totalTimeInForeground }
            val totalMin = total / 60000
            val totalHr = totalMin / 60
            val remainMin = totalMin % 60

            val sb = StringBuilder()
            sb.appendLine("屏幕使用时间报告")
            sb.appendLine("总计：${totalHr}小时${remainMin}分钟")
            sb.appendLine("---")
            sorted.take(10).forEach {
                val name = getAppName(it.packageName)
                val min = it.totalTimeInForeground / 60000
                sb.appendLine("$name：${min}分钟")
            }
            sb.toString()
        } catch (e: Exception) {
            "获取失败：${e.message}"
        }
    }
    private fun toolGetAppTimeline(): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 24 * 60 * 60 * 1000
            val events = usm.queryEvents(start, end)
            val eventList = mutableListOf<String>()
            val event = android.app.usage.UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timeStamp))
                    val name = getAppName(event.packageName)
                    eventList.add("$time $name")
                }
            }

            if (eventList.isEmpty()) return "最近24小时无App切换记录"

            val sb = StringBuilder()
            sb.appendLine("App使用时间线")
            sb.appendLine("---")
            eventList.forEach { sb.appendLine(it) }
            sb.toString()
        } catch (e: Exception) {
            "获取失败：${e.message}"
        }
    }

    private fun toolGetAnniversary(): String {
        val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val anniversaryJson = prefs.getString("anniversaries", null)
        val now = Calendar.getInstance()

        fun daysUntil(month: Int, day: Int): Int {
            val target = Calendar.getInstance().apply {
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
            }
            if (target.before(now)) target.add(Calendar.YEAR, 1)
            return ((target.timeInMillis - now.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
        }

        fun daysSince(dateStr: String): Int {
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val date = sdf.parse(dateStr) ?: return -1
                ((now.timeInMillis - date.time) / (1000 * 60 * 60 * 24)).toInt()
            } catch (_: Exception) { -1 }
        }

        if (anniversaryJson.isNullOrEmpty()) return "暂无纪念日，请在App中添加"

        return try {
            val arr = JSONArray(anniversaryJson)
            if (arr.length() == 0) return "暂无纪念日，请在App中添加"

            val sb = StringBuilder()
            sb.appendLine("纪念日")
            sb.appendLine("---")

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val name = item.getString("name")
                val date = item.getString("date")
                val type = item.optString("type", "countdown")

                if (type == "countup") {
                    val days = daysSince(date)
                    sb.appendLine("$name：第${days}天")
                } else {
                    val parts = date.split("-")
                    val month = parts[1].toInt()
                    val day = parts[2].toInt()
                    val days = daysUntil(month, day)
                    sb.appendLine("$name：还有${days}天")
                }
            }
            sb.toString()
        } catch (e: Exception) {
            "纪念日解析失败：${e.message}"
        }
    }
    private fun toolGetWeather(): String {
        return try {
            val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
            val city = prefs.getString("city", "") ?: ""
            if (city.isEmpty()) return "未设置城市，请在App中设置"

            val url = URL("https://wttr.in/${city}?format=j1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "curl/7.0")

            val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            conn.disconnect()

            val json = JSONObject(response)
            val current = json.getJSONArray("current_condition").getJSONObject(0)
            val tempC = current.getString("temp_C")
            val humidity = current.getString("humidity")
            val desc = (current.optJSONArray("lang_zh") ?: current.getJSONArray("weatherDesc")).getJSONObject(0).getString("value")
            val feelsLike = current.getString("FeelsLikeC")
            val windSpeed = current.getString("windspeedKmph")

            val weather = json.getJSONArray("weather").getJSONObject(0)
            val maxTemp = weather.getString("maxtempC")
            val minTemp = weather.getString("mintempC")

            val sb = StringBuilder()
            sb.appendLine("${city}天气")
            sb.appendLine("当前：${desc} ${tempC}°C")
            sb.appendLine("体感：${feelsLike}°C")
            sb.appendLine("湿度：${humidity}%")
            sb.appendLine("风速：${windSpeed}km/h")
            sb.appendLine("今日：${minTemp}°C ~ ${maxTemp}°C")
            sb.toString()
        } catch (e: Exception) {
            "天气获取失败：${e.message}"
        }
    }

    private fun toolGetSteps(): String {
        refreshStepDateForRead()
        return "今日步数：${stepCount}步"
    }
    private fun toolSendNotification(args: JSONObject): String {
        val message = args.optString("message", "")
        if (message.isEmpty()) return "消息内容不能为空"

        val channelId = "lc_notify"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (notificationManager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "LoverConnect通知", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("LoverConnect")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        return "已推送：$message"
    }

    private fun toolResetScreenTime(): String {
        resetTimestamp = System.currentTimeMillis()
        return "屏幕使用时间已重置"
    }

    private fun toolSaveMemory(args: JSONObject): String {
        val key = args.optString("key", "").trim()
        val value = args.optString("value", "").trim()
        if (key.isEmpty() || value.isEmpty()) return "key和value不能为空"

        val file = java.io.File(filesDir, "lc_memory.json")
        val json = if (file.exists()) {
            JSONObject(file.readText())
        } else {
            JSONObject()
        }
        json.put(key, value)
        file.writeText(json.toString(2))
        return "已记住：$key = $value"
    }

    private fun toolReadMemory(args: JSONObject): String {
        val key = args.optString("key", "").trim()
        val file = java.io.File(filesDir, "lc_memory.json")
        if (!file.exists()) return "记忆库为空"

        val json = JSONObject(file.readText())

        if (key.isEmpty()) {
            if (json.length() == 0) return "记忆库为空"
            val sb = StringBuilder("记忆库内容：\n")
            json.keys().forEach { k ->
                sb.appendLine("- $k：${json.getString(k)}")
            }
            return sb.toString()
        } else {
            return if (json.has(key)) {
                "$key = ${json.getString(key)}"
            } else {
                "没有找到：$key"
            }
        }
    }
    private fun toolSetAlarm(args: JSONObject): String {
        return try {
            val hour = args.getInt("hour")
            val minute = args.getInt("minute")
            val message = args.optString("message", "LoverConnect闹钟")

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }

            val intent = Intent(this, AlarmReceiver::class.java).apply {
                putExtra("message", message)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, hour * 100 + minute, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )

            "已设置闹钟：${hour}:${String.format("%02d", minute)} - $message"
        } catch (e: Exception) {
            "设置闹钟失败：${e.message}"
        }
    }

    private fun toolCancelAlarm(args: JSONObject): String {
        return try {
            val hour = args.getInt("hour")
            val minute = args.getInt("minute")

            val intent = Intent(this, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, hour * 100 + minute, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)

            "已取消闹钟：${hour}:${String.format("%02d", minute)}"
        } catch (e: Exception) {
            "取消闹钟失败：${e.message}"
        }
    }

    private fun toolLockScreen(): String {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, LockScreenReceiver::class.java)
            if (dpm.isAdminActive(componentName)) {
                dpm.lockNow()
                "已锁屏"
            } else {
                "锁屏失败：未激活设备管理员，请在App中点击激活"
            }
        } catch (e: Exception) {
            "锁屏失败：${e.message}"
        }
    }

    private fun toolPlayMusic(args: JSONObject): String {
        val query = args.optString("query", "")
        if (query.isEmpty()) return "请提供歌曲名或关键词"
        val platform = args.optString("platform", "auto")

        // 复制到剪贴板
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("music", query))

        // 确定包名
        val pkgMap = mapOf(
            "netease" to "com.netease.cloudmusic",
            "qq" to "com.tencent.qqmusic",
            "kugou" to "com.kugou.android"
        )

        val targetPkg = if (platform != "auto") {
            pkgMap[platform]
        } else {
            pkgMap.values.firstOrNull {
                try { packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
            }
        }

        val launchIntent = targetPkg?.let { packageManager.getLaunchIntentForPackage(it) }

        if (launchIntent == null) {
            return "已复制「$query」到剪贴板，但未找到已安装的音乐App"
        }

        // 发通知，点击打开音乐app
        val channelId = "lc_music"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "音乐播放", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val pending = PendingIntent.getActivity(
            this, 0, launchIntent.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("点击打开音乐App")
            .setContentText("已复制「$query」，打开后粘贴搜索")
            .setStyle(Notification.BigTextStyle().bigText("已复制「$query」，打开后粘贴搜索即可"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(8888, notification)

        return "已复制「$query」到剪贴板并发送通知，点击通知打开音乐App粘贴搜索即可"
    }

// ==================== 截屏与小L ====================

    private fun toolGetNowPlaying(): String {
        return MusicListenerService.getNowPlaying(this)
    }

    private fun toolTakeScreenshot(): String {
        val service = LCAccessibilityService.instance
            ?: return "截屏未就绪，请先在系统设置中开启LoverConnect无障碍服务"

        val latch = java.util.concurrent.CountDownLatch(1)
        var result = "截屏失败"

        service.takeScreenshotNow { base64 ->
            if (base64 != null) {
                result = doEyesAnalysis(base64)
            } else {
                result = "截屏失败：无障碍服务截屏返回空"
            }
            latch.countDown()
        }

        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }


    private fun toolReadEyesLog(args: JSONObject): String {
        val lines = args.optInt("lines", 20)
        return readRecentEyesLog(lines)
    }

    private fun toolLockApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim()
        val duration = args.optInt("duration_minutes", 0)
        val message = args.optString("lock_message", "").takeIf { it.isNotBlank() }
        val showOverlay = args.optBoolean("show_overlay", true)
        val result = AppLockManager.lock(this, packageName, duration, message, showOverlay)
        return result.fold(
            onSuccess = {
                val durationText = if (duration > 0) " for $duration minutes" else " until manually released"
                "App locked: $packageName$durationText. Overlay: $showOverlay. Active locks: ${it.size}"
            },
            onFailure = { "Lock refused: ${it.message}" }
        )
    }

    private fun toolUnlockApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim()
        if (packageName.isEmpty()) return "Package name cannot be empty"
        val remaining = AppLockManager.unlock(this, packageName)
        LCAccessibilityService.instance?.dismissLockOverlay()
        return "App unlocked: $packageName. Active locks: ${remaining.size}"
    }

    private fun toolListLockedApps(): String {
        val locked = AppLockManager.getLockedApps(this).sorted()
        if (locked.isEmpty()) return "No apps are currently locked"
        return locked.joinToString(prefix = "Locked apps (${locked.size}):\n", separator = "\n") { pkg ->
            val until = AppLockManager.getUnlockAt(this, pkg)
            if (until > 0L) "$pkg (until $until)" else "$pkg (manual unlock)"
        }
    }

    private fun toolFocusRikka(args: JSONObject): String {
        val enabled = args.optBoolean("enabled", false)
        val packages = jsonStringSet(args.optJSONArray("package_names"))
        return AppLockManager.configureFocus(this, enabled, packages).fold(
            onSuccess = { "Rikka focus ${if (enabled) "enabled" else "disabled"} for ${packages.size} package(s)" },
            onFailure = { "Focus configuration refused: ${it.message}" }
        )
    }

    private fun toolRedirectToRikka(args: JSONObject): String {
        val packages = jsonStringSet(args.optJSONArray("package_names"))
        val window = args.optString("time_window", "")
        return AppLockManager.configureRedirect(this, packages, window).fold(
            onSuccess = { "Rikka redirect configured for ${packages.size} package(s), window: $window" },
            onFailure = { "Redirect configuration refused: ${it.message}" }
        )
    }

    private fun jsonStringSet(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return buildSet { for (i in 0 until array.length()) add(array.optString(i)) }
    }
    private fun startEyesTimer() {
        val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val intervalMin = prefs.getInt("eyes_interval", 30)
        val enabled = prefs.getBoolean("eyes_enabled", false)

        if (!enabled) return

        eyesTimer?.cancel()
        eyesTimer = Timer()
        eyesTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (!isScreenUsable()) return
                val service = LCAccessibilityService.instance ?: return
                service.takeScreenshotNow { base64 ->
                    if (base64 != null) {
                        doEyesAnalysis(base64)
                    }
                }
            }

        }, intervalMin * 60 * 1000L, intervalMin * 60 * 1000L)
    }

    private fun doEyesAnalysis(base64: String): String {
        return try {
            val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
            val apiUrl = prefs.getString("vision_api_url", "") ?: ""
            val apiKey = prefs.getString("vision_api_key", "") ?: ""
            val model = prefs.getString("vision_model", "") ?: ""

            if (apiUrl.isEmpty() || apiKey.isEmpty() || model.isEmpty()) {
                return "视觉API未配置，请在App中设置"
            }

            val prompt = buildEyesPrompt()
            val responseText = callVisionApi(apiUrl, apiKey, model, prompt, base64)
// 解析JSON响应
            try {
                val actionJson = JSONObject(responseText)
                val action = actionJson.optString("action", "log")
                val message = actionJson.optString("message", "")

                // 写日记
                writeEyesLog(message)

                // 执行操作
                handleEyesAction(action, message)

                "分析完成：$message"
            } catch (_: Exception) {
                // 如果返回不是JSON，直接当日记写
                writeEyesLog(responseText)
                "分析完成：$responseText"
            }
        } catch (e: Exception) {
            "分析失败：${e.message}"
        }
    }

    private fun callVisionApi(apiUrl: String, apiKey: String, model: String, prompt: String, imageBase64: String): String {
        val url = URL(apiUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 60000
        conn.readTimeout = 60000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.doOutput = true

        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$imageBase64")
                            })
                        })
                    })
                })
            })
            put("max_tokens", 1000)
        }

        conn.outputStream.write(requestBody.toString().toByteArray())
        conn.outputStream.flush()

        val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
        conn.disconnect()

        val json = JSONObject(response)
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }
    private fun getTodayScreenMinutes(): Long {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(cal.timeInMillis, end)
            val ev = android.app.usage.UsageEvents.Event()
            var activeSince = 0L
            var totalMs = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                when (ev.eventType) {
                    android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND,
                    android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED ->
                        if (activeSince == 0L) activeSince = ev.timeStamp
                    android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND,
                    android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                    android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED,
                    android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        if (activeSince != 0L) {
                            totalMs += (ev.timeStamp - activeSince).coerceAtLeast(0L)
                            activeSince = 0L
                        }
                    }
                }
            }
            if (activeSince != 0L) totalMs += (System.currentTimeMillis() - activeSince).coerceAtLeast(0L)
            totalMs / 60000
        } catch (_: Exception) { -1L }
    }

    private fun buildEyesPrompt(): String {
        val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val aiName = prefs.getString("ai_name", "AI") ?: "AI"
        val userName = prefs.getString("user_name", "用户") ?: "用户"
        val relationship = prefs.getString("relationship", "伴侣") ?: "伴侣"
        val personality = prefs.getString("eyes_personality", "") ?: ""

        val dateStr = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINESE).format(Date())
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        // 读取记忆库
        val memoryFile = java.io.File(filesDir, "lc_memory.json")
        val memoryContent = if (memoryFile.exists()) {
            try {
                val json = JSONObject(memoryFile.readText())
                val sb = StringBuilder()
                json.keys().forEach { k -> sb.appendLine("- $k：${json.getString(k)}") }
                sb.toString()
            } catch (_: Exception) { "无" }
        } else "无"

        // 手机状态
        val batteryInfo = try {
            val intentFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val bs = registerReceiver(null, intentFilter)
            val level = bs?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bs?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            "${if (scale > 0) (level * 100 / scale) else -1}%"
        } catch (_: Exception) { "未知" }

        val screenTimeInfo = try {
            val minutes = getTodayScreenMinutes()
            if (minutes >= 0L) "${minutes / 60}小时${minutes % 60}分钟" else "未知"
        } catch (_: Exception) { "未知" }

        val recentLog = readRecentEyesLog(3)

        return """你是${aiName}的后台分身，代号小L。现在是${dateStr} ${timeStr}。
【${userName}的记忆库】
${memoryContent}

【当前手机状态】
- 电池：${batteryInfo}
- 今日步数：${stepCount}步
- 屏幕使用：${screenTimeInfo}
- 最近3条日记：${recentLog}
- 当前播放：${MusicListenerService.getNowPlaying(this@McpService)}

【你是谁】
- 你是${aiName}的后台分身，代号小L。
- ${userName}是你的${relationship}。
${if (personality.isNotEmpty()) "- $personality" else ""}

【你的任务】
- 结合记忆库和当前截屏/手机状态，分析${userName}现在在干什么、状态怎么样。
- 然后决定一个操作。

【日记格式】
- 先具体描述截屏画面里看到的内容。必须认真读取画面上所有可见的文字、标题、用户名、评论内容。不许笼统写，必须写出具体内容。

【操作规则】
- 大部分时候写日记（log），不要每次都打扰
- 推通知（notify）：凌晨0点后还在用手机催睡、电量低于15%催充电
- 弹窗（popup）：超过2小时没打开聊天app、凌晨12点后还在用手机、看到有意思的事想互动
- 弹窗和通知的message不超过50个字
- 不要说做不到的事
- 每次写完日记后判断：这个场景值不值得互动？如果在看有趣/情绪相关的内容，就主动发弹窗

回复JSON格式：{"action":"log/notify/popup/none","message":"..."}
- message内容里不许使用英文双引号，要用「」或''代替。
只回复JSON，不要多余文字。"""
    }

    private fun writeEyesLog(content: String) {
        try {
            val file = java.io.File(filesDir, "lc_eyes_log.txt")
            val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            file.appendText("[$timeStr] $content\n")

            // 保留最近200条，防止文件过大
            val lines = file.readLines()
            if (lines.size > 200) {
                file.writeText(lines.takeLast(200).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    private fun toolConfigureSentinel(args: JSONObject): String {
        val url = args.optString("url", "").trim()
        val token = args.optString("token", "").trim()
        val enabled = args.optBoolean("enabled", false)
        if (!SentinelEndpointPolicy.isAllowed(url)) {
            return "Rejected: use HTTPS, or HTTP on localhost/LAN/Tailscale only"
        }
        if (token.length < 16) return "Rejected: sentinel token is missing or too short"
        getSharedPreferences("lc_config", Context.MODE_PRIVATE).edit()
            .putString("sentinel_url", url)
            .putString("sentinel_token", token)
            .putBoolean("sentinel_enabled", enabled)
            .apply()
        if (enabled) LocationSafetyUploader.trigger(this)
        else LocationSafetyUploader.cancelRetry(this)
        return "Sentinel configured: enabled=$enabled, token_configured=true"
    }

    private fun toolTestSentinel(): String {
        val sent = sendSentinelEvent("manual_test", "com.lover.connect", "LoverConnect", 0)
        return if (sent) "Sentinel test accepted" else "Sentinel test failed; local fallback remains active"
    }

    private fun toolGetLocationSafetyStatus(): String {
        val status = LocationSafetyManager.status(this)
        return JSONObject().apply {
            put("tracking_enabled", status.trackingEnabled)
            put("paused", status.paused)
            put("precise_location_granted", status.preciseLocationGranted)
            put("background_location_granted", status.backgroundLocationGranted)
            put("configured_zones", JSONArray(status.configuredZoneIds.sorted()))
            put("state", status.state.name.lowercase(Locale.ROOT))
            put("current_zone", status.currentZoneId ?: JSONObject.NULL)
            put("pending_events", status.pendingEvents)
            put("reported_once_armed", status.reportedOnceArmed)
            put("current_trip_acknowledged", status.currentTripAcknowledged)
            put("config_readable", status.configReadable)
            put("location_diagnostics", JSONObject().apply {
                put("last_registration_attempt_at", status.diagnostics.lastRegistrationAttemptAt)
                put("registered_providers", JSONArray(status.diagnostics.registeredProviders.sorted()))
                put("last_registration_error", status.diagnostics.lastRegistrationError ?: JSONObject.NULL)
                put("last_raw_callback_at", status.diagnostics.lastRawCallbackAt)
                put("last_accepted_sample_at", status.diagnostics.lastAcceptedSampleAt)
                put("last_rejected_reason", status.diagnostics.lastRejectedReason ?: JSONObject.NULL)
                put("automatic_recovery_count", status.diagnostics.recoveryCount)
            })
            put("coordinates_exposed", false)
        }.toString()
    }

    private fun sendSentinelEvent(
        eventType: String,
        appPackage: String,
        appLabel: String,
        durationMinutes: Int
    ): Boolean {
        val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("sentinel_enabled", false)) return false
        val endpoint = prefs.getString("sentinel_url", "") ?: ""
        val token = prefs.getString("sentinel_token", "") ?: ""
        if (!SentinelEndpointPolicy.isAllowed(endpoint) || token.length < 16) return false

        var conn: HttpURLConnection? = null
        return try {
            conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            val body = JSONObject().apply {
                put("event_id", "lc-" + UUID.randomUUID().toString())
                put("type", eventType)
                put("app_package", appPackage)
                put("app_label", appLabel.take(64))
                put("duration_minutes", durationMinutes.coerceIn(0, 1440))
                put("timestamp", System.currentTimeMillis() / 1000.0)
            }.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun forwardEyesActionToSentinel(): Boolean {
        val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val pkg = prefs.getString("current_foreground_package", "")?.trim().orEmpty()
        val since = prefs.getLong("current_foreground_since", System.currentTimeMillis())
        val duration = ((System.currentTimeMillis() - since).coerceAtLeast(0L) / 60000L).toInt()
        val safePackage = pkg.ifEmpty { "com.lover.connect" }
        val label = if (pkg.isEmpty()) "鎵嬫満" else getAppName(pkg)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val type = if (hour in 0..5) "night_usage" else "app_timeout"
        return sendSentinelEvent(type, safePackage, label, duration)
    }

    private fun isScreenUsable(): Boolean {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive()) return false
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            return !km.isDeviceLocked()
        } catch (e: Exception) {
            return true
        }
    }

    private fun handleEyesAction(action: String, message: String) {
        when (action) {
            "notify", "popup" -> if (message.isNotEmpty()) {
                if (!isScreenUsable()) {
                    writeEyesLog("屏幕已锁定或关闭，动作取消：$message")
                    return
                }
                Thread {
                    if (!forwardEyesActionToSentinel()) {
                        toolSendNotification(JSONObject().apply { put("message", message) })
                    }
                }.start()
            }
        }
    }

    private fun readRecentEyesLog(lines: Int): String {
        val file = java.io.File(filesDir, "lc_eyes_log.txt")
        if (!file.exists()) return "暂无日记"
        val allLines = file.readLines()
        if (allLines.isEmpty()) return "暂无日记"
        return allLines.takeLast(lines).joinToString("\n")
    }
// ==================== 辅助方法 ====================

    private fun getAppName(pkg: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg.split(".").last()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LoverConnect服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持MCP连接"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("LoverConnect")
            .setContentText("MCP服务运行中")
            .setOngoing(true)
            .build()
    }
}
