package com.sb.dsp

import kotlin.math.max

/**
 * HeadroomManager: Calcula y gestiona dinámicamente el Auto Headroom.
 *
 * Analiza en tiempo real las ganancias agregadas del pipeline:
 * - Pre-Gain
 * - Bass Boost (estimación de ganancia equivalente en graves)
 * - Tone (Bass, Mid, Treble)
 * - Máxima ganancia positiva del modo EQ activo
 *
 * Si la suma de ganancias supera 0 dB, aplica una atenuación protectora automática
 * para garantizar que el motor DSP nunca recorte (clipping) internamente antes
 * de alcanzar el Limitador.
 */
object HeadroomManager {

    /**
     * Calcula la atenuación de headroom requerida (en dB, valor <= 0f).
     * Devuelve 0f si no hay ganancias positivas activas o si Auto Headroom está desactivado.
     */
    fun calculateRequiredHeadroomDb(config: DspConfig): Float {
        if (!config.autoHeadroomEnabled) return 0f

        var maxPotentialBoost = 0f

        // 1. Pre-Gain positivo
        if (config.preGain > 0f) {
            maxPotentialBoost += config.preGain
        }

        // 2. Bass Boost: 1000 de fuerza equivale aproximadamente a +8 dB a 60 Hz
        if (config.bassBoostEnabled && config.bassBoostStrength > 0) {
            val bassBoostDb = (config.bassBoostStrength / 1000f) * 8.0f
            maxPotentialBoost = max(maxPotentialBoost, bassBoostDb)
        }

        // 3. Controles de Tono (graves, medios, agudos)
        val maxToneBoost = maxOf(
            maxOf(0f, config.toneBass),
            maxOf(0f, config.toneMid),
            maxOf(0f, config.toneTreble)
        )
        maxPotentialBoost += maxToneBoost

        // 4. Máximo boost del ecualizador en uso
        val activeGains = config.activeEqGains()
        val maxEqBoost = activeGains.filter { it > 0f }.maxOrNull() ?: 0f
        maxPotentialBoost += maxEqBoost

        // Si la ganancia acumulada es positiva, necesitamos ese margen como reducción (-dB)
        return if (maxPotentialBoost > 0.1f) {
            // Se reserva exactamente la ganancia pico más 0.5 dB de margen de inter-sample peak
            -(maxPotentialBoost + 0.5f)
        } else {
            0f
        }
    }
}
