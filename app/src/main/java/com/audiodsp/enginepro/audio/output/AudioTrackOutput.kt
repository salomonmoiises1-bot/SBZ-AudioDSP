package com.audiodsp.enginepro.audio.output

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Low-latency real-time PCM audio sink using Android AudioTrack.
 * Receives DSP-processed PCM audio samples and streams them to the default audio output (headphones/speakers).
 */
class AudioTrackOutput(
    private val sampleRate: Int = 48000,
    private val channelConfig: Int = AudioFormat.CHANNEL_OUT_STEREO,
    private val audioEncoding: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    companion object {
        private const val TAG = "AudioTrackOutput"
    }

    private var audioTrack: AudioTrack? = null
    var isRunning: Boolean = false
        private set

    val minBufferSize: Int by lazy {
        val calculated = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (calculated > 0) calculated * 2 else 4096
    }

    fun start(): Boolean {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(audioEncoding)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                Log.e(TAG, "Failed to initialize AudioTrack output.")
                return false
            }

            track.play()
            audioTrack = track
            isRunning = true
            Log.i(TAG, "AudioTrack output started successfully.")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Exception starting AudioTrack: ${e.message}", e)
            isRunning = false
            return false
        }
    }

    /**
     * Writes processed interleaved 16-bit PCM shorts to the audio output.
     * Blocking call to maintain steady hardware buffer fill without dropping frames.
     */
    fun write(data: ShortArray, offset: Int, size: Int): Int {
        val track = audioTrack ?: return -1
        return track.write(data, offset, size, AudioTrack.WRITE_BLOCKING)
    }

    fun stop() {
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    pause()
                    flush()
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception stopping AudioTrack: ${e.message}")
        } finally {
            audioTrack = null
            isRunning = false
        }
    }
}
