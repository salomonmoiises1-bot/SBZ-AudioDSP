package com.sb.dsp

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * CapabilityAdapter: Responsable de mapear el estado lógico de DspConfig a las
 * capacidades físicas reales que el dispositivo Android ofrece.
 *
 * Principio fundamental de SB:
 * NUNCA engañar al usuario. Si el usuario mueve 32 bandas lógicas pero el hardware
 * solo posee 5 bandas nativas, este adaptador realiza la interpolación acústica óptima
 * (mediante distancia logarítmica de frecuencia) y expone la diferencia entre:
 * - Logical bands (32)
 * - Applied hardware bands (e.g. 5)
 */
object CapabilityAdapter {

    /**
     * Mapea una lista de bandas lógicas (frecuencias y ganancias en dB) a las bandas
     * físicas del ecualizador nativo de Android.
     */
    fun mapLogicalEqToNativeBands(
        logicalFreqs: List<Float>,
        logicalGains: List<Float>,
        nativeFreqsHz: List<Int>,
        minLevelMb: Short,
        maxLevelMb: Short
    ): ShortArray {
        if (nativeFreqsHz.isEmpty() || logicalFreqs.isEmpty() || logicalGains.isEmpty()) {
            return ShortArray(0)
        }

        val result = ShortArray(nativeFreqsHz.size)

        for (i in nativeFreqsHz.indices) {
            val targetFreq = nativeFreqsHz[i].toFloat()
            val interpolatedDb = interpolateGainAtFrequency(targetFreq, logicalFreqs, logicalGains)

            // Convertir dB a milibeles (1 dB = 100 mB)
            val millibels = (interpolatedDb * 100f).roundToInt()
            val clampedMb = millibels.coerceIn(minLevelMb.toInt(), maxLevelMb.toInt()).toShort()
            result[i] = clampedMb
        }

        return result
    }

    /**
     * Realiza una interpolación logarítmica (en octavas) para obtener la ganancia
     * de ecualización estimada para cualquier frecuencia objetivo.
     */
    fun interpolateGainAtFrequency(
        targetFreq: Float,
        sourceFreqs: List<Float>,
        sourceGains: List<Float>
    ): Float {
        if (sourceFreqs.isEmpty() || sourceGains.isEmpty()) return 0f
        if (targetFreq <= sourceFreqs.first()) return sourceGains.first()
        if (targetFreq >= sourceFreqs.last()) return sourceGains.last()

        val logTarget = ln(targetFreq)

        // Encontrar intervalo
        for (i in 0 until sourceFreqs.size - 1) {
            val f1 = sourceFreqs[i]
            val f2 = sourceFreqs[i + 1]

            if (targetFreq in f1..f2) {
                val logF1 = ln(f1)
                val logF2 = ln(f2)
                val fraction = if (logF2 != logF1) (logTarget - logF1) / (logF2 - logF1) else 0f
                val g1 = sourceGains[i]
                val g2 = sourceGains[i + 1]
                return g1 + fraction * (g2 - g1)
            }
        }

        return sourceGains.last()
    }

    /**
     * Mapea ganancias de 32 bandas lógicas a N bandas de DynamicsProcessing PreEQ o PostEQ.
     */
    fun mapLogicalEqToDynamicsProcessingBands(
        logicalFreqs: List<Float>,
        logicalGains: List<Float>,
        targetBandFreqs: List<Float>
    ): FloatArray {
        val result = FloatArray(targetBandFreqs.size)
        for (i in targetBandFreqs.indices) {
            result[i] = interpolateGainAtFrequency(targetBandFreqs[i], logicalFreqs, logicalGains)
        }
        return result
    }

    /**
     * Genera una descripción técnica transparente de cómo se aplica la configuración.
     */
    fun getMappingAuditDescription(
        eqMode: EqMode,
        capabilities: DspCapabilities
    ): String {
        val logicalCount = when (eqMode) {
            EqMode.EQ10 -> 10
            EqMode.EQ20 -> 20
            EqMode.EQ32 -> 32
        }

        val hwBands = capabilities.effectiveHardwareEqBands
        return if (hwBands == 0) {
            "Aviso: Ningún ecualizador por hardware disponible en esta sesión."
        } else if (hwBands >= logicalCount) {
            "Mapeo 1:1 directo: Las $logicalCount bandas lógicas se procesan en $hwBands bandas físicas."
        } else {
            "Mapeo acústico adaptativo: $logicalCount bandas lógicas proyectadas logarítmicamente sobre $hwBands bandas físicas reales de Android."
        }
    }
}
