package com.audiodsp.enginepro.audio.effects

import android.util.Log
import com.audiodsp.enginepro.dsp.core.AudioDspEngine

/**
 * Compatibility controller for the old Android global AudioEffect path.
 *
 * IMPORTANT:
 *
 * This class intentionally does NOT create:
 *
 * - DynamicsProcessing
 * - Virtualizer
 * - global audio session 0 effects
 *
 * The application now uses the software DSP pipeline:
 *
 * MediaProjection
 *      ↓
 * AudioPlaybackCapture
 *      ↓
 * AudioRecord
 *      ↓
 * AudioDspEngine
 *      ↓
 * AudioTrack
 *
 * This class remains only so older UI/project references do not
 * break compilation.
 */
class NativeSystemEffectController {

    companion object {

        private const val TAG =
            "NativeSystemEffectController"
    }

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * The old native/global Android effect path is intentionally disabled.
     *
     * The real DSP processing is performed by AudioDspEngine through
     * AudioDspService's software audio pipeline.
     */
    fun start(
        engine: AudioDspEngine
    ): Boolean {

        stop()

        lastError =
            "Global Android AudioEffect is disabled. " +
            "Use the Android 14 software DSP capture pipeline."

        Log.i(
            TAG,
            "Native global AudioEffect disabled; software DSP is the active path."
        )

        return false
    }

    /**
     * Kept for compatibility with existing callers.
     *
     * There is no Android AudioEffect to synchronize anymore.
     */
    fun sync(
        engine: AudioDspEngine
    ): Boolean {

        lastError = null

        return false
    }

    /**
     * Releases the compatibility controller.
     *
     * No Android AudioEffect resources are created by this class.
     */
    fun stop() {

        isActive = false
        lastError = null

        Log.d(
            TAG,
            "Native effect controller stopped."
        )
    }
}
