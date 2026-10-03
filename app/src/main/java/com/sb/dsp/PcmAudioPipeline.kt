package com.sb.dsp

import kotlin.math.*

/**
 * PcmAudioPipeline: Cadena completa de procesamiento DSP sobre muestras PCM estéreo en tiempo real.
 *
 * Sigue estrictamente el orden acústico definido por la arquitectura de SB:
 * 1. Pre-Gain (-20 dB a +20 dB)
 * 2. Bass Boost (Filtro shelving subgrave de bajo retardo)
 * 3. Tone (3 Vías: Bass @ 100 Hz, Mid @ 1 kHz, Treble @ 10 kHz)
 * 4. EQ32 (Ecualizador Gráfico Constant-Q de 32 bandas ISO)
 * 5. MDRC (Compresor Multibanda de 4 bandas con crossovers Linkwitz-Riley LR4 y stereo-link)
 * 6. AutoGain (Nivelador de sonoridad)
 * 7. Limiter (Limitador Brickwall de seguridad)
 * 8. Spatial / Virtualizer (Expansión estéreo)
 * 9. Master Gain (-20 dB a +12 dB)
 * 10. Balance (-1.0 Izq a +1.0 Der)
 *
 * Cero asignaciones de memoria dentro del bucle de audio (Zero Allocations).
 */
