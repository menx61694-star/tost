package com.tost.permissionbridge

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateStatus() }

    private lateinit var status: TextView
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

        status = TextView(this).apply {
            textSize = 16f
            setPadding(32, 32, 32, 20)
        }

        val requestButton = Button(this).apply {
            text = "Request available permissions"
            setOnClickListener {
                val missing = PermissionManager.missingPermissions(this@MainActivity)
                if (missing.isNotEmpty()) permissionLauncher.launch(missing)
            }
        }

        val connectButton = Button(this).apply {
            text = "Save & connect to server"
            setOnClickListener {
                prefs.edit()
                    .putString(WebSocketService.KEY_SERVER_URL, serverUrl.text.toString().trim())
                    .putString(WebSocketService.KEY_TOKEN, token.text.toString().trim())
                    .apply()
                WebSocketService.start(this@MainActivity)
                updateStatus()
            }
        }

        val stopButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener { WebSocketService.stop(this@MainActivity) }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(serverUrl)
            addView(token)
            addView(connectButton)
            addView(stopButton)
            addView(status)
            addView(requestButton)
        }

        setContentView(layout)
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) updateStatus()
    }

    private fun updateStatus() {
        val missing = PermissionManager.missingPermissions(this)
        status.text = if (missing.isEmpty()) {
            "All currently requestable runtime permissions are granted."
        } else {
            "Missing permissions: ${missing.size}\n\n" + missing.joinToString("\n")
        }
    }
}
