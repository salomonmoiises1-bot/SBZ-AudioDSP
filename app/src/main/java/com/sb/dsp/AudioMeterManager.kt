package com.sb.dsp

import android.media.audiofx.Visualizer
import android.util.Log

/**
 * Medición de nivel del mix global mediante Visualizer.
 *
 * No captura PCM para reprocesarlo: sólo obtiene la medición Peak/RMS que Android
 * expone para la salida. En sesión 0 se usa el mix global, que es precisamente la
 * ruta que utiliza SB para su DSP system-wide.
 */
class AudioMeterManager {
    companion object {
        private const val TAG = "SB_AudioMeter"
    }

    private var visualizer: Visualizer? = null
    private val measurement = Visualizer.MeasurementPeakRms()

    var isAvailable: Boolean = false
        private set

    fun initialize(audioSessionId: Int): Boolean {
        release()
        return try {
            val v = Visualizer(audioSessionId)
            v.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
            v.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            v.enabled = true
            visualizer = v
            isAvailable = true
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Visualizer no disponible en sesión $audioSessionId: ${e.message}")
            visualizer = null
            isAvailable = false
            false
        }
    }

    /** RMS en dBFS. Devuelve null si Android no pudo entregar una medición válida. */
    @Synchronized
    fun readRmsDb(): Float? {
        val v = visualizer ?: return null
        return try {
            if (!v.enabled) return null
            val result = v.getMeasurementPeakRms(measurement)
            if (result != Visualizer.SUCCESS) return null
            (measurement.mRms / 100f).takeIf { it.isFinite() }
        } catch (_: Throwable) {
            null
        }
    }

    fun release() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Throwable) {
        } finally {
            visualizer = null
            isAvailable = false
        }
    }
}
