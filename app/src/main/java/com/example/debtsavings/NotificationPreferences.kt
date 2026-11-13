package com.example.debtsavings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationPreferences {
    const val PREF_ENABLED = "notifications_enabled"

    fun hasRuntimePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(Persistence.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_ENABLED, true) &&
            hasRuntimePermission(context) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
