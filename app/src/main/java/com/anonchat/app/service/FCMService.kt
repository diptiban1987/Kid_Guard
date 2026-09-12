package com.anonchat.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anonchat.app.R
import com.anonchat.app.ui.main.MainActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FCMService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Cache for the cloud server: every device register / bulk report
        // carries this token so the server can push FCM wake pings (remote
        // revival of a killed app process from the parent dashboard).
        try {
            com.anonchat.app.parentalcontrol.api.CloudConfig.fcmToken = token
        } catch (_: Exception) { }
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(userId)
            .update("fcmToken", token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data

        // ── FCM wake ping (remote revival) ────────────────────────────────
        // The parent dashboard pushed this while the app process was dead.
        // A high-priority FCM message puts us in the temporary allowlist
        // (~10 s) during which starting the foreground service is permitted,
        // so re-arm the entire keep-alive chain immediately: TrackerService
        // (FGS) + exact alarm + WorkManager. The chain then re-heartbeats
        // (dashboard flips ONLINE) and polls for pending commands.
        if (data["type"] == "wake") {
            Log.w("FCMService", "Wake ping received — re-arming keep-alive chain")
            try {
                com.anonchat.app.parentalcontrol.keepalive.KeepAliveScheduler
                    .scheduleAll(applicationContext)
                Log.w("FCMService", "Wake revival: keep-alive chain re-armed")
            } catch (e: Exception) {
                Log.e("FCMService", "Wake revival failed", e)
            }
            return
        }

        val title = data["title"] ?: "New Message"
        val body = data["body"] ?: ""
        val chatId = data["chatId"] ?: ""

        if (chatId.isNotEmpty()) {
            showNotification(title, body, chatId)
        }
    }

    private fun showNotification(title: String, body: String, chatId: String) {
        val channelId = getString(R.string.default_notification_channel_id)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chatId", chatId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Chat messages notifications"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(chatId.hashCode(), notificationBuilder.build())
    }
}
