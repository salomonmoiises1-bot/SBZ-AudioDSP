package com.sbznext.dsp

data class MdrcBand(
    val thresholdDb: Float = -18f,
    val ratio: Float = 2f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 80f,
    val makeupDb: Float = 0f
)

data class DspConfig(
    val enabled: Boolean = true,
    val preGainDb: Float = 0f,
    val bassBoostDb: Float = 0f,
    val toneBassDb: Float = 0f,
    val toneMidDb: Float = 0f,
    val toneTrebleDb: Float = 0f,
    val eqEnabled: Boolean = true,
    val eqGainsDb: FloatArray = FloatArray(32),
    val mdrcEnabled: Boolean = true,
    val mdrcCutoffsHz: FloatArray = floatArrayOf(160f, 800f, 4000f, 20000f),
    val mdrcBands: Array<MdrcBand> = Array(4) { MdrcBand() },
    val autoGainEnabled: Boolean = true,
    val autoGainHeadroomDb: Float = 1f,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -1f,
    val spatialEnabled: Boolean = false,
    val spatialWidth: Float = 0.15f,
    val masterGainDb: Float = 0f,
    val balance: Float = 0f
) {
    companion object {
        val FREQUENCIES = floatArrayOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f,
            125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
            800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f,
            5000f, 6300f, 8000f, 10000f, 12500f, 14000f, 16000f, 20000f
        )
    }
}
