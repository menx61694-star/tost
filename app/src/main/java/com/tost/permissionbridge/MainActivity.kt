package com.tost.permissionbridge

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateStatus()
    }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }

        val requestButton = Button(this).apply {
            text = "Request available permissions"
            setOnClickListener {
                val missing = PermissionManager.missingPermissions(this@MainActivity)
                if (missing.isNotEmpty()) permissionLauncher.launch(missing)
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
            "All requestable runtime permissions are granted."
        } else {
            "Missing permissions: ${missing.size}\n\n" + missing.joinToString("\n")
        }
    }
}
