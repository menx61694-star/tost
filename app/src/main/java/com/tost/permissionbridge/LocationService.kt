package com.tost.permissionbridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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

/** User-started location and step session with explicit start, pause/resume, and stop controls. */
class LocationService : Service() {
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var stepCounterSensor: Sensor? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = publishLocation(location)
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER || event.values.isEmpty()) return
            handleStepCounterValue(event.values[0].toDouble())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorManager = getSystemService(SensorManager::class.java)
        stepCounterSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
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
                return START_STICKY
            }
        }

        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        startAsForeground()

        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            // A normal user Start creates a fresh session. There is no active session to recover.
            startNewLocationSession()
        } else if (prefs.getBoolean(KEY_PAUSED, false)) {
            // If Android recreated the service while a session was paused, preserve that state.
            updateNotification("Location session paused")
        } else {
            // Service was recreated while tracking. Restore listeners without resetting the workout.
            startLocationUpdates()
            startStepCounting(prime = false)
        }

        // Keep an active tracking session recoverable if the service process is reclaimed.
        return START_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasActivityRecognitionPermission(): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun startNewLocationSession() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ROUTE, "[]")
            .putBoolean(KEY_ACTIVE, true)
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_SESSION_START, System.currentTimeMillis())
            .putLong(KEY_PAUSED_MS, 0L)
            .putLong(KEY_PAUSE_STARTED, 0L)
            .putLong(KEY_STEPS, 0L)
            .putLong(KEY_STEP_BASELINE, -1L)
            .putBoolean(KEY_STEP_PRIME, true)
            .putBoolean(KEY_STEPS_AVAILABLE, hasActivityRecognitionPermission() && stepCounterSensor != null)
            .apply()
        startLocationUpdates()
        startStepCounting(prime = true)
    }

    private fun resumeLocationSession() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            startAsForeground()
            startNewLocationSession()
            return
        }
        if (!prefs.getBoolean(KEY_PAUSED, false)) {
            startAsForeground()
            startLocationUpdates()
            startStepCounting(prime = false)
            return
        }

        val now = System.currentTimeMillis()
        val pauseStarted = prefs.getLong(KEY_PAUSE_STARTED, 0L)
        val extraPaused = if (pauseStarted > 0L) (now - pauseStarted).coerceAtLeast(0L) else 0L
        prefs.edit()
            .putBoolean(KEY_PAUSED, false)
            .putLong(KEY_PAUSED_MS, prefs.getLong(KEY_PAUSED_MS, 0L) + extraPaused)
            .putLong(KEY_PAUSE_STARTED, 0L)
            .putBoolean(KEY_STEP_PRIME, true)
            .apply()
        startAsForeground()
        startLocationUpdates()
        startStepCounting(prime = true)
    }

    private fun startLocationUpdates() {
        val manager = getSystemService(LocationManager::class.java) ?: return
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: SecurityException) {
            // Permission may have been revoked while the service was being restored.
        }
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

    private fun startStepCounting(prime: Boolean) {
        val manager = sensorManager ?: return
        val sensor = stepCounterSensor ?: return
        if (!hasActivityRecognitionPermission()) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_STEPS_AVAILABLE, true).putBoolean(KEY_STEP_PRIME, prime).apply()
        try {
            manager.unregisterListener(stepListener, sensor)
            manager.registerListener(stepListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        } catch (_: SecurityException) {
            prefs.edit().putBoolean(KEY_STEPS_AVAILABLE, false).apply()
        }
    }

    private fun stopStepCounting() {
        try {
            sensorManager?.unregisterListener(stepListener, stepCounterSensor)
        } catch (_: Exception) {
            // Sensor may already have been released.
        }
    }

    private fun handleStepCounterValue(rawValue: Double) {
        if (!rawValue.isFinite() || rawValue < 0.0) return
        val current = rawValue.toLong()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false) || prefs.getBoolean(KEY_PAUSED, false)) return

        val currentSteps = prefs.getLong(KEY_STEPS, 0L).coerceAtLeast(0L)
        val priming = prefs.getBoolean(KEY_STEP_PRIME, false)
        if (priming || prefs.getLong(KEY_STEP_BASELINE, -1L) < 0L) {
            prefs.edit()
                .putLong(KEY_STEP_BASELINE, current - currentSteps)
                .putBoolean(KEY_STEP_PRIME, false)
                .apply()
            return
        }

        val baseline = prefs.getLong(KEY_STEP_BASELINE, current)
        val sessionSteps = (current - baseline).coerceAtLeast(currentSteps)
        prefs.edit().putLong(KEY_STEPS, sessionSteps).apply()
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
        val validCoordinates = location.latitude.isFinite() && location.longitude.isFinite() &&
            kotlin.math.abs(location.latitude) <= 90 && kotlin.math.abs(location.longitude) <= 180
        val acceptPoint = validCoordinates && (oldLat == null || oldLon == null || distanceMeters <= MAX_ROUTE_JUMP_METERS)
        if (acceptPoint && (oldLat == null || oldLon == null || distanceMeters >= MIN_ROUTE_DISTANCE_METERS)) {
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
        stopStepCounting()
        updateNotification("Location session paused")
    }

    private fun stopLocationSession() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ACTIVE, false)) {
            WorkoutHistory.saveCompleted(this, prefs)
        }
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: SecurityException) {
            // Permission may have been revoked while the session was running.
        }
        locationManager = null
        stopStepCounting()
        prefs.edit()
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
        stopStepCounting()
        // Do not clear session state here. An unexpected service/process destruction must not
        // turn an in-progress workout into a fresh/finished one before Android can recreate it.
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
        const val KEY_STEPS = "steps"
        const val KEY_STEP_BASELINE = "step_baseline"
        const val KEY_STEP_PRIME = "step_prime"
        const val KEY_STEPS_AVAILABLE = "steps_available"
        const val ACTION_STOP = "com.tost.permissionbridge.STOP_LOCATION"
        const val ACTION_PAUSE = "com.tost.permissionbridge.PAUSE_LOCATION"
        const val ACTION_RESUME = "com.tost.permissionbridge.RESUME_LOCATION"
        private const val CHANNEL_ID = "tost_location"
        private const val NOTIFICATION_ID = 1002
        private const val MAX_ROUTE_POINTS = 500
        private const val MIN_ROUTE_DISTANCE_METERS = 5f
        private const val MAX_ROUTE_JUMP_METERS = 500f

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
