package com.anonchat.app.util

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat

object AppHider {

    private const val LAUNCHER_ALIAS = "com.anonchat.app.ui.calculator.CalculatorActivity"
    private const val NOTIFICATION_CHANNEL_ID = "app_hide_channel"
    const val UNHIDE_ACTION = "com.anonchat.app.UNHIDE"
    const val NOTIFICATION_ID = 1001

    /**
     * Locks the app back to the Calculator disguise.
     * The launcher icon remains ENABLED as the Calculator app so the OS never shows
     * "Uninstall/Force Stop" or disables the component.
     */
    fun hideApp(context: Context) {
        val componentName = ComponentName(context.packageName, LAUNCHER_ALIAS)

        SecretCodeManager.setAppHidden(context, true)

        try {
            context.packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            android.util.Log.e("AppHider", "Failed to set component state", e)
        }

        // Return immediately to CalculatorActivity (the disguise interface)
        try {
            val calcIntent = Intent(context, com.anonchat.app.ui.calculator.CalculatorActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(calcIntent)
        } catch (e: Exception) {
            android.util.Log.e("AppHider", "Failed to launch CalculatorActivity", e)
        }

        if (context is Activity) {
            try {
                context.finishAffinity()
            } catch (_: Exception) { }
        }
    }

    fun showApp(context: Context) {
        val componentName = ComponentName(context.packageName, LAUNCHER_ALIAS)

        try {
            context.packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            android.util.Log.e("AppHider", "Failed to enable launcher alias", e)
        }

        SecretCodeManager.setAppHidden(context, false)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun isAppHidden(context: Context): Boolean {
        return SecretCodeManager.isAppHidden(context)
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AnonChat Hidden App",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when AnonChat is in disguise mode"
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
