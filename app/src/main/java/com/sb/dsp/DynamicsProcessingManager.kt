package com.sb.dsp

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log

/**
 * DynamicsProcessingManager: Gestiona la API nativa de DynamicsProcessing de Android (API 28+).
 * Es la arquitectura más avanzada y potente de Android AudioFX, proveyendo:
 * - Pre-EQ (usado para Pre-Gain y Tone)
 * - MBC (Multiband Compressor de 4 bandas para MDRC)
 * - Post-EQ
 * - Limiter (Limitador pico final)
 *
 * Implementa estrictamente la separación Realtime / Estructural:
 * - Los cambios de ganancia, umbral, ratio, ataque y relajación se aplican en tiempo real
 *   sin reconstruir el efecto.
 * - Solo los cambios estructurales (crossovers modificados) disparan reconstrucción controlada.
 */
class DynamicsProcessingManager {
    companion object {
        private const val TAG = "SB_DynamicsProcessing"
        const val MBC_BAND_COUNT = 4
        const val PRE_EQ_BAND_COUNT = 4
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    var isAvailable: Boolean = false
        private set
    var currentSessionId: Int = 0
        private set

    // Motor nativo de respaldo por software (Linkwitz-Riley 4th order 4-band MDRC)
    val softwareMdrc = MdrcProcessor()

    // Rastreo de crossovers actuales para evitar reconstrucciones innecesarias
    private var appliedCutoffs = floatArrayOf(160f, 800f, 4000f, 20000f)

    fun initialize(audioSessionId: Int, priority: Int = 1000, initialConfig: DspConfig = DspConfig.DEFAULT): Boolean {
        softwareMdrc.updateConfig(initialConfig)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "DynamicsProcessing requiere Android 9.0 (API 28) o superior. Activando motor MDRC nativo por software.")
            isAvailable = false
            return false
        }

        release()
        currentSessionId = audioSessionId

        return try {
            val builder = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.Config.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, // Canales estéreo
                true, PRE_EQ_BAND_COUNT, // Pre-EQ habilitado
                true, MBC_BAND_COUNT,    // MBC habilitado (4 bandas MDRC)
                false, 0,                // Post-EQ
                true                     // Limiter habilitado
            )

            // Configurar bandas iniciales de MBC con crossovers precisos
            val initialBands = listOf(initialConfig.mdrcBand1, initialConfig.mdrcBand2, initialConfig.mdrcBand3, initialConfig.mdrcBand4)
            val initialCutoffs = floatArrayOf(
                initialConfig.mdrcCutoff1,
                initialConfig.mdrcCutoff2,
                initialConfig.mdrcCutoff3,
                initialConfig.mdrcCutoff4
            )

            for (ch in 0..1) {
                for (b in 0 until MBC_BAND_COUNT) {
                    val bandCfg = initialBands[b]
                    val mbcBand = DynamicsProcessing.MbcBand(
                        initialConfig.mdrcEnabled && bandCfg.enabled,
                        initialCutoffs[b],
                        bandCfg.attackTime,
                        bandCfg.releaseTime,
                        bandCfg.ratio,
                        bandCfg.threshold,
                        bandCfg.kneeWidth,
                        0f, // noiseGateThreshold
                        1f, // expanderRatio
                        bandCfg.preGain,
                        bandCfg.postGain
                    )
                    builder.setMbcBand(ch, b, mbcBand)
                }
            }

            val dpConfig = builder.build()
            val dp = DynamicsProcessing(priority, audioSessionId, dpConfig)
            dynamicsProcessing = dp
            isAvailable = true
            appliedCutoffs = initialCutoffs

