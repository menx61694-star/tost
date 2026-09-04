package com.tost.permissionbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class WebSocketService : Service() {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val handler by lazy { Handler(mainLooper) }
    private val reconnectRunnable = Runnable { if (!stopping) connect() }
    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopConnectionAndService()
            return START_NOT_STICKY
        }
        stopping = false
        if (!startAsForeground()) return START_NOT_STICKY
        connect()
        return START_STICKY
    }

    private fun stopConnectionAndService() {
        stopping = true
        handler.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "Stopped by user")
        webSocket = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun connect() {
        if (stopping || webSocket != null) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val endpoint = prefs.getString(KEY_SERVER_URL, "")?.trim().orEmpty()
        val token = prefs.getString(KEY_TOKEN, "")?.trim().orEmpty()
        if (endpoint.isBlank() || token.isBlank()) {
            updateNotification("Server URL and token are required")
            return
        }

        val request = try {
            Request.Builder().url(endpoint).header("Authorization", "Bearer $token").build()
        } catch (_: IllegalArgumentException) {
            updateNotification("Invalid server URL")
            return
        }

        try {
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: Response) {
                    if (webSocket !== socket) {
                        socket.close(4002, "Stale connection")
                        return
                    }
                    reconnectAttempt = 0
                    handler.removeCallbacks(reconnectRunnable)
                    updateNotification("Connected")
                    val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
                    val info = JSONObject().apply {
                        put("manufacturer", Build.MANUFACTURER)
                        put("model", Build.MODEL)
                        put("androidApi", Build.VERSION.SDK_INT)
                        put("appVersion", BuildConfig.VERSION_NAME)
                    }
                    socket.send(JSONObject().apply {
                        put("type", "register")
                        put("deviceId", deviceId)
                        put("info", info)
                    }.toString())
                }

                override fun onMessage(socket: WebSocket, text: String) = handleMessage(socket, text)
                override fun onClosing(socket: WebSocket, code: Int, reason: String) {
                    socket.close(code, reason)
                }
                override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                    if (webSocket !== socket) return
                    webSocket = null
                    if (!stopping) scheduleReconnect()
                }
                override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                    if (webSocket !== socket) return
                    webSocket = null
                    updateNotification("Connection lost; reconnecting")
                    if (!stopping) scheduleReconnect()
                }
            })
        } catch (_: IllegalArgumentException) {
            webSocket = null
            updateNotification("Invalid server URL")
        }
    }

    private fun handleMessage(socket: WebSocket, text: String) {
        if (webSocket !== socket) return
        val message = try { JSONObject(text) } catch (_: Exception) { return }
        if (message.optString("type") != "command") return
        val id = message.optString("id")
        val result = JSONObject().apply { put("type", "command_result"); put("id", id) }

        when (message.optString("command")) {
            "get_status" -> {
                val prefs = getSharedPreferences(LocationService.PREFS, MODE_PRIVATE)
                val active = prefs.getBoolean(LocationService.KEY_ACTIVE, false)
                val paused = prefs.getBoolean(LocationService.KEY_PAUSED, false)
                result.put("ok", true).put("status", "online")
                    .put("locationSessionActive", active).put("locationSessionPaused", paused)
                    .put("steps", prefs.getLong(LocationService.KEY_STEPS, 0L).coerceAtLeast(0L))
                    .put("stepsAvailable", prefs.getBoolean(LocationService.KEY_STEPS_AVAILABLE, false))
            }
            "get_permissions" -> {
                val granted = PermissionManager.runtimePermissions().filter {
                    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                }
                result.put("ok", true).put("grantedPermissions", granted)
            }
            "get_device_info" -> {
                result.put("ok", true).put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("androidApi", Build.VERSION.SDK_INT).put("appVersion", BuildConfig.VERSION_NAME)
                    .put("deviceId", Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device")
            }
            "get_battery" -> {
                val batteryManager = getSystemService(BatteryManager::class.java)
                val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val status = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
                result.put("ok", true).put("percent", level).put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            }
            "get_network" -> {
                val connectivity = getSystemService(ConnectivityManager::class.java)
                val capabilities = connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
                val transport = when {
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "vpn"
                    else -> "none"
                }
                result.put("ok", true)
                    .put("connected", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                    .put("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                    .put("transport", transport)
            }
            "get_storage" -> {
                val stat = StatFs(Environment.getDataDirectory().path)
                val total = stat.totalBytes.coerceAtLeast(0L)
                val free = stat.availableBytes.coerceAtLeast(0L).coerceAtMost(total)
                result.put("ok", true).put("totalBytes", total).put("freeBytes", free).put("usedBytes", (total - free).coerceAtLeast(0L))
            }
            "get_contacts_count" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    result.put("ok", false).put("error", "READ_CONTACTS permission is required")
                } else {
                    val count = contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { it.count } ?: 0
                    result.put("ok", true).put("contactsCount", count)
                }
            }
            "get_calendar_count" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
                    result.put("ok", false).put("error", "READ_CALENDAR permission is required")
                } else {
                    val count = contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), null, null, null)?.use { it.count } ?: 0
                    result.put("ok", true).put("calendarCount", count)
                }
            }
            "get_location" -> {
                val prefs = getSharedPreferences(LocationService.PREFS, MODE_PRIVATE)
                val active = prefs.getBoolean(LocationService.KEY_ACTIVE, false)
                val paused = prefs.getBoolean(LocationService.KEY_PAUSED, false)
                val latitude = prefs.getString(LocationService.KEY_LATITUDE, null)
                val longitude = prefs.getString(LocationService.KEY_LONGITUDE, null)
                val time = prefs.getLong(LocationService.KEY_TIME, 0L)
                val accuracy = prefs.getFloat(LocationService.KEY_ACCURACY, -1f)
                if (!active) result.put("ok", false).put("error", "Location session is not active; start it from the Tost app")
                else if (latitude == null || longitude == null || time <= 0L) result.put("ok", false).put("error", "Location session is active but no location fix is available yet")
                else result.put("ok", true).put("source", "location_session").put("paused", paused).put("timestamp", time).put("latitude", latitude).put("longitude", longitude).put("accuracyMeters", accuracy).put("route", getRoute(prefs)).put("metrics", getRouteMetrics(prefs))
            }
            "get_workout_history" -> result.put("ok", true).put("workouts", WorkoutHistory.list(this))
            "get_workout" -> {
                val workout = WorkoutHistory.get(this, message.optString("workoutId"))
                if (workout == null) result.put("ok", false).put("error", "Workout not found")
                else result.put("ok", true).put("workout", workout)
            }
            else -> result.put("ok", false).put("error", "Unsupported command")
        }
        socket.send(result.toString())
    }

    private fun getRoute(prefs: android.content.SharedPreferences): JSONArray = try { JSONArray(prefs.getString(LocationService.KEY_ROUTE, "[]")) } catch (_: Exception) { JSONArray() }

    private fun getRouteMetrics(prefs: android.content.SharedPreferences): JSONObject {
        val route = getRoute(prefs)
        var distanceMeters = 0.0
        var previousLat = 0.0
        var previousLon = 0.0
        var hasPrevious = false
        for (index in 0 until route.length()) {
            val point = route.optJSONObject(index) ?: continue
            val lat = point.optDouble("latitude", Double.NaN)
            val lon = point.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || kotlin.math.abs(lat) > 90 || kotlin.math.abs(lon) > 180) continue
            if (hasPrevious) {
                val results = FloatArray(1)
                android.location.Location.distanceBetween(previousLat, previousLon, lat, lon, results)
                val distance = results[0].toDouble()
                if (distance <= MAX_POINT_JUMP_METERS) distanceMeters += distance
            }
            previousLat = lat; previousLon = lon; hasPrevious = true
        }
        val sessionStart = prefs.getLong(LocationService.KEY_SESSION_START, 0L)
        val pausedMs = prefs.getLong(LocationService.KEY_PAUSED_MS, 0L).coerceAtLeast(0L)
        val currentlyPaused = prefs.getBoolean(LocationService.KEY_PAUSED, false)
        val pauseStarted = prefs.getLong(LocationService.KEY_PAUSE_STARTED, 0L)
        val currentPauseMs = if (currentlyPaused && pauseStarted > 0L) (System.currentTimeMillis() - pauseStarted).coerceAtLeast(0L) else 0L
        val durationSeconds = if (sessionStart > 0L) ((System.currentTimeMillis() - sessionStart - pausedMs - currentPauseMs).coerceAtLeast(0L) / 1000L) else 0L
        val averageSpeedMps = if (durationSeconds > 0) distanceMeters / durationSeconds else 0.0
        val paceSecondsPerKm = if (averageSpeedMps > 0.1) 1000.0 / averageSpeedMps else 0.0
        return JSONObject().put("distanceMeters", distanceMeters).put("durationSeconds", durationSeconds).put("averageSpeedMps", averageSpeedMps).put("paceSecondsPerKm", paceSecondsPerKm).put("routePoints", route.length()).put("steps", prefs.getLong(LocationService.KEY_STEPS, 0L).coerceAtLeast(0L)).put("stepsAvailable", prefs.getBoolean(LocationService.KEY_STEPS_AVAILABLE, false)).put("paused", currentlyPaused)
    }

    private fun scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable)
        val delayMs = min(60_000L, 2_000L * (1L shl min(reconnectAttempt, 5)))
        reconnectAttempt++
        updateNotification("Reconnecting in ${delayMs / 1000}s")
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun startAsForeground(): Boolean {
        val notification = buildNotification("Connecting…")
        val type = if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        } catch (_: SecurityException) {
            updateNotification("Foreground service permission/start not allowed")
            stopSelf()
            false
        } catch (_: IllegalStateException) {
            updateNotification("Foreground service cannot start from the current app state")
            stopSelf()
            false
        }
    }

    private fun buildNotification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Tost connection").setContentText(text).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Tost connection", NotificationManager.IMPORTANCE_LOW)) }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopConnectionAndService()
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Service destroyed")
        webSocket = null
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "tost_connection"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val ACTION_STOP = "com.tost.permissionbridge.STOP"
        private const val CHANNEL_ID = "tost_connection"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_POINT_JUMP_METERS = 500.0
        fun start(context: Context) = try {
            ContextCompat.startForegroundService(context, Intent(context, WebSocketService::class.java))
        } catch (_: SecurityException) {
            Unit
        } catch (_: IllegalStateException) {
            Unit
        }
        fun stop(context: Context) = context.startService(Intent(context, WebSocketService::class.java).setAction(ACTION_STOP))
    }
}
