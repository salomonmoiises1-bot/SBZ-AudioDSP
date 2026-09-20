package com.audiodsp.enginepro.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.audiodsp.enginepro.audio.service.AudioDspService
import com.audiodsp.enginepro.ui.screens.MainScreen
import com.audiodsp.enginepro.ui.theme.AudioDSPEngineProTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AudioDSPEngineProTheme {
                MainScreen(
                    onStartRequested = { startNativeDsp() },
                    onStopRequested = { stopAudioService() }
                )
            }
        }
    }

    private fun startNativeDsp() {
        val serviceIntent = Intent(this, AudioDspService::class.java).apply {
            action = AudioDspService.ACTION_START_NATIVE
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo iniciar el DSP nativo: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAudioService() {
        val serviceIntent = Intent(this, AudioDspService::class.java).apply {
            action = AudioDspService.ACTION_STOP_PROCESSING
        }
        startService(serviceIntent)
    }
}
