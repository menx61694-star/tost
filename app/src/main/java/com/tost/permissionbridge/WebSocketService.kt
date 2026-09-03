package com.tost.permissionbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min

class WebSocketService : Service() {

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectAttempt = 0
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            webSocket?.close(1000, "Stopped by user")
            stopSelf()
            return START_NOT_STICKY
        }

        stopping = false
        startAsForeground()
        connect()
        return START_STICKY
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

        val request = Request.Builder()
            .url(endpoint)
            .addQueryParameter("token", token)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(socket: WebSocket, response: Response) {
                reconnectAttempt = 0
                updateNotification("Connected")

                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    ?: "unknown-device"
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

            override fun onMessage(socket: WebSocket, text: String) {
                handleMessage(socket, text)
            }

            override fun onClosing(socket: WebSocket, code: Int, reason: String) {
                socket.close(code, reason)
            }

            override fun onClosed(socket: WebSocket, code: Int, reason: String) {
                webSocket = null
                if (!stopping) scheduleReconnect()
            }

            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
                webSocket = null
                updateNotification("Connection lost; reconnecting")
                if (!stopping) scheduleReconnect()
            }
        })
    }

    private fun handleMessage(socket: WebSocket, text: String) {
        val message = try { JSONObject(text) } catch (_: Exception) { return }
        if (message.optString("type") != "command") return

        val id = message.optString("id")
        when (message.optString("command")) {
            "get_status" -> {
                socket.send(JSONObject().apply {
                    put("type", "command_result")
                    put("id", id)
                    put("ok", true)
                    put("status", "online")
                }.toString())
            }
            "get_permissions" -> {
                val granted = PermissionManager.runtimePermissions().filter {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        it
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                socket.send(JSONObject().apply {
                    put("type", "command_result")
                    put("id", id)
                    put("ok", true)
                    put("grantedPermissions", granted.joinToString(","))
                }.toString())
            }
            else -> {
                socket.send(JSONObject().apply {
                    put("type", "command_result")
                    put("id", id)
                    put("ok", false)
                    put("error", "Unsupported command")
                }.toString())
            }
        }
    }

    private fun scheduleReconnect() {
        val delayMs = min(60_000L, 2_000L * (1L shl min(reconnectAttempt, 5)))
        reconnectAttempt++
        updateNotification("Reconnecting in ${delayMs / 1000}s")
        android.os.Handler(mainLooper).postDelayed({
            if (!stopping) connect()
        }, delayMs)
    }

    private fun startAsForeground() {
        val notification = buildNotification("Connecting…")
        val serviceType = if (Build.VERSION.SDK_INT >= 29) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Tost connection")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tost connection",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onTimeout(startId: Int) {
        // Android 15+ can call this for dataSync foreground-service time limits.
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
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

        fun start(context: Context) {
            val intent = Intent(context, WebSocketService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WebSocketService::class.java).setAction(ACTION_STOP))
        }
    }
}
