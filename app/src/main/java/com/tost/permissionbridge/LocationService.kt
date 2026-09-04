package com.tost.permissionbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
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
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        publishLastKnownLocation()
        return START_NOT_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun publishLastKnownLocation() {
        val manager = getSystemService(LocationManager::class.java) ?: return
        val providers = manager.getProviders(true)
        var best: Location? = null

        for (provider in providers) {
            try {
                val location = manager.getLastKnownLocation(provider) ?: continue
                if (best == null || location.time > best!!.time) best = location
            } catch (_: SecurityException) {
                return
            }
        }

        if (best != null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_TIME, best!!.time)
                .putFloat(KEY_LATITUDE, best!!.latitude.toFloat())
                .putFloat(KEY_LONGITUDE, best!!.longitude.toFloat())
                .putFloat(KEY_ACCURACY, best!!.accuracy)
                .apply()
        }
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

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "tost_location"
        const val KEY_TIME = "time"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ACCURACY = "accuracy"
        const val ACTION_STOP = "com.tost.permissionbridge.STOP_LOCATION"
        private const val CHANNEL_ID = "tost_location"
        private const val NOTIFICATION_ID = 1002
    }
}
