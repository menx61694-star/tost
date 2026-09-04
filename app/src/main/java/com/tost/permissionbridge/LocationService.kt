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

/**
 * User-started location session. Kept separate from the persistent data-sync
 * WebSocket service because Android applies different foreground-service rules
 * to location access.
 */
class LocationService : Service() {
    private var locationManager: LocationManager? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            publishLocation(location)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLocationSession()
            return START_NOT_STICKY
        }

        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        startLocationUpdates()
        return START_NOT_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, true).apply()
            }
        } catch (_: SecurityException) {
            stopLocationSession()
        }
    }

    private fun publishLocation(location: Location) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_TIME, location.time)
            .putString(KEY_LATITUDE, location.latitude.toString())
            .putString(KEY_LONGITUDE, location.longitude.toString())
            .putFloat(KEY_ACCURACY, location.accuracy)
            .putBoolean(KEY_ACTIVE, true)
            .apply()
    }

    private fun stopLocationSession() {
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: SecurityException) {
            // Permission may have been revoked while the session was running.
        }
        locationManager = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Tost location session")
            .setContentText("Location session is active")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tost location", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: SecurityException) {
            // Ignore cleanup failure.
        }
        locationManager = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "tost_location"
        const val KEY_ACTIVE = "active"
        const val KEY_TIME = "time"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ACCURACY = "accuracy"
        const val ACTION_STOP = "com.tost.permissionbridge.STOP_LOCATION"
        private const val CHANNEL_ID = "tost_location"
        private const val NOTIFICATION_ID = 1002

        fun start(context: android.content.Context) = ContextCompat.startForegroundService(
            context, Intent(context, LocationService::class.java)
        )

        fun stop(context: android.content.Context) = context.startService(
            Intent(context, LocationService::class.java).setAction(ACTION_STOP)
        )
    }
}
