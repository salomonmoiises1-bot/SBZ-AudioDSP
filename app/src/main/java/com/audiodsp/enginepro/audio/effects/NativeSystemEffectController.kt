package com.audiodsp.enginepro.audio.effects

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import com.audiodsp.enginepro.dsp.core.AudioDspEngine

/**
 * Native Android audio-effect controller.
 *
 * IMPORTANT:
 *
 * This controller does NOT capture microphone audio.
 * It does NOT use AudioRecord.
 * It does NOT use AudioPlaybackCapture.
 *
 * It attaches a native Android audio effect to an AudioSession.
 *
 * Android audio effects are session based. Therefore an arbitrary
 * third-party application's playback session cannot always be obtained
 * or controlled by another application on every Android device.
 *
 * The controller therefore keeps the session ID explicit instead of
 * pretending that session 0 is a guaranteed global DSP path.
 */
class NativeSystemEffectController {

    companion object {
        private const val TAG = "NativeSystemEffect"

        /**
         * Android's global output mix session.
         *
         * Session 0 is retained only as an explicit fallback/diagnostic
         * target. It is NOT assumed to provide universal global EQ
         * behavior on modern Android.
         */
        const val GLOBAL_OUTPUT_MIX_SESSION = 0

        /**
         * Special value meaning:
         * no playback session has been selected yet.
         */
        const val NO_SESSION = -1
    }

    private var effect: DynamicsProcessing? = null

    private var currentSessionId: Int = NO_SESSION

    private var active = false

    private var lastErrorMessage: String? = null

    val isActive: Boolean
        get() = active && effect != null

    val sessionId: Int
        get() = currentSessionId

    val lastError: String?
        get() = lastErrorMessage

    /**
     * Starts the native Android effect.
     *
     * If no session has been explicitly selected, we first try the
     * Android output-mix session as a compatibility fallback.
     *
     * This does NOT guarantee that all applications will be affected.
     */
    fun start(
        dspEngine: AudioDspEngine,
        audioSessionId: Int = GLOBAL_OUTPUT_MIX_SESSION
    ): Boolean {

        stop()

        lastErrorMessage = null

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {

            lastErrorMessage =
                "DynamicsProcessing requires Android 9/API 28 or newer."

            Log.e(TAG, lastErrorMessage!!)

            return false
        }

        return try {

            currentSessionId = audioSessionId

            Log.d(
                TAG,
                "Creating DynamicsProcessing for audioSessionId=$currentSessionId"
            )

            val config = buildConfiguration(dspEngine)

            effect = DynamicsProcessing(
                DynamicsProcessing.VENDOR_UUID,
                DynamicsProcessing.EFFECT_TYPE_NULL,
                0,
                currentSessionId,
                config
            )

            effect?.enabled = true

            active = true

            Log.d(
                TAG,
                "Native audio effect started. session=$currentSessionId"
            )

            true

        } catch (e: Exception) {

            active = false
            effect = null

            lastErrorMessage =
                "Unable to create native DynamicsProcessing: " +
                    "${e.javaClass.simpleName}: ${e.localizedMessage}"

            Log.e(
                TAG,
                lastErrorMessage!!,
                e
            )

            false
        }
    }

    /**
     * Changes the session used by the native effect.
     *
     * The existing effect is released before creating the new one.
     */
    fun attachToSession(
        dspEngine: AudioDspEngine,
        audioSessionId: Int
    ): Boolean {

        if (audioSessionId < 0) {

            lastErrorMessage =
                "Invalid audio session ID: $audioSessionId"

            return false
        }

        Log.d(
            TAG,
            "Attaching DSP to audioSessionId=$audioSessionId"
        )

        return start(
            dspEngine = dspEngine,
            audioSessionId = audioSessionId
        )
    }

    /**
     * Synchronizes the DSP state with the native Android effect.
     *
     * This implementation intentionally keeps the native effect as the
     * actual audio-path processor. The software AudioDspEngine remains
     * the source of parameter values.
     */
    fun sync(
        dspEngine: AudioDspEngine
    ): Boolean {

        val nativeEffect = effect

        if (!active || nativeEffect == null) {

            lastErrorMessage =
                "Native audio effect is not active."

            return false
        }

        return try {

            if (nativeEffect.enabled.not()) {
                nativeEffect.enabled = true
            }

            syncEqualizer(
                nativeEffect = nativeEffect,
                dspEngine = dspEngine
            )

            syncPreamp(
                nativeEffect = nativeEffect,
                dspEngine = dspEngine
            )

            true

        } catch (e: Exception) {

            lastErrorMessage =
                "Failed to synchronize native audio effect: " +
                    "${e.localizedMessage}"

            Log.e(
                TAG,
                lastErrorMessage!!,
                e
            )

            false
        }
    }

