package com.sb.dsp

/**
 * Calcula el headroom preventivo antes del procesamiento dinámico.
 * Suma los boosts positivos que pueden coexistir; no usa el máximo de cada
 * etapa porque eso subestimaría el margen necesario cuando varias etapas están
 * elevando la misma señal.
 */
object HeadroomManager {
    fun calculateRequiredHeadroomDb(config: DspConfig): Float {
        if (!config.autoHeadroomEnabled) return 0f

        var potentialBoost = 0f

        if (config.preGain > 0f) {
            potentialBoost += config.preGain
        }

        if (config.bassBoostEnabled && config.bassBoostStrength > 0) {
            potentialBoost += (config.bassBoostStrength / 1000f) * 9f
        }

        potentialBoost += maxOf(
            0f,
            config.toneBass,
            config.toneMid,
            config.toneTreble
        )

        potentialBoost += config.activeEqGains().maxOrNull()?.coerceAtLeast(0f) ?: 0f

        return if (potentialBoost > 0.1f) {
            -(potentialBoost + 0.5f)
        } else {
            0f
        }
    }
}
