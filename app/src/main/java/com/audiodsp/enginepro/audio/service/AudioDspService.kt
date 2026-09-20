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
import com.audiodsp.enginepro.audio.effects.NativeSystemEffectController
import com.audiodsp.enginepro.audio.output.AudioTrackOutput
import com.audiodsp.enginepro.dsp.core.AudioBuffer
import com.audiodsp.enginepro.dsp.core.AudioDspEngine
import com.audiodsp.enginepro.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Long-lived audio DSP service.
 *
 * Main mode:
 *   Android native audio effect / output processing.
 *
 * Diagnostic mode:
 *   MediaProjection -> AudioRecord -> DSP -> AudioTrack.
 *
 * The diagnostic pipeline is NOT the normal processing path because
 * Android may continue playing the original source simultaneously,
 * resulting in dry + processed audio.
 */
class AudioDspService : Service() {

    companion object {
        private const val TAG = "AudioDspService"

        const val CHANNEL_ID = "audio_dsp_engine_pro_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_NATIVE =
            "com.audiodsp.enginepro.action.START_NATIVE"

        const val ACTION_START_CAPTURE_DIAGNOSTIC =
            "com.audiodsp.enginepro.action.START_CAPTURE_DIAGNOSTIC"

        const val ACTION_STOP_PROCESSING =
            "com.audiodsp.enginepro.action.STOP"

        const val ACTION_TOGGLE_BYPASS =
            "com.audiodsp.enginepro.action.TOGGLE_BYPASS"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private var instance: AudioDspService? = null

        fun getInstance(): AudioDspService? = instance

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> =
            _isServiceActive.asStateFlow()

        private val _serviceError = MutableStateFlow<String?>(null)
        val serviceError: StateFlow<String?> =
            _serviceError.asStateFlow()
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioDspService = this@AudioDspService
    }

    private val binder = LocalBinder()

    val dspEngine: AudioDspEngine
        get() = (application as AudioDspApplication).dspEngine

    private val nativeEffect = NativeSystemEffectController()

    private val captureManager =
        PlaybackCaptureManager(sampleRate = 48000)

    private val audioOutput =
        AudioTrackOutput(sampleRate = 48000)

    private var mediaProjection: android.media.projection.MediaProjection? = null

    private var audioThread: Thread? = null

    private val isLoopRunning =
        AtomicBoolean(false)

    private var diagnosticCaptureMode = false

    override fun onCreate() {
        super.onCreate()

        instance = this

        _isServiceActive.value = false
        _serviceError.value = null

        Log.d(TAG, "AudioDspService created")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START_NATIVE -> {
                startNativeEffect()
            }

            ACTION_START_CAPTURE_DIAGNOSTIC -> {

                val resultCode =
                    intent.getIntExtra(EXTRA_RESULT_CODE, 0)

                val resultData: Intent? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }

                if (resultCode != 0 && resultData != null) {
                    startCaptureDiagnostic(
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
        }

        return START_NOT_STICKY
    }

    private fun startNativeEffect() {

        Log.d(TAG, "Starting native Android DSP")

        diagnosticCaptureMode = false

        stopCapturePipeline()

        mediaProjection?.stop()
        mediaProjection = null

        _serviceError.value = null

        try {

            startForegroundForMediaPlayback()

            if (!nativeEffect.start(dspEngine)) {

                fail(
                    nativeEffect.lastError
                        ?: "Native Android audio effect is unavailable on this device."
                )

                return
            }

            if (!nativeEffect.sync(dspEngine)) {

                fail(
                    nativeEffect.lastError
                        ?: "Unable to synchronize DSP settings with the native audio effect."
                )

                return
            }

            _isServiceActive.value = true

            updateNotification()

            Log.d(TAG, "Native Android DSP started")

        } catch (e: Exception) {

            fail(
                "Unable to start native DSP: ${e.localizedMessage}"
            )
        }
    }

    fun syncNativeEffect() {

        if (!nativeEffect.isActive) {
            return
        }

        try {

            if (!nativeEffect.sync(dspEngine)) {

                _serviceError.value =
                    nativeEffect.lastError
                        ?: "Failed to synchronize native DSP."
            }

        } catch (e: Exception) {

            _serviceError.value =
                "DSP synchronization error: ${e.localizedMessage}"

            Log.e(
                TAG,
                "Native DSP synchronization failed",
                e
            )
        }

        updateNotification()
    }

    private fun toggleBypass() {

        dspEngine.isBypassGlobal =
            !dspEngine.isBypassGlobal

        Log.d(
            TAG,
            "DSP bypass = ${dspEngine.isBypassGlobal}"
        )

        if (nativeEffect.isActive) {
            syncNativeEffect()
        }

        updateNotification()
    }

    private fun startCaptureDiagnostic(
        resultCode: Int,
        resultData: Intent
    ) {

        Log.d(TAG, "Starting diagnostic capture pipeline")

        diagnosticCaptureMode = true

        nativeEffect.stop()

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

            if (!captureManager.startCapture(projection)) {

                throw IllegalStateException(
                    captureManager.currentStatus.toString()
                )
            }

            if (!audioOutput.start()) {

                throw IllegalStateException(
                    "AudioTrack output failed."
                )
            }

            startAudioThread()

            _isServiceActive.value = true

            _serviceError.value =
                "Diagnostic capture mode: source audio may remain audible on some devices."

            updateNotification()

            Log.d(
                TAG,
                "Diagnostic capture pipeline started"
            )

        } catch (e: Exception) {

            fail(
                "Capture diagnostic failed: ${e.localizedMessage}"
            )
        }
    }

