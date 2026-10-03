package com.sb.dsp

/**
 * Modos de ecualizador soportados por SB.
 */
enum class EqMode {
    EQ10,
    EQ20,
    EQ32
}

/**
 * Configuración de banda para el compresor multibanda (MDRC / MBC).
 */
data class MdrcBandConfig(
    val enabled: Boolean = true,
    val threshold: Float = -12f,      // dB (-60f a 0f)
    val ratio: Float = 2.5f,          // 1.0f a 20.0f
    val attackTime: Float = 15f,       // ms (1f a 500f)
    val releaseTime: Float = 120f,     // ms (10f a 1000f)
    val kneeWidth: Float = 6f,         // dB (0f a 18f)
    val preGain: Float = 0f,          // dB (-15f a +15f)
    val postGain: Float = 0f          // dB (-15f a +15f)
) {
    fun validate(): MdrcBandConfig {
        val safeThreshold = if (threshold.isNaN() || threshold.isInfinite()) -12f else threshold.coerceIn(-60f, 0f)
        val safeRatio = if (ratio.isNaN() || ratio.isInfinite()) 2.5f else ratio.coerceIn(1f, 20f)
        val safeAttack = if (attackTime.isNaN() || attackTime.isInfinite()) 15f else attackTime.coerceIn(0.5f, 500f)
        val safeRelease = if (releaseTime.isNaN() || releaseTime.isInfinite()) 120f else releaseTime.coerceIn(5f, 1500f)
        val safeKnee = if (kneeWidth.isNaN() || kneeWidth.isInfinite()) 6f else kneeWidth.coerceIn(0f, 24f)
        val safePreGain = if (preGain.isNaN() || preGain.isInfinite()) 0f else preGain.coerceIn(-20f, 20f)
        val safePostGain = if (postGain.isNaN() || postGain.isInfinite()) 0f else postGain.coerceIn(-20f, 20f)

        return copy(
            threshold = safeThreshold,
            ratio = safeRatio,
            attackTime = safeAttack,
            releaseTime = safeRelease,
            kneeWidth = safeKnee,
            preGain = safePreGain,
            postGain = safePostGain
        )
    }
}

/**
 * DspConfig: Única fuente de verdad del estado lógico del procesador DSP de SB.
 */
