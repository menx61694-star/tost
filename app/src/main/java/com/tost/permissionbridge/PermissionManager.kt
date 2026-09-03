package com.tost.permissionbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionManager {
    /** Runtime permissions that this app may request when supported by the device/API level. */
    fun runtimePermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.WRITE_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.WRITE_CALL_LOG)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.READ_MEDIA_AUDIO)
    }.filter { permission ->
        permission != Manifest.permission.POST_NOTIFICATIONS || android.os.Build.VERSION.SDK_INT >= 33
    }.filter { permission ->
        permission !in setOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        ) || android.os.Build.VERSION.SDK_INT >= 31
    }.filter { permission ->
        permission !in setOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        ) || android.os.Build.VERSION.SDK_INT >= 33
    }.toTypedArray()

    fun missingPermissions(context: Context): Array<String> = runtimePermissions()
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()
}
