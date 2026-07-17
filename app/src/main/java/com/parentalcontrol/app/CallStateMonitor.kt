package com.parentalcontrol.app

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.parentalcontrol.app.api.ApiClient

class CallStateMonitor(private val context: Context) {

    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyCallback: TelephonyCallback? = null

    var isCallActive = false
        private set
    var currentCallNumber: String? = null
        private set
    var currentCallState = STATE_IDLE
        private set
    var callStartedAt: Long = 0
        private set

    var onCallStateChanged: ((state: Int, number: String?) -> Unit)? = null

    fun start() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startModern()
            } else {
                startLegacy()
            }
            Log.d(TAG, "Call state monitor started")
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_PHONE_STATE permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call monitor: ${e.message}")
        }
    }

    private fun writeLog(msg: String) {
        try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            java.io.File(context.filesDir, "debug.log").appendText("$ts CallMonitor: $msg\n")
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun startLegacy() {
        phoneStateListener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallState(state, phoneNumber)
            }
        }
        @Suppress("DEPRECATION")
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        writeLog("Legacy listener registered")
    }

    private fun startModern() {
        telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val number = getLastOutgoingNumber()
                handleCallState(state, number)
            }
        }
        telephonyManager.registerTelephonyCallback(context.mainExecutor, telephonyCallback!!)
        writeLog("Modern callback registered")
    }

    private fun getLastOutgoingNumber(): String? {
        return try {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun handleCallState(state: Int, phoneNumber: String?) {
        val now = System.currentTimeMillis()

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                currentCallState = STATE_RINGING
                currentCallNumber = phoneNumber
                callStartedAt = now
                isCallActive = true
                writeLog("CALL RINGING: $phoneNumber")
                reportCallState(STATE_RINGING, phoneNumber, now)
                onCallStateChanged?.invoke(STATE_RINGING, phoneNumber)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                currentCallState = STATE_ACTIVE
                if (callStartedAt == 0L) callStartedAt = now
                isCallActive = true
                writeLog("CALL ACTIVE: $currentCallNumber")
                reportCallState(STATE_ACTIVE, currentCallNumber, now)
                onCallStateChanged?.invoke(STATE_ACTIVE, currentCallNumber)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val prevNumber = currentCallNumber
                val duration = if (callStartedAt > 0) (now - callStartedAt) / 1000 else 0
                currentCallState = STATE_IDLE
                currentCallNumber = null
                isCallActive = false
                callStartedAt = 0
                writeLog("CALL ENDED. Duration: ${duration}s")
                reportCallState(STATE_IDLE, prevNumber, now)
                onCallStateChanged?.invoke(STATE_IDLE, prevNumber)
            }
        }
    }

    private fun reportCallState(state: Int, phoneNumber: String?, timestamp: Long) {
        Thread {
            try {
                ApiClient.reportCallState(state, phoneNumber, timestamp)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report call state: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                phoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping call monitor: ${e.message}")
        }
        phoneStateListener = null
        telephonyCallback = null
    }

    companion object {
        private const val TAG = "CallStateMonitor"
        const val STATE_IDLE = 0
        const val STATE_RINGING = 1
        const val STATE_ACTIVE = 2
    }
}