data class DspConfig(
    val dspEnabled: Boolean = true,

    // Pre-Gain / Preamp (-20dB a +20dB)
    val preGain: Float = 0f,

    // Bass Boost
    val bassBoostEnabled: Boolean = false,
    val bassBoostStrength: Int = 0, // 0 a 1000

    // Tone Control (Shelving Bass @ 100Hz, Peaking Mid @ 1kHz, Shelving Treble @ 10kHz)
    val toneBass: Float = 0f,    // -12dB a +12dB
    val toneMid: Float = 0f,     // -12dB a +12dB
    val toneTreble: Float = 0f,  // -12dB a +12dB

    // EQ Modo y Ganancias independientes
    val eqMode: EqMode = EqMode.EQ32,
    val eq10Gains: List<Float> = List(10) { 0f },
    val eq20Gains: List<Float> = List(20) { 0f },
    val eq32Gains: List<Float> = List(32) { 0f },

    // MDRC / Multiband Dynamics (4 bandas)
    val mdrcEnabled: Boolean = false,
    val mdrcBand1: MdrcBandConfig = MdrcBandConfig(threshold = -12f, ratio = 2.0f, attackTime = 20f, releaseTime = 150f), // LOW
    val mdrcBand2: MdrcBandConfig = MdrcBandConfig(threshold = -14f, ratio = 2.5f, attackTime = 15f, releaseTime = 100f), // LOW-MID
    val mdrcBand3: MdrcBandConfig = MdrcBandConfig(threshold = -14f, ratio = 2.5f, attackTime = 10f, releaseTime = 80f),  // HIGH-MID
    val mdrcBand4: MdrcBandConfig = MdrcBandConfig(threshold = -16f, ratio = 3.0f, attackTime = 5f, releaseTime = 60f),   // HIGH

    // Crossovers MDRC (Frecuencias de corte estrictamente ascendentes)
    val mdrcCutoff1: Float = 160f,
    val mdrcCutoff2: Float = 800f,
    val mdrcCutoff3: Float = 4000f,
    val mdrcCutoff4: Float = 20000f,

    // Auto Gain
    val autoGainEnabled: Boolean = false,
    val autoGainTarget: Float = -14f, // LUFS/RMS target dB

    // Auto Headroom dinámico
    val autoHeadroomEnabled: Boolean = true,

    // Limiter (Protección final contra clipping)
    val limiterEnabled: Boolean = true,
    val limiterThreshold: Float = -0.5f, // dB
    val limiterAttack: Float = 1.5f,     // ms
    val limiterRelease: Float = 50f,     // ms
    val limiterRatio: Float = 10.0f,

    // Virtualizer / Spatial
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 0,    // 0 a 1000

    // Master Gain y Balance
    val masterGain: Float = 0f,          // -20dB a +12dB
    val balance: Float = 0f              // -1.0 (Left) a +1.0 (Right)
) {
    companion object {
        // Frecuencias estándar de EQ10
        val FREQUENCIES_EQ10 = listOf(
            31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )

        // Frecuencias estándar de EQ20
        val FREQUENCIES_EQ20 = listOf(
            31.5f, 45f, 63f, 90f, 125f, 180f, 250f, 355f, 500f, 710f,
            1000f, 1400f, 2000f, 2800f, 4000f, 5600f, 8000f, 11200f, 16000f, 20000f
        )

        /**
         * Frecuencias estándar de EQ32:
         * 31 frecuencias estándar ISO (20 Hz a 20000 Hz) + frecuencia base de 16 Hz
         * (Sub-bass anchor ISO 1/3 de octava) para completar exactamente 32 bandas
         * con distribución logarítmica acústicamente rigurosa.
         */
        val FREQUENCIES_EQ32 = listOf(
            16f, 20f, 25f, 31f, 40f, 50f, 63f, 80f, 100f, 125f,
            160f, 200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f,
            1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f,
            16000f, 20000f
        )

        val DEFAULT = DspConfig()
    }

    /**
     * Valida y sanitiza todos los parámetros asegurando que no existan NaNs,
     * infinitos, o crossovers invertidos.
     */
    fun validate(): DspConfig {
        val safePreGain = if (preGain.isNaN() || preGain.isInfinite()) 0f else preGain.coerceIn(-20f, 20f)
        val safeBassStrength = bassBoostStrength.coerceIn(0, 1000)
        val safeToneBass = if (toneBass.isNaN() || toneBass.isInfinite()) 0f else toneBass.coerceIn(-15f, 15f)
        val safeToneMid = if (toneMid.isNaN() || toneMid.isInfinite()) 0f else toneMid.coerceIn(-15f, 15f)
        val safeToneTreble = if (toneTreble.isNaN() || toneTreble.isInfinite()) 0f else toneTreble.coerceIn(-15f, 15f)

        val sanitizedEq10 = (eq10Gains.take(10) + List(maxOf(0, 10 - eq10Gains.size)) { 0f }).map {
            if (it.isNaN() || it.isInfinite()) 0f else it.coerceIn(-15f, 15f)
        }

        val sanitizedEq20 = (eq20Gains.take(20) + List(maxOf(0, 20 - eq20Gains.size)) { 0f }).map {
            if (it.isNaN() || it.isInfinite()) 0f else it.coerceIn(-15f, 15f)
        }

        val sanitizedEq32 = (eq32Gains.take(32) + List(maxOf(0, 32 - eq32Gains.size)) { 0f }).map {
            if (it.isNaN() || it.isInfinite()) 0f else it.coerceIn(-15f, 15f)
        }

        // Sanitización estricta de Crossovers MDRC: cutoff1 < cutoff2 < cutoff3 < cutoff4
        var c1 = if (mdrcCutoff1.isNaN() || mdrcCutoff1.isInfinite()) 160f else mdrcCutoff1.coerceIn(40f, 500f)
        var c2 = if (mdrcCutoff2.isNaN() || mdrcCutoff2.isInfinite()) 800f else mdrcCutoff2.coerceIn(c1 + 50f, 2500f)
        var c3 = if (mdrcCutoff3.isNaN() || mdrcCutoff3.isInfinite()) 4000f else mdrcCutoff3.coerceIn(c2 + 100f, 10000f)
        var c4 = if (mdrcCutoff4.isNaN() || mdrcCutoff4.isInfinite()) 20000f else mdrcCutoff4.coerceIn(c3 + 200f, 22000f)

        // Asegurar orden estrictamente creciente
        if (c2 <= c1) c2 = c1 + 100f
        if (c3 <= c2) c3 = c2 + 500f
        if (c4 <= c3) c4 = c3 + 1000f

        val safeAutoGainTarget = if (autoGainTarget.isNaN() || autoGainTarget.isInfinite()) -14f else autoGainTarget.coerceIn(-30f, 0f)
        val safeLimiterThreshold = if (limiterThreshold.isNaN() || limiterThreshold.isInfinite()) -0.5f else limiterThreshold.coerceIn(-24f, 0f)
        val safeLimiterAttack = if (limiterAttack.isNaN() || limiterAttack.isInfinite()) 1.5f else limiterAttack.coerceIn(0.1f, 100f)
        val safeLimiterRelease = if (limiterRelease.isNaN() || limiterRelease.isInfinite()) 50f else limiterRelease.coerceIn(5f, 1000f)
        val safeLimiterRatio = if (limiterRatio.isNaN() || limiterRatio.isInfinite()) 10f else limiterRatio.coerceIn(1f, 50f)

        val safeVirtualizerStrength = virtualizerStrength.coerceIn(0, 1000)
        val safeMasterGain = if (masterGain.isNaN() || masterGain.isInfinite()) 0f else masterGain.coerceIn(-20f, 12f)
        val safeBalance = if (balance.isNaN() || balance.isInfinite()) 0f else balance.coerceIn(-1.0f, 1.0f)

        return copy(
            eqMode = EqMode.EQ32,
            preGain = safePreGain,
            bassBoostStrength = safeBassStrength,
            toneBass = safeToneBass,
            toneMid = safeToneMid,
            toneTreble = safeToneTreble,
            eq10Gains = sanitizedEq10,
            eq20Gains = sanitizedEq20,
            eq32Gains = sanitizedEq32,
            mdrcBand1 = mdrcBand1.validate(),
            mdrcBand2 = mdrcBand2.validate(),
            mdrcBand3 = mdrcBand3.validate(),
            mdrcBand4 = mdrcBand4.validate(),
            mdrcCutoff1 = c1,
            mdrcCutoff2 = c2,
            mdrcCutoff3 = c3,
            mdrcCutoff4 = c4,
            autoGainTarget = safeAutoGainTarget,
            limiterThreshold = safeLimiterThreshold,
            limiterAttack = safeLimiterAttack,
            limiterRelease = safeLimiterRelease,
            limiterRatio = safeLimiterRatio,
            virtualizerStrength = safeVirtualizerStrength,
            masterGain = safeMasterGain,
            balance = safeBalance
        )
    }

    /**
     * Devuelve las ganancias del modo EQ activo actualmente.
     */
    fun activeEqGains(): List<Float> = eq32Gains

    /**
     * El backend de SB usa siempre el banco EQ32 de 32 bandas reales.
     * Los campos EQ10/EQ20 se conservan únicamente para compatibilidad con
     * configuraciones antiguas almacenadas, pero nunca participan del DSP.
     */
    fun activeEqFrequencies(): List<Float> = FREQUENCIES_EQ32
}
