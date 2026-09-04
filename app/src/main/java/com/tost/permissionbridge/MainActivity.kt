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

    private val singlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { renderPermissions() }

    private lateinit var permissionContainer: LinearLayout
    private lateinit var serverUrl: EditText
    private lateinit var token: EditText

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
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
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

        val locationStartButton = Button(this).apply {
            text = "Start location session"
            setOnClickListener { startLocationSession() }
        }

        val locationStopButton = Button(this).apply {
            text = "Stop location session"
            setOnClickListener { LocationService.stop(this@MainActivity) }
        }

        permissionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(serverUrl)
            addView(token)
            addView(connectButton)
            addView(stopButton)
            addView(TextView(this@MainActivity).apply {
                text = "Location session"
                textSize = 20f
                setPadding(0, 24, 0, 8)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Start this user-visible session before remote location reads. Tost will show an ongoing notification while it is active."
                setPadding(0, 0, 0, 8)
            })
            addView(locationStartButton)
            addView(locationStopButton)
            addView(TextView(this@MainActivity).apply {
                text = "Permissions"
                textSize = 20f
                setPadding(0, 24, 0, 8)
            })
            addView(permissionContainer)
        }

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        renderPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (::permissionContainer.isInitialized) renderPermissions()
    }

    private fun startLocationSession() {
        val hasForegroundLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (hasForegroundLocation) {
            LocationService.start(this)
        } else {
            singlePermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    private fun renderPermissions() {
        permissionContainer.removeAllViews()

        val runtime = PermissionManager.runtimeCatalog()
            .filter { Build.VERSION.SDK_INT >= it.minApi }
        val special = PermissionManager.specialAccessCatalog()
            .filter { Build.VERSION.SDK_INT >= it.minApi }

        addSection("Runtime permissions")
        runtime.forEach { entry ->
            addPermissionRow(entry)
        }

        if (Build.VERSION.SDK_INT >= 29) {
            addSection("Background location")
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val foregroundGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

            val actionText = when {
                granted -> "Granted"
                !foregroundGranted -> "Grant location first"
                Build.VERSION.SDK_INT >= 30 -> "Open Settings"
                else -> "Request"
            }

            addActionRow("Background location", actionText) {
                when {
                    granted -> Unit
                    !foregroundGranted -> singlePermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    Build.VERSION.SDK_INT >= 30 -> openAppDetailsSettings()
                    else -> singlePermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
        }

        addSection("Special app access")
        special.forEach { entry ->
            val granted = PermissionManager.isSpecialAccessGranted(this, entry.id)
            addActionRow(entry.description, if (granted) "Granted" else "Open Settings") {
                if (!granted) PermissionManager.specialAccessIntent(this, entry.id)?.let(::startActivity)
            }
        }

        addSection("System-only / restricted")
        permissionContainer.addView(TextView(this).apply {
            text = "Some permissions are signature, privileged, hard-restricted, or Play-policy restricted. Tost will not pretend they are ordinary runtime permissions. They need a qualifying system role, installer allowlist, default-handler role, or feature-specific approval."
            setPadding(0, 0, 0, 12)
        })
    }

    private fun openAppDetailsSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        )
    }

    private fun addSection(title: String) {
        permissionContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setPadding(0, 18, 0, 8)
        })
    }

    private fun addPermissionRow(entry: PermissionEntry) {
        val permission = entry.permission ?: return
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        addActionRow(entry.description, if (granted) "Granted" else "Request") {
            if (!granted) singlePermissionLauncher.launch(permission)
        }
    }

    private fun addActionRow(label: String, actionText: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        val labelView = TextView(this).apply {
            text = label
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val button = Button(this).apply {
            text = actionText
            isEnabled = actionText != "Granted"
            setOnClickListener { action() }
        }
        row.addView(labelView)
        row.addView(button)
        permissionContainer.addView(row)
    }
}
