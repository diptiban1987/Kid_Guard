package com.anonchat.app.parentalcontrol.keepalive

import android.content.Context
import android.util.Log
import com.anonchat.app.parentalcontrol.api.CloudConfig
import com.anonchat.app.parentalcontrol.receiver.AlarmReceiver
import com.anonchat.app.parentalcontrol.service.TrackerService
import com.anonchat.app.parentalcontrol.work.HeartbeatWorker

/**
 * KeepAliveScheduler — single entry point that (re-)arms every
 * self-restart mechanism we have, in priority order:
 *
 * 1. [TrackerService] foreground service  — primary, every 15 s while running.
 * 2. [AlarmReceiver] exact alarm          — self-perpetuating every 2 min.
 * 3. [HeartbeatWorker]                    — WorkManager backup every 15 min.
 *
 * It is called from:
 *  - [com.anonchat.app.AnonChatApp.onCreate]        (process start)
 *  - [com.anonchat.app.parentalcontrol.receiver.BootReceiver] (boot / package replaced)
 *  - [com.anonchat.app.parentalcontrol.receiver.AlarmReceiver] (every 2 min)
 *  - [com.anonchat.app.parentalcontrol.work.HeartbeatWorker] (every 15 min)
 *  - [com.anonchat.app.parentalcontrol.receiver.SetupReceiver] KEEPALIVE_RESET action
 *
 * Crucially, the alarm and WorkManager are scheduled even when the FGS
 * cannot start (e.g. Android 14 user-stopped state on RealmeUI). When the
 * user next opens the app, [TrackerService.start] succeeds and the FGS
 * picks up reporting again — meanwhile AlarmReceiver keeps the device
 * visible as ONLINE via its own direct keep-alive report.
 *
 * The scheduler is **idempotent**: calling it twice in a row is safe
 * because each step uses existing-policy / unique-work semantics.
 */
object KeepAliveScheduler {

    private const val TAG = "KeepAliveScheduler"

    /**
     * Re-arm the full keep-alive chain. Safe to call from any thread, from
     * any receiver, at any time.
     */
    fun scheduleAll(context: Context) {
        val ctx = context.applicationContext
        try {
            TrackerService.start(ctx)
            Log.d(TAG, "TrackerService.start() invoked from scheduler")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TrackerService", e)
        }

        try {
            AlarmReceiver.scheduleExactAlarm(ctx)
            Log.d(TAG, "AlarmReceiver.scheduleExactAlarm() invoked from scheduler")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule AlarmReceiver", e)
        }

        try {
            HeartbeatWorker.schedule(ctx)
            Log.d(TAG, "HeartbeatWorker.schedule() invoked from scheduler")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule HeartbeatWorker", e)
        }

        try {
            CloudConfig.keepAliveLastArmedAtMs = System.currentTimeMillis()
        } catch (_: Exception) { }
    }

    /**
     * Tear down the keep-alive chain. Called when the user intentionally
     * disables the parental control (e.g. via the secret code) or when
     * the device is unpaired.
     */
    fun cancelAll(context: Context) {
        val ctx = context.applicationContext
        try { AlarmReceiver.cancelAlarm(ctx) } catch (_: Exception) { }
        try { HeartbeatWorker.cancel(ctx) }      catch (_: Exception) { }
        Log.d(TAG, "KeepAliveScheduler: cancelled all keep-alive mechanisms")
    }
}
