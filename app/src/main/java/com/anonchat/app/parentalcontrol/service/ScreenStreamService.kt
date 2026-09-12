package com.anonchat.app.parentalcontrol.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.anonchat.app.parentalcontrol.api.ApiClient
import java.io.ByteArrayOutputStream

/**
 * Live screen-view stream ("Live Screen" viewer, like Watcher's Live Screen
 * Sharing). Captures the display via MediaProjection at ~1 fps, JPEG-encodes
 * (scaled to <=720 px wide) and POSTs base64 frames to /api/report/screen-frame
 * — the server keeps ONLY the latest frame per device, which the parent
 * dashboard's Live Screen viewer polls.
 *
 * Runs as a mediaProjection foreground service (mandatory for targetSdk 34+).
 * The projection consent result arrives via [ScreenShareConsentActivity].
 * Stops itself on: parent "stop_screen_view", max duration, projection
 * revocation, or a sustained upload-failure streak (device offline).
 */
class ScreenStreamService : Service() {

    companion object {
        private const val TAG = "ScreenStream"
        private const val CHANNEL_ID = "screen_stream"
        private const val NOTIF_ID = 4243
        private const val MAX_WIDTH_PX = 720
        private const val MAX_FRAME_BYTES = 300_000
        private const val UPLOAD_FAIL_LIMIT = 12
        private const val FRAME_INTERVAL_MS = 1000L

        @Volatile
        var isStreaming = false
            private set

        private var instance: ScreenStreamService? = null

        fun start(context: Context, resultCode: Int, data: Intent, commandId: String?, durationSec: Int) {
            val intent = Intent(context, ScreenStreamService::class.java)
                .putExtra("resultCode", resultCode)
                .putExtra("data", data)
                .putExtra("commandId", commandId)
                .putExtra("duration", durationSec)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Parent sent "stop_screen_view" (or the viewer wants the stream ended). */
        fun stopStreaming() {
            instance?.stopStream("stopped by parent")
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var streamThread: HandlerThread? = null
    private var streamHandler: Handler? = null
    private var commandId: String? = null
    private var deadlineMs = 0L
    private var lastFrameHash = 0
    private var consecutiveUploadFails = 0
    private var stopping = false

    private val frameLoop = object : Runnable {
        override fun run() {
            if (stopping) return
            if (System.currentTimeMillis() >= deadlineMs) {
                stopStream("duration reached")
                return
            }
            try {
                captureAndUploadFrame()
            } catch (e: Exception) {
                Log.e(TAG, "frame error: ${e.message}")
            }
            streamHandler?.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra("resultCode", Int.MIN_VALUE)
        val resultData = intent.getParcelableExtra<Intent>("data")
        if (resultData == null || resultCode == Int.MIN_VALUE) {
            reportCommand("failed", "Missing projection result")
            stopSelf()
            return START_NOT_STICKY
        }
        commandId = intent.getStringExtra("commandId")
        val durationSec = intent.getIntExtra("duration", 300).coerceIn(30, 900)
        deadlineMs = System.currentTimeMillis() + durationSec * 1000L

        // Foreground notification FIRST — required before any projection setup.
        startForegroundCompat()

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}")
            reportCommand("failed", "Could not start projection: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection revoked by system/user")
                stopStream("projection revoked")
            }
        }, null)

        val metrics = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val scale = minOf(1f, MAX_WIDTH_PX.toFloat() / metrics.widthPixels)
        val w = (metrics.widthPixels * scale).toInt()
        val h = (metrics.heightPixels * scale).toInt()

        streamThread = HandlerThread("ScreenStream").also { it.start() }
        streamHandler = Handler(streamThread!!.looper)

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "KidGuardScreen", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, streamHandler
        )

        isStreaming = true
        Log.d(TAG, "Screen stream started ${w}x${h} @${1000 / FRAME_INTERVAL_MS}fps for ${durationSec}s")
        streamHandler?.postDelayed(frameLoop, 600)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!stopping) stopStream("service destroyed")
        if (instance == this) instance = null
        super.onDestroy()
    }

    /** Grab the latest image, JPEG it, upload. Static screens are skipped. */
    private fun captureAndUploadFrame() {
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            val rowPadding = rowStride - pixelStride * width
            buffer.rewind()
            val full = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(buffer)
            val bitmap = if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
                full.recycle()
                cropped
            } else full

            // Skip identical frames (idle chat screen) — saves upload + battery.
            val hash = hashOf(bitmap)
            if (hash == lastFrameHash) {
                bitmap.recycle()
                image.close()
                return
            }
            lastFrameHash = hash

            val b64 = encodeBase64(bitmap) ?: run {
                bitmap.recycle()
                image.close()
                return
            }
            bitmap.recycle()
            val ts = System.currentTimeMillis()
            Thread {
                val ok = ApiClient.uploadScreenFrame(b64, ts)
                if (ok) {
                    consecutiveUploadFails = 0
                } else {
                    consecutiveUploadFails++
                    Log.w(TAG, "frame upload failed ($consecutiveUploadFails in a row)")
                    if (consecutiveUploadFails >= UPLOAD_FAIL_LIMIT) stopStream("server unreachable")
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "capture frame error: ${e.message}")
        } finally {
            try { image.close() } catch (_: Exception) {}
        }
    }

    /** Cheap perceptual hash: sample ~256 pixels; identical screens hash equal. */
    private fun hashOf(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var hash = 1469598103
        val step = maxOf(1, pixels.size / 256)
        var i = 0
        while (i < pixels.size) {
            hash = hash * 31 + pixels[i]
            i += step
        }
        return hash
    }

    /** JPEG with decreasing quality until the frame fits the upload budget. */
    private fun encodeBase64(bitmap: Bitmap): String? {
        var quality = 55
        while (quality >= 30) {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            val bytes = out.toByteArray()
            if (bytes.size <= MAX_FRAME_BYTES || quality == 30) {
                return Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            quality -= 10
        }
        return null
    }

    private fun stopStream(reason: String) {
        if (stopping) return
        stopping = true
        isStreaming = false
        Log.d(TAG, "Stopping screen stream: $reason")
        try { streamHandler?.removeCallbacks(frameLoop) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { streamThread?.quitSafely() } catch (_: Exception) {}
        streamThread = null
        reportCommand("completed", "Screen stream ended: $reason")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun reportCommand(status: String, result: String? = null) {
        val id = commandId ?: return
        Thread {
            try { ApiClient.updateCommandStatus(id, status, result) } catch (_: Exception) {}
        }.start()
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Screen viewing", NotificationManager.IMPORTANCE_LOW)
                )
            }
            return Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen viewing active")
                .setContentText("Parent is viewing this device's screen")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()
        }
        @Suppress("DEPRECATION")
        return Notification.Builder(this)
            .setContentTitle("Screen viewing active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }
}
