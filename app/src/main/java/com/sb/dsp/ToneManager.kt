package com.sb.dsp

/**
 * ToneManager: Control de tono de 3 vías de SB (Bass, Mid, Treble).
 *
 * Especificación acústica:
 * - Bass: Filtro Low-Shelf centrado en 100 Hz (-15 dB a +15 dB).
 * - Mid: Filtro Peaking centrado en 1000 Hz con Q = 1.0 (-15 dB a +15 dB).
 * - Treble: Filtro High-Shelf centrado en 10000 Hz (-15 dB a +15 dB).
 *
 * Es 100% independiente de BassBoost.
 */
class ToneManager(sampleRate: Float = 48000f) {

    val bassFilter = BiquadFilter(
        type = BiquadFilter.Type.LOW_SHELF,
        frequency = 100f,
        sampleRate = sampleRate,
        q = 0.707f,
        gainDb = 0f
    )

    val midFilter = BiquadFilter(
        type = BiquadFilter.Type.PEAKING,
        frequency = 1000f,
        sampleRate = sampleRate,
        q = 1.0f,
        gainDb = 0f
    )

    val trebleFilter = BiquadFilter(
        type = BiquadFilter.Type.HIGH_SHELF,
        frequency = 10000f,
        sampleRate = sampleRate,
        q = 0.707f,
        gainDb = 0f
    )

    fun applyConfig(config: DspConfig) {
        bassFilter.updateGain(config.toneBass)
        midFilter.updateGain(config.toneMid)
        trebleFilter.updateGain(config.toneTreble)
    }

    fun processSample(sampleL: Float, sampleR: Float): Pair<Float, Float> {
        val (l1, r1) = bassFilter.processSample(sampleL, sampleR)
        val (l2, r2) = midFilter.processSample(l1, r1)
        return trebleFilter.processSample(l2, r2)
    }

    fun reset() {
        bassFilter.reset()
        midFilter.reset()
        trebleFilter.reset()
    }
}
