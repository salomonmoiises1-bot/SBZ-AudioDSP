package com.sbznext.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sbznext.MainActivity
import com.sbznext.R
import com.sbznext.dsp.DspEngine

class PlaybackDspService : Service() {
    companion object {
        const val ACTION_STOP = "com.sbznext.STOP"
        private const val CHANNEL = "sbznext_audio"
        private const val NOTIFICATION_ID = 1001
    }

    private var engine: DspEngine? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        createChannel()
        startForegroundCompat()

        val sampleRate = getSystemService(AudioManager::class.java)
            ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()
            ?.takeIf { it >= 8000 }
            ?: 48_000

        val dsp = DspEngine(sampleRate)
        return try {
            dsp.start()
            DspRuntime.attach(dsp)
            engine = dsp
            running = true
            START_STICKY
        } catch (_: Throwable) {
            dsp.release()
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "sBz Next DSP", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_dsp)
            .setContentTitle("sBz Next")
            .setContentText("DSP global · EQ32 · MDRC · Limiter")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Detener",
                PendingIntent.getService(
                    this,
                    2,
                    Intent(this, PlaybackDspService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    override fun onDestroy() {
        running = false
        DspRuntime.detach()
        engine?.release()
        engine = null
        super.onDestroy()
    }
}
