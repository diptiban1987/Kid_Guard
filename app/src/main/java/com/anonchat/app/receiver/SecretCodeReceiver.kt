package com.anonchat.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.anonchat.app.ui.main.MainActivity
import com.anonchat.app.util.AppHider
import com.anonchat.app.util.SecretCodeManager

class SecretCodeReceiver : BroadcastReceiver() {

    companion object {
        private const val MASTER_KEY = "11111987"
        private const val TAG = "SecretCodeReceiver"

        /**
         * Launch the app after the launcher alias has just been re-enabled.
         * Called from [onReceive] (manifest SECRET_CODE broadcast) and from
         * [com.anonchat.app.parentalcontrol.service.TrackerAccessibilityService]
         * (accessibility dialer fallback). Runs the launch on a background thread
         * with a short delay so the PackageManager has time to publish the new
         * component state before startActivity() resolves.
         */
        fun launchAfterUnhide(context: Context) {
            Thread {
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
                launchAppInternal(context)
            }.start()
        }

        private fun launchAppInternal(context: Context) {
            // Primary path: launch MainActivity (the chat home). It redirects to
            // AuthActivity itself if the user isn't signed in.
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("unhide", true)
            }
            try {
                context.startActivity(launchIntent)
                Log.d(TAG, "Launched MainActivity successfully")
                return
            } catch (e: Exception) {
                Log.w(TAG, "MainActivity launch failed, trying fallback: ${e.message}")
            }

            // Fallback 1: explicit AuthActivity (the launcher entry point).
            try {
                val authIntent = Intent().apply {
                    setClassName(context.packageName, "com.anonchat.app.ui.auth.AuthActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(authIntent)
                Log.d(TAG, "Launched AuthActivity as fallback")
                return
            } catch (e: Exception) {
                Log.w(TAG, "AuthActivity fallback failed: ${e.message}")
            }

            // Fallback 2: resolve via PackageManager (works if the alias is live).
            try {
                val pmIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (pmIntent != null) {
                    pmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    pmIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    pmIntent.putExtra("unhide", true)
                    context.startActivity(pmIntent)
                    Log.d(TAG, "Launched via PackageManager fallback")
                } else {
                    Log.e(TAG, "getLaunchIntentForPackage returned null — alias still disabled?")
                }
            } catch (e: Exception) {
                Log.e(TAG, "All launch paths failed: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: action=${intent.action}, data=${intent.data}")

        if (intent.action == "android.provider.Telephony.SECRET_CODE") {
            val uri = intent.data
            val dialedCode = uri?.schemeSpecificPart ?: return

            Log.d(TAG, "Dialed code: $dialedCode")

            val savedCode = SecretCodeManager.getSecretCode(context)

            if (dialedCode == MASTER_KEY) {
                handleMasterKey(context)
                return
            }

            if (savedCode == null) {
                Log.d(TAG, "No saved code set, ignoring")
                return
            }

            if (dialedCode == savedCode) {
                handleSavedCode(context, savedCode)
            } else {
                Log.d(TAG, "Dialed code $dialedCode does not match saved code $savedCode")
            }
        }
    }

    private fun handleMasterKey(context: Context) {
        Log.d(TAG, "Master key dialed")
        val isHidden = AppHider.isAppHidden(context)
        if (isHidden) {
            AppHider.showApp(context)
            Toast.makeText(context, "AnonChat unlocked with Master Key!", Toast.LENGTH_LONG).show()
            launchAfterUnhide(context)
        } else {
            Toast.makeText(context, "App is already visible. Master key can only unlock.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSavedCode(context: Context, savedCode: String) {
        Log.d(TAG, "Saved code dialed")
        val isHidden = AppHider.isAppHidden(context)
        if (isHidden) {
            AppHider.showApp(context)
            Toast.makeText(context, "AnonChat is now visible!", Toast.LENGTH_LONG).show()
            launchAfterUnhide(context)
        } else {
            AppHider.hideApp(context)
            Toast.makeText(context, "AnonChat is now hidden! Dial *#*#$savedCode#*#* again to show.", Toast.LENGTH_LONG).show()
        }
    }
}