    private fun startForegroundForMediaPlayback() {

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun startForegroundForMediaProjection() {

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
                action = ACTION_STOP_PROCESSING
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
                action = ACTION_TOGGLE_BYPASS
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
            if (diagnosticCaptureMode) {
                "Diagnostic capture DSP"
            } else {
                "Native Android output DSP"
            }

        val bypassText =
            if (dspEngine.isBypassGlobal) {
                "Enable DSP"
            } else {
                "Bypass DSP"
            }

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                getString(R.string.dsp_running_title)
            )
            .setContentText(mode)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                bypassText,
                bypassPendingIntent
            )
            .addAction(
                0,
                getString(R.string.dsp_stop_action),
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

    private fun startAudioThread() {

        if (isLoopRunning.get()) {
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

                    android.os.Process.setThreadPriority(
                        android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
                    )

                    val frameSize = 512
                    val sampleCount = frameSize * 2

                    val input =
                        ShortArray(sampleCount)

                    val output =
                        ShortArray(sampleCount)

                    val buffer =
                        AudioBuffer(frameSize)

                    while (isLoopRunning.get()) {

                        val read =
                            captureManager.read(
                                input,
                                0,
                                sampleCount
                            )

                        if (read <= 0) {
                            break
                        }

                        val frames = read / 2

                        if (frames <= 0) {
                            continue
                        }

                        buffer.frameCount = frames

                        for (i in 0 until frames) {

                            buffer.left[i] =
                                input[i * 2] / 32768f

                            buffer.right[i] =
                                input[i * 2 + 1] / 32768f
                        }

                        dspEngine.processBuffer(buffer)

                        for (i in 0 until frames) {

                            output[i * 2] =
                                (
                                    buffer.left[i]
                                        .coerceIn(-1f, 1f) *
                                        32767f
                                    ).toInt().toShort()

                            output[i * 2 + 1] =
                                (
                                    buffer.right[i]
                                        .coerceIn(-1f, 1f) *
                                        32767f
                                    ).toInt().toShort()
                        }

                        audioOutput.write(
                            output,
                            0,
                            frames * 2
                        )
                    }

                } catch (e: InterruptedException) {

                    Log.d(
                        TAG,
                        "Audio thread interrupted."
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Audio processing thread failed.",
                        e
                    )

                    // MutableStateFlow uses .value; postValue() belongs to LiveData.
                    _serviceError.value =
                        "Audio processing error: ${e.localizedMessage}"

                } finally {

                    isLoopRunning.set(false)

                    Log.d(
                        TAG,
                        "Audio processing thread stopped."
                    )
                }

            }.apply {

                name = "AudioDSP-Diagnostic"
                priority = Thread.MAX_PRIORITY
                start()
            }
    }

    private fun stopCapturePipeline() {

        isLoopRunning.set(false)

        audioThread?.interrupt()
        audioThread = null

        try {
            captureManager.stopCapture()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Error stopping capture manager.",
                e
            )
        }

        try {
            audioOutput.stop()
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Error stopping AudioTrack output.",
                e
            )
        }

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
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

        nativeEffect.stop()

        diagnosticCaptureMode = false

        _isServiceActive.value = false

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    private fun fail(message: String) {

        Log.e(
            TAG,
            message
        )

        _serviceError.value = message
        _isServiceActive.value = false

        stopCapturePipeline()

        nativeEffect.stop()

        diagnosticCaptureMode = false

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

        instance = null

        stopCapturePipeline()

        nativeEffect.stop()

        diagnosticCaptureMode = false

        _isServiceActive.value = false

        super.onDestroy()
    }
}