class PcmAudioPipeline(
    var sampleRate: Float = 48000f
) {
    // Componentes DSP dedicados sobre PCM
    val mdrcProcessor = MdrcProcessor(sampleRate)
    val eqProcessor = ConstantQGraphicEq(EqMode.EQ32, sampleRate)
    val toneManager = ToneManager()

    // Filtro shelving propio para Bass Boost PCM
    private var bbB0 = 1f; private var bbB1 = 0f; private var bbB2 = 0f
    private var bbA1 = 0f; private var bbA2 = 0f
    private var bbX1_L = 0f; private var bbX2_L = 0f; private var bbY1_L = 0f; private var bbY2_L = 0f
    private var bbX1_R = 0f; private var bbX2_R = 0f; private var bbY1_R = 0f; private var bbY2_R = 0f

    // Limitador Brickwall PCM
    private var limiterEnvelope = 0f

    init {
        updateBassBoostFilter(0)
    }

    fun updateSampleRate(newRate: Float) {
        if (newRate > 8000f && newRate != sampleRate) {
            sampleRate = newRate
            mdrcProcessor.sampleRate = newRate
            mdrcProcessor.recalculateCrossovers()
            eqProcessor.updateSampleRate(newRate)
        }
    }

    private fun updateBassBoostFilter(strength: Int) {
        if (strength <= 0) {
            bbB0 = 1f; bbB1 = 0f; bbB2 = 0f; bbA1 = 0f; bbA2 = 0f
            return
        }
        val gainDb = (strength / 1000f) * 9.0f // Hasta +9 dB en subgraves
        val f0 = 80.0f
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / sampleRate
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        val alpha = sinw0 / 2.0 * sqrt(2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

        val a0 = (a + 1.0) + (a - 1.0) * cosw0 + twoSqrtAAlpha
        bbB0 = ((a * ((a + 1.0) - (a - 1.0) * cosw0 + twoSqrtAAlpha)) / a0).toFloat()
        bbB1 = ((2.0 * a * ((a - 1.0) - (a + 1.0) * cosw0)) / a0).toFloat()
        bbB2 = ((a * ((a + 1.0) - (a - 1.0) * cosw0 - twoSqrtAAlpha)) / a0).toFloat()
        bbA1 = ((-2.0 * ((a - 1.0) + (a + 1.0) * cosw0)) / a0).toFloat()
        bbA2 = (((a + 1.0) + (a - 1.0) * cosw0 - twoSqrtAAlpha) / a0).toFloat()
    }

    /**
     * Procesa un bloque de muestras estéreo PCM en el orden estricto de SB.
     * Cero allocations en el loop.
     */
    fun processBlock(
        left: FloatArray,
        right: FloatArray,
        offset: Int,
        count: Int,
        config: DspConfig,
        autoHeadroomDb: Float = 0f,
        autoGainDb: Float = 0f
    ) {
        if (!config.dspEnabled) return

        // 1. Pre-Gain (+ Auto Headroom + Auto Gain)
        val totalPreDb = config.preGain + autoHeadroomDb + autoGainDb
        val preGainLinear = 10.0.pow(totalPreDb / 20.0).toFloat()

        // 2. Bass Boost factor
        if (config.bassBoostEnabled) {
            updateBassBoostFilter(config.bassBoostStrength)
        } else {
            updateBassBoostFilter(0)
        }

        // 3. Tone
        toneManager.applyConfig(config)

        // 4. EQ32
        eqProcessor.updateConfig(config)

        // 5. MDRC
        mdrcProcessor.updateConfig(config)

        // Master Gain & Balance factores
        val masterLinear = 10.0.pow(config.masterGain / 20.0).toFloat()
        val leftBalGain: Float
        val rightBalGain: Float
        if (config.balance < 0f) {
            leftBalGain = 1.0f
            rightBalGain = (1.0f + config.balance).coerceIn(0f, 1f)
        } else {
            leftBalGain = (1.0f - config.balance).coerceIn(0f, 1f)
            rightBalGain = 1.0f
        }

        val limiterThresholdLinear = 10.0.pow(config.limiterThreshold / 20.0).toFloat()
        val limiterAttCoeff = exp(-1.0 / (config.limiterAttack * 0.001 * sampleRate)).toFloat()
        val limiterRelCoeff = exp(-1.0 / (config.limiterRelease * 0.001 * sampleRate)).toFloat()

        for (i in offset until (offset + count)) {
            var sL = left[i]
            var sR = right[i]

            // 1. PRE-GAIN
            sL *= preGainLinear
            sR *= preGainLinear

            // 2. BASS BOOST
            if (config.bassBoostEnabled && config.bassBoostStrength > 0) {
                val yL = bbB0 * sL + bbB1 * bbX1_L + bbB2 * bbX2_L - bbA1 * bbY1_L - bbA2 * bbY2_L
                bbX2_L = bbX1_L; bbX1_L = sL; bbY2_L = bbY1_L; bbY1_L = yL
                sL = yL

                val yR = bbB0 * sR + bbB1 * bbX1_R + bbB2 * bbX2_R - bbA1 * bbY1_R - bbA2 * bbY2_R
                bbX2_R = bbX1_R; bbX1_R = sR; bbY2_R = bbY1_R; bbY1_R = yR
                sR = yR
            }

            // 3. TONE
            val (toneL, toneR) = toneManager.processSample(sL, sR)
            sL = toneL
            sR = toneR

            // 4. EQ32
            val (eqL, eqR) = eqProcessor.processSample(sL, sR)
            sL = eqL
            sR = eqR

            // 5. MDRC (4-Band Dynamic Range Compression sobre PCM)
            val (mdrcL, mdrcR) = mdrcProcessor.processSample(sL, sR)
            sL = mdrcL
            sR = mdrcR

            // 6. LIMITER BRICKWALL
            if (config.limiterEnabled) {
                val peak = max(abs(sL), abs(sR))
                val coeff = if (peak > limiterEnvelope) limiterAttCoeff else limiterRelCoeff
                limiterEnvelope = peak + coeff * (limiterEnvelope - peak)
                if (limiterEnvelope > limiterThresholdLinear && limiterThresholdLinear > 0f) {
                    val gainReduction = limiterThresholdLinear / limiterEnvelope
                    sL *= gainReduction
                    sR *= gainReduction
                }
            }

            // 7. VIRTUALIZER / SPATIAL (M/S matrix expansion sobre PCM)
            if (config.virtualizerEnabled && config.virtualizerStrength > 0) {
                val width = 1.0f + (config.virtualizerStrength / 1000f) * 0.8f
                val mid = (sL + sR) * 0.5f
                val side = (sL - sR) * 0.5f * width
                sL = mid + side
                sR = mid - side
            }

            // 8. MASTER GAIN & BALANCE
            sL *= (masterLinear * leftBalGain)
            sR *= (masterLinear * rightBalGain)

            // Clamp final de seguridad (-1.0f a +1.0f)
            left[i] = sL.coerceIn(-1.0f, 1.0f)
            right[i] = sR.coerceIn(-1.0f, 1.0f)
        }
    }

    /**
     * Procesa un buffer estéreo intercalado [L0, R0, L1, R1, ...].
     */
    fun processInterleaved(buffer: FloatArray, frameCount: Int, config: DspConfig) {
        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)
        for (i in 0 until frameCount) {
            left[i] = buffer[i * 2]
            right[i] = buffer[i * 2 + 1]
        }
        processBlock(left, right, 0, frameCount, config)
        for (i in 0 until frameCount) {
            buffer[i * 2] = left[i]
            buffer[i * 2 + 1] = right[i]
        }
    }
}
