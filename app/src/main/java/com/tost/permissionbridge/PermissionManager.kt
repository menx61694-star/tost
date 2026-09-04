package com.tost.permissionbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionEntry(
    val id: String,
    val permission: String?,
    val category: String,
    val minApi: Int = 1,
    val requestable: Boolean,
    val description: String
)

object PermissionManager {
    /** Runtime permissions that this bridge can legitimately request from the user. */
    fun runtimeCatalog(): List<PermissionEntry> = buildList {
        add(PermissionEntry("camera", Manifest.permission.CAMERA, "Camera", 1, true, "Camera access"))
        add(PermissionEntry("microphone", Manifest.permission.RECORD_AUDIO, "Microphone", 1, true, "Microphone access"))
        add(PermissionEntry("location_coarse", Manifest.permission.ACCESS_COARSE_LOCATION, "Location", 1, true, "Approximate location"))
        add(PermissionEntry("location_fine", Manifest.permission.ACCESS_FINE_LOCATION, "Location", 1, true, "Precise location"))
        add(PermissionEntry("contacts_read", Manifest.permission.READ_CONTACTS, "Contacts", 1, true, "Read contacts"))
        add(PermissionEntry("contacts_write", Manifest.permission.WRITE_CONTACTS, "Contacts", 1, true, "Modify contacts"))
        add(PermissionEntry("calendar_read", Manifest.permission.READ_CALENDAR, "Calendar", 1, true, "Read calendar"))
        add(PermissionEntry("calendar_write", Manifest.permission.WRITE_CALENDAR, "Calendar", 1, true, "Modify calendar"))
        add(PermissionEntry("phone_state", Manifest.permission.READ_PHONE_STATE, "Phone", 1, true, "Read phone state"))
        add(PermissionEntry("call_phone", Manifest.permission.CALL_PHONE, "Phone", 1, true, "Place calls"))
        add(PermissionEntry("activity", Manifest.permission.ACTIVITY_RECOGNITION, "Activity", 29, true, "Recognize physical activity"))
        add(PermissionEntry("bluetooth_scan", Manifest.permission.BLUETOOTH_SCAN, "Bluetooth", 31, true, "Discover nearby Bluetooth devices"))
        add(PermissionEntry("bluetooth_connect", Manifest.permission.BLUETOOTH_CONNECT, "Bluetooth", 31, true, "Connect to paired Bluetooth devices"))
        add(PermissionEntry("notifications", Manifest.permission.POST_NOTIFICATIONS, "Notifications", 33, true, "Post notifications"))
        add(PermissionEntry("media_images", Manifest.permission.READ_MEDIA_IMAGES, "Media", 33, true, "Read shared images"))
        add(PermissionEntry("media_video", Manifest.permission.READ_MEDIA_VIDEO, "Media", 33, true, "Read shared videos"))
        add(PermissionEntry("media_audio", Manifest.permission.READ_MEDIA_AUDIO, "Media", 33, true, "Read shared audio"))
    }

    /** Runtime permissions that can be passed to Android's permission launcher. */
    fun runtimePermissions(): Array<String> = runtimeCatalog()
        .filter { it.requestable && Build.VERSION.SDK_INT >= it.minApi && it.permission != null }
        .mapNotNull { it.permission }
        .toTypedArray()

    fun missingPermissions(context: Context): Array<String> = runtimePermissions()
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()

    /** Permissions that are intentionally not included in the ordinary runtime request flow. */
    fun restrictedCatalog(): List<PermissionEntry> = buildList {
        add(PermissionEntry("read_sms", Manifest.permission.READ_SMS, "Restricted", 1, false, "Read SMS messages — restricted / policy controlled"))
        add(PermissionEntry("send_sms", Manifest.permission.SEND_SMS, "Restricted", 1, false, "Send SMS messages — restricted / policy controlled"))
        add(PermissionEntry("read_call_log", Manifest.permission.READ_CALL_LOG, "Restricted", 1, false, "Read call log — restricted / policy controlled"))
        add(PermissionEntry("write_call_log", Manifest.permission.WRITE_CALL_LOG, "Restricted", 1, false, "Write call log — restricted / policy controlled"))
    }

    /**
     * Permissions that need a Settings-based special-access flow rather than a runtime dialog.
     * These are exposed as a catalog only; the app does not silently open Settings.
     */
    fun specialAccessCatalog(): List<PermissionEntry> = buildList {
        add(PermissionEntry("overlay", Manifest.permission.SYSTEM_ALERT_WINDOW, "Special access", 23, false, "Display over other apps"))
        add(PermissionEntry("exact_alarm", Manifest.permission.SCHEDULE_EXACT_ALARM, "Special access", 31, false, "Schedule exact alarms"))
        add(PermissionEntry("ignore_battery_optimizations", Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "Special access", 23, false, "Request exemption from battery optimizations"))
        if (Build.VERSION.SDK_INT >= 30) {
            add(PermissionEntry("manage_external_storage", Manifest.permission.MANAGE_EXTERNAL_STORAGE, "Special access", 30, false, "Broad shared-storage access"))
        }
    }

    fun isSpecialAccessGranted(context: Context, id: String): Boolean = when (id) {
        "overlay" -> Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
        "exact_alarm" -> Build.VERSION.SDK_INT < 31 || context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()
        "ignore_battery_optimizations" -> Build.VERSION.SDK_INT < 23 || context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        "manage_external_storage" -> Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
        else -> false
    }

    fun specialAccessIntent(context: Context, id: String): Intent? = when (id) {
        "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = android.net.Uri.parse("package:${context.packageName}") }
        "exact_alarm" -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = android.net.Uri.parse("package:${context.packageName}") }
        "ignore_battery_optimizations" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = android.net.Uri.parse("package:${context.packageName}") }
        "manage_external_storage" -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = android.net.Uri.parse("package:${context.packageName}") }
        else -> null
    }
}
