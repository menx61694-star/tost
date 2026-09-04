package com.tost.permissionbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private var pendingLocationStart = false

    private val singlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        renderPermissions()
        updateLocationControls()

        if (pendingLocationStart) {
            pendingLocationStart = false
            if (granted) startLocationSession()
            else locationStatus.text = "Permission denied — session not started"
        }
    }

    private lateinit var permissionContainer: LinearLayout
    private lateinit var serverUrl: EditText
    private lateinit var token: EditText
    private lateinit var locationStatus: TextView
    private lateinit var locationStartButton: Button
    private lateinit var locationPauseButton: Button
    private lateinit var locationStopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(WebSocketService.PREFS, MODE_PRIVATE)
        serverUrl = EditText(this).apply {
            hint = "WebSocket URL (e.g. wss://example.com/ws)"
            setText(prefs.getString(WebSocketService.KEY_SERVER_URL, ""))
        }
        token = EditText(this).apply {
            hint = "Server token"
            setText(prefs.getString(WebSocketService.KEY_TOKEN, ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val connectButton = Button(this).apply {
            text = "Save & connect to server"
            setOnClickListener {
                prefs.edit()
                    .putString(WebSocketService.KEY_SERVER_URL, serverUrl.text.toString().trim())
                    .putString(WebSocketService.KEY_TOKEN, token.text.toString().trim())
                    .apply()
                WebSocketService.start(this@MainActivity)
            }
        }
        val stopButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { WebSocketService.stop(this@MainActivity) }
        }

        locationStatus = TextView(this).apply { textSize = 15f; setPadding(0, 4, 0, 8) }
        locationStartButton = Button(this).apply { text = "Start"; setOnClickListener { startLocationSession() } }
        locationPauseButton = Button(this).apply {
            setOnClickListener {
                val locationPrefs = getSharedPreferences(LocationService.PREFS, MODE_PRIVATE)
                if (locationPrefs.getBoolean(LocationService.KEY_PAUSED, false)) LocationService.resume(this@MainActivity)
                else LocationService.pause(this@MainActivity)
                window.decorView.postDelayed(::updateLocationControls, 150)
            }
        }
        locationStopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                LocationService.stop(this@MainActivity)
                window.decorView.postDelayed(::updateLocationControls, 150)
            }
        }

        permissionContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val locationControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(locationStartButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(locationPauseButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(locationStopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(serverUrl); addView(token); addView(connectButton); addView(stopButton)
            addView(TextView(this@MainActivity).apply { text = "Location & steps session"; textSize = 20f; setPadding(0, 24, 0, 8) })
            addView(locationStatus); addView(locationControls)
            addView(TextView(this@MainActivity).apply {
                text = "Location and step counting are user-started. Pause stops GPS and step updates without ending the session; Resume continues the same workout. Stop ends the session. Step counting requires Activity recognition on Android 10+ and a device step-counter sensor."
                setPadding(0, 8, 0, 8)
            })
            addView(TextView(this@MainActivity).apply { text = "Permissions"; textSize = 20f; setPadding(0, 24, 0, 8) })
            addView(permissionContainer)
        }

        setContentView(ScrollView(this).apply { addView(root) })
        renderPermissions(); updateLocationControls()
    }

    override fun onResume() {
        super.onResume()
        if (::permissionContainer.isInitialized) { renderPermissions(); updateLocationControls() }
    }

    private fun startLocationSession() {
        val hasForegroundLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasForegroundLocation) {
            pendingLocationStart = true
            singlePermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            return
        }
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            pendingLocationStart = true
            singlePermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            return
        }
        pendingLocationStart = false
        LocationService.start(this)
        window.decorView.postDelayed(::updateLocationControls, 150)
    }

    private fun updateLocationControls() {
        if (!::locationStatus.isInitialized) return
        val prefs = getSharedPreferences(LocationService.PREFS, MODE_PRIVATE)
        val active = prefs.getBoolean(LocationService.KEY_ACTIVE, false)
        val paused = prefs.getBoolean(LocationService.KEY_PAUSED, false)
        val stepsAvailable = prefs.getBoolean(LocationService.KEY_STEPS_AVAILABLE, false)
        val stepText = if (active) if (stepsAvailable) " · step counter available" else " · steps unavailable" else ""
        when {
            !active -> { locationStatus.text = "Ready — no active session"; locationStartButton.isEnabled = true; locationPauseButton.isEnabled = false; locationPauseButton.text = "Pause"; locationStopButton.isEnabled = false }
            paused -> { locationStatus.text = "Paused — route and steps are preserved$stepText"; locationStartButton.isEnabled = false; locationPauseButton.isEnabled = true; locationPauseButton.text = "Resume"; locationStopButton.isEnabled = true }
            else -> { locationStatus.text = "Running — GPS and step updates active$stepText"; locationStartButton.isEnabled = false; locationPauseButton.isEnabled = true; locationPauseButton.text = "Pause"; locationStopButton.isEnabled = true }
        }
    }

    private fun renderPermissions() {
        permissionContainer.removeAllViews()
        val runtime = PermissionManager.runtimeCatalog().filter { Build.VERSION.SDK_INT >= it.minApi }
        val special = PermissionManager.specialAccessCatalog().filter { Build.VERSION.SDK_INT >= it.minApi }
        val restricted = PermissionManager.restrictedCatalog().filter { Build.VERSION.SDK_INT >= it.minApi }

        addSection("Runtime permissions")
        runtime.forEach(::addPermissionRow)

        if (Build.VERSION.SDK_INT >= 29) {
            addSection("Background location")
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            val foregroundGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val actionText = when { granted -> "Granted"; !foregroundGranted -> "Grant location first"; Build.VERSION.SDK_INT >= 30 -> "Open Settings"; else -> "Request" }
            addActionRow("Background location", actionText) {
                when { granted -> Unit; !foregroundGranted -> singlePermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION); Build.VERSION.SDK_INT >= 30 -> openAppDetailsSettings(); else -> singlePermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
            }
        }

        addSection("Special app access")
        special.forEach { entry ->
            val granted = PermissionManager.isSpecialAccessGranted(this, entry.id)
            addActionRow(entry.description, if (granted) "Granted" else "Open Settings") {
                if (!granted) PermissionManager.specialAccessIntent(this, entry.id)?.let(::startActivity)
            }
        }

        addSection("Restricted / policy-controlled")
        restricted.forEach { entry ->
            val granted = entry.permission?.let { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED } == true
            addActionRow(entry.description, if (granted) "Granted" else "Not requestable") { }
        }
        permissionContainer.addView(TextView(this).apply {
            text = "SMS and call-log access is shown for transparency but is not exposed as an ordinary request button. Availability depends on Android restrictions, app role/default-handler requirements, and Google Play policy."
            setPadding(0, 0, 0, 12)
        })
    }

    private fun openAppDetailsSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.parse("package:$packageName") })
    }
    private fun addSection(title: String) {
        permissionContainer.addView(TextView(this).apply { text = title; textSize = 18f; setPadding(0, 18, 0, 8) })
    }
    private fun addPermissionRow(entry: PermissionEntry) {
        val permission = entry.permission ?: return
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        addActionRow(entry.description, if (granted) "Granted" else "Request") { if (!granted) singlePermissionLauncher.launch(permission) }
    }
    private fun addActionRow(label: String, actionText: String, action: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 4) }
        val labelView = TextView(this).apply { text = label; textSize = 15f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val button = Button(this).apply { text = actionText; isEnabled = actionText != "Granted" && actionText != "Not requestable"; setOnClickListener { action() } }
        row.addView(labelView); row.addView(button); permissionContainer.addView(row)
    }
}
