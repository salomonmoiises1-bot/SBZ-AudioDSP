package com.audiodsp.enginepro.audio.output

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Real-time PCM output using Android AudioTrack.
 *
 * Receives PCM16 stereo audio after AudioDspEngine processing
 * and sends it to the normal Android media output.
 *
 * This class does NOT use Android AudioEffect.
 */
class AudioTrackOutput(
    private val sampleRate: Int = 48000,
    private val channelConfig: Int =
        AudioFormat.CHANNEL_OUT_STEREO,
    private val audioEncoding: Int =
        AudioFormat.ENCODING_PCM_16BIT
) {

    companion object {
        private const val TAG =
            "AudioTrackOutput"
    }

    private var audioTrack: AudioTrack? = null

    var isRunning: Boolean = false
        private set

    val minBufferSize: Int by lazy {

        val calculated =
            AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfig,
                audioEncoding
            )

        if (calculated > 0) {
            calculated * 2
        } else {
            4096
        }
    }

    /**
     * Creates and starts the PCM output.
     */
    fun start(): Boolean {

        stop()

        return try {

            val audioAttributes =
                AudioAttributes.Builder()
                    .setUsage(
                        AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_MUSIC
                    )
                    .build()

            val audioFormat =
                AudioFormat.Builder()
                    .setEncoding(
                        audioEncoding
                    )
                    .setSampleRate(
                        sampleRate
                    )
                    .setChannelMask(
                        channelConfig
                    )
                    .build()

            /*
             * First attempt:
             * Android low-latency output.
             */
            val track = try {

                AudioTrack.Builder()
                    .setAudioAttributes(
                        audioAttributes
                    )
                    .setAudioFormat(
                        audioFormat
                    )
                    .setBufferSizeInBytes(
                        minBufferSize
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )
                    .setPerformanceMode(
                        AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                    )
                    .build()

            } catch (e: Exception) {

                /*
                 * Some devices do not support the requested
                 * low-latency configuration.
                 *
                 * Fall back to the normal AudioTrack path.
                 */
                Log.w(
                    TAG,
                    "Low-latency AudioTrack unavailable. " +
                        "Falling back to normal output.",
                    e
                )

                AudioTrack.Builder()
                    .setAudioAttributes(
                        audioAttributes
                    )
                    .setAudioFormat(
                        audioFormat
                    )
                    .setBufferSizeInBytes(
                        minBufferSize
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )
                    .build()
            }

            if (
                track.state !=
                AudioTrack.STATE_INITIALIZED
            ) {

                track.release()

                Log.e(
                    TAG,
                    "Failed to initialize AudioTrack output."
                )

                isRunning = false

                return false
            }

            track.play()

            if (
                track.playState !=
                AudioTrack.PLAYSTATE_PLAYING
            ) {

                track.release()

                Log.e(
                    TAG,
                    "AudioTrack could not enter PLAYING state."
                )

                isRunning = false

                return false
            }

            audioTrack = track

            isRunning = true

            Log.i(
                TAG,
                "AudioTrack output started: " +
                    "${sampleRate} Hz stereo PCM16."
            )

            true

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Exception starting AudioTrack: " +
                    e.localizedMessage,
                e
            )

            audioTrack = null
            isRunning = false

            false
        }
    }

    /**
     * Writes interleaved PCM16 samples.
     *
     * size is expressed in samples, not frames.
     */
    fun write(
        data: ShortArray,
        offset: Int,
        size: Int
    ): Int {

        val track =
            audioTrack
                ?: return -1

        if (
            !isRunning
        ) {
            return -1
        }

        return try {

            track.write(
                data,
                offset,
                size,
                AudioTrack.WRITE_BLOCKING
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "AudioTrack write failed: " +
                    e.localizedMessage,
                e
            )

            isRunning = false

            -1
        }
    }

    /**
     * Stops and releases the AudioTrack.
     */
    fun stop() {

        val track =
            audioTrack

        audioTrack = null
        isRunning = false

        if (track == null) {
            return
        }

        try {

            if (
                track.playState ==
                AudioTrack.PLAYSTATE_PLAYING
            ) {
                track.pause()
            }

            track.flush()

            if (
                track.state ==
                AudioTrack.STATE_INITIALIZED
            ) {
                track.stop()
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Exception stopping AudioTrack: " +
                    e.localizedMessage
            )

        } finally {

            try {
                track.release()
            } catch (_: Exception) {
            }
        }
    }
}
