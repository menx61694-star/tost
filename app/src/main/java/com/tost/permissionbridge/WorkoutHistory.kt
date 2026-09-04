package com.tost.permissionbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Stores completed workout summaries and routes in private app storage. */
object WorkoutHistory {
    private const val DIRECTORY = "workouts"
    private const val MAX_WORKOUTS = 20

    fun saveCompleted(context: Context, prefs: android.content.SharedPreferences) {
        val start = prefs.getLong(LocationService.KEY_SESSION_START, 0L)
        if (start <= 0L) return

        val end = System.currentTimeMillis()
        val pausedMs = prefs.getLong(LocationService.KEY_PAUSED_MS, 0L).coerceAtLeast(0L)
        val currentlyPaused = prefs.getBoolean(LocationService.KEY_PAUSED, false)
        val pauseStarted = prefs.getLong(LocationService.KEY_PAUSE_STARTED, 0L)
        val currentPause = if (currentlyPaused && pauseStarted > 0L) {
            (end - pauseStarted).coerceAtLeast(0L)
        } else 0L
        val durationSeconds = ((end - start - pausedMs - currentPause).coerceAtLeast(0L) / 1000L)

        val route = parseRoute(prefs.getString(LocationService.KEY_ROUTE, "[]"))
        val distanceMeters = routeDistance(route)
        val averageSpeedMps = if (durationSeconds > 0) distanceMeters / durationSeconds else 0.0
        val paceSecondsPerKm = if (averageSpeedMps > 0.1) 1000.0 / averageSpeedMps else 0.0
        val steps = prefs.getLong(LocationService.KEY_STEPS, 0L).coerceAtLeast(0L)
        val stepsAvailable = prefs.getBoolean(LocationService.KEY_STEPS_AVAILABLE, false)

        val workout = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("startTime", start)
            .put("endTime", end)
            .put("steps", steps)
            .put("stepsAvailable", stepsAvailable)
            .put("distanceMeters", distanceMeters)
            .put("durationSeconds", durationSeconds)
            .put("averageSpeedMps", averageSpeedMps)
            .put("paceSecondsPerKm", paceSecondsPerKm)
            .put("route", route)

        val dir = File(context.filesDir, DIRECTORY)
        if (!dir.exists() && !dir.mkdirs()) return
        File(dir, "${workout.getString("id")}.json").writeText(workout.toString())
        prune(dir)
    }

    fun list(context: Context): JSONArray {
        val dir = File(context.filesDir, DIRECTORY)
        if (!dir.isDirectory) return JSONArray()
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        val result = JSONArray()
        for (file in files.take(MAX_WORKOUTS)) {
            try {
                val workout = JSONObject(file.readText())
                workout.remove("route")
                result.put(workout)
            } catch (_: Exception) {
                // Ignore one corrupt workout rather than failing the whole history request.
            }
        }
        return result
    }

    fun get(context: Context, id: String): JSONObject? {
        if (id.isBlank() || id.contains('/') || id.contains('\\')) return null
        val file = File(File(context.filesDir, DIRECTORY), "$id.json")
        if (!file.isFile) return null
        return try { JSONObject(file.readText()) } catch (_: Exception) { null }
    }

    private fun prune(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        files.drop(MAX_WORKOUTS).forEach { it.delete() }
    }

    private fun parseRoute(value: String?): JSONArray =
        try { JSONArray(value ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun routeDistance(route: JSONArray): Double {
        var distance = 0.0
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
                val segment = results[0].toDouble()
                if (segment <= 500.0) distance += segment
            }
            previousLat = lat
            previousLon = lon
            hasPrevious = true
        }
        return distance
    }
}
