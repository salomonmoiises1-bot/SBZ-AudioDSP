package com.sb.dsp

import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.sqrt

/**
 * Visualizer usado exclusivamente como instrumento de medición/feedback.
 * No procesa ni modifica el audio.
 */
class VisualizerManager {
    companion object {
        private const val TAG = "SB_Visualizer"
        private const val CAPTURE_RATE_HZ = 30
    }

    private var visualizer: Visualizer? = null
    private var waveformBuffer = ByteArray(0)
    @Volatile private var rmsDb = -96f
    @Volatile private var peakDb = -96f

    val currentRmsDb: Float get() = rmsDb
    val currentPeakDb: Float get() = peakDb

    fun initialize(audioSessionId: Int): Boolean {
        release()
        return try {
            val v = Visualizer(audioSessionId)
            val range = Visualizer.getCaptureSizeRange()
            val size = range[0].coerceAtLeast(256).coerceAtMost(range[1]).coerceAtMost(1024)
            waveformBuffer = ByteArray(size)
            v.captureSize = size
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer,
                        waveform: ByteArray,
                        samplingRate: Int
                    ) {
                        updateLevels(waveform)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer,
                        fft: ByteArray,
                        samplingRate: Int
                    ) = Unit
                },
                (CAPTURE_RATE_HZ * 1000).coerceAtMost(Visualizer.getMaxCaptureRate()),
                true,
                false
            )
            v.enabled = true
            visualizer = v
            Log.d(TAG, "Visualizer activo en sesión $audioSessionId, captura=${size}B")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "No se pudo iniciar Visualizer en sesión $audioSessionId: ${e.message}")
            visualizer = null
            false
        }
    }

    private fun updateLevels(waveform: ByteArray) {
        if (waveform.isEmpty()) return
        var sumSquares = 0.0
        var peak = 0.0
        for (sample in waveform) {
            val normalized = sample.toInt() / 128.0
            val absSample = kotlin.math.abs(normalized)
            sumSquares += normalized * normalized
            if (absSample > peak) peak = absSample
        }
        val rms = sqrt(sumSquares / waveform.size).coerceAtLeast(1.0e-6)
        rmsDb = (20.0 * kotlin.math.log10(rms)).toFloat().coerceIn(-96f, 0f)
        peakDb = (20.0 * kotlin.math.log10(peak.coerceAtLeast(1.0e-6))).toFloat().coerceIn(-96f, 0f)
    }

    fun reset() {
        rmsDb = -96f
        peakDb = -96f
    }

    fun release() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Throwable) {
        } finally {
            visualizer = null
            waveformBuffer = ByteArray(0)
            reset()
        }
    }
}
