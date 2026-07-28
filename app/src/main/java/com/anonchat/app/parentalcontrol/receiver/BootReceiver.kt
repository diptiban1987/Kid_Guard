package com.anonchat.app.parentalcontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.anonchat.app.parentalcontrol.service.TrackerService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_REBOOT
        ) {
            TrackerService.start(context)
            com.anonchat.app.parentalcontrol.receiver.AlarmReceiver.scheduleExactAlarm(context)
        }
    }
}
