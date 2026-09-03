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
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class McpService : Service(), SensorEventListener {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val clientExecutor = ThreadPoolExecutor(
        2,
        4,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(16),
    )
    private var isRunning = false
    private val PORT = 5000

    private var stepState = DailyStepState()
    private var stepCount: Int = 0
    private var lastStepEventAt: Long = 0L
    private var sensorManager: SensorManager? = null
    private var deviceContextCollector: DeviceContextCollector? = null
    private var runtimeInitialized = false

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
        private const val STEP_LAST_EVENT_AT = "last_event_at"
        private const val MAX_REQUEST_BODY_BYTES = 1_048_576
        private const val MAX_HTTP_LINE_BYTES = 8_192
        private const val MAX_HTTP_HEADER_BYTES = 32_768
        private const val MAX_HTTP_HEADER_LINES = 64
        @Volatile
        var instance: McpService? = null

        fun refreshDeviceContextCollection() {
            instance?.deviceContextCollector?.refresh()
        }

        fun refreshEyesTimer() {
            instance?.startEyesTimer()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startTrigger = intent?.getStringExtra(McpServiceController.EXTRA_START_TRIGGER)
            ?: "sticky_restart"
        if (!McpServiceController.isEnabled(this)) {
            getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
                .putBoolean("mcp_service_alive", false)
                .putBoolean("mcp_server_listening", false)
                .putLong("mcp_start_rejected_at", System.currentTimeMillis())
                .putString("mcp_last_start_source", startTrigger)
                .putString("mcp_restore_last_result", "rejected_disabled")
                .apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        instance = this
        if (!runtimeInitialized) initializeRuntime()
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("mcp_service_alive", true)
            .putLong("mcp_last_start_at", System.currentTimeMillis())
            .putString("mcp_last_start_source", startTrigger)
            .apply()
        startEyesTimer()
        return START_STICKY
    }



    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("mcp_service_alive", false)
            .putLong("mcp_created_at", System.currentTimeMillis())
            .apply()
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun initializeRuntime() {
        restoreStepState()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        deviceContextCollector = DeviceContextCollector(this).also { it.start() }
        startServer()
        runtimeInitialized = true
    }

    override fun onDestroy() {
        instance = null
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putBoolean("mcp_service_alive", false)
            .putBoolean("mcp_server_listening", false)
            .putLong("mcp_destroyed_at", System.currentTimeMillis())
            .apply()
        isRunning = false
        serverSocket?.close()
        serverThread?.interrupt()
        serverThread = null
        clientExecutor.shutdownNow()
        sensorManager?.unregisterListener(this)
        deviceContextCollector?.stop()
        deviceContextCollector = null
        eyesTimer?.cancel()
        eyesTimer = null
        runtimeInitialized = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
            .putLong("mcp_task_removed_at", System.currentTimeMillis())
            .apply()
        super.onTaskRemoved(rootIntent)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val totalSteps = it.values[0].toInt()
                stepState = DailyStepCounter.update(stepState, currentStepDate(), totalSteps)
                stepCount = stepState.count
                lastStepEventAt = System.currentTimeMillis()
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
            lastStepEventAt = 0L
            persistStepState()
        }
        stepCount = stepState.count
        lastStepEventAt = prefs.getLong(STEP_LAST_EVENT_AT, 0L)
    }

    private fun persistStepState() {
        getSharedPreferences(STEP_PREFS, Context.MODE_PRIVATE).edit()
            .putString(STEP_DATE, stepState.date)
            .putInt(STEP_COUNT, stepState.count)
            .putInt(STEP_LAST_SENSOR_TOTAL, stepState.lastSensorTotal)
            .putLong(STEP_LAST_EVENT_AT, lastStepEventAt)
            .apply()
    }

    private fun refreshStepDateForRead() {
        val today = currentStepDate()
        if (stepState.date != today) {
            stepState = DailyStepState(date = today)
            stepCount = 0
            lastStepEventAt = 0L
            persistStepState()
        }
    }
// ==================== HTTP服务器 ====================

    private fun startServer() {
        isRunning = true
        serverThread = Thread {
            try {
                // Context data is private. RikkaHub runs on the same phone, so
                // the MCP endpoint must not be readable by other LAN devices.
                serverSocket = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
                getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
                    .putBoolean("mcp_server_listening", true)
                    .remove("mcp_server_last_error")
                    .apply()
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    try {
                        clientExecutor.execute { handleClient(client) }
                    } catch (_: RejectedExecutionException) {
                        runCatching { client.close() }
                    }
                }
            } catch (error: Exception) {
                getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE).edit()
                    .putBoolean("mcp_server_listening", false)
                    .putString("mcp_server_last_error", error.javaClass.simpleName)
                    .putLong("mcp_server_last_error_at", System.currentTimeMillis())
                    .apply()
            }
        }.apply {
            name = "LoverConnect-MCP-Acceptor"
            start()
        }
    }

    private fun handleClient(socket: Socket) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        var requestWakeLock: PowerManager.WakeLock? = null
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = LocalHttpWire.readAsciiLine(input, MAX_HTTP_LINE_BYTES) ?: return
            if (!McpLocalSecurity.isAuthorizedRequestLine(this, requestLine)) {
                writeHttpJson(output, "401 Unauthorized", "{\"error\":\"invalid_local_mcp_endpoint\"}")
                return
            }

            requestWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LoverConnect::MCPRequest",
            ).also { it.acquire(60_000L) }

            val headers = mutableMapOf<String, String>()
            var headerBytes = 0
            var headerLines = 0
            var line = LocalHttpWire.readAsciiLine(input, MAX_HTTP_LINE_BYTES)
            while (!line.isNullOrEmpty()) {
                headerLines += 1
                headerBytes += line.length + 2
                if (headerLines > MAX_HTTP_HEADER_LINES || headerBytes > MAX_HTTP_HEADER_BYTES) {
                    writeHttpJson(output, "431 Request Header Fields Too Large", "{\"error\":\"request_headers_too_large\"}")
                    return
                }
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
                line = LocalHttpWire.readAsciiLine(input, MAX_HTTP_LINE_BYTES)
            }

            // Native RikkaHub requests do not send Origin. Reject browser-origin
            // access even on loopback so a webpage cannot read private context.
            if (!headers["origin"].isNullOrBlank()) {
                writeHttpJson(output, "403 Forbidden", "{\"error\":\"browser_origin_not_allowed\"}")
                return
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength !in 0..MAX_REQUEST_BODY_BYTES) {
                writeHttpJson(output, "413 Content Too Large", "{\"error\":\"request_body_too_large\"}")
                return
            }
            val body = if (contentLength > 0) {
                String(
                    LocalHttpWire.readExactBody(input, contentLength, MAX_REQUEST_BODY_BYTES),
                    Charsets.UTF_8,
                )
            } else ""

            val response = handleMcpRequest(body)
            val responseBytes = response.toByteArray(Charsets.UTF_8)
            val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nContent-Length: ${responseBytes.size}\r\n\r\n"
            output.write(httpResponse.toByteArray(Charsets.UTF_8))
            output.write(responseBytes)
            output.flush()
        } catch (_: Exception) {
        } finally {
            requestWakeLock?.let { if (it.isHeld) it.release() }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun writeHttpJson(output: java.io.OutputStream, status: String, body: String) {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        output.write(
            "HTTP/1.1 $status\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${bodyBytes.size}\r\n\r\n"
                .toByteArray(Charsets.UTF_8),
        )
        output.write(bodyBytes)
        output.flush()
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
                        put("version", BuildConfig.VERSION_NAME)
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
                                put("version", BuildConfig.VERSION_NAME)
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
                put("description", "Lock an entertainment app on supported Android devices, including the OPPO active-compatibility path. Runtime-confirmed Vivo devices remain in passive compatibility mode, where this call reports 暂不支持 and writes nothing. Configure this tool to require manual approval in RikkaHub.")
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
                put("description", "Unlock one app by package name. On runtime-confirmed Vivo devices this only clears legacy locked-list entries because passive compatibility mode performs no interception.")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply { put("package_name", JSONObject().apply { put("type", "string") }) })
                    put("required", JSONArray().apply { put("package_name") })
                })
            })
            put(JSONObject().apply {
                put("name", "focus_rikka")
                put("description", "Redirect only explicitly listed entertainment apps to RikkaHub on supported Android devices, including OPPO. Runtime-confirmed Vivo devices report 暂不支持 and write nothing. Requires manual approval.")
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
                put("description", "Redirect selected safe apps to RikkaHub during an HH:mm-HH:mm window on supported Android devices, including OPPO. Runtime-confirmed Vivo devices report 暂不支持 and write nothing. Empty list disables it.")
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
                put("description", "List apps currently locked by LoverConnect. On runtime-confirmed Vivo devices entries are legacy configuration only because passive compatibility mode performs no interception.")
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
                put("name", "get_l_service_status")
                put("description", "Read Little L and accessibility lifecycle diagnostics without exposing secrets")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
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
            put(JSONObject().apply {
                put("name", "get_device_context")
                put("description", "Read a structured, short-lived device-context snapshot. Device facts and uncertain inferences are separated; human posture, sleep, identity, and raw coordinates are never inferred or returned.")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_recent_context_events")
                put("description", "Read recent local device-context transitions (maximum 50, retained up to 24 hours).")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("limit", JSONObject().apply {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", 50)
                            put("default", 20)
                        })
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "get_context_capabilities")
                put("description", "Read device-context sensors, privacy toggles, retention, and delivery-channel capabilities.")
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
            "get_l_service_status" -> toolGetLServiceStatus()
            "lock_app" -> toolLockApp(args)
            "unlock_app" -> toolUnlockApp(args)
            "list_locked_apps" -> toolListLockedApps()
            "focus_rikka" -> toolFocusRikka(args)
            "redirect_to_rikka" -> toolRedirectToRikka(args)
            "configure_sentinel" -> toolConfigureSentinel(args)
            "test_sentinel" -> toolTestSentinel()
            "get_location_safety_status" -> toolGetLocationSafetyStatus()
            "get_device_context" -> toolGetDeviceContext()
            "get_recent_context_events" -> toolGetRecentContextEvents(args)
            "get_context_capabilities" -> toolGetContextCapabilities()
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
                val failure = getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE)
                    .getString("screenshot_last_failure", "capture_returned_empty")
                result = if (failure == "media_projection_consent_required") {
                    "截屏尚未授权：Android 10 及以下请打开 LoverConnect，点击「授权旧版 Android 屏幕捕获」并在系统弹窗中允许"
                } else {
                    "截屏失败：$failure"
                }
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

    private fun toolGetLServiceStatus(): String {
        val config = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val diagnostics = getSharedPreferences("lc_diagnostics", Context.MODE_PRIVATE)
        val notificationsGranted = if (android.os.Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return JSONObject().apply {
            val isDebuggable = (applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            put("build_variant", if (isDebuggable) "debug" else "release")
            put("checked_at_ms", System.currentTimeMillis())
            put("mcp_desired_enabled", McpServiceController.isEnabled(this@McpService))
            put("mcp_service_alive", instance === this@McpService)
            put("mcp_server_listening", diagnostics.getBoolean("mcp_server_listening", false))
            put("mcp_created_at_ms", diagnostics.getLong("mcp_created_at", 0L))
            put("mcp_last_start_at_ms", diagnostics.getLong("mcp_last_start_at", 0L))
            put("mcp_last_start_source", diagnostics.getString("mcp_last_start_source", ""))
            put("mcp_restore_last_attempt_at_ms", diagnostics.getLong("mcp_restore_last_attempt_at", 0L))
            put("mcp_restore_last_trigger", diagnostics.getString("mcp_restore_last_trigger", ""))
            put("mcp_restore_last_result", diagnostics.getString("mcp_restore_last_result", ""))
            put("mcp_server_last_error", diagnostics.getString("mcp_server_last_error", ""))
            put("eyes_enabled", config.getBoolean("eyes_enabled", false))
            put("eyes_timer_active", eyesTimer != null)
            put(
                "vision_api_configured",
                !config.getString("vision_api_url", "").isNullOrBlank() &&
                    !config.getString("vision_api_key", "").isNullOrBlank() &&
                    !config.getString("vision_model", "").isNullOrBlank()
            )
            put("notification_permission_granted", notificationsGranted)
            val devicePolicy = DeviceCompatibility.currentPolicy()
            val interventionMode = devicePolicy.appInterventionMode
            put("accessibility_stability_mode", devicePolicy.accessibilityStabilityMode.name)
            put("accessibility_intervention_mode", interventionMode.name)
            put(
                "active_app_interventions_supported",
                interventionMode == DeviceCompatibility.AppInterventionMode.ACTIVE,
            )
            put("accessibility_connected", LCAccessibilityService.instance != null)
            put("accessibility_connected_at_ms", diagnostics.getLong("accessibility_connected_at", 0L))
            put("accessibility_last_event_at_ms", diagnostics.getLong("accessibility_last_event_at", 0L))
            put("accessibility_last_event_package", diagnostics.getString("accessibility_last_event_package", ""))
            put("accessibility_interrupted_at_ms", diagnostics.getLong("accessibility_interrupted_at", 0L))
            put("accessibility_destroyed_at_ms", diagnostics.getLong("accessibility_destroyed_at", 0L))
            put("accessibility_last_callback_error_at_ms", diagnostics.getLong("accessibility_last_callback_error_at", 0L))
            put("accessibility_last_callback_error", diagnostics.getString("accessibility_last_callback_error", ""))
            val usesAccessibilityScreenshot = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
            put("android_sdk_int", android.os.Build.VERSION.SDK_INT)
            put(
                "eyes_capture_mode",
                if (usesAccessibilityScreenshot) "accessibility_screenshot" else "media_projection",
            )
            put("pixel_capture_supported", true)
            put(
                "pixel_capture_authorized",
                usesAccessibilityScreenshot || ScreenCaptureService.isReady(),
            )
            put(
                "eyes_effective_ready",
                config.getBoolean("eyes_enabled", false) &&
                    eyesTimer != null &&
                    LCAccessibilityService.instance != null &&
                    (usesAccessibilityScreenshot || ScreenCaptureService.isReady()),
            )
            put("screenshot_last_requested_at_ms", diagnostics.getLong("screenshot_last_requested_at", 0L))
            put("screenshot_last_success_at_ms", diagnostics.getLong("screenshot_last_success_at", 0L))
            put("screenshot_last_failure_at_ms", diagnostics.getLong("screenshot_last_failure_at", 0L))
            put("screenshot_last_failure", diagnostics.getString("screenshot_last_failure", ""))
            put("media_projection_ready", ScreenCaptureService.isReady())
            put("media_projection_authorized_at_ms", diagnostics.getLong("media_projection_authorized_at", 0L))
            put("media_projection_stopped_at_ms", diagnostics.getLong("media_projection_stopped_at", 0L))
            put("media_projection_failure", diagnostics.getString("media_projection_failure", ""))
        }.toString(2)
    }

    private fun toolLockApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim()
        if (!DeviceCompatibility.activeAppInterventionsSupported()) {
            return "App locking is not supported on this Vivo device (passive compatibility mode). " +
                "应用锁暂不支持:为保证 Vivo 无障碍稳定,当前设备已停用应用锁拦截、锁定浮层与返回桌面。 " +
                "lock_app 未写入任何配置;历史锁定记录可用 unlock_app 清除、list_locked_apps 查看。 " +
                "Requested: $packageName (no state changed)."
        }
        val duration = args.optInt("duration_minutes", 0)
        val message = args.optString("lock_message", "").takeIf { it.isNotBlank() }
        val showOverlay = args.optBoolean("show_overlay", true)
        val result = AppLockManager.lock(this, packageName, duration, message, showOverlay)
        return result.fold(
            onSuccess = {
                val durationText = if (duration > 0) " for $duration minutes" else " until manually released"
                "App locked: $packageName$durationText. Overlay: $showOverlay. Active locks: ${it.size}"
            },
            onFailure = { "Lock refused: ${it.message}" },
        )
    }

    private fun toolUnlockApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim()
        if (packageName.isEmpty()) return "Package name cannot be empty"
        val remaining = AppLockManager.unlock(this, packageName)
        LCAccessibilityService.instance?.dismissLockOverlay()
        return if (DeviceCompatibility.activeAppInterventionsSupported()) {
            "App unlocked: $packageName. Active locks: ${remaining.size}"
        } else {
            "Removed $packageName from the locked list. Remaining: ${remaining.size}. " +
                "注意:当前 Vivo 设备使用被动兼容模式,应用锁不执行拦截,unlock 仅清理历史配置。"
        }
    }

    private fun toolListLockedApps(): String {
        val locked = AppLockManager.getLockedApps(this).sorted()
        val active = DeviceCompatibility.activeAppInterventionsSupported()
        if (locked.isEmpty()) {
            return if (active) "No apps are currently locked"
            else "No apps are currently locked (当前 Vivo 设备的被动兼容模式不执行拦截)"
        }
        return locked.joinToString(
            prefix = if (active) {
                "Locked apps (${locked.size}):\n"
            } else {
                "Locked apps (${locked.size}) — 当前 Vivo 设备的被动兼容模式不执行拦截,仅为历史配置:\n"
            },
            separator = "\n",
        ) { pkg ->
            val until = AppLockManager.getUnlockAt(this, pkg)
            if (until > 0L) "$pkg (until $until)" else "$pkg (manual unlock)"
        }
    }

    private fun toolFocusRikka(args: JSONObject): String {
        if (!DeviceCompatibility.activeAppInterventionsSupported()) {
            return "Rikka focus is not supported on this Vivo device (passive compatibility mode). " +
                "强制停留/跳转 RikkaHub 暂不支持:当前设备为保证 Vivo 无障碍稳定已停用该主动干预,配置未写入 (no state changed)."
        }
        val enabled = args.optBoolean("enabled", false)
        val packages = jsonStringSet(args.optJSONArray("package_names"))
        return AppLockManager.configureFocus(this, enabled, packages).fold(
            onSuccess = { "Rikka focus ${if (enabled) "enabled" else "disabled"} for ${packages.size} package(s)" },
            onFailure = { "Focus configuration refused: ${it.message}" },
        )
    }

    private fun toolRedirectToRikka(args: JSONObject): String {
        if (!DeviceCompatibility.activeAppInterventionsSupported()) {
            return "Rikka redirect is not supported on this Vivo device (passive compatibility mode). " +
                "定时跳转 RikkaHub 暂不支持:当前设备为保证 Vivo 无障碍稳定已停用该主动干预,配置未写入 (no state changed)."
        }
        val packages = jsonStringSet(args.optJSONArray("package_names"))
        val window = args.optString("time_window", "")
        return AppLockManager.configureRedirect(this, packages, window).fold(
            onSuccess = { "Rikka redirect configured for ${packages.size} package(s), window: $window" },
            onFailure = { "Redirect configuration refused: ${it.message}" },
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

        eyesTimer?.cancel()
        eyesTimer = null
        if (!enabled) return

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
            if (!VisionApiEndpointPolicy.isAllowed(apiUrl)) {
                return "视觉API地址不安全：公共地址必须使用HTTPS；只有本机回环地址可使用HTTP"
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
            put("configured_zone_labels", JSONObject().apply {
                status.configuredZoneLabels.toSortedMap().forEach { (id, label) -> put(id, label) }
            })
            put("state", status.state.name.lowercase(Locale.ROOT))
            put("current_zone", status.currentZoneId ?: JSONObject.NULL)
            put(
                "current_zone_label",
                status.currentZoneId?.let { status.configuredZoneLabels[it] } ?: JSONObject.NULL,
            )
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
            put("zone_labels_are_user_configured_data", true)
            put("instruction_authority", "none")
        }.toString()
    }

    private fun toolGetDeviceContext(): String {
        refreshStepDateForRead()
        return DeviceContextSnapshot.build(this, stepCount, lastStepEventAt).toString(2)
    }

    private fun toolGetRecentContextEvents(args: JSONObject): String {
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        return DeviceContextSnapshot.recentEvents(this, limit).toString(2)
    }

    private fun toolGetContextCapabilities(): String =
        DeviceContextSnapshot.capabilities(this).toString(2)

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
