package com.anonchat.app.parentalcontrol.work

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helper to mitigate OEM aggressive battery killing (IQOO FuntouchOS,
 * RealmeUI, MIUI) which would otherwise force-stop TrackerService and
 * stop the periodic report loop.
 *
 * Steps performed:
 *  1. Request exemption from doze/battery optimisations for our package
 *     (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS already declared in manifest).
 *  2. On Android 11+, ask the user to whitelist the app via the OEM's
 *     "auto-start" / "background activity" screen. We try a few known
 *     component names that cover the most common ROMs.
 */
object BatteryOptimizationHelper {

    private val OEM_AUTO_START_COMPONENTS = listOf(
        // IQOO / Vivo
        ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
        ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        ),
        // Realme / Oppo (ColorOS)
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity"
        ),
        ComponentName(
            "com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity"
        ),
        // Xiaomi (MIUI)
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        // Huawei
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        // Samsung (Device care)
        ComponentName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        )
    )

    /**
     * Returns true when the app is already exempt from battery optimisations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Build an Intent that asks the system to whitelist our app from doze.
     * Caller is responsible for Activity.startActivityForResult.
     */
    fun buildIgnoreBatteryOptimizationsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        if (isIgnoringBatteryOptimizations(context)) return null
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    /**
     * Try to find the OEM-specific auto-start / background-runner screen.
     * Returns null if we cannot resolve one on this device.
     */
    fun buildAutoStartIntent(context: Context): Intent? {
        for (cn in OEM_AUTO_START_COMPONENTS) {
            val intent = Intent().apply {
                component = cn
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val resolved = intent.resolveActivity(context.packageManager)
            if (resolved != null) return intent
        }
        return null
    }
}
