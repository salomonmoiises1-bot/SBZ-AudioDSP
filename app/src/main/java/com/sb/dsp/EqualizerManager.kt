package com.sb.dsp

import android.media.audiofx.Equalizer
import android.util.Log

/**
 * EqualizerManager: Gestiona el ecualizador nativo del sistema Android (android.media.audiofx.Equalizer).
 * Detecta las capacidades físicas de bandas y rangos y realiza readback de los valores reales.
 */
class EqualizerManager {
    companion object {
        private const val TAG = "SB_Equalizer"
    }

    private var equalizer: Equalizer? = null
    private var lastConfig: DspConfig? = null
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
            val bands = eq.numberOfBands
            val range = eq.bandLevelRange
            minLevelMb = range[0]
            maxLevelMb = range[1]
            numberOfBands = bands

            val freqs = mutableListOf<Int>()
            for (i in 0 until bands) {
                // centerFreq se devuelve en milithercios (mHz) -> dividir entre 1000 para Hz
                freqs.add(eq.getCenterFreq(i.toShort()) / 1000)
            }
            centerFrequenciesHz = freqs
            equalizer = eq
            isAvailable = true

            Log.d(TAG, "Equalizer nativo inicializado: $bands bandas, rango: [${minLevelMb}mB, ${maxLevelMb}mB], freqs: $centerFrequenciesHz")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer nativo no disponible en sesión $audioSessionId: ${e.message}")
            isAvailable = false
            equalizer = null
            false
        }
    }

    /**
     * Aplica las ganancias lógicas adaptadas a las bandas físicas.
     */
    fun applyConfig(config: DspConfig) {
        val eq = equalizer ?: return
        if (!isAvailable) return

        try {
            if (eq.enabled != config.dspEnabled) {
                eq.enabled = config.dspEnabled
            }

            if (!config.dspEnabled || numberOfBands <= 0) {
                lastConfig = config
                return
            }

            if (lastConfig == config) return

            // Mapear ganancias lógicas según el modo activo
            val logicalFreqs = config.activeEqFrequencies()
            val logicalGains = config.activeEqGains()

            val mappedLevelsMb = CapabilityAdapter.mapLogicalEqToNativeBands(
                logicalFreqs = logicalFreqs,
                logicalGains = logicalGains,
                nativeFreqsHz = centerFrequenciesHz,
                minLevelMb = minLevelMb,
                maxLevelMb = maxLevelMb
            )

            for (i in mappedLevelsMb.indices) {
                eq.setBandLevel(i.toShort(), mappedLevelsMb[i])
            }

            // Readback verification
            val actualLevel0 = eq.getBandLevel(0.toShort())
            Log.v(TAG, "Equalizer readback band 0: $actualLevel0 mB")
            lastConfig = config
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando Equalizer: ${e.message}", e)
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
            lastConfig = null
        }
    }
}
