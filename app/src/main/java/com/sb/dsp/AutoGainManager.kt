package com.sb.dsp

import kotlin.math.abs

/**
 * AutoGainManager: Normalización y compensación automática de ganancia (AGC/Auto-Gain).
 *
 * Mantiene el nivel de volumen percibido objetivo, coordinándose con Auto Headroom
 * para evitar el efecto "pumping" (bombeo de volumen).
 */
class AutoGainManager {
    private var currentCompensatedGainDb = 0f
    private val smoothingFactor = 0.05f // Filtro pasa-bajos para cambios suaves

    /**
     * Calcula la ganancia makeup en dB para alcanzar el objetivo configurado.
     * Garantiza que la ganancia makeup nunca supere el límite seguro de +12 dB.
     */
    fun calculateEffectiveGain(
        config: DspConfig,
        autoHeadroomDb: Float,
        measuredRmsDb: Float = -96f
    ): Float {
        if (!config.autoGainEnabled) {
            currentCompensatedGainDb = 0f
            return 0f
        }

        // Fallback cuando no hay medición válida del Visualizer:
        // estimación del offset medio introducido por el EQ y pre-gain
        val avgEqGain = if (config.activeEqGains().isNotEmpty()) {
            config.activeEqGains().average().toFloat()
        } else {
            0f
        }

        val netInputGain = config.preGain + (avgEqGain * 0.5f)
        // Usa medición real del Visualizer cuando está disponible; conserva el cálculo anterior como fallback.
        val targetDelta = if (measuredRmsDb > -95f) {
            (config.autoGainTarget - measuredRmsDb).coerceIn(-12f, 6f)
        } else {
            -netInputGain
        }

        // Limitar la ganancia makeup entre -12 dB y +6 dB
        val clampedTarget = targetDelta.coerceIn(-12f, 6f)

        // Suavizado temporal exponencial para evitar saltos o bombeos acústicos
        currentCompensatedGainDb += (clampedTarget - currentCompensatedGainDb) * smoothingFactor

        // Si autoHeadroom está reduciendo, compensamos solo hasta el 50% de la reducción para no anular la protección
        val safeHeadroomCompensation = abs(autoHeadroomDb) * 0.3f
        return (currentCompensatedGainDb + safeHeadroomCompensation).coerceIn(-12f, 6f)
    }

    fun reset() {
        currentCompensatedGainDb = 0f
    }
}
