package com.sb.dsp

/**
 * ConstantQGraphicEq: Motor de ecualización gráfica Constant-Q.
 * Mantiene un factor Q constante en cada modo (Q ≈ 4.32 para 1/3 de octava en EQ32),
 * garantizando anchos de banda proporcionales logarítmicamente idénticos a los estándares
 * profesionales de audio.
 */
class ConstantQGraphicEq(
    val eqMode: EqMode = EqMode.EQ32,
    var sampleRate: Float = 48000f
) {
    val frequencies: List<Float> = when (eqMode) {
        EqMode.EQ10 -> DspConfig.FREQUENCIES_EQ10
        EqMode.EQ20 -> DspConfig.FREQUENCIES_EQ20
        EqMode.EQ32 -> DspConfig.FREQUENCIES_EQ32
    }

    // Factor Q según la densidad de octava
    private val constantQ: Float = when (eqMode) {
        EqMode.EQ10 -> 1.414f // 1 octava
        EqMode.EQ20 -> 2.871f // 1/2 octava
        EqMode.EQ32 -> 4.318f // 1/3 octava
    }

    private val filters: Array<BiquadFilter> = Array(frequencies.size) { i ->
        val freq = frequencies[i]
        val type = when (i) {
            0 -> BiquadFilter.Type.LOW_SHELF
            frequencies.size - 1 -> BiquadFilter.Type.HIGH_SHELF
            else -> BiquadFilter.Type.PEAKING
        }
        BiquadFilter(
            type = type,
            frequency = freq,
            sampleRate = sampleRate,
            q = constantQ,
            gainDb = 0f
        )
    }

    /**
     * Aplica la lista de ganancias a los filtros correspondientes.
     */
    fun setGains(gains: List<Float>) {
        val count = minOf(filters.size, gains.size)
        for (i in 0 until count) {
            filters[i].updateGain(gains[i])
        }
    }

    /**
     * Aplica la configuración lógica actual al motor Constant-Q.
     *
     * El pipeline PCM mantiene un banco físico de 32 filtros. Si el modo lógico
     * es EQ10/EQ20, las ganancias se interpolan logarítmicamente sobre las
     * frecuencias EQ32; EQ32 se aplica directamente.
     */
    fun updateConfig(config: DspConfig) {
        if (!config.dspEnabled) {
            setGains(emptyList())
            return
        }

        val sourceFreqs = config.activeEqFrequencies()
        val sourceGains = config.activeEqGains()

        if (sourceFreqs.size == frequencies.size && sourceFreqs == frequencies) {
            setGains(sourceGains)
            return
        }

        val mapped = frequencies.map { target ->
            CapabilityAdapter.interpolateGainAtFrequency(
                targetFreq = target,
                sourceFreqs = sourceFreqs,
                sourceGains = sourceGains
            )
        }
        setGains(mapped)
    }

    /**
     * Procesa una muestra estéreo a través de toda la cascada serie de filtros Constant-Q.
     */
    fun processSample(sampleL: Float, sampleR: Float): Pair<Float, Float> {
        var currentL = sampleL
        var currentR = sampleR

        for (filter in filters) {
            val (nextL, nextR) = filter.processSample(currentL, currentR)
            currentL = nextL
            currentR = nextR
        }

        return Pair(currentL, currentR)
    }

    fun updateSampleRate(newFs: Float) {
        if (newFs > 8000f && newFs != sampleRate) {
            sampleRate = newFs
            for (filter in filters) {
                filter.sampleRate = newFs
                filter.recalculateCoefficients()
            }
        }
    }

    fun reset() {
        for (filter in filters) {
            filter.reset()
        }
    }
}
