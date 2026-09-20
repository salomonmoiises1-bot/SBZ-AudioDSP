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
 * Long-lived controller for the no-root audio effect.
 *
 * Default mode is Android's native DynamicsProcessing attached to output mix
 * session 0. The old MediaProjection capture pipeline remains available as an
 * explicit diagnostic path, but it is NOT the default because duplicating
 * captured PCM through AudioTrack can produce a dry+processed mix on devices
 * where the source playback is not muted.
 */
class AudioDspService : Service() {
    companion object {
        const val CHANNEL_ID = "audio_dsp_engine_pro_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_NATIVE = "com.audiodsp.enginepro.action.START_NATIVE"
        const val ACTION_START_CAPTURE_DIAGNOSTIC = "com.audiodsp.enginepro.action.START_CAPTURE_DIAGNOSTIC"
        const val ACTION_STOP_PROCESSING = "com.audiodsp.enginepro.action.STOP"
        const val ACTION_TOGGLE_BYPASS = "com.audiodsp.enginepro.action.TOGGLE_BYPASS"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val TAG = "AudioDspService"

        private var instance: AudioDspService? = null
        fun getInstance(): AudioDspService? = instance

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _serviceError = MutableStateFlow<String?>(null)
        val serviceError: StateFlow<String?> = _serviceError.asStateFlow()
    }

    inner class LocalBinder : Binder() { fun getService(): AudioDspService = this@AudioDspService }
    private val binder = LocalBinder()

    val dspEngine: AudioDspEngine
        get() = (application as AudioDspApplication).dspEngine

    private val nativeEffect = NativeSystemEffectController()
    private val captureManager = PlaybackCaptureManager(sampleRate = 48000)
    private val audioOutput = AudioTrackOutput(sampleRate = 48000)

    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var audioThread: Thread? = null
    private val isLoopRunning = AtomicBoolean(false)
    private var diagnosticCaptureMode = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_NATIVE -> startNativeEffect()
            ACTION_START_CAPTURE_DIAGNOSTIC -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (data != null && code != 0) startCaptureDiagnostic(code, data)
                else fail("Missing MediaProjection consent data.")
            }
            ACTION_STOP_PROCESSING -> stopProcessing()
            ACTION_TOGGLE_BYPASS -> {
                dspEngine.isBypassGlobal = !dspEngine.isBypassGlobal
                nativeEffect.sync(dspEngine)
                updateNotification()
            }
        }
        return START_STICKY
    }

    fun syncNativeEffect() {
        if (nativeEffect.isActive && !nativeEffect.sync(dspEngine)) {
            _serviceError.value = nativeEffect.lastError
        }
    }

    private fun startNativeEffect() {
        diagnosticCaptureMode = false
        stopCapturePipeline()
        startForegroundForMediaPlayback()
        if (!nativeEffect.start(dspEngine)) {
            fail(nativeEffect.lastError ?: "Native Android audio effect is unavailable on this device.")
            return
        }
        _serviceError.value = null
        _isServiceActive.value = true
        updateNotification()
    }

    private fun startCaptureDiagnostic(code: Int, data: Intent) {
        diagnosticCaptureMode = true
        nativeEffect.stop()
        stopCapturePipeline()
        startForegroundForMediaProjection()
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection = manager.getMediaProjection(code, data) ?: throw IllegalStateException("MediaProjection unavailable")
            mediaProjection = projection
            projection.registerCallback(object : android.media.projection.MediaProjection.Callback() {
                override fun onStop() { stopProcessing() }
            }, null)
            if (!captureManager.startCapture(projection)) throw IllegalStateException(captureManager.currentStatus.toString())
            if (!audioOutput.start()) throw IllegalStateException("AudioTrack output failed")
            startAudioThread()
            _isServiceActive.value = true
            _serviceError.value = "Diagnostic capture mode: source audio may remain audible on some devices."
        } catch (e: Exception) {
            fail("Capture diagnostic failed: ${e.localizedMessage}")
        }
    }

    private fun startForegroundForMediaPlayback() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun startForegroundForMediaProjection() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AudioDspService::class.java).setAction(ACTION_STOP_PROCESSING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val bypass = PendingIntent.getService(
            this, 2, Intent(this, AudioDspService::class.java).setAction(ACTION_TOGGLE_BYPASS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mode = if (diagnosticCaptureMode) "Diagnostic capture DSP" else "Native Android output DSP"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.dsp_running_title))
            .setContentText(mode)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, if (dspEngine.isBypassGlobal) "Enable DSP" else "Bypass DSP", bypass)
            .addAction(0, getString(R.string.dsp_stop_action), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startAudioThread() {
        isLoopRunning.set(true)
        audioThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frameSize = 512
            val shortCount = frameSize * 2
            val input = ShortArray(shortCount)
            val output = ShortArray(shortCount)
            val buffer = AudioBuffer(frameSize)
            while (isLoopRunning.get()) {
                val read = captureManager.read(input, 0, shortCount)
                if (read <= 0) break
                val frames = read / 2
                buffer.frameCount = frames
                for (i in 0 until frames) {
                    buffer.left[i] = input[i * 2] / 32768f
                    buffer.right[i] = input[i * 2 + 1] / 32768f
                }
                dspEngine.processBuffer(buffer)
                for (i in 0 until frames) {
                    output[i * 2] = (buffer.left[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    output[i * 2 + 1] = (buffer.right[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                audioOutput.write(output, 0, frames * 2)
            }
        }.apply { name = "AudioDSP-Diagnostic"; start() }
    }

    private fun stopCapturePipeline() {
        isLoopRunning.set(false)
        audioThread?.interrupt()
        audioThread = null
        captureManager.stopCapture()
        audioOutput.stop()
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun stopProcessing() {
        stopCapturePipeline()
        nativeEffect.stop()
        _isServiceActive.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        _serviceError.value = message
        _isServiceActive.value = false
        stopCapturePipeline()
        nativeEffect.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        stopCapturePipeline()
        nativeEffect.stop()
        _isServiceActive.value = false
        super.onDestroy()
    }
}
