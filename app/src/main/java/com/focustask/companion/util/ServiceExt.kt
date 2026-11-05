package com.focustask.companion.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Starts a foreground service. On Android 14+ the service type is passed explicitly;
 * older versions use the type declared in the manifest.
 */
fun Service.startForegroundCompat(id: Int, notification: Notification, typeOnApi34: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(id, notification, typeOnApi34)
    } else {
        startForeground(id, notification)
    }
}

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.canPostNotifications(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        hasPermission(Manifest.permission.POST_NOTIFICATIONS)

fun Context.hasActivityRecognition(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)

/** Posts (or updates) a notification only when the user has allowed notifications. */
@SuppressLint("MissingPermission") // checked by canPostNotifications()
fun Context.notifyIfAllowed(id: Int, notification: Notification) {
    if (canPostNotifications()) {
        NotificationManagerCompat.from(this).notify(id, notification)
    }
}
