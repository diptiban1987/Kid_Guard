package com.anonchat.kidguard.updates

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Consent-prompted updater for the KidGuard child agent.
 *
 * Design intent
 * -------------
 * The user (the device owner — e.g. the child, or the parent holding the phone)
 * must explicitly tap "Update" on a visible dialog or notification before any
 * APK is downloaded or installed. There is deliberately NO silent background
 * install path: on stock Android that isn't possible without being a device
 * owner app, and attempting to hide installs is what makes software
 * stalkerware. This class exists to make the legitimate, transparent flow easy.
 *
 * Flow
 * ----
 * 1. [checkForUpdate] GETs the server's update metadata endpoint and compares
 *    versionCode to the installed package.
 * 2. If a newer version exists, it posts a high-visibility notification AND
 *    returns metadata so the caller can show an in-app dialog.
 * 3. [downloadAndPromptInstall] downloads to app-private storage, then shows
 *    the user a "tap to install" notification that fires an ACTION_VIEW intent
 *    via FileProvider. Android itself displays the final consent screen.
 */
object UpdateManager {

    private const val CHANNEL_ID = "kidguard_updates"
    private const val NOTIF_ID = 1001
    private const val PREFS = "kidguard_updates"
    private const val KEY_LAST_PROMPT_VERSION = "last_prompted_version"

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val versionCode: Int,
        val downloadUrl: String,
        val changelog: String,
    )

    /** Installed versionCode, or 0 on failure. */
    fun currentVersionCode(context: Context): Int {
        return try {
            val p: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt()
            @Suppress("DEPRECATION") else p.versionCode
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Call from a background worker (e.g. periodic WorkManager). Returns
     * UpdateInfo when an update is available; also shows the user-visible
     * prompt unless we've already prompted for this version.
     */
    suspend fun checkForUpdate(
        context: Context,
        serverBaseUrl: String,   // e.g. "https://diptiban2021.pythonanywhere.com"
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        val current = currentVersionCode(context)
        val url = URL("$serverBaseUrl/api/v1/app/check-update")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }
        val payload = JSONObject().put("version_code", current).toString()
        conn.outputStream.use { it.write(payload.toByteArray()) }

        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            return@withContext null
        }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val json = JSONObject(body)
        val info = UpdateInfo(
            hasUpdate = json.optBoolean("has_update", false),
            versionCode = json.optInt("version_code", current),
            downloadUrl = json.optString("download_url", ""),
            changelog = json.optString("changelog", ""),
        )
        if (info.hasUpdate) {
            maybePromptUser(context, info, serverBaseUrl)
            return@withContext info
        }
        null
    }

    /** Show one prompt per version (don't nag every sync). */
    private fun maybePromptUser(context: Context, info: UpdateInfo, serverBaseUrl: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_LAST_PROMPT_VERSION, 0) >= info.versionCode) return
        prefs.edit().putInt(KEY_LAST_PROMPT_VERSION, info.versionCode).apply()
        showUpdateNotification(context, info, serverBaseUrl)
    }

    /** Download the APK to private storage. Call from IO dispatcher. */
    suspend fun downloadApk(
        context: Context,
        serverBaseUrl: String,
        downloadUrl: String,
    ): File? = withContext(Dispatchers.IO) {
        return@withContext try {
            val full = if (downloadUrl.startsWith("http")) downloadUrl
                       else serverBaseUrl.trimEnd('/') + downloadUrl
            val conn = (URL(full).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
            }
            if (conn.responseCode != 200) { conn.disconnect(); return@withContext null }
            val out = File(context.filesDir, "kidguard_update.apk")
            conn.inputStream.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            out
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Full consent flow: download, then show a system install screen by tapping
     * the notification. The user can always cancel at the Android prompt.
     */
    fun downloadAndPromptInstall(
        scope: CoroutineScope,
        activity: Activity,
        info: UpdateInfo,
        serverBaseUrl: String,
    ) {
        scope.launch {
            val apk = downloadApk(activity, serverBaseUrl, info.downloadUrl) ?: return@launch
            showInstallNotification(activity, apk, info)
        }
    }

    // ── Notifications ────────────────────────────────────────────────────

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "App updates",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "KidGuard update available" }
            )
        }
    }

    private fun showUpdateNotification(context: Context, info: UpdateInfo, serverBaseUrl: String) {
        ensureChannel(context)
        // Tapping opens the app so the user can review the changelog and tap
        // "Update" — download+install only starts after that explicit tap.
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("KidGuard update available")
            .setContentText("Version ${info.versionCode}: ${info.changelog}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Version ${info.versionCode} is available.\n\n${info.changelog}\n\nTap to open the app and update."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notif)
    }

    private fun showInstallNotification(context: Context, apk: File, info: UpdateInfo) {
        ensureChannel(context)
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pi = PendingIntent.getActivity(
            context, 1, install,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("KidGuard update downloaded")
            .setContentText("Tap to install version ${info.versionCode}")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID + 1, notif)
    }
}
