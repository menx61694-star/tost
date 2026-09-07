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
    fun runtimeCatalog(): List<PermissionEntry> = buildList {
        add(PermissionEntry("camera", Manifest.permission.CAMERA, "Camera", 1, true, "Camera access — remote camera snapshots"))
        add(PermissionEntry("location_coarse", Manifest.permission.ACCESS_COARSE_LOCATION, "Location", 1, true, "Approximate location — live route"))
        add(PermissionEntry("location_fine", Manifest.permission.ACCESS_FINE_LOCATION, "Location", 1, true, "Precise location — better route accuracy"))
        add(PermissionEntry("contacts_read", Manifest.permission.READ_CONTACTS, "Contacts", 1, true, "Read contacts — contacts count"))
        add(PermissionEntry("calendar_read", Manifest.permission.READ_CALENDAR, "Calendar", 1, true, "Read calendar — calendar count"))
        add(PermissionEntry("activity", Manifest.permission.ACTIVITY_RECOGNITION, "Activity", 29, true, "Recognize physical activity — step counter"))
        add(PermissionEntry("notifications", Manifest.permission.POST_NOTIFICATIONS, "Notifications", 33, true, "Show foreground-service notifications"))
        add(PermissionEntry("media_images", Manifest.permission.READ_MEDIA_IMAGES, "Media", 33, true, "Read image metadata — storage breakdown"))
        add(PermissionEntry("media_video", Manifest.permission.READ_MEDIA_VIDEO, "Media", 33, true, "Read video metadata — storage breakdown"))
        add(PermissionEntry("media_audio", Manifest.permission.READ_MEDIA_AUDIO, "Media", 33, true, "Read audio metadata — storage breakdown"))
        if (Build.VERSION.SDK_INT < 33) add(PermissionEntry("media_external", Manifest.permission.READ_EXTERNAL_STORAGE, "Media", 1, true, "Read shared media on Android 12 and lower — storage breakdown"))
    }

    fun runtimePermissions(): Array<String> = runtimeCatalog().filter { it.requestable && Build.VERSION.SDK_INT >= it.minApi && it.permission != null }.mapNotNull { it.permission }.toTypedArray()
    fun missingPermissions(context: Context): Array<String> = runtimePermissions().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    fun specialAccessCatalog(): List<PermissionEntry> = emptyList()
    fun isSpecialAccessGranted(context: Context, id: String): Boolean = false
    fun specialAccessIntent(context: Context, id: String): Intent? = null
}
