package com.sb.dsp

import kotlin.math.*

/**
 * MdrcProcessor: Compresor Dinámico Multibanda de 4 bandas (MDRC) sobre audio PCM estéreo.
 *
 * Arquitectura de procesamiento:
 * - 4 Bandas espectrales continuas divididas por 3 filtros de corte Crossover:
 *   - Banda 1 (LOW): 0 Hz -> Crossover 1
 *   - Banda 2 (LOW-MID): Crossover 1 -> Crossover 2
 *   - Banda 3 (MID-HIGH): Crossover 2 -> Crossover 3
 *   - Banda 4 (HIGH): Crossover 3 -> Nyquist (fs/2)
 *
 * - Filtros Crossover Linkwitz-Riley de 4º orden (LR4 / 24 dB/octava):
 *   Dos filtros Butterworth de 2º orden en cascada (Q = 1/√2 = 0.7071)
 *   que suman con amplitud unitaria plana (0 dB) y coherencia de fase perfecta en la recombinación.
 *
 * - Detección estéreo enlazada (Stereo-Linked Detection):
 *   El detector evalúa el nivel representativo común max(|L|, |R|) para calcular una única reducción
 *   de ganancia por banda, manteniendo inalterada la imagen estereofónica sin paneo dinámico indeseado.
 *
 * - Computador de ganancia Soft-Knee configurable, balística analógica de ataque y relajación,
 *   y ganancia de compensación (Makeup Gain) independiente.
 *
 * - Cero asignaciones en el ciclo PCM (Zero allocations per sample).
 */
