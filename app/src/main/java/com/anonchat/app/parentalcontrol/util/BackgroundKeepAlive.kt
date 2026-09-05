package com.anonchat.app.parentalcontrol.util

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * BackgroundKeepAlive — guides the user (or installer) to enable the OEM-specific
 * "allow this app to run in the background" setting that is required for the
 * TrackerService foreground service to survive after the user swipes the app
 * away from recents.
 *
 * On Vivo/FuntouchOS: this is "Settings → Battery → Background power consumption"
 *   → "High background power consumption".
 * On Realme/ColorOS:    "Settings → Battery → App battery management"
 *   → toggle "Allow background running" per app, AND
 *   "Phone Manager → Privacy permissions → Autostart".
 * On Xiaomi/MIUI:       "Settings → Apps → Manage apps → [this app] → Autostart".
 * On Samsung/OneUI:     "Settings → Battery → Background usage limits"
 *   → "Never sleeping apps" (only if user toggles it).
 * On stock Android 14:  no Autostart, but the user must not swipe the app from
 *   recents — service is restarted by [com.anonchat.app.parentalcontrol.receiver.BootReceiver]
 *   on every boot.
 *
 * The check is throttled: it shows at most once every 7 days, and only on devices
 * that are likely to be affected. The flag is shared between both flavors via
 * `kidguard_keepalive` SharedPreferences so the chatgpt flavor (Vivo) and the
 * calculator flavor (Realme) keep independent state.
 */
object BackgroundKeepAlive {

    private const val TAG = "BackgroundKeepAlive"
    private const val PREFS_NAME = "kidguard_keepalive"
    private const val KEY_LAST_PROMPT = "last_prompt_ms"
    private const val KEY_DISMISSED_FOR = "dismissed_for_ms"
    private const val PROMPT_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000   // 7 days
    private const val ACTION_MARK_DONE = "com.anonchat.app.MARK_KEEPALIVE_DONE"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * True if the OEM is one of the known-restrictive ones that needs the
     * Autostart / Background power consumption toggle enabled. Stock AOSP
     * and Pixel are excluded.
     */
    fun isRestrictiveOem(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand        = Build.BRAND.lowercase()
        val fingerprint  = Build.FINGERPRINT.lowercase()
        return when {
            manufacturer.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> true
            manufacturer.contains("oppo")  || brand.contains("realme") || brand.contains("oppo")  -> true
            manufacturer.contains("vivo")  || brand.contains("vivo") || brand.contains("iqoo")   -> true
            manufacturer.contains("huawei") || brand.contains("honor") -> true
            manufacturer.contains("samsung") -> true   // OneUI restricted mode
            manufacturer.contains("oneplus") -> true
            manufacturer.contains("letv") || manufacturer.contains("letv") -> true
            manufacturer.contains("meizu") -> true
            manufacturer.contains("nubia") -> true
            fingerprint.contains("miui") || fingerprint.contains("coloros") ||
                fingerprint.contains("funtouch") || fingerprint.contains("emui") ||
                fingerprint.contains("magicui") || fingerprint.contains("oneui") -> true
            else -> false
        }
    }

