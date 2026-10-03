package com.sb.dsp

/**
 * Utilidades de diagnóstico para SB.
 *
 * El EQ de SB no se proyecta sobre el ecualizador nativo de Android: las 32
 * bandas de DspConfig se aplican 1:1 al banco Post-EQ de DynamicsProcessing.
 */
object CapabilityAdapter {
    fun interpolateGainAtFrequency(
        targetFreq: Float,
        sourceFreqs: List<Float>,
        sourceGains: List<Float>
    ): Float {
        if (sourceFreqs.isEmpty() || sourceGains.isEmpty()) return 0f
        if (targetFreq <= sourceFreqs.first()) return sourceGains.first()
        if (targetFreq >= sourceFreqs.last()) return sourceGains.last()
        for (i in 0 until minOf(sourceFreqs.size, sourceGains.size) - 1) {
            val f1 = sourceFreqs[i]
            val f2 = sourceFreqs[i + 1]
            if (targetFreq in f1..f2) {
                val logF1 = kotlin.math.ln(f1)
                val logF2 = kotlin.math.ln(f2)
                val logTarget = kotlin.math.ln(targetFreq)
                val fraction = if (logF2 != logF1) (logTarget - logF1) / (logF2 - logF1) else 0f
                return sourceGains[i] + fraction * (sourceGains[i + 1] - sourceGains[i])
            }
        }
        return sourceGains.last()
    }

    fun getMappingAuditDescription(capabilities: DspCapabilities): String =
        if (capabilities.hasDynamicsProcessing) {
            "EQ32: 32 bandas reales 1:1 en el Post-EQ de DynamicsProcessing. El Equalizer nativo no participa en el procesamiento del EQ."
        } else {
            "EQ32: requiere DynamicsProcessing para aplicar las 32 bandas reales; no se proyecta sobre el Equalizer nativo."
        }
}