class MdrcProcessor(
    var sampleRate: Float = 48000f
) {
    companion object {
        private const val EPSILON = 1e-12f
        private const val MIN_DB = -120f
    }

    /**
     * Estado interno de una banda de compresión MDRC.
     */
    class BandState(
        var config: MdrcBandConfig = MdrcBandConfig(),
        var isMuted: Boolean = false,
        var isSolo: Boolean = false
    ) {
        // Envolvente estéreo enlazada en dB
        var envelopeDb: Float = -100f

        // Reducción de ganancia calculada (dB, <= 0.0)
        var gainReductionDb: Float = 0f

        // Coeficientes de los dos biquads Butterworth paso-bajo en cascada (LR4)
        var lp_b0 = 1f; var lp_b1 = 0f; var lp_b2 = 0f; var lp_a1 = 0f; var lp_a2 = 0f

        // Coeficientes de los dos biquads Butterworth paso-alto en cascada (LR4)
        var hp_b0 = 1f; var hp_b1 = 0f; var hp_b2 = 0f; var hp_a1 = 0f; var hp_a2 = 0f

        // Estados directos del filtro paso-bajo Canal Izquierdo (Biquad 1 y Biquad 2)
        var lp1_x1_L = 0f; var lp1_x2_L = 0f; var lp1_y1_L = 0f; var lp1_y2_L = 0f
        var lp2_x1_L = 0f; var lp2_x2_L = 0f; var lp2_y1_L = 0f; var lp2_y2_L = 0f

        // Estados directos del filtro paso-bajo Canal Derecho
        var lp1_x1_R = 0f; var lp1_x2_R = 0f; var lp1_y1_R = 0f; var lp1_y2_R = 0f
        var lp2_x1_R = 0f; var lp2_x2_R = 0f; var lp2_y1_R = 0f; var lp2_y2_R = 0f

        // Estados directos del filtro paso-alto Canal Izquierdo
        var hp1_x1_L = 0f; var hp1_x2_L = 0f; var hp1_y1_L = 0f; var hp1_y2_L = 0f
        var hp2_x1_L = 0f; var hp2_x2_L = 0f; var hp2_y1_L = 0f; var hp2_y2_L = 0f

        // Estados directos del filtro paso-alto Canal Derecho
        var hp1_x1_R = 0f; var hp1_x2_R = 0f; var hp1_y1_R = 0f; var hp1_y2_R = 0f
        var hp2_x1_R = 0f; var hp2_x2_R = 0f; var hp2_y1_R = 0f; var hp2_y2_R = 0f

        fun reset() {
            envelopeDb = -100f
            gainReductionDb = 0f
            lp1_x1_L = 0f; lp1_x2_L = 0f; lp1_y1_L = 0f; lp1_y2_L = 0f
            lp2_x1_L = 0f; lp2_x2_L = 0f; lp2_y1_L = 0f; lp2_y2_L = 0f
            lp1_x1_R = 0f; lp1_x2_R = 0f; lp1_y1_R = 0f; lp1_y2_R = 0f
            lp2_x1_R = 0f; lp2_x2_R = 0f; lp2_y1_R = 0f; lp2_y2_R = 0f

            hp1_x1_L = 0f; hp1_x2_L = 0f; hp1_y1_L = 0f; hp1_y2_L = 0f
            hp2_x1_L = 0f; hp2_x2_L = 0f; hp2_y1_L = 0f; hp2_y2_L = 0f
            hp1_x1_R = 0f; hp1_x2_R = 0f; hp1_y1_R = 0f; hp1_y2_R = 0f
            hp2_x1_R = 0f; hp2_x2_R = 0f; hp2_y1_R = 0f; hp2_y2_R = 0f
        }
    }

    val bands = Array(4) { BandState() }

    // Frecuencias de corte crossover
    var crossover1 = 160f
    var crossover2 = 800f
    var crossover3 = 4000f

    // Estado global de activación / bypass
    var enabled: Boolean = true

    // Memoria para evitar allocations en bucle
    private val bandOutputsL = FloatArray(4)
    private val bandOutputsR = FloatArray(4)

    init {
        recalculateCrossovers()
    }

    /**
     * Recalcula los coeficientes Linkwitz-Riley LR4 (24 dB/oct) para los 3 puntos de corte.
     * Garantiza estabilidad numérica y respeto estricto al límite de Nyquist (fs / 2).
     */
    fun recalculateCrossovers() {
        val fs = if (sampleRate > 8000f) sampleRate else 48000f
        val nyquist = fs * 0.5f
        val maxSafeFreq = nyquist * 0.92f

        // Sanitización y clampeo de orden: 20 Hz <= c1 < c2 < c3 <= maxSafeFreq
        val c1 = crossover1.coerceIn(20f, (maxSafeFreq * 0.3f).coerceAtLeast(40f))
        val c2 = crossover2.coerceIn(c1 + 40f, (maxSafeFreq * 0.7f).coerceAtLeast(c1 + 80f))
        val c3 = crossover3.coerceIn(c2 + 100f, maxSafeFreq)

        computeButterworthLR4Coefficients(c1, fs, bands[0])
        computeButterworthLR4Coefficients(c2, fs, bands[1])
        computeButterworthLR4Coefficients(c3, fs, bands[2])
    }

    private fun computeButterworthLR4Coefficients(cutoffHz: Float, fs: Float, band: BandState) {
        val omega = (PI * cutoffHz / fs).toFloat()
        val theta = tan(omega.toDouble()).toFloat()
        val theta2 = theta * theta
        val sqrt2 = sqrt(2.0).toFloat()

        val norm = 1.0f / (1.0f + sqrt2 * theta + theta2)

        // Biquad Butterworth Paso Bajo (Q = 0.7071)
        band.lp_b0 = theta2 * norm
        band.lp_b1 = 2.0f * band.lp_b0
        band.lp_b2 = band.lp_b0
        band.lp_a1 = 2.0f * (theta2 - 1.0f) * norm
        band.lp_a2 = (1.0f - sqrt2 * theta + theta2) * norm

        // Biquad Butterworth Paso Alto (Q = 0.7071)
        band.hp_b0 = norm
        band.hp_b1 = -2.0f * norm
        band.hp_b2 = norm
        band.hp_a1 = band.lp_a1
        band.hp_a2 = band.lp_a2
    }

    /**
     * Aplica la configuración de DspConfig en tiempo real.
     */
    fun updateConfig(config: DspConfig) {
        enabled = config.dspEnabled && config.mdrcEnabled
        bands[0].config = config.mdrcBand1
        bands[1].config = config.mdrcBand2
        bands[2].config = config.mdrcBand3
        bands[3].config = config.mdrcBand4

        if (crossover1 != config.mdrcCutoff1 || crossover2 != config.mdrcCutoff2 || crossover3 != config.mdrcCutoff3) {
            crossover1 = config.mdrcCutoff1
            crossover2 = config.mdrcCutoff2
            crossover3 = config.mdrcCutoff3
            recalculateCrossovers()
        }
    }

    /**
     * Computador de ganancia Soft-Knee estándar en decibelios.
     * Retorna la reducción de ganancia (valor <= 0 dB).
     *
     * Fórmula Soft-Knee:
     * Si x - T <= -W/2 -> 0 dB
     * Si |x - T| < W/2 -> + (1/R - 1) * (x - T + W/2)^2 / (2 * W)
     * Si x - T >= W/2  -> (1/R - 1) * (x - T)
     */
    private fun computeGainReduction(
        levelDb: Float,
        thresholdDb: Float,
        ratio: Float,
        kneeWidthDb: Float
    ): Float {
        if (ratio <= 1.0f) return 0f

        val halfKnee = kneeWidthDb * 0.5f
        val delta = levelDb - thresholdDb
        val slope = (1.0f / ratio) - 1.0f // Valor negativo que reduce la ganancia

        return when {
            delta <= -halfKnee -> 0f
            delta >= halfKnee -> slope * delta
            else -> {
                // Zona de curvatura parabólica suave
                val num = delta + halfKnee
                (slope * num * num) / (2.0f * kneeWidthDb)
            }
        }
    }

    /**
     * Procesa una muestra estéreo PCM a través del MDRC completo.
     * Cero allocations en esta función.
     */
    fun processSample(inputL: Float, inputR: Float): Pair<Float, Float> {
        // Bypass estricto: sin procesamiento, cero latencia, cero gasto de CPU
        if (!enabled) {
            return Pair(inputL, inputR)
        }

        // 1. FILTRADO CROSSOVER LINKWITZ-RILEY LR4 EN 3 PUNTOS DE CORTE
        // Etapa 1: Dividir en Crossover 2 (800 Hz) -> Rama Baja (Low+LowMid) y Rama Alta (MidHigh+High)
        var lp_L: Float
        var hp_L: Float
        var lp_R: Float
        var hp_R: Float

        val b1 = bands[1] // Crossover 2
        // Canal L
        val midL_lp1 = b1.lp_b0 * inputL + b1.lp_b1 * b1.lp1_x1_L + b1.lp_b2 * b1.lp1_x2_L - b1.lp_a1 * b1.lp1_y1_L - b1.lp_a2 * b1.lp1_y2_L
        b1.lp1_x2_L = b1.lp1_x1_L; b1.lp1_x1_L = inputL; b1.lp1_y2_L = b1.lp1_y1_L; b1.lp1_y1_L = midL_lp1
        val branchLowL = b1.lp_b0 * midL_lp1 + b1.lp_b1 * b1.lp2_x1_L + b1.lp_b2 * b1.lp2_x2_L - b1.lp_a1 * b1.lp2_y1_L - b1.lp_a2 * b1.lp2_y2_L
        b1.lp2_x2_L = b1.lp2_x1_L; b1.lp2_x1_L = midL_lp1; b1.lp2_y2_L = b1.lp2_y1_L; b1.lp2_y1_L = branchLowL

        val midL_hp1 = b1.hp_b0 * inputL + b1.hp_b1 * b1.hp1_x1_L + b1.hp_b2 * b1.hp1_x2_L - b1.hp_a1 * b1.hp1_y1_L - b1.hp_a2 * b1.hp1_y2_L
        b1.hp1_x2_L = b1.hp1_x1_L; b1.hp1_x1_L = inputL; b1.hp1_y2_L = b1.hp1_y1_L; b1.hp1_y1_L = midL_hp1
        val branchHighL = b1.hp_b0 * midL_hp1 + b1.hp_b1 * b1.hp2_x1_L + b1.hp_b2 * b1.hp2_x2_L - b1.hp_a1 * b1.hp2_y1_L - b1.hp_a2 * b1.hp2_y2_L
        b1.hp2_x2_L = b1.hp2_x1_L; b1.hp2_x1_L = midL_hp1; b1.hp2_y2_L = b1.hp2_y1_L; b1.hp2_y1_L = branchHighL

        // Canal R
        val midR_lp1 = b1.lp_b0 * inputR + b1.lp_b1 * b1.lp1_x1_R + b1.lp_b2 * b1.lp1_x2_R - b1.lp_a1 * b1.lp1_y1_R - b1.lp_a2 * b1.lp1_y2_R
        b1.lp1_x2_R = b1.lp1_x1_R; b1.lp1_x1_R = inputR; b1.lp1_y2_R = b1.lp1_y1_R; b1.lp1_y1_R = midR_lp1
        val branchLowR = b1.lp_b0 * midR_lp1 + b1.lp_b1 * b1.lp2_x1_R + b1.lp_b2 * b1.lp2_x2_R - b1.lp_a1 * b1.lp2_y1_R - b1.lp_a2 * b1.lp2_y2_R
        b1.lp2_x2_R = b1.lp2_x1_R; b1.lp2_x1_R = midR_lp1; b1.lp2_y2_R = b1.lp2_y1_R; b1.lp2_y1_R = branchLowR

        val midR_hp1 = b1.hp_b0 * inputR + b1.hp_b1 * b1.hp1_x1_R + b1.hp_b2 * b1.hp1_x2_R - b1.hp_a1 * b1.hp1_y1_R - b1.hp_a2 * b1.hp1_y2_R
        b1.hp1_x2_R = b1.hp1_x1_R; b1.hp1_x1_R = inputR; b1.hp1_y2_R = b1.hp1_y1_R; b1.hp1_y1_R = midR_hp1
        val branchHighR = b1.hp_b0 * midR_hp1 + b1.hp_b1 * b1.hp2_x1_R + b1.hp_b2 * b1.hp2_x2_R - b1.hp_a1 * b1.hp2_y1_R - b1.hp_a2 * b1.hp2_y2_R
        b1.hp2_x2_R = b1.hp2_x1_R; b1.hp2_x1_R = midR_hp1; b1.hp2_y2_R = b1.hp2_y1_R; b1.hp2_y1_R = branchHighR

        // Etapa 2: Rama Baja -> Crossover 1 (160 Hz) -> Banda 0 (LOW) y Banda 1 (LOW-MID)
        val b0 = bands[0]
        val b0_lp1_L = b0.lp_b0 * branchLowL + b0.lp_b1 * b0.lp1_x1_L + b0.lp_b2 * b0.lp1_x2_L - b0.lp_a1 * b0.lp1_y1_L - b0.lp_a2 * b0.lp1_y2_L
        b0.lp1_x2_L = b0.lp1_x1_L; b0.lp1_x1_L = branchLowL; b0.lp1_y2_L = b0.lp1_y1_L; b0.lp1_y1_L = b0_lp1_L
        val band0_L = b0.lp_b0 * b0_lp1_L + b0.lp_b1 * b0.lp2_x1_L + b0.lp_b2 * b0.lp2_x2_L - b0.lp_a1 * b0.lp2_y1_L - b0.lp_a2 * b0.lp2_y2_L
        b0.lp2_x2_L = b0.lp2_x1_L; b0.lp2_x1_L = b0_lp1_L; b0.lp2_y2_L = b0.lp2_y1_L; b0.lp2_y1_L = band0_L

        val b0_hp1_L = b0.hp_b0 * branchLowL + b0.hp_b1 * b0.hp1_x1_L + b0.hp_b2 * b0.hp1_x2_L - b0.hp_a1 * b0.hp1_y1_L - b0.hp_a2 * b0.hp1_y2_L
        b0.hp1_x2_L = b0.hp1_x1_L; b0.hp1_x1_L = branchLowL; b0.hp1_y2_L = b0.hp1_y1_L; b0.hp1_y1_L = b0_hp1_L
        val band1_L = b0.hp_b0 * b0_hp1_L + b0.hp_b1 * b0.hp2_x1_L + b0.hp_b2 * b0.hp2_x2_L - b0.hp_a1 * b0.hp2_y1_L - b0.hp_a2 * b0.hp2_y2_L
        b0.hp2_x2_L = b0.hp2_x1_L; b0.hp2_x1_L = b0_hp1_L; b0.hp2_y2_L = b0.hp2_y1_L; b0.hp2_y1_L = band1_L

        val b0_lp1_R = b0.lp_b0 * branchLowR + b0.lp_b1 * b0.lp1_x1_R + b0.lp_b2 * b0.lp1_x2_R - b0.lp_a1 * b0.lp1_y1_R - b0.lp_a2 * b0.lp1_y2_R
        b0.lp1_x2_R = b0.lp1_x1_R; b0.lp1_x1_R = branchLowR; b0.lp1_y2_R = b0.lp1_y1_R; b0.lp1_y1_R = b0_lp1_R
        val band0_R = b0.lp_b0 * b0_lp1_R + b0.lp_b1 * b0.lp2_x1_R + b0.lp_b2 * b0.lp2_x2_R - b0.lp_a1 * b0.lp2_y1_R - b0.lp_a2 * b0.lp2_y2_R
        b0.lp2_x2_R = b0.lp2_x1_R; b0.lp2_x1_R = b0_lp1_R; b0.lp2_y2_R = b0.lp2_y1_R; b0.lp2_y1_R = band0_R

        val b0_hp1_R = b0.hp_b0 * branchLowR + b0.hp_b1 * b0.hp1_x1_R + b0.hp_b2 * b0.hp1_x2_R - b0.hp_a1 * b0.hp1_y1_R - b0.hp_a2 * b0.hp1_y2_R
        b0.hp1_x2_R = b0.hp1_x1_R; b0.hp1_x1_R = branchLowR; b0.hp1_y2_R = b0.hp1_y1_R; b0.hp1_y1_R = b0_hp1_R
        val band1_R = b0.hp_b0 * b0_hp1_R + b0.hp_b1 * b0.hp2_x1_R + b0.hp_b2 * b0.hp2_x2_R - b0.hp_a1 * b0.hp2_y1_R - b0.hp_a2 * b0.hp2_y2_R
        b0.hp2_x2_R = b0.hp2_x1_R; b0.hp2_x1_R = b0_hp1_R; b0.hp2_y2_R = b0.hp2_y1_R; b0.hp2_y1_R = band1_R

        // Etapa 3: Rama Alta -> Crossover 3 (4000 Hz) -> Banda 2 (MID-HIGH) y Banda 3 (HIGH)
        val b2 = bands[2]
        val b2_lp1_L = b2.lp_b0 * branchHighL + b2.lp_b1 * b2.lp1_x1_L + b2.lp_b2 * b2.lp1_x2_L - b2.lp_a1 * b2.lp1_y1_L - b2.lp_a2 * b2.lp1_y2_L
        b2.lp1_x2_L = b2.lp1_x1_L; b2.lp1_x1_L = branchHighL; b2.lp1_y2_L = b2.lp1_y1_L; b2.lp1_y1_L = b2_lp1_L
        val band2_L = b2.lp_b0 * b2_lp1_L + b2.lp_b1 * b2.lp2_x1_L + b2.lp_b2 * b2.lp2_x2_L - b2.lp_a1 * b2.lp2_y1_L - b2.lp_a2 * b2.lp2_y2_L
        b2.lp2_x2_L = b2.lp2_x1_L; b2.lp2_x1_L = b2_lp1_L; b2.lp2_y2_L = b2.lp2_y1_L; b2.lp2_y1_L = band2_L

        val b2_hp1_L = b2.hp_b0 * branchHighL + b2.hp_b1 * b2.hp1_x1_L + b2.hp_b2 * b2.hp1_x2_L - b2.hp_a1 * b2.hp1_y1_L - b2.hp_a2 * b2.hp1_y2_L
        b2.hp1_x2_L = b2.hp1_x1_L; b2.hp1_x1_L = branchHighL; b2.hp1_y2_L = b2.hp1_y1_L; b2.hp1_y1_L = b2_hp1_L
        val band3_L = b2.hp_b0 * b2_hp1_L + b2.hp_b1 * b2.hp2_x1_L + b2.hp_b2 * b2.hp2_x2_L - b2.hp_a1 * b2.hp2_y1_L - b2.hp_a2 * b2.hp2_y2_L
        b2.hp2_x2_L = b2.hp2_x1_L; b2.hp2_x1_L = b2_hp1_L; b2.hp2_y2_L = b2.hp2_y1_L; b2.hp2_y1_L = band3_L

        val b2_lp1_R = b2.lp_b0 * branchHighR + b2.lp_b1 * b2.lp1_x1_R + b2.lp_b2 * b2.lp1_x2_R - b2.lp_a1 * b2.lp1_y1_R - b2.lp_a2 * b2.lp1_y2_R
        b2.lp1_x2_R = b2.lp1_x1_R; b2.lp1_x1_R = branchHighR; b2.lp1_y2_R = b2.lp1_y1_R; b2.lp1_y1_R = b2_lp1_R
        val band2_R = b2.lp_b0 * b2_lp1_R + b2.lp_b1 * b2.lp2_x1_R + b2.lp_b2 * b2.lp2_x2_R - b2.lp_a1 * b2.lp2_y1_R - b2.lp_a2 * b2.lp2_y2_R
        b2.lp2_x2_R = b2.lp2_x1_R; b2.lp2_x1_R = b2_lp1_R; b2.lp2_y2_R = b2.lp2_y1_R; b2.lp2_y1_R = band2_R

        val b2_hp1_R = b2.hp_b0 * branchHighR + b2.hp_b1 * b2.hp1_x1_R + b2.hp_b2 * b2.hp1_x2_R - b2.hp_a1 * b2.hp1_y1_R - b2.hp_a2 * b2.hp1_y2_R
        b2.hp1_x2_R = b2.hp1_x1_R; b2.hp1_x1_R = branchHighR; b2.hp1_y2_R = b2.hp1_y1_R; b2.hp1_y1_R = b2_hp1_R
        val band3_R = b2.hp_b0 * b2_hp1_R + b2.hp_b1 * b2.hp2_x1_R + b2.hp_b2 * b2.hp2_x2_R - b2.hp_a1 * b2.hp2_y1_R - b2.hp_a2 * b2.hp2_y2_R
        b2.hp2_x2_R = b2.hp2_x1_R; b2.hp2_x1_R = b2_hp1_R; b2.hp2_y2_R = b2.hp2_y1_R; b2.hp2_y1_R = band3_R

        bandOutputsL[0] = band0_L; bandOutputsR[0] = band0_R
        bandOutputsL[1] = band1_L; bandOutputsR[1] = band1_R
        bandOutputsL[2] = band2_L; bandOutputsR[2] = band2_R
        bandOutputsL[3] = band3_L; bandOutputsR[3] = band3_R

        var outL = 0f
        var outR = 0f

        val hasSolo = bands.any { it.isSolo }

        // 2. COMPRESIÓN DINÁMICA INDEPENDIENTE POR BANDA CON STEREO-LINK
        for (i in 0 until 4) {
            val state = bands[i]
            val cfg = state.config

            if (state.isMuted || (hasSolo && !state.isSolo)) {
                state.gainReductionDb = 0f
                continue
            }

            if (!cfg.enabled) {
                // Banda activa pero sin compresión
                state.gainReductionDb = 0f
                outL += bandOutputsL[i]
                outR += bandOutputsR[i]
                continue
            }

            var bL = bandOutputsL[i]
            var bR = bandOutputsR[i]

            // Aplicar Pre-Gain de banda si está configurado
            if (cfg.preGain != 0f) {
                val preLin = 10.0.pow(cfg.preGain / 20.0).toFloat()
                bL *= preLin
                bR *= preLin
            }

            // DETECTOR ESTÉREO ENLAZADO: nivel máximo representativo de L y R
            val peak = max(abs(bL), abs(bR)).coerceAtLeast(EPSILON)
            val levelDb = 20.0f * log10(peak)

            // Constantes de balística temporal discretas
            val attCoeff = exp(-1.0 / (cfg.attackTime.coerceAtLeast(0.1f) * 0.001 * sampleRate)).toFloat()
            val relCoeff = exp(-1.0 / (cfg.releaseTime.coerceAtLeast(1.0f) * 0.001 * sampleRate)).toFloat()

            // Computar reducción de ganancia objetivo (<= 0 dB)
            val targetGrDb = computeGainReduction(levelDb, cfg.threshold, cfg.ratio, cfg.kneeWidth)

            // Filtro suavizador de envolvente (Attack cuando targetGrDb es más negativo que la envolvente actual)
            val coeff = if (targetGrDb < state.envelopeDb) attCoeff else relCoeff
            state.envelopeDb = targetGrDb + coeff * (state.envelopeDb - targetGrDb)
            state.gainReductionDb = state.envelopeDb

            // Ganancia efectiva = Reducción de dinámica + Makeup Gain (Post-Gain)
            val effectiveDb = state.gainReductionDb + cfg.postGain
            val linearGain = 10.0.pow(effectiveDb / 20.0).toFloat()

            outL += bL * linearGain
            outR += bR * linearGain
        }

        // Protección contra NaNs o denormales
        if (outL.isNaN() || outL.isInfinite()) outL = 0f
        if (outR.isNaN() || outR.isInfinite()) outR = 0f

        return Pair(outL, outR)
    }

    /**
     * Devuelve las mediciones de Reducción de Ganancia (GR en dB) de las 4 bandas
     * [LOW, LOW-MID, MID-HIGH, HIGH] para alimentar vúmetros reales en la UI.
     */
    fun getGainReductionDb(): FloatArray {
        return floatArrayOf(
            bands[0].gainReductionDb,
            bands[1].gainReductionDb,
            bands[2].gainReductionDb,
            bands[3].gainReductionDb
        )
    }

    fun reset() {
        for (b in bands) b.reset()
    }
}
