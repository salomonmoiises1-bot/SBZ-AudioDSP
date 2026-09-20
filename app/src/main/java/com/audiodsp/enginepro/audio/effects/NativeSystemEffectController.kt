package com.audiodsp.enginepro.audio.effects

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Virtualizer
import android.util.Log
import com.audiodsp.enginepro.dsp.core.AudioDspEngine
import com.audiodsp.enginepro.dsp.equalizer.Equalizer32Band
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10

/**
 * Best-effort no-root integration with Android's audio effect framework.
 *
 * The effect is attached to audio session 0 (the global output mix). Android
 * documents this path as deprecated for insert effects, so availability is
 * device/firmware dependent. When the platform refuses the effect, the caller
 * receives a failure instead of silently pretending that DSP is active.
 *
 * DynamicsProcessing provides native 32-band EQ, MBC and limiter stages. The
 * app's DSP model remains the source of truth; this class translates those
 * settings to the platform effect when the native route is available.
 */
class NativeSystemEffectController {
    companion object {
        private const val TAG = "NativeSystemEffect"
        private const val GLOBAL_SESSION = 0
        private const val CHANNELS = 2
        private const val MBC_BANDS = 3
    }

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    private var dynamics: DynamicsProcessing? = null
    private var virtualizer: Virtualizer? = null

    fun start(engine: AudioDspEngine): Boolean {
        stop()
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            lastError = "DynamicsProcessing requires Android 9 (API 28) or newer."
            return false
        }

        return try {
            val config = buildConfig(engine)
            val effect = DynamicsProcessing(
                100,
                GLOBAL_SESSION,
                config
            )
            effect.enabled = !engine.isBypassGlobal
            dynamics = effect

            // Virtualizer is optional. Failure must not disable the main EQ/MBC/limiter path.
            virtualizer = try {
                Virtualizer(100, GLOBAL_SESSION).also {
                    it.enabled = engine.virtualizer.isEnabled && engine.virtualizer.strength > 0.001f
                    it.setStrength((engine.virtualizer.strength * 1000f).toInt().coerceIn(0, 1000).toShort())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native virtualizer unavailable: ${e.message}")
                null
            }

            isActive = true
            lastError = null
            Log.i(TAG, "Native Android output effect attached to session 0.")
            true
        } catch (e: Throwable) {
            dynamics?.release()
            dynamics = null
            virtualizer?.release()
            virtualizer = null
            isActive = false
            lastError = "Android rejected the global output effect: ${e.localizedMessage ?: e.javaClass.simpleName}"
            Log.e(TAG, lastError, e)
            false
        }
    }

