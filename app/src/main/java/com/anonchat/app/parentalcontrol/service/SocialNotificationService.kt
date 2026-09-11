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
            "com.skype.raider" to "Skype",
            // Modded WhatsApp clones rename the package — without these
            // entries their notifications are ignored entirely (line
            // SOCIAL_PACKAGES[packageName] ?: return below). Common on
            // vivo/iQOO devices where GB/FM/YO WhatsApp is installed.
            "com.gbwhatsapp" to "WhatsApp (GB)",
            "com.fmwhatsapp" to "WhatsApp (FM)",
            "com.yowhatsapp" to "WhatsApp (YO)",
            "com.instagram.lite" to "Instagram Lite",
            "com.facebook.mlite" to "Messenger Lite"
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

        // ── Deduplication caches ──
        // Exact-notification key (pkg+id+sender+content) stops the same
        // notification object from being re-captured when it is *updated*
        // (progress ticks, timestamp refreshes, group summary refreshes).
        private val recentUniqueKeys = LinkedHashSet<String>()
        // Content key (pkg+sender+content) with a short time window stops
        // re-capture of the same logical message when the OS re-posts it
        // as a "new" notification seconds later. Insertion-ordered so the
        // oldest keys can be evicted one by one — a full clear() wiped every
        // known key during busy bursts, after which all still-active
        // notifications were re-captured and flooded the dashboard with
        // duplicate rows.
        private val recentContentAt = LinkedHashMap<String, Long>()

        private const val CONTENT_DEDUP_WINDOW_MS = 60_000L
        private const val MAX_DEDUP_ENTRIES = 300

        /** True if this (sender, content) pair is new and should be captured. */
        fun shouldCapture(packageName: String, sbnId: Int, sender: String, content: String, postTime: Long): Boolean {
            synchronized(recentUniqueKeys) {
                val key = "$packageName#$sbnId#$sender#$content"
                if (!recentUniqueKeys.add(key)) return false
                if (recentUniqueKeys.size > MAX_DEDUP_ENTRIES) {
                    val it = recentUniqueKeys.iterator()
                    it.next(); it.remove()
                }
                val ck = "$packageName#$sender#$content"
                val last = recentContentAt[ck]
                if (last != null && postTime - last < CONTENT_DEDUP_WINDOW_MS) return false
                // Re-insert so the map stays oldest-first for eviction.
                recentContentAt.remove(ck)
                recentContentAt[ck] = postTime
                if (recentContentAt.size > MAX_DEDUP_ENTRIES) {
                    val eldestIt = recentContentAt.entries.iterator()
                    eldestIt.next(); eldestIt.remove()
                }
                return true
            }
        }

        /** Strip control characters / span artifacts and collapse whitespace. */
        fun sanitize(raw: String?): String {
            if (raw.isNullOrEmpty()) return ""
            return raw
                .replace(Regex("[\\p{Cntrl}]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /**
         * Group-chat notifications embed the real sender in the text:
         * "Group Name" as title, "John: hello everyone" as content.
         * Extract the real sender when possible.
         */
        fun splitEmbeddedSender(sender: String, content: String, appName: String): Pair<String, String> {
            // Only if the title is not already a person (i.e. it's the app or a group)
            if (sender.isBlank() || sender.equals(appName, ignoreCase = true)) {
                val m = Regex("^([^:./@]{1,32})\\s*:\\s(.+)$", RegexOption.DOT_MATCHES_ALL).find(content)
                if (m != null) {
                    val name = m.groupValues[1].trim()
                    val text = m.groupValues[2].trim()
                    // Reject obvious non-senders (URLs, times, numbers)
                    if (name.isNotEmpty() && !name.any { it.isDigit() }) {
                        return Pair(name, text)
                    }
                }
            }
            return Pair(sender, content)
        }

        /** Self-sent and "typing" noise — never real messages. */
        fun isSummaryNoise(sender: String, content: String): Boolean {
            if (sender.isBlank() && content.isBlank()) return true
            if (sender.equals("You", ignoreCase = true)) return true
            val c = content.lowercase()
            if (c.endsWith("is typing...") || c.endsWith("is typing…")) return true
            return false
        }

        /**
         * Digest/bundled notifications ("WhatsApp — 3 new messages"). Some OEM
         * skins (notably vivo OriginOS on iQOO phones) deliver these instead of
         * per-chat notifications. They used to be dropped as noise, which made
         * WhatsApp disappear completely from the Social tab on such devices —
         * they are now kept (see onNotificationPosted) with
         * message_type="summary" so the parent still sees the activity.
         */
        fun isDigestNotification(sender: String, content: String): Boolean {
            val c = content.lowercase()
            if (Regex("^\\d+ new (messages|chats|notifications)").containsMatchIn(c)) return true
            if (c.contains(" new messages") && (c.startsWith("you") || c.contains(" sent "))) return true
            return false
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
        if (!TrackerService.isRunning) {
            TrackerService.start(this)
        }
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
        if (!TrackerService.isRunning) {
            TrackerService.start(this)
        }

        val packageName = sbn.packageName ?: return
        val appName = SOCIAL_PACKAGES[packageName] ?: return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        try {
            // Skip ongoing/persistent notifications (e.g., "WhatsApp Web is active")
            if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

            val (rawSender, rawContent) = extractDetailedContent(notification, extras)

            // Sanitize span/control-character artifacts out of the raw text
            var sender = sanitize(rawSender)
            var content = sanitize(rawContent)

            // Group chats embed the real sender inside the text ("John: hi all")
            val (s2, c2) = splitEmbeddedSender(sender, content, appName)
            sender = s2; content = c2

            // Skip self-sent and "typing" noise. NOTE: digest/bundled
            // notifications are NOT skipped — on some devices (vivo OriginOS /
            // iQOO) they are the only form WhatsApp notifications take.
            if (isSummaryNoise(sender, content)) return
            val isDigest = isDigestNotification(sender, content)

            // Skip exact re-captures (notification updates) and same-content
            // re-posts within a short window
            if (!shouldCapture(packageName, sbn.id, sender, content, sbn.postTime)) return

            // Skip empty notifications
            if (sender.isBlank() && content.isBlank()) return

            // Content-hidden rows: vivo OriginOS ("hide notification content"),
            // lock-screen privacy, or WhatsApp's own App Lock strip the message
            // text out of notifications. Keep the row with a marker so the
            // activity is still visible instead of silently vanishing.
            if (content.isBlank()) content = "(message content hidden by device)"

            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

            // Determine message type
            val messageType = when {
                isDigest -> "summary"
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

        // 1. Try MessagingStyle extra "android.messages" (WhatsApp, Telegram, Messenger).
        //    Prefer the most recent RECEIVED message: outgoing messages (no sender
        //    person) are the child's own replies and would masquerade as content.
        val messagesParcelables = extras.getParcelableArray("android.messages")
        if (messagesParcelables != null && messagesParcelables.isNotEmpty()) {
            for (i in messagesParcelables.indices.reversed()) {
                val msgBundle = messagesParcelables[i] as? android.os.Bundle ?: continue
                val msgText = msgBundle.getCharSequence("text")?.toString()
                val msgSender = msgBundle.getCharSequence("sender")?.toString()
                if (!msgText.isNullOrBlank()) {
                    content = msgText
                    // An outgoing message has no sender — keep looking backwards
                    if (!msgSender.isNullOrBlank()) {
                        sender = msgSender
                        break
                    }
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
