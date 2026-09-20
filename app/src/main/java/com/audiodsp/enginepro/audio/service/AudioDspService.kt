package com.audiodsp.enginepro.audio.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.audiodsp.enginepro.AudioDspApplication
import com.audiodsp.enginepro.R
import com.audiodsp.enginepro.audio.capture.PlaybackCaptureManager
import com.audiodsp.enginepro.audio.output.AudioTrackOutput
import com.audiodsp.enginepro.dsp.core.AudioBuffer
import com.audiodsp.enginepro.dsp.core.AudioDspEngine
import com.audiodsp.enginepro.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main audio DSP service.
 *
 * Android 14 software DSP pipeline:
 *
 * MediaProjection
 *      ↓
 * AudioPlaybackCapture
 *      ↓
 * AudioRecord
 *      ↓
 * AudioDspEngine
 *      ↓
 * 32-band EQ / MDRC / Tone / Limiter / etc.
 *      ↓
 * AudioTrack
 *
 * NativeSystemEffectController is intentionally NOT used here.
 *
 * This avoids Android's global output AudioEffect path, which can be
 * rejected on Android 14 with:
 *
 * "AudioEffect: invalid parameter operation"
 */
class AudioDspService : Service() {

    companion object {

        private const val TAG = "AudioDspService"

        const val CHANNEL_ID =
            "audio_dsp_engine_pro_channel"

        const val NOTIFICATION_ID = 1001

        /*
         * Kept for compatibility with the existing UI.
         *
         * IMPORTANT:
         * This action no longer starts a global Android AudioEffect.
         * The actual DSP processing uses the capture pipeline.
         */
        const val ACTION_START_NATIVE =
            "com.audiodsp.enginepro.action.START_NATIVE"

        const val ACTION_START_CAPTURE_DIAGNOSTIC =
            "com.audiodsp.enginepro.action.START_CAPTURE_DIAGNOSTIC"

        const val ACTION_STOP_PROCESSING =
            "com.audiodsp.enginepro.action.STOP"

        const val ACTION_TOGGLE_BYPASS =
            "com.audiodsp.enginepro.action.TOGGLE_BYPASS"

        const val EXTRA_RESULT_CODE =
            "extra_result_code"

        const val EXTRA_RESULT_DATA =
            "extra_result_data"

        private var instance: AudioDspService? = null

        fun getInstance(): AudioDspService? =
            instance

        private val _isServiceActive =
            MutableStateFlow(false)

        val isServiceActive: StateFlow<Boolean> =
            _isServiceActive.asStateFlow()

        private val _serviceError =
            MutableStateFlow<String?>(null)

        val serviceError: StateFlow<String?> =
            _serviceError.asStateFlow()
    }

    inner class LocalBinder : Binder() {

        fun getService(): AudioDspService =
            this@AudioDspService
    }

    private val binder =
        LocalBinder()

    val dspEngine: AudioDspEngine
        get() =
            (application as AudioDspApplication).dspEngine

    private val captureManager =
        PlaybackCaptureManager(
            sampleRate = 48000
        )

    private val audioOutput =
        AudioTrackOutput(
            sampleRate = 48000
        )

    private var mediaProjection:
            android.media.projection.MediaProjection? = null

    private var audioThread: Thread? = null

    private val isLoopRunning =
        AtomicBoolean(false)

    private var captureMode = false

    override fun onCreate() {

        super.onCreate()

        instance = this

        _isServiceActive.value = false
        _serviceError.value = null

        Log.d(
            TAG,
            "AudioDspService created"
        )
    }

    override fun onBind(
        intent: Intent?
    ): IBinder {

        return binder
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            /*
             * The old implementation attempted to create a global
             * Android AudioEffect here.
             *
             * That path is intentionally disabled.
             *
             * The real processing path is MediaProjection ->
             * AudioRecord -> DSP -> AudioTrack.
             */
            ACTION_START_NATIVE -> {

                Log.d(
                    TAG,
                    "ACTION_START_NATIVE received."
                )

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        0
                    )

                val resultData =
                    getResultData(intent)

                if (
                    resultCode != 0 &&
                    resultData != null
                ) {

                    startSoftwareDsp(
                        resultCode,
                        resultData
                    )

                } else {

                    fail(
                        "Software DSP requires MediaProjection permission. " +
                            "Press START CAPTURE to authorize audio capture."
                    )
                }
            }

            ACTION_START_CAPTURE_DIAGNOSTIC -> {

                val resultCode =
                    intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        0
                    )

