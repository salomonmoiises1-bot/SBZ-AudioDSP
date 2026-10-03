package com.sb.dsp

import android.os.Build

/**
 * Representa las capacidades físicas reales detectadas en el dispositivo Android.
 * SB NUNCA simula capacidades; si un dispositivo solo ofrece 5 bandas nativas,
 * se informa con total fidelidad.
 */
data class DspCapabilities(
    val sessionId: Int = 0,
    val isSessionZeroSupported: Boolean = false,

    // Ecualizador Nativo de Android (android.media.audiofx.Equalizer)
    val hasEqualizer: Boolean = false,
    val nativeEqBands: Int = 0,
    val nativeEqMinLevelMb: Short = -1500, // milibeles (-15dB)
    val nativeEqMaxLevelMb: Short = 1500,  // milibeles (+15dB)
    val nativeEqCenterFreqsHz: List<Int> = emptyList(),

    // DynamicsProcessing (API 28+)
    val hasDynamicsProcessing: Boolean = false,
    val dpChannelCount: Int = 2,
    val hasPreEq: Boolean = false,
    val preEqBandCount: Int = 0,
    val hasMbc: Boolean = false,
    val mbcBandCount: Int = 0,
    val hasPostEq: Boolean = false,
    val postEqBandCount: Int = 0,
    val hasLimiter: Boolean = false,

    // Efectos complementarios nativos
    val hasBassBoost: Boolean = false,
    val isBassBoostStrengthSupported: Boolean = false,
    val hasVirtualizer: Boolean = false,
    val isVirtualizerStrengthSupported: Boolean = false,

    // Metadatos del dispositivo
    val deviceManufacturer: String = Build.MANUFACTURER,
    val deviceModel: String = Build.MODEL,
    val androidVersion: String = Build.VERSION.RELEASE,
    val sdkInt: Int = Build.VERSION.SDK_INT
) {
    /**
     * Determina el estado de soporte general del dispositivo.
     */
    fun getSummaryDescription(): String = buildString {
        append("Android $androidVersion (API $sdkInt) - $deviceManufacturer $deviceModel\n")
        append("DynamicsProcessing: ${if (hasDynamicsProcessing) "Disponible (MBC: $mbcBandCount bandas, Limiter: $hasLimiter)" else "No soportado"}\n")
        append("Equalizer Nativo: ${if (hasEqualizer) "$nativeEqBands bandas (${nativeEqMinLevelMb/100}dB a +${nativeEqMaxLevelMb/100}dB)" else "No disponible"}\n")
        append("BassBoost: ${if (hasBassBoost) "Disponible" else "No disponible"}, Virtualizer: ${if (hasVirtualizer) "Disponible" else "No disponible"}\n")
        append("Ruta Global (Sesión 0): ${if (isSessionZeroSupported) "Permitida" else "Restringida por fabricante"}")
    }

    /**
     * Informa cuántas bandas físicas reales pueden procesarse nativamente.
     */
    val effectiveHardwareEqBands: Int
        get() = when {
            hasDynamicsProcessing && preEqBandCount > 0 -> preEqBandCount
            hasDynamicsProcessing && postEqBandCount > 0 -> postEqBandCount
            hasEqualizer -> nativeEqBands
            else -> 0
        }
}
