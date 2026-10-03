package com.sb.dsp

/**
 * AutoGain basado en una medición RMS real del mix global.
 *
 * Visualizer entrega RMS en mB/dBFS; no se inventa un nivel a partir de la suma
 * de ganancias del EQ. La corrección se limita y se suaviza para evitar pumping.
 */
class AutoGainManager {
    private var currentGainDb = 0f
    private var measuredRmsDb: Float? = null
    private val smoothingFactor = 0.08f

    fun updateMeasuredRms(rmsDb: Float?) {
        if (rmsDb == null || !rmsDb.isFinite()) return
        measuredRmsDb = rmsDb
    }

    fun calculateEffectiveGain(
        config: DspConfig,
        autoHeadroomDb: Float
    ): Float {
        if (!config.autoGainEnabled) {
            currentGainDb = 0f
            return 0f
        }

        val rms = measuredRmsDb
        if (rms == null) return currentGainDb

        val errorDb = (config.autoGainTarget - rms).coerceIn(-12f, 6f)
        // Never let AutoGain cancel the complete protective headroom.
        val safeTarget = (errorDb + autoHeadroomDb.coerceAtMost(0f) * 0.25f)
            .coerceIn(-12f, 6f)
        currentGainDb += (safeTarget - currentGainDb) * smoothingFactor
        return currentGainDb.coerceIn(-12f, 6f)
    }

    fun reset() {
        currentGainDb = 0f
        measuredRmsDb = null
    }
}
