package com.sb.dsp

import android.media.audiofx.Equalizer
import android.util.Log

/**
 * Detecta el Equalizer nativo únicamente para diagnóstico de capacidades.
 *
 * El procesamiento del EQ de SB NO utiliza este efecto. El EQ activo es siempre
 * el banco EQ32 de 32 bandas aplicado 1:1 al Post-EQ de DynamicsProcessing.
 */
class EqualizerManager {
    companion object {
        private const val TAG = "SB_Equalizer"
    }

    private var equalizer: Equalizer? = null
    var isAvailable: Boolean = false
        private set
    var numberOfBands: Short = 0
        private set
    var minLevelMb: Short = -1500
        private set
    var maxLevelMb: Short = 1500
        private set
    var centerFrequenciesHz: List<Int> = emptyList()
        private set

    fun initialize(audioSessionId: Int, priority: Int = 1000): Boolean {
        release()
        return try {
            val eq = Equalizer(priority, audioSessionId)
            numberOfBands = eq.numberOfBands
            val range = eq.bandLevelRange
            minLevelMb = range[0]
            maxLevelMb = range[1]
            centerFrequenciesHz = buildList {
                for (i in 0 until numberOfBands.toInt()) {
                    add(eq.getCenterFreq(i.toShort()) / 1000)
                }
            }
            equalizer = eq
            // Disabled: capability inspection only; never becomes the SB EQ backend.
            eq.enabled = false
            isAvailable = true
            Log.d(TAG, "Equalizer nativo detectado para diagnóstico: $numberOfBands bandas, freqs=$centerFrequenciesHz")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer nativo no disponible en sesión $audioSessionId: ${e.message}")
            isAvailable = false
            equalizer = null
            false
        }
    }

    fun release() {
        try {
            equalizer?.enabled = false
            equalizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar Equalizer: ${e.message}")
        } finally {
            equalizer = null
            isAvailable = false
            numberOfBands = 0
            centerFrequenciesHz = emptyList()
        }
    }
}
