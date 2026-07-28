package com.anonchat.app.parentalcontrol.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.anonchat.app.parentalcontrol.service.TrackerService

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "AlarmReceiver fired — action: ${intent.action}")
        try {
            // Ensure TrackerService is running & reporting
            TrackerService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service from AlarmReceiver", e)
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