            Log.d(TAG, "DynamicsProcessing inicializado con éxito en sesión $audioSessionId con 4 bandas MDRC.")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al inicializar DynamicsProcessing en sesión $audioSessionId: ${e.message}. Activando respaldo MDRC por software.")
            isAvailable = false
            dynamicsProcessing = null
            false
        }
    }

    /**
     * Aplica la configuración distinguiendo entre parámetros realtime y estructurales.
     */
    fun applyConfig(config: DspConfig, autoHeadroomDb: Float = 0f, autoGainDb: Float = 0f) {
        // Actualizar siempre el motor de software para garantizar consistencia y cálculo de reducción
        softwareMdrc.updateConfig(config)

        val dp = dynamicsProcessing ?: return
        if (!isAvailable || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        try {
            // Verificar si los crossovers han cambiado (Parámetro estructural)
            val cutoffsChanged = config.mdrcCutoff1 != appliedCutoffs[0] ||
                    config.mdrcCutoff2 != appliedCutoffs[1] ||
                    config.mdrcCutoff3 != appliedCutoffs[2] ||
                    config.mdrcCutoff4 != appliedCutoffs[3]

            if (cutoffsChanged) {
                Log.d(TAG, "Cambio de crossover estructural detectado. Reconstruyendo DynamicsProcessing...")
                initialize(currentSessionId, initialConfig = config)
                return applyConfig(config, autoHeadroomDb, autoGainDb)
            }

            // Habilitación global
            if (dp.enabled != config.dspEnabled) {
                dp.enabled = config.dspEnabled
            }

            if (!config.dspEnabled) return

            // 1. APLICAR PRE-EQ (Pre-Gain + Tone + Auto Headroom)
            val netPreGain = config.preGain + autoHeadroomDb + autoGainDb
            for (ch in 0..1) {
                // Banda 0: Bass Shelf (100 Hz) + ToneBass
                val b0 = DynamicsProcessing.EqBand(true, 100f, netPreGain + config.toneBass)
                dp.setPreEqBand(ch, 0, b0)

                // Banda 1: Mid Peaking (1000 Hz) + ToneMid
                val b1 = DynamicsProcessing.EqBand(true, 1000f, netPreGain + config.toneMid)
                dp.setPreEqBand(ch, 1, b1)

                // Banda 2: Treble Shelf (10000 Hz) + ToneTreble
                val b2 = DynamicsProcessing.EqBand(true, 10000f, netPreGain + config.toneTreble)
                dp.setPreEqBand(ch, 2, b2)

                // Banda 3: Ganancia general transparente
                val b3 = DynamicsProcessing.EqBand(true, 500f, netPreGain)
                dp.setPreEqBand(ch, 3, b3)
            }

            // 2. APLICAR MBC / MDRC (4 Bandas: Low, Low-Mid, High-Mid, High)
            val bands = listOf(config.mdrcBand1, config.mdrcBand2, config.mdrcBand3, config.mdrcBand4)
            val cutoffs = listOf(config.mdrcCutoff1, config.mdrcCutoff2, config.mdrcCutoff3, config.mdrcCutoff4)

            for (ch in 0..1) {
                for (bIndex in 0 until MBC_BAND_COUNT) {
                    val bandConfig = bands[bIndex]
                    val cutoff = cutoffs[bIndex]
                    val mbcBand = DynamicsProcessing.MbcBand(
                        config.mdrcEnabled && bandConfig.enabled,
                        cutoff,
                        bandConfig.attackTime,
                        bandConfig.releaseTime,
                        bandConfig.ratio,
                        bandConfig.threshold,
                        bandConfig.kneeWidth,
                        0f, // noiseGateThreshold
                        1f, // expanderRatio
                        bandConfig.preGain,
                        bandConfig.postGain
                    )
                    dp.setMbcBand(ch, bIndex, mbcBand)
                }
            }

            // 3. APLICAR LIMITER
            for (ch in 0..1) {
                val limiter = DynamicsProcessing.Limiter(
                    config.limiterEnabled,
                    config.limiterEnabled,
                    0, // linkGroup
                    config.limiterAttack,
                    config.limiterRelease,
                    config.limiterRatio,
                    config.limiterThreshold,
                    0f // postGain
                )
                dp.setLimiter(ch, limiter)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando parámetros DynamicsProcessing: ${e.message}", e)
        }
    }

    /**
     * Devuelve las mediciones de reducción de ganancia (Gain Reduction en dB)
     * para las 4 bandas del MDRC.
     */
    fun getGainReductionDb(): FloatArray = softwareMdrc.getGainReductionDb()

    fun release() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dynamicsProcessing?.enabled = false
                dynamicsProcessing?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar DynamicsProcessing: ${e.message}")
        } finally {
            dynamicsProcessing = null
            isAvailable = false
            softwareMdrc.reset()
        }
    }
}
