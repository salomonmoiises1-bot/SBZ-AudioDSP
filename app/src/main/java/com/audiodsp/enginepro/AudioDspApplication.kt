package com.audiodsp.enginepro

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.audiodsp.enginepro.audio.service.AudioDspService
import com.audiodsp.enginepro.dsp.core.AudioDspEngine
import com.audiodsp.enginepro.data.preferences.DspPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class AudioDspApplication : Application() {

    val dspEngine = AudioDspEngine(sampleRate = 48000f)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        restoreDspSettings()
    }

    private fun restoreDspSettings() {
        appScope.launch {
            runCatching {
                val settings = DspPreferencesRepository(this@AudioDspApplication).settingsFlow.first()
                DspPreferencesRepository(this@AudioDspApplication).applySettings(dspEngine, settings)
            }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AudioDspService.CHANNEL_ID,
                getString(R.string.dsp_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.dsp_service_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