    fun sync(engine: AudioDspEngine): Boolean {
        val effect = dynamics ?: return false

        return try {
            /*
             * DynamicsProcessing does not expose setConfig() on the effect
             * instance. Its individual stages are updated through the public
             * API instead. The stage objects keep the same band counts that
             * were created in start().
             */
            val leftGain = channelGainDb(engine, -1f)
            val rightGain = channelGainDb(engine, 1f)

            effect.setInputGainbyChannel(0, leftGain)
            effect.setInputGainbyChannel(1, rightGain)

            val eq = DynamicsProcessing.Eq(
                true,
                !engine.isBypassGlobal,
                Equalizer32Band.BAND_COUNT
            )

            for (i in 0 until Equalizer32Band.BAND_COUNT) {
                val frequency = Equalizer32Band.FREQUENCIES[i]
                val gain = if (engine.equalizer32.isEnabled) {
                    combinedBandGain(
                        engine,
                        frequency,
                        engine.equalizer32.getBandGain(i)
                    )
                } else {
                    0f
                }

                eq.setBand(
                    i,
                    DynamicsProcessing.EqBand(
                        true,
                        frequency,
                        gain.coerceIn(-24f, 24f)
                    )
                )
            }

            effect.setPreEqAllChannelsTo(eq)

            val mbc = DynamicsProcessing.Mbc(
                true,
                engine.mdrc.isEnabled && !engine.isBypassGlobal,
                MBC_BANDS
            )

            setMbcBand(mbc, 0, 250f, engine.mdrc.lowParams)
            setMbcBand(mbc, 1, 3500f, engine.mdrc.midParams)
            setMbcBand(mbc, 2, 20000f, engine.mdrc.highParams)

            effect.setMbcAllChannelsTo(mbc)

            val limiter = DynamicsProcessing.Limiter(
                true,
                engine.limiter.isEnabled && !engine.isBypassGlobal,
                0,
                0.5f,
                engine.limiter.releaseMs.coerceIn(10f, 500f),
                20f,
                engine.limiter.ceilingDb,
                0f
            )

            effect.setLimiterAllChannelsTo(limiter)

            effect.enabled = !engine.isBypassGlobal

            virtualizer?.let {
                it.enabled =
                    engine.virtualizer.isEnabled &&
                    engine.virtualizer.strength > 0.001f

                it.setStrength(
                    (engine.virtualizer.strength * 1000f)
                        .toInt()
                        .coerceIn(0, 1000)
                        .toShort()
                )
            }

            lastError = null
            true

        } catch (e: Throwable) {
            lastError =
                "Native effect update failed: ${
                    e.localizedMessage ?: e.javaClass.simpleName
                }"

            Log.e(TAG, lastError, e)
            false
        }
    }

    fun stop() {
        try { dynamics?.release() } catch (_: Throwable) { }
        try { virtualizer?.release() } catch (_: Throwable) { }
        dynamics = null
        virtualizer = null
        isActive = false
    }

    private fun buildConfig(engine: AudioDspEngine): DynamicsProcessing.Config {
        val builder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
            CHANNELS,
            true, Equalizer32Band.BAND_COUNT,
            true, MBC_BANDS,
            false, 0,
            true
        )
            .setPreferredFrameDuration(10.666f)

        val leftGain = channelGainDb(engine, -1f)
        val rightGain = channelGainDb(engine, 1f)
        builder.setInputGainByChannelIndex(0, leftGain)
        builder.setInputGainByChannelIndex(1, rightGain)

        val eq = DynamicsProcessing.Eq(true, !engine.isBypassGlobal, Equalizer32Band.BAND_COUNT)
        for (i in 0 until Equalizer32Band.BAND_COUNT) {
            val frequency = Equalizer32Band.FREQUENCIES[i]
            val gain = if (engine.equalizer32.isEnabled) {
                combinedBandGain(engine, frequency, engine.equalizer32.getBandGain(i))
            } else {
                0f
            }
            eq.setBand(i, DynamicsProcessing.EqBand(true, frequency, gain.coerceIn(-24f, 24f)))
        }
        builder.setPreEqAllChannelsTo(eq)

        val mbc = DynamicsProcessing.Mbc(true, engine.mdrc.isEnabled && !engine.isBypassGlobal, MBC_BANDS)
        setMbcBand(mbc, 0, 250f, engine.mdrc.lowParams)
        setMbcBand(mbc, 1, 3500f, engine.mdrc.midParams)
        setMbcBand(mbc, 2, 20000f, engine.mdrc.highParams)
        builder.setMbcAllChannelsTo(mbc)

        val limiter = DynamicsProcessing.Limiter(
            true,
            engine.limiter.isEnabled && !engine.isBypassGlobal,
            0,
            0.5f,
            engine.limiter.releaseMs.coerceIn(10f, 500f),
            20f,
            engine.limiter.ceilingDb,
            0f
        )
        builder.setLimiterAllChannelsTo(limiter)
        return builder.build()
    }

    private fun setMbcBand(
        mbc: DynamicsProcessing.Mbc,
        index: Int,
        cutoff: Float,
        params: com.audiodsp.enginepro.dsp.dynamics.MdrcProcessor.BandParams
    ) {
        mbc.setBand(
            index,
            DynamicsProcessing.MbcBand(
                true,
                cutoff,
                params.attackMs.coerceIn(0.1f, 2000f),
                params.releaseMs.coerceIn(1f, 5000f),
                params.ratio.coerceIn(1f, 20f),
                params.thresholdDb.coerceIn(-60f, 0f),
                3f,
                -96f,
                1f,
                0f,
                params.makeupGainDb.coerceIn(-24f, 24f)
            )
        )
    }

    private fun channelGainDb(engine: AudioDspEngine, side: Float): Float {
        val master = engine.masterGainDb
        val balance = engine.balance
        val angle = (balance + 1f) * 0.25f * Math.PI.toFloat()
        val l = (kotlin.math.cos(angle) * 1.4142f).coerceIn(0f, 1f)
        val r = (kotlin.math.sin(angle) * 1.4142f).coerceIn(0f, 1f)
        val gain = if (side < 0f) l else r
        return master + 20f * log10(gain.coerceAtLeast(0.0001f))
    }

    private fun combinedBandGain(engine: AudioDspEngine, frequency: Float, eqGain: Float): Float {
        val bass = engine.tone.bassDb * shelfWeight(frequency, 180f, low = true)
        val mid = engine.tone.midDb * gaussianWeight(frequency, 1000f, 1.25f)
        val treble = engine.tone.trebleDb * shelfWeight(frequency, 5500f, low = false)
        val bassBoost = engine.bassBoost.strengthDb * gaussianWeight(frequency, 80f, 1.0f)
        return eqGain + bass + mid + treble + bassBoost
    }

    private fun shelfWeight(frequency: Float, pivot: Float, low: Boolean): Float {
        val x = ln((frequency / pivot).coerceAtLeast(0.01f).toDouble()).toFloat()
        val w = 1f / (1f + exp(if (low) 2.8f * x else -2.8f * x))
        return w.coerceIn(0f, 1f)
    }

    private fun gaussianWeight(frequency: Float, center: Float, widthOctaves: Float): Float {
        val oct = (ln((frequency / center).coerceAtLeast(0.01f).toDouble()) / ln(2.0)).toFloat()
        return exp(-0.5f * (oct / widthOctaves).let { it * it })
    }
}