    /**
     * Returns the OEM-specific Settings Intent that opens the right page to
     * grant the app "run in background" permission. Returns null if no
     * specific intent is known — in that case the generic Battery Optimization
     * settings page is returned.
     */
    fun oemAutostartIntent(ctx: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand        = Build.BRAND.lowercase()
        val component: ComponentName? = when {
            // RealmeUI (ColorOS fork) — "Phone Manager → App Autostart"
            manufacturer.contains("oppo") || brand.contains("realme") -> {
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            }
            // Vivo (FuntouchOS / OriginOS) — "iManager → Background power consumption"
            manufacturer.contains("vivo") || brand.contains("iqoo") -> {
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            }
            // MIUI — "Security center → Autostart"
            manufacturer.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> {
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            // Huawei / Honor — "Phone Manager → App launch"
            manufacturer.contains("huawei") || brand.contains("honor") -> {
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }
            // Samsung OneUI — "Battery → Background usage limits"
            manufacturer.contains("samsung") -> {
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            }
            // OnePlus — "Battery → Battery optimization"
            manufacturer.contains("oneplus") -> {
                ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            }
            else -> null
        }
        if (component != null) {
            val intent = Intent().apply {
                setComponent(component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return if (intent.resolveActivity(ctx.packageManager) != null) intent else null
        }
        return null
    }

    /**
     * Generic fallback: open the system Battery Optimization page where the
     * user can set this app to "Not optimized".
     */
    fun batteryOptimizationIntent(ctx: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun appDetailsIntent(ctx: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${ctx.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * True if we've already shown the prompt within the cooldown window, OR
     * if the user / installer has marked the keep-alive as done (via the
     * [ACTION_MARK_DONE] broadcast).
     */
    fun shouldShowPrompt(ctx: Context): Boolean {
        if (!isRestrictiveOem()) return false
        val p = prefs(ctx)
        val dismissedUntil = p.getLong(KEY_DISMISSED_FOR, 0L)
        if (System.currentTimeMillis() < dismissedUntil) return false
        val lastPrompt = p.getLong(KEY_LAST_PROMPT, 0L)
        return System.currentTimeMillis() - lastPrompt > PROMPT_COOLDOWN_MS
    }

    fun markPromptShown(ctx: Context) {
        prefs(ctx).edit()
            .putLong(KEY_LAST_PROMPT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Permanently mark the keep-alive as configured — called either by the
     * user tapping "Already enabled" in the dialog, or by the ADB trigger:
     *   adb shell am broadcast -a com.anonchat.app.MARK_KEEPALIVE_DONE \
     *       -p com.anonchat.app
     */
    fun markDone(ctx: Context) {
        // Dismiss forever (long enough to outlast any reasonable reinstall cycle).
        prefs(ctx).edit()
            .putLong(KEY_DISMISSED_FOR, System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
            .apply()
        Log.i(TAG, "Keep-alive marked done by ${ctx.packageName}")
    }

    /**
     * Show a non-cancelable dialog explaining why the user needs to enable
     * OEM-specific autostart. The dialog has two buttons:
     *  - "Open Settings" — launches the OEM autostart page (or battery opt
     *    page as a fallback).
     *  - "Already enabled" — marks the prompt as done and dismisses.
     *
     * After dismissal the prompt is throttled to once per 7 days.
     */
    fun showPromptIfNeeded(activity: Activity) {
        if (!shouldShowPrompt(activity)) return
        val oemIntent = oemAutostartIntent(activity)
        val title = "Keep this app running"
        val body  = buildString {
            append("For KidGuard to report location, messages, calls and apps, ")
            append("Android needs permission to keep this app running in the background.\n\n")
            append("On your phone, please enable '")
            append(oemName())
            append("' in the next screen. ")
            append("If you don't see it there, open Settings → Apps → ")
            append(activity.applicationInfo.loadLabel(activity.packageManager))
            append(" → Battery and turn off 'Restricted'.")
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(body)
            .setCancelable(false)
            .setPositiveButton("Open Settings") { d, _ ->
                markPromptShown(activity)
                d.dismiss()
                val intent = oemIntent ?: batteryOptimizationIntent(activity)
                try {
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "OEM intent failed, falling back to app details", e)
                    try {
                        activity.startActivity(appDetailsIntent(activity))
                    } catch (_: Exception) { }
                }
            }
            .setNegativeButton("Already enabled") { d, _ ->
                markDone(activity)
                markPromptShown(activity)
                d.dismiss()
            }
            .show()
    }

    private fun oemName(): String {
        val m = Build.MANUFACTURER.lowercase()
        val b = Build.BRAND.lowercase()
        return when {
            b.contains("realme") || m.contains("oppo") -> "Allow background running"
            b.contains("iqoo")   || m.contains("vivo") -> "High background power consumption"
            b.contains("redmi")  || b.contains("poco") || m.contains("xiaomi") -> "Autostart"
            b.contains("honor")  || m.contains("huawei") -> "App launch"
            m.contains("samsung") -> "Don't put to sleep"
            m.contains("oneplus") -> "Allow background activity"
            else -> "Run in background"
        }
    }
}
