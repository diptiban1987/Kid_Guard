package com.anonchat.app.parentalcontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.anonchat.app.parentalcontrol.keepalive.KeepAliveScheduler

/**
 * BootReceiver — re-arms the full keep-alive chain when the device
 * boots, when the app is updated (in place), and on screen-on /
 * user-unlock events. Together with [AlarmReceiver] and
 * [com.anonchat.app.parentalcontrol.work.HeartbeatWorker] this forms
 * a layered restart chain so the parental control service survives:
 *
 *  - Phone reboot.
 *  - App auto-update (e.g. triggered by UpdateManager).
 *  - User swiping the app from recents.
 *  - OEM aggressively killing the FGS.
 *  - App process being recycled by Android.
 *
 * The receiver is intentionally idempotent: every action simply calls
 * [KeepAliveScheduler.scheduleAll] which is a no-op if the chain is
 * already armed.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (RESTART_ACTIONS.contains(action)) {
            Log.d(TAG, "BootReceiver fired for $action — re-arming keep-alive chain")
            try {
                KeepAliveScheduler.scheduleAll(context)
            } catch (e: Exception) {
                Log.e(TAG, "KeepAliveScheduler.scheduleAll failed for $action", e)
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"

        /**
         * All actions that should re-arm the keep-alive chain. Kept here
         * (and not in the manifest) so the manifest remains the single
         * source of truth for what the OS may deliver to this receiver.
         */
        private val RESTART_ACTIONS = setOf(
            // Device / user unlocked boot completed.
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_REBOOT,

            // HTC and old OEM "fast boot" / "quick boot" variants.
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",

            // App was just updated in place (e.g. by UpdateManager or
            // the system installer). The OS delivers MY_PACKAGE_REPLACED
            // even if the app is not currently running.
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.PACKAGE_REPLACED",

            // User-present / screen-on is a low-cost re-arm point. The
            // OS already wakes the device, so this is free.
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON
        )
    }
}
