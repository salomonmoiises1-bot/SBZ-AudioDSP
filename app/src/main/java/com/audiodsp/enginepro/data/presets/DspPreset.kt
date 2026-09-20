package com.audiodsp.enginepro.data.presets

import com.audiodsp.enginepro.dsp.core.AudioDspEngine
import com.audiodsp.enginepro.dsp.equalizer.Equalizer32Band

/**
 * Functional DSP Presets for AudioDSP Engine Pro.
 * Configures real 32-band EQ curves, tone balance, dynamic compression, and bass boost.
 */
data class DspPreset(
    val id: String,
    val name: String,
    val description: String,
    val preGainDb: Float = 0.0f,
    val bassBoostDb: Float = 0.0f,
    val toneBassDb: Float = 0.0f,
    val toneMidDb: Float = 0.0f,
    val toneTrebleDb: Float = 0.0f,
    val virtualizerStrength: Float = 0.0f,
    val bandGains: FloatArray // 32 bands
) {
    fun applyToEngine(engine: AudioDspEngine) {
        engine.equalizer32.setPreGain(preGainDb)
        for (i in 0 until Equalizer32Band.BAND_COUNT) {
            val gain = if (i < bandGains.size) bandGains[i] else 0.0f
            engine.equalizer32.setBandGain(i, gain)
        }
        engine.bassBoost.setStrength(bassBoostDb)
        engine.tone.setBass(toneBassDb)
        engine.tone.setMid(toneMidDb)
        engine.tone.setTreble(toneTrebleDb)
        engine.virtualizer.setStrength(virtualizerStrength)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DspPreset) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        val FLAT = DspPreset(
            id = "flat",
            name = "Flat (Neutral)",
            description = "Uncolored natural audio pass-through across all frequencies",
            preGainDb = 0f,
            bassBoostDb = 0f,
            toneBassDb = 0f,
            toneMidDb = 0f,
            toneTrebleDb = 0f,
            virtualizerStrength = 0f,
            bandGains = FloatArray(32) { 0f }
        )

        val BASS_BOOST = DspPreset(
            id = "bass_boost",
            name = "Bass Boost",
            description = "Punchy low-end reinforcement for electronic, EDM, and hip-hop",
            preGainDb = -2.0f, // Headroom for boost
            bassBoostDb = 6.0f,
            toneBassDb = 4.0f,
            toneMidDb = -0.5f,
            toneTrebleDb = 1.0f,
            virtualizerStrength = 0.2f,
            bandGains = floatArrayOf(
                5.5f, 6.0f, 6.5f, 6.0f, 5.0f, 4.5f, 3.5f, 2.5f, // 16 - 100 Hz
                1.5f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, // 125 - 500 Hz
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f, 1.0f, // 630 - 4000 Hz
                1.0f, 1.5f, 1.5f, 2.0f, 2.0f, 1.5f, 1.0f, 0.5f  // 5k - 20k Hz
            )
        )

        val VOCAL = DspPreset(
            id = "vocal",
            name = "Vocal Clarity",
            description = "High dialog intelligibility with low rumble cut and presence boost",
            preGainDb = 0.0f,
            bassBoostDb = 0.0f,
            toneBassDb = -2.0f,
            toneMidDb = 3.0f,
            toneTrebleDb = 2.0f,
            virtualizerStrength = 0.1f,
            bandGains = floatArrayOf(
                -4.0f, -3.5f, -3.0f, -2.5f, -2.0f, -1.0f, 0.0f, 0.5f,
                1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.0f,
                3.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.5f, 2.0f, 2.5f,
                2.0f, 1.5f, 1.0f, 0.5f, 0.0f, -0.5f, -1.0f, -2.0f
            )
        )

        val ROCK = DspPreset(
            id = "rock",
            name = "Rock",
            description = "V-curve with driving bass punch and aggressive distorted electric guitar edge",
            preGainDb = -1.5f,
            bassBoostDb = 3.5f,
            toneBassDb = 3.0f,
            toneMidDb = -1.5f,
            toneTrebleDb = 3.0f,
            virtualizerStrength = 0.25f,
            bandGains = floatArrayOf(
                4.0f, 4.5f, 5.0f, 4.5f, 4.0f, 3.0f, 2.0f, 1.5f,
                0.5f, -0.5f, -1.5f, -2.0f, -2.0f, -1.5f, -1.0f, 0.0f,
                0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f,
                4.0f, 3.5f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.0f
            )
        )

        val POP = DspPreset(
            id = "pop",
            name = "Pop",
            description = "Modern commercial radio curve with warm low-mid and crisp airy highs",
            preGainDb = -1.0f,
            bassBoostDb = 2.5f,
            toneBassDb = 2.0f,
            toneMidDb = 1.0f,
            toneTrebleDb = 2.5f,
            virtualizerStrength = 0.2f,
            bandGains = floatArrayOf(
                2.0f, 2.5f, 3.0f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f,
                1.0f, 0.5f, 0.0f, 0.0f, 0.5f, 1.0f, 1.5f, 2.0f,
                2.0f, 2.0f, 2.5f, 2.5f, 3.0f, 3.0f, 3.5f, 3.5f,
                3.0f, 3.0f, 2.5f, 2.5f, 2.0f, 1.5f, 1.0f, 0.5f
            )
        )

        val CLASSICAL = DspPreset(
            id = "classical",
            name = "Classical",
            description = "Expansive acoustic hall staging with refined orchestral timbre balance",
            preGainDb = 0.0f,
            bassBoostDb = 0.0f,
            toneBassDb = 1.0f,
            toneMidDb = 0.0f,
            toneTrebleDb = 1.5f,
            virtualizerStrength = 0.4f,
            bandGains = floatArrayOf(
                1.0f, 1.5f, 2.0f, 2.0f, 1.5f, 1.0f, 0.5f, 0.0f,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.5f, 0.5f, 1.0f, 1.0f, 1.5f, 1.5f, 2.0f, 2.0f,
                2.5f, 2.5f, 2.5f, 2.0f, 1.5f, 1.0f, 0.5f, 0.0f
            )
        )

        val ELECTRONIC = DspPreset(
            id = "electronic",
            name = "Electronic",
            description = "Deep sub-bass extension, wide spatial imaging, and synthetic sparkle",
            preGainDb = -2.5f,
            bassBoostDb = 5.0f,
            toneBassDb = 4.0f,
            toneMidDb = -1.0f,
            toneTrebleDb = 3.5f,
            virtualizerStrength = 0.5f,
            bandGains = floatArrayOf(
                6.0f, 6.5f, 6.0f, 5.5f, 4.5f, 3.5f, 2.5f, 1.5f,
                0.5f, 0.0f, -0.5f, -1.0f, -1.0f, -0.5f, 0.0f, 0.5f,
                1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f,
                5.0f, 5.0f, 4.5f, 4.0f, 3.5f, 3.0f, 2.5f, 2.0f
            )
        )

        val ALL_PRESETS = listOf(
            FLAT,
            BASS_BOOST,
            VOCAL,
            ROCK,
            POP,
            CLASSICAL,
            ELECTRONIC
        )
    }
}
