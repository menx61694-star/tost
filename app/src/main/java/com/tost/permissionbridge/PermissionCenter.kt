package com.tost.permissionbridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/** Single registry for permissions used by currently implemented Tost features. */
object PermissionCenter {
    data class Entry(val key: String, val permission: String, val minApi: Int = 1)

    val runtime: List<Entry> = listOf(
        Entry("Camera", Manifest.permission.CAMERA),
        Entry("Approximate location", Manifest.permission.ACCESS_COARSE_LOCATION),
        Entry("Precise location", Manifest.permission.ACCESS_FINE_LOCATION),
        Entry("Contacts read", Manifest.permission.READ_CONTACTS),
        Entry("Calendar read", Manifest.permission.READ_CALENDAR),
        Entry("Activity recognition", Manifest.permission.ACTIVITY_RECOGNITION, 29),
        Entry("Notifications", Manifest.permission.POST_NOTIFICATIONS, 33),
        Entry("Photos", Manifest.permission.READ_MEDIA_IMAGES, 33),
        Entry("Videos", Manifest.permission.READ_MEDIA_VIDEO, 33),
        Entry("Audio", Manifest.permission.READ_MEDIA_AUDIO, 33)
    )

    fun supportedRuntimePermissions(): Array<String> = runtime.filter { Build.VERSION.SDK_INT >= it.minApi }.map { it.permission }.distinct().toTypedArray()
    fun missingRuntimePermissions(context: Context): Array<String> = supportedRuntimePermissions().filter { ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }.toTypedArray()
    fun specialIntent(context: Context, permission: String): Intent? = null
    fun isSpecialGranted(context: Context, permission: String): Boolean = false
}
