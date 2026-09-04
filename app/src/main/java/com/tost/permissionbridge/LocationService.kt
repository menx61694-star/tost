package com.tost.permissionbridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/** User-started location session with explicit start, pause/resume, and stop controls. */
class LocationService : Service() {
    private var locationManager: LocationManager? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = publishLocation(location)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLocationSession()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseLocationSession()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                if (!hasLocationPermission()) {
                    stopLocationSession()
                    return START_NOT_STICKY
                }
                resumeLocationSession()
                return START_NOT_STICKY
            }
        }

        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        startNewLocationSession()
        return START_NOT_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startNewLocationSession() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ROUTE, "[]")
            .putBoolean(KEY_ACTIVE, true)
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_SESSION_START, System.currentTimeMillis())
            .putLong(KEY_PAUSED_MS, 0L)
            .putLong(KEY_PAUSE_STARTED, 0L)
            .apply()
        startLocationUpdates()
    }

    private fun resumeLocationSession() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            startAsForeground()
            startNewLocationSession()
            return
        }
        if (!prefs.getBoolean(KEY_PAUSED, false)) return

        val now = System.currentTimeMillis()
        val pauseStarted = prefs.getLong(KEY_PAUSE_STARTED, 0L)
        val extraPaused = if (pauseStarted > 0L) (now - pauseStarted).coerceAtLeast(0L) else 0L
        prefs.edit()
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_PAUSED_MS, prefs.getLong(KEY_PAUSED_MS, 0L) + extraPaused)
            .putLong(KEY_PAUSE_STARTED, 0L)
            .apply()
        startAsForeground()
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        val manager = getSystemService(LocationManager::class.java) ?: return
        locationManager = manager
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val criteria = Criteria().apply {
            accuracy = if (fine) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE
            powerRequirement = Criteria.POWER_LOW
        }

        try {
            val provider = manager.getBestProvider(criteria, true)
            if (provider != null) {
                manager.requestLocationUpdates(provider, 5_000L, 0f, locationListener, mainLooper)
                manager.getLastKnownLocation(provider)?.let(::publishLocation)
            } else {
                stopLocationSession()
            }
        } catch (_: SecurityException) {
            stopLocationSession()
        }
    }

    private fun publishLocation(location: Location) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false) || prefs.getBoolean(KEY_PAUSED, false)) return

        val oldLat = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull()
        val oldLon = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull()
        val distanceMeters = if (oldLat != null && oldLon != null) {
            val results = FloatArray(1)
            Location.distanceBetween(oldLat, oldLon, location.latitude, location.longitude, results)
            results[0]
        } else Float.MAX_VALUE

        val route = try { JSONArray(prefs.getString(KEY_ROUTE, "[]")) } catch (_: Exception) { JSONArray() }
        if (oldLat == null || oldLon == null || distanceMeters >= MIN_ROUTE_DISTANCE_METERS) {
            route.put(JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("timestamp", location.time)
                put("accuracyMeters", location.accuracy)
            })
            while (route.length() > MAX_ROUTE_POINTS) route.remove(0)
        }

        prefs.edit()
            .putLong(KEY_TIME, location.time)
            .putString(KEY_LATITUDE, location.latitude.toString())
            .putString(KEY_LONGITUDE, location.longitude.toString())
            .putFloat(KEY_ACCURACY, location.accuracy)
            .putString(KEY_ROUTE, route.toString())
            .apply()
    }

    private fun pauseLocationSession() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false) || prefs.getBoolean(KEY_PAUSED, false)) return
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: SecurityException) {
            // Permission may have been revoked while the session was running.
        }
        locationManager = null
        prefs.edit()
            .putBoolean(KEY_PAUSED, true)
            .putLong(KEY_PAUSE_STARTED, System.currentTimeMillis())
            .apply()
        updateNotification("Location session paused")
    }

    private fun stopLocationSession() {
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: SecurityException) {
            // Permission may have been revoked while the session was running.
        }
        locationManager = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_PAUSE_STARTED, 0L)
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground() {
        val notification = buildNotification("Location session is active")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Tost location session")
        .setContentText(text)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tost location", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        try { locationManager?.removeUpdates(locationListener) } catch (_: SecurityException) { }
        locationManager = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, false)
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_PAUSE_STARTED, 0L)
            .apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "tost_location"
        const val KEY_ACTIVE = "active"
        const val KEY_PAUSED = "paused"
        const val KEY_TIME = "time"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_ROUTE = "route"
        const val KEY_SESSION_START = "session_start"
        const val KEY_PAUSED_MS = "paused_ms"
        const val KEY_PAUSE_STARTED = "pause_started"
        const val ACTION_STOP = "com.tost.permissionbridge.STOP_LOCATION"
        const val ACTION_PAUSE = "com.tost.permissionbridge.PAUSE_LOCATION"
        const val ACTION_RESUME = "com.tost.permissionbridge.RESUME_LOCATION"
        private const val CHANNEL_ID = "tost_location"
        private const val NOTIFICATION_ID = 1002
        private const val MAX_ROUTE_POINTS = 500
        private const val MIN_ROUTE_DISTANCE_METERS = 5f

        fun start(context: android.content.Context) = ContextCompat.startForegroundService(
            context, Intent(context, LocationService::class.java)
        )

        fun pause(context: android.content.Context) = context.startService(
            Intent(context, LocationService::class.java).setAction(ACTION_PAUSE)
        )

        fun resume(context: android.content.Context) = ContextCompat.startForegroundService(
            context, Intent(context, LocationService::class.java).setAction(ACTION_RESUME)
        )

        fun stop(context: android.content.Context) = context.startService(
            Intent(context, LocationService::class.java).setAction(ACTION_STOP)
        )
    }
}
