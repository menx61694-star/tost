package com.tost.permissionbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
    private val singlePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        renderPermissions(); updateLocationControls(); updateRemoteAccessControls()
        if (pendingLocationStart) { pendingLocationStart = false; if (granted) startLocationSession() else locationStatus.text = "Permission denied — session not started" }
    }
    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) try { RemoteScreenService.start(this, result.resultCode, result.data!!); window.decorView.postDelayed(::updateRemoteAccessControls, 300) } catch (_: Exception) { screenStatus.text = "Screen sharing could not start" }
        else screenStatus.text = "Screen sharing permission was cancelled"
    }
    private lateinit var permissionContainer: LinearLayout
    private lateinit var serverUrl: EditText
    private lateinit var token: EditText
    private lateinit var connectionStatus: TextView
    private lateinit var locationStatus: TextView
    private lateinit var locationStartButton: Button
    private lateinit var locationPauseButton: Button
    private lateinit var locationStopButton: Button
    private lateinit var cameraStatus: TextView
    private lateinit var cameraButton: Button
    private lateinit var screenStatus: TextView
    private lateinit var screenButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(WebSocketService.PREFS, MODE_PRIVATE)
        serverUrl = EditText(this).apply { hint = "WebSocket URL (e.g. wss://example.com/ws)"; setText(prefs.getString(WebSocketService.KEY_SERVER_URL, "")) }
        token = EditText(this).apply { hint = "Server token"; setText(prefs.getString(WebSocketService.KEY_TOKEN, "")); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        connectionStatus = TextView(this).apply { textSize = 16f; setPadding(0, 8, 0, 8) }
        val connectButton = Button(this).apply { text = "Save & connect to server"; setOnClickListener { prefs.edit().putString(WebSocketService.KEY_SERVER_URL, serverUrl.text.toString().trim()).putString(WebSocketService.KEY_TOKEN, token.text.toString().trim()).apply(); WebSocketService.start(this@MainActivity); window.decorView.postDelayed(::updateConnectionStatus, 150) } }
        val stopButton = Button(this).apply { text = "Disconnect"; setOnClickListener { WebSocketService.stop(this@MainActivity); window.decorView.postDelayed(::updateConnectionStatus, 150) } }
        locationStatus = TextView(this).apply { textSize = 15f; setPadding(0, 4, 0, 8) }
        locationStartButton = Button(this).apply { text = "Start"; setOnClickListener { startLocationSession() } }
        locationPauseButton = Button(this).apply { setOnClickListener { val p = getSharedPreferences(LocationService.PREFS, MODE_PRIVATE); if (p.getBoolean(LocationService.KEY_PAUSED, false)) LocationService.resume(this@MainActivity) else LocationService.pause(this@MainActivity); window.decorView.postDelayed(::updateLocationControls, 150) } }
        locationStopButton = Button(this).apply { text = "Stop"; setOnClickListener { LocationService.stop(this@MainActivity); window.decorView.postDelayed(::updateLocationControls, 150) } }
        cameraStatus = TextView(this).apply { textSize = 15f; setPadding(0, 4, 0, 8) }
        cameraButton = Button(this).apply { text = "Enable remote camera"; setOnClickListener { if (RemoteCameraService.isActive()) RemoteCameraService.stop(this@MainActivity) else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) try { RemoteCameraService.start(this@MainActivity) } catch (_: Exception) { cameraStatus.text = "Camera service could not start" } else singlePermissionLauncher.launch(Manifest.permission.CAMERA); window.decorView.postDelayed(::updateRemoteAccessControls, 300) } }
        screenStatus = TextView(this).apply { textSize = 15f; setPadding(0, 4, 0, 8) }
        screenButton = Button(this).apply { text = "Enable screen sharing"; setOnClickListener { if (RemoteScreenService.isActive()) { RemoteScreenService.stop(this@MainActivity); window.decorView.postDelayed(::updateRemoteAccessControls, 300) } else { val manager = getSystemService(MediaProjectionManager::class.java); screenCaptureLauncher.launch(manager.createScreenCaptureIntent()) } } }
        permissionContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val locationControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(locationStartButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(locationPauseButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(locationStopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        val remoteControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(cameraStatus); addView(cameraButton); addView(screenStatus); addView(screenButton) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24)
            addView(serverUrl); addView(token); addView(connectionStatus); addView(connectButton); addView(stopButton)
            addView(TextView(this@MainActivity).apply { text = "Location & steps session"; textSize = 20f; setPadding(0, 24, 0, 8) }); addView(locationStatus); addView(locationControls)
            addView(TextView(this@MainActivity).apply { text = "Location and step counting are user-started. Pause stops GPS and step updates without ending the session; Resume continues the same workout. Stop ends the session."; setPadding(0, 8, 0, 8) })
            addView(TextView(this@MainActivity).apply { text = "Remote access"; textSize = 20f; setPadding(0, 24, 0, 8) }); addView(remoteControls)
            addView(TextView(this@MainActivity).apply { text = "Camera and screen sharing are explicit user-started modes. Android shows its privacy indicators/notification while they are active. The web dashboard can request snapshots only while the corresponding mode is enabled."; setPadding(0, 8, 0, 8) })
            addView(TextView(this@MainActivity).apply { text = "Permissions used by Tost"; textSize = 20f; setPadding(0, 24, 0, 8) }); addView(permissionContainer)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        renderPermissions(); updateLocationControls(); updateConnectionStatus(); updateRemoteAccessControls()
    }

    override fun onResume() { super.onResume(); if (::permissionContainer.isInitialized) { renderPermissions(); updateLocationControls(); updateConnectionStatus(); updateRemoteAccessControls() } }
    private fun updateConnectionStatus() { if (!::connectionStatus.isInitialized) return; val p = getSharedPreferences(WebSocketService.PREFS, MODE_PRIVATE); connectionStatus.text = "Server connection: ${p.getString(WebSocketService.KEY_CONNECTION_STATUS, WebSocketService.STATUS_DISCONNECTED).orEmpty()}" }
    private fun updateRemoteAccessControls() {
        if (!::cameraStatus.isInitialized) return
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED; val cameraActive = RemoteCameraService.isActive()
        cameraStatus.text = when { !cameraGranted -> "Camera: permission required"; cameraActive -> "Camera: remote access active"; else -> "Camera: off" }; cameraButton.text = if (cameraActive) "Disable remote camera" else if (cameraGranted) "Enable remote camera" else "Grant camera & enable"
        val screenActive = RemoteScreenService.isActive(); screenStatus.text = if (screenActive) "Screen: remote sharing active" else "Screen: off"; screenButton.text = if (screenActive) "Disable screen sharing" else "Enable screen sharing"
    }
    private fun startLocationSession() {
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) { pendingLocationStart = true; singlePermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION); return }
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) { pendingLocationStart = true; singlePermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION); return }
        pendingLocationStart = false; LocationService.start(this); window.decorView.postDelayed(::updateLocationControls, 150)
    }
    private fun updateLocationControls() {
        if (!::locationStatus.isInitialized) return
        val p = getSharedPreferences(LocationService.PREFS, MODE_PRIVATE); val active = p.getBoolean(LocationService.KEY_ACTIVE, false); val paused = p.getBoolean(LocationService.KEY_PAUSED, false); val stepsAvailable = p.getBoolean(LocationService.KEY_STEPS_AVAILABLE, false); val stepText = if (active) if (stepsAvailable) " · step counter available" else " · steps unavailable" else ""
        when { !active -> { locationStatus.text = "Ready — no active session"; locationStartButton.isEnabled = true; locationPauseButton.isEnabled = false; locationPauseButton.text = "Pause"; locationStopButton.isEnabled = false }; paused -> { locationStatus.text = "Paused — route and steps are preserved$stepText"; locationStartButton.isEnabled = false; locationPauseButton.isEnabled = true; locationPauseButton.text = "Resume"; locationStopButton.isEnabled = true }; else -> { locationStatus.text = "Running — GPS and step updates active$stepText"; locationStartButton.isEnabled = false; locationPauseButton.isEnabled = true; locationPauseButton.text = "Pause"; locationStopButton.isEnabled = true } }
    }
    private fun renderPermissions() {
        permissionContainer.removeAllViews(); val runtime = PermissionManager.runtimeCatalog().filter { Build.VERSION.SDK_INT >= it.minApi }
        runtime.forEach(::addPermissionRow)
        permissionContainer.addView(TextView(this).apply { text = "Only permissions used by current features are listed. Camera and screen sharing require explicit user activation; storage categories use Android media indexes."; setPadding(0, 12, 0, 12) })
    }
    private fun addPermissionRow(entry: PermissionEntry) { val permission = entry.permission ?: return; val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED; addActionRow(entry.description, if (granted) "Granted" else "Request") { if (!granted) singlePermissionLauncher.launch(permission) } }
    private fun addActionRow(label: String, actionText: String, action: () -> Unit) { val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 4, 0, 4) }; val labelView = TextView(this).apply { text = label; textSize = 15f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }; val button = Button(this).apply { text = actionText; isEnabled = actionText != "Granted"; setOnClickListener { action() } }; row.addView(labelView); row.addView(button); permissionContainer.addView(row) }
}