    /**
     * Applies the 32-band EQ values when the native effect exposes
     * enough EQ bands.
     *
     * DynamicsProcessing supports a configurable number of EQ bands.
     * The native Android effect is therefore used as the actual
     * processing path instead of manually copying PCM through AudioTrack.
     */
    private fun syncEqualizer(
        nativeEffect: DynamicsProcessing,
        dspEngine: AudioDspEngine
    ) {

        val eq = nativeEffect.getPreEqByChannelIndex(0)

        val bandCount = eq.bandCount

        val gains = readEqualizerGains(dspEngine)

        val count = minOf(
            bandCount,
            gains.size
        )

        for (i in 0 until count) {

            val band = eq.getBand(i)

            band.gain = gains[i]

            eq.setBand(
                i,
                band
            )
        }

        nativeEffect.setPreEqByChannelIndex(
            0,
            eq
        )

        Log.d(
            TAG,
            "Native EQ synchronized: nativeBands=$bandCount softwareBands=${gains.size}"
        )
    }

    /**
     * Reads the software EQ values.
     *
     * This method deliberately uses reflection as a compatibility
     * bridge because the exact public property names of the project's
     * AudioDspEngine may differ between project revisions.
     *
     * If no readable 32-band array is exposed, the native EQ remains
     * at its current values and synchronization reports no crash.
     */
    private fun readEqualizerGains(
        dspEngine: AudioDspEngine
    ): FloatArray {

        try {

            val candidates = arrayOf(
                "eqGainsDb",
                "equalizerGainsDb",
                "bandGainsDb",
                "gainsDb",
                "eqBands"
            )

            for (name in candidates) {

                try {

                    val field =
                        dspEngine.javaClass.getDeclaredField(name)

                    field.isAccessible = true

                    val value = field.get(dspEngine)

                    when (value) {

                        is FloatArray -> {
                            return value.copyOf()
                        }

                        is DoubleArray -> {
                            return FloatArray(value.size) { index ->
                                value[index].toFloat()
                            }
                        }

                        is List<*> -> {

                            val result =
                                FloatArray(value.size)

                            for (i in value.indices) {
                                result[i] =
                                    (value[i] as? Number)
                                        ?.toFloat()
                                        ?: 0f
                            }

                            return result
                        }
                    }

                } catch (_: NoSuchFieldException) {
                    // Try the next known property name.
                }
            }

        } catch (e: Exception) {

            Log.w(
                TAG,
                "Unable to read EQ values from AudioDspEngine.",
                e
            )
        }

        /**
         * Safe fallback.
         *
         * The project must later expose the actual 32-band state directly
         * from AudioDspEngine instead of relying on reflection.
         */
        return FloatArray(32)
    }

    /**
     * Preamp is represented by the native effect's input gain.
     *
     * DynamicsProcessing exposes input gain in millibels.
     */
    private fun syncPreamp(
        nativeEffect: DynamicsProcessing,
        dspEngine: AudioDspEngine
    ) {

        val preampDb = readPreampDb(dspEngine)

        nativeEffect.inputGainMb =
            (preampDb * 100f)
                .toInt()
                .coerceIn(
                    -12000,
                    12000
                )
    }

    private fun readPreampDb(
        dspEngine: AudioDspEngine
    ): Float {

        val candidates = arrayOf(
            "preampDb",
            "preGainDb",
            "globalPreampDb",
            "masterPreampDb"
        )

        for (name in candidates) {

            try {

                val field =
                    dspEngine.javaClass.getDeclaredField(name)

                field.isAccessible = true

                val value =
                    field.get(dspEngine)

                if (value is Number) {
                    return value.toFloat()
                }

            } catch (_: Exception) {
                // Try next property.
            }
        }

        return 0f
    }

    /**
     * Builds the native DynamicsProcessing configuration.
     *
     * We deliberately configure a 32-band pre-EQ so the Android native
     * effect has the same band count as AudioDSP Engine Pro.
     */
    private fun buildConfiguration(
        dspEngine: AudioDspEngine
    ): DynamicsProcessing.Config {

        val channelCount = 2

        val eqBandCount = 32

        val preEqConfig =
            DynamicsProcessing.Eq(
                true,
                eqBandCount
            )

        val postEqConfig =
            DynamicsProcessing.Eq(
                true,
                eqBandCount
            )

        val mbcConfig =
            DynamicsProcessing.Mbc(
                false,
                1
            )

        val limiterConfig =
            DynamicsProcessing.Limiter(
                true,
                0,
                0,
                0f,
                0f,
                0f,
                0f
            )

        return DynamicsProcessing.Config.Builder(
            channelCount,
            true,
            preEqConfig,
            mbcConfig,
            postEqConfig,
            limiterConfig
        ).build()
    }

    /**
     * Stops and releases the native effect.
     */
    fun stop() {

        try {

            effect?.enabled = false

        } catch (_: Exception) {
        }

        try {

            effect?.release()

        } catch (_: Exception) {
        }

        effect = null

        active = false

        currentSessionId = NO_SESSION

        Log.d(
            TAG,
            "Native audio effect stopped."
        )
    }
}
