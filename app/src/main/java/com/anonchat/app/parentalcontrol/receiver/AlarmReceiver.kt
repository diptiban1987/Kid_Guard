package com.anonchat.app.parentalcontrol.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anonchat.app.parentalcontrol.service.TrackerService

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REPORT) {
            if (!TrackerService.isRunning) {
                TrackerService.start(context)
            }
        }
    }

    companion object {
        private const val ACTION_REPORT = "com.anonchat.app.parentalcontrol.REPORT"
        private const val REQUEST_CODE = 1001

        fun scheduleAlarm(context: Context) {
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

            val intervalMs = TrackerService.REPORT_INTERVAL_MS
            val triggerAtMs = System.currentTimeMillis() + intervalMs

            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                triggerAtMs,
                intervalMs,
                pendingIntent
            )
        }

        fun cancelAlarm(context: Context) {
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
        }
    }
}
