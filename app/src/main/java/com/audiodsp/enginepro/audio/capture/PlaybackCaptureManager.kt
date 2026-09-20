package com.audiodsp.enginepro.audio.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log

/**
 * Handles official Android system playback capture using MediaProjection and
 * AudioPlaybackCaptureConfiguration (Android 10 / API 29+).
 *
 * STRICT POLICY: Does NOT fall back to MediaRecorder.AudioSource.MIC.
 * Only captures genuine system media playback authorized by the user.
 */
class PlaybackCaptureManager(
    private val sampleRate: Int = 48000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_STEREO,
    private val audioEncoding: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    companion object {
        private const val TAG = "PlaybackCaptureMgr"
    }

    sealed class CaptureStatus {
        object Idle : CaptureStatus()
        object Initializing : CaptureStatus()
        object Capturing : CaptureStatus()
        data class Error(val message: String, val isRestricted: Boolean = false) : CaptureStatus()
    }

    private var audioRecord: AudioRecord? = null
    var currentStatus: CaptureStatus = CaptureStatus.Idle
        private set

    val minBufferSize: Int by lazy {
        val calculated = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (calculated > 0) calculated * 2 else 4096
    }

    /**
     * Creates and starts the AudioRecord configured with AudioPlaybackCaptureConfiguration.
     */
    @SuppressLint("MissingPermission")
    fun startCapture(mediaProjection: MediaProjection): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            currentStatus = CaptureStatus.Error("System playback capture requires Android 10 (API 29) or higher.")
            return false
        }

        try {
            currentStatus = CaptureStatus.Initializing

            // Configure audio playback capture matching standard media and game streams
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(audioEncoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                currentStatus = CaptureStatus.Error(
                    "AudioRecord failed to initialize with system playback capture. " +
                    "Some applications may disallow playback capture via android:allowAudioPlaybackCapture=\"false\"."
                )
                return false
            }

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
                record.release()
                currentStatus = CaptureStatus.Error("AudioRecord could not enter recording state.")
                return false
            }

            audioRecord = record
            currentStatus = CaptureStatus.Capturing
            Log.i(TAG, "AudioPlaybackCapture successfully initialized at $sampleRate Hz stereo.")
            return true

        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException creating playback capture: ${se.message}", se)
            currentStatus = CaptureStatus.Error("Permission denied: MediaProjection was revoked or unauthorized.")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing playback capture: ${e.message}", e)
            currentStatus = CaptureStatus.Error("Capture error: ${e.localizedMessage ?: "Unknown initialization failure"}")
            return false
        }
    }

    /**
     * Reads interleaved 16-bit PCM shorts from the active system capture.
     * Zero memory allocation in steady state.
     */
    fun read(destination: ShortArray, offset: Int, size: Int): Int {
        val record = audioRecord ?: return -1
        return record.read(destination, offset, size, AudioRecord.READ_BLOCKING)
    }

    /**
     * Stops and releases the AudioRecord session cleanly.
     */
    fun stopCapture() {
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during AudioRecord stop: ${e.message}")
        } finally {
            audioRecord = null
            currentStatus = CaptureStatus.Idle
        }
    }
}
