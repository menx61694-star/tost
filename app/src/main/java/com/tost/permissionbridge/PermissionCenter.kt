package com.tost.permissionbridge

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Central permission registry/checker.
 * Runtime permissions are requested through Android's runtime permission API.
 * Special permissions are exposed as Settings actions because Android does not
 * grant them through requestPermissions().
 */
object PermissionCenter {
    data class Entry(
        val key: String,
        val permission: String,
        val minApi: Int = 1,
        val special: Boolean = false
    )

    val runtime: List<Entry> = listOf(
        Entry("Camera", Manifest.permission.CAMERA),
        Entry("Microphone", Manifest.permission.RECORD_AUDIO),
        Entry("Approximate location", Manifest.permission.ACCESS_COARSE_LOCATION),
        Entry("Precise location", Manifest.permission.ACCESS_FINE_LOCATION),
        Entry("Contacts read", Manifest.permission.READ_CONTACTS),
        Entry("Contacts write", Manifest.permission.WRITE_CONTACTS),
        Entry("Calendar read", Manifest.permission.READ_CALENDAR),
        Entry("Calendar write", Manifest.permission.WRITE_CALENDAR),
        Entry("Phone state", Manifest.permission.READ_PHONE_STATE),
        Entry("Direct calls", Manifest.permission.CALL_PHONE),
        Entry("Call log read", Manifest.permission.READ_CALL_LOG),
        Entry("Call log write", Manifest.permission.WRITE_CALL_LOG),
        Entry("SMS read", Manifest.permission.READ_SMS),
        Entry("SMS send", Manifest.permission.SEND_SMS),
        Entry("Activity recognition", Manifest.permission.ACTIVITY_RECOGNITION),
        Entry("Notifications", Manifest.permission.POST_NOTIFICATIONS, 33),
        Entry("Bluetooth scan", Manifest.permission.BLUETOOTH_SCAN, 31),
        Entry("Bluetooth connect", Manifest.permission.BLUETOOTH_CONNECT, 31),
        Entry("Bluetooth advertise", Manifest.permission.BLUETOOTH_ADVERTISE, 31),
        Entry("Photos", Manifest.permission.READ_MEDIA_IMAGES, 33),
        Entry("Videos", Manifest.permission.READ_MEDIA_VIDEO, 33),
        Entry("Audio", Manifest.permission.READ_MEDIA_AUDIO, 33),
        Entry("Body sensors", Manifest.permission.BODY_SENSORS),
        Entry("Body sensors background", Manifest.permission.BODY_SENSORS_BACKGROUND, 33),
        Entry("Background location", Manifest.permission.ACCESS_BACKGROUND_LOCATION, 29)
    )

    /** Special-access capabilities; these cannot be granted by requestPermissions(). */
    val special: List<String> = listOf(
        Manifest.permission.SYSTEM_ALERT_WINDOW,
        Manifest.permission.MANAGE_EXTERNAL_STORAGE,
        Manifest.permission.REQUEST_INSTALL_PACKAGES,
        Manifest.permission.SCHEDULE_EXACT_ALARM,
        Manifest.permission.USE_FULL_SCREEN_INTENT,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    )

    fun supportedRuntimePermissions(): Array<String> = runtime
        .filter { Build.VERSION.SDK_INT >= it.minApi }
        .map { it.permission }
        .distinct()
        .toTypedArray()

    fun missingRuntimePermissions(context: Context): Array<String> = supportedRuntimePermissions()
        .filter { ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        .toTypedArray()

    fun specialIntent(context: Context, permission: String): Intent? = when (permission) {
        Manifest.permission.SYSTEM_ALERT_WINDOW ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        Manifest.permission.MANAGE_EXTERNAL_STORAGE ->
            if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")) else null
        Manifest.permission.REQUEST_INSTALL_PACKAGES ->
            if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")) else null
        Manifest.permission.SCHEDULE_EXACT_ALARM ->
            if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")) else null
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ->
            if (Build.VERSION.SDK_INT >= 23) Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")) else null
        else -> null
    }

    fun isSpecialGranted(context: Context, permission: String): Boolean = when (permission) {
        Manifest.permission.SYSTEM_ALERT_WINDOW -> Settings.canDrawOverlays(context)
        Manifest.permission.MANAGE_EXTERNAL_STORAGE -> Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
        Manifest.permission.REQUEST_INSTALL_PACKAGES -> Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
        Manifest.permission.SCHEDULE_EXACT_ALARM -> Build.VERSION.SDK_INT < 31 ||
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> Build.VERSION.SDK_INT < 23 ||
            (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName)
        else -> false
    }
}
