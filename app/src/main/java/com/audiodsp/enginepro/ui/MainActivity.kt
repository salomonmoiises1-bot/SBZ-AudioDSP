package com.audiodsp.enginepro.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
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

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 5001
    }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContent {
            AudioDSPEngineProTheme {

                MainScreen(
                    onStartRequested = {
                        requestAudioCapture()
                    },
                    onStopRequested = {
                        stopAudioService()
                    }
                )
            }
        }
    }

    /**
     * Requests Android's official MediaProjection permission.
     *
     * This is required for AudioPlaybackCapture on Android 10+
     * and is the entry point of the software DSP pipeline.
     */
    private fun requestAudioCapture() {

        try {

            val projectionManager =
                getSystemService(
                    MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            val captureIntent =
                projectionManager.createScreenCaptureIntent()

            startActivityForResult(
                captureIntent,
                REQUEST_MEDIA_PROJECTION
            )

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "No se pudo solicitar la captura de audio: " +
                    (e.localizedMessage ?: "Error desconocido"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated(
        "Deprecated in AndroidX Activity, but retained for compatibility " +
            "with the current project structure."
    )
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode !=
            REQUEST_MEDIA_PROJECTION
        ) {
            return
        }

        if (
            resultCode == RESULT_OK &&
            data != null
        ) {

            startSoftwareDspService(
                resultCode,
                data
            )

        } else {

            Toast.makeText(
                this,
                "La captura de audio fue cancelada.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Starts the real Android 14 software DSP pipeline.
     */
    private fun startSoftwareDspService(
        resultCode: Int,
        resultData: Intent
    ) {

        val serviceIntent =
            Intent(
                this,
                AudioDspService::class.java
            ).apply {

                action =
                    AudioDspService
                        .ACTION_START_CAPTURE_DIAGNOSTIC

                putExtra(
                    AudioDspService.EXTRA_RESULT_CODE,
                    resultCode
                )

                putExtra(
                    AudioDspService.EXTRA_RESULT_DATA,
                    resultData
                )
            }

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                ContextCompat.startForegroundService(
                    this,
                    serviceIntent
                )

            } else {

                startService(
                    serviceIntent
                )
            }

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "No se pudo iniciar el DSP: " +
                    (
                        e.localizedMessage
                            ?: "Error desconocido"
                    ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopAudioService() {

        val serviceIntent =
            Intent(
                this,
                AudioDspService::class.java
            ).apply {

                action =
                    AudioDspService
                        .ACTION_STOP_PROCESSING
            }

        try {

            startService(
                serviceIntent
            )

        } catch (e: Exception) {

            Toast.makeText(
                this,
                "No se pudo detener el DSP: " +
                    (
                        e.localizedMessage
                            ?: "Error desconocido"
                    ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