                val resultData =
                    getResultData(intent)

                if (
                    resultCode != 0 &&
                    resultData != null
                ) {

                    startSoftwareDsp(
                        resultCode,
                        resultData
                    )

                } else {

                    fail(
                        "Missing MediaProjection consent data."
                    )
                }
            }

            ACTION_STOP_PROCESSING -> {

                stopProcessing()
            }

            ACTION_TOGGLE_BYPASS -> {

                toggleBypass()
            }

            null -> {

                Log.d(
                    TAG,
                    "Service restarted without an action."
                )
            }

            else -> {

                Log.w(
                    TAG,
                    "Unknown service action: ${intent.action}"
                )
            }
        }

        return START_NOT_STICKY
    }

    private fun getResultData(
        intent: Intent
    ): Intent? {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            intent.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )

        } else {

            @Suppress("DEPRECATION")
            intent.getParcelableExtra(
                EXTRA_RESULT_DATA
            )
        }
    }

    /**
     * Main software DSP path.
     */
    private fun startSoftwareDsp(
        resultCode: Int,
        resultData: Intent
    ) {

        Log.d(
            TAG,
            "Starting Android 14 software DSP pipeline"
        )

        captureMode = true

        stopCapturePipeline()

        _serviceError.value = null

        try {

            startForegroundForMediaProjection()

            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as android.media.projection.MediaProjectionManager

            val projection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )
                    ?: throw IllegalStateException(
                        "MediaProjection unavailable."
                    )

            mediaProjection = projection

            projection.registerCallback(
                object :
                    android.media.projection.MediaProjection.Callback() {

                    override fun onStop() {

                        Log.d(
                            TAG,
                            "MediaProjection stopped."
                        )

                        stopProcessing()
                    }
                },
                null
            )

            /*
             * Start AudioPlaybackCapture.
             */
            if (
                !captureManager.startCapture(
                    projection
                )
            ) {

                throw IllegalStateException(
                    "AudioPlaybackCapture failed: " +
                        captureManager.currentStatus
                )
            }

            /*
             * Start AudioTrack output.
             */
            if (
                !audioOutput.start()
            ) {

                throw IllegalStateException(
                    "AudioTrack output failed."
                )
            }

            /*
             * Start DSP processing thread.
             */
            startAudioThread()

            _isServiceActive.value = true

            _serviceError.value = null

            updateNotification()

            Log.d(
                TAG,
                "Software DSP pipeline started successfully"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Unable to start software DSP",
                e
            )

            fail(
                "Capture failed: ${e.localizedMessage}"
            )
        }
    }

    private fun toggleBypass() {

        dspEngine.isBypassGlobal =
            !dspEngine.isBypassGlobal

        Log.d(
            TAG,
            "DSP bypass = ${dspEngine.isBypassGlobal}"
        )

        updateNotification()
    }

    private fun startForegroundForMediaProjection() {

        val notification =
            buildNotification()

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun buildNotification(): Notification {

        val openIntent =
            Intent(
                this,
                MainActivity::class.java
            )

        val openPendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
            )

        val stopIntent =
            Intent(
                this,
                AudioDspService::class.java
            ).apply {

                action =
                    ACTION_STOP_PROCESSING
            }

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
            )

        val bypassIntent =
            Intent(
                this,
                AudioDspService::class.java
            ).apply {

                action =
                    ACTION_TOGGLE_BYPASS
            }

        val bypassPendingIntent =
            PendingIntent.getService(
                this,
                2,
                bypassIntent,
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
            )

        val mode =
            if (captureMode) {

                "Android 14 Software DSP"

            } else {

                "Audio DSP stopped"
            }

        val bypassText =
            if (
                dspEngine.isBypassGlobal
            ) {

                "Enable DSP"

            } else {

                "Bypass DSP"
            }

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                getString(
                    R.string.dsp_running_title
                )
            )
            .setContentText(
                mode
            )
            .setSmallIcon(
                R.drawable.ic_notification
            )
            .setContentIntent(
                openPendingIntent
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                bypassText,
                bypassPendingIntent
            )
            .addAction(
                0,
                getString(
                    R.string.dsp_stop_action
                ),
                stopPendingIntent
            )
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    private fun updateNotification() {

        val manager =
            getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as android.app.NotificationManager

        manager.notify(
            NOTIFICATION_ID,
            buildNotification()
        )
    }

    /**
     * Real-time audio processing loop.
     *
     * AudioPlaybackCapture
     *       ↓
     * Short PCM
     *       ↓
     * Float AudioBuffer
     *       ↓
     * AudioDspEngine
     *       ↓
     * Short PCM
     *       ↓
     * AudioTrack
     */
    private fun startAudioThread() {

        if (
            isLoopRunning.get()
        ) {

            Log.w(
                TAG,
                "Audio thread is already running."
            )

            return
        }

        isLoopRunning.set(true)

        audioThread =
            Thread {

                try {

                    android.os.Process
                        .setThreadPriority(
                            android.os.Process
                                .THREAD_PRIORITY_URGENT_AUDIO
                        )

                    val frameSize = 512

                    val sampleCount =
                        frameSize * 2

                    val input =
                        ShortArray(
                            sampleCount
                        )

                    val output =
                        ShortArray(
                            sampleCount
                        )

                    val buffer =
                        AudioBuffer(
                            frameSize
                        )

                    while (
                        isLoopRunning.get()
                    ) {

                        val read =
                            captureManager.read(
                                input,
                                0,
                                sampleCount
                            )

                        if (
                            read <= 0
                        ) {

                            continue
                        }

                        val frames =
                            read / 2

                        if (
                            frames <= 0
                        ) {

                            continue
                        }

                        buffer.frameCount =
                            frames

                        /*
                         * Convert PCM16 → float.
                         */
                        for (
                            i in 0 until frames
                        ) {

                            buffer.left[i] =
                                input[
                                    i * 2
                                ] / 32768f

                            buffer.right[i] =
                                input[
                                    i * 2 + 1
                                ] / 32768f
                        }

                        /*
                         * REAL DSP PROCESSING.
                         *
                         * The AudioDspEngine is where the
                         * 32-band EQ, MDRC, tone controls,
                         * limiter, gain, etc. are applied.
                         */
                        dspEngine.processBuffer(
                            buffer
                        )

                        /*
                         * Convert float → PCM16.
                         */
                        for (
                            i in 0 until frames
                        ) {

                            output[
                                i * 2
                            ] =
                                (
                                    buffer.left[i]
                                        .coerceIn(
                                            -1f,
                                            1f
                                        ) *
                                        32767f
                                    )
                                    .toInt()
                                    .toShort()

                            output[
                                i * 2 + 1
                            ] =
                                (
                                    buffer.right[i]
                                        .coerceIn(
                                            -1f,
                                            1f
                                        ) *
                                        32767f
                                    )
                                    .toInt()
                                    .toShort()
                        }

                        /*
                         * Send processed PCM to Android output.
                         */
                        audioOutput.write(
                            output,
                            0,
                            frames * 2
                        )
                    }

                } catch (
                    e: InterruptedException
                ) {

                    Log.d(
                        TAG,
                        "Audio thread interrupted."
                    )

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "Audio processing thread failed.",
                        e
                    )

                    _serviceError.value =
                        "Audio processing error: " +
                            e.localizedMessage

                } finally {

                    isLoopRunning.set(false)

                    Log.d(
                        TAG,
                        "Audio processing thread stopped."
                    )
                }

            }.apply {

                name =
                    "AudioDSP-Software"

                priority =
                    Thread.MAX_PRIORITY

                start()
            }
    }

    private fun stopCapturePipeline() {

        isLoopRunning.set(false)

        audioThread?.interrupt()

        audioThread = null

        try {

            captureManager.stopCapture()

        } catch (
            e: Exception
        ) {

            Log.w(
                TAG,
                "Error stopping capture manager.",
                e
            )
        }

        try {

            audioOutput.stop()

        } catch (
            e: Exception
        ) {

            Log.w(
                TAG,
                "Error stopping AudioTrack output.",
                e
            )
        }

        try {

            mediaProjection?.stop()

        } catch (
            e: Exception
        ) {

            Log.w(
                TAG,
                "Error stopping MediaProjection.",
                e
            )
        }

        mediaProjection = null
    }

    private fun stopProcessing() {

        Log.d(
            TAG,
            "Stopping audio processing."
        )

        stopCapturePipeline()

        captureMode = false

        _isServiceActive.value = false

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    private fun fail(
        message: String
    ) {

        Log.e(
            TAG,
            message
        )

        _serviceError.value =
            message

        _isServiceActive.value =
            false

        stopCapturePipeline()

        captureMode = false

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    override fun onDestroy() {

        Log.d(
            TAG,
            "AudioDspService destroyed."
        )

        stopCapturePipeline()

        captureMode = false

        _isServiceActive.value =
            false

        instance = null

        super.onDestroy()
    }
}
