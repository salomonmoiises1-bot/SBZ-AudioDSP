package com.audiodsp.enginepro.dsp.core

import com.audiodsp.enginepro.dsp.dynamics.AutoGainProcessor
import com.audiodsp.enginepro.dsp.dynamics.LimiterProcessor
import com.audiodsp.enginepro.dsp.dynamics.MdrcProcessor
import com.audiodsp.enginepro.dsp.effects.BassBoostProcessor
import com.audiodsp.enginepro.dsp.effects.ToneProcessor
import com.audiodsp.enginepro.dsp.effects.VirtualizerProcessor
import com.audiodsp.enginepro.dsp.equalizer.Equalizer32Band
import com.audiodsp.enginepro.dsp.metering.MeterProcessor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**

The Master Audio DSP Engine.

Orchestrates the full modular audio chain over PCM audio:

INPUT

↓

Pre-Gain

↓

Bass Boost

↓

Tone (Bass, Mid, Treble)

↓

32-Band EQ

↓

MDRC (Multi-band Dynamic Range Compression)

↓

AutoGain (AGC)

↓

Virtualizer (Haas & Spatial Stereo Widening)

↓

Master Gain

↓

Balance (Constant-Power Pan Law)

↓

Limiter (Peak Brickwall Limiter)

↓

Meter Processor (Telemetry analysis)

↓

OUTPUT
*/
class AudioDspEngine(
val sampleRate: Float = 48000f
) {
// Processors
val bassBoost = BassBoostProcessor(sampleRate)
val tone = ToneProcessor(sampleRate)
val equalizer32 = Equalizer32Band(sampleRate)
val mdrc = MdrcProcessor(sampleRate)
val autoGain = AutoGainProcessor(sampleRate)
val virtualizer = VirtualizerProcessor(sampleRate)
val limiter = LimiterProcessor(sampleRate)
val meter = MeterProcessor()


// Global Controls  
@Volatile  
var isBypassGlobal: Boolean = false  

@Volatile  
var masterGainDb: Float = 0.0f  
    set(value) {  
        field = value.coerceIn(-36.0f, 12.0f)  
        masterGainLinear = 10.0.pow(field / 20.0).toFloat()  
    }  

private var masterGainLinear: Float = 1.0f  

/**  
 * Stereo Balance:  
 * -1.0f = 100% Left (Right muted)  
 *  0.0f = Center (0 dB attenuation)  
 * +1.0f = 100% Right (Left muted)  
 */  
@Volatile  
var balance: Float = 0.0f  
    set(value) {  
        field = value.coerceIn(-1.0f, 1.0f)  
        computeBalanceGains()  
    }  

private var balanceGainL: Float = 1.0f  
private var balanceGainR: Float = 1.0f  

init {  
    computeBalanceGains()  
}  

private fun computeBalanceGains() {  
    // -3dB constant power panning  
    val angle = (balance + 1.0f) * 0.25f * PI.toFloat() // 0 to PI/2  
    balanceGainL = (cos(angle) * 1.4142f).coerceIn(0f, 1f)  
    balanceGainR = (sin(angle) * 1.4142f).coerceIn(0f, 1f)  
}  

/**  
 * Processes one audio buffer through the full DSP chain.  
 * Guaranteed zero heap allocations in the audio thread loop.  
 */  
fun processBuffer(buffer: AudioBuffer) {  
    val frames = buffer.frameCount  
    if (frames <= 0) return  

    if (isBypassGlobal) {  
        // Bypass mode: Audio passes directly without DSP alteration,  
        // while preserving filter state memory and analyzing meters.  
        meter.analyze(buffer)  
        return  
    }  

    // 1. Bass Boost  
    bassBoost.process(buffer)  

    // 2. Tone (Bass, Mid, Treble)  
    tone.process(buffer)  

    // 3. 32-Band Graphic EQ (Includes Pre-Gain inside Equalizer32Band)  
    equalizer32.process(buffer)  

    // 4. MDRC (Multi-band Dynamic Range Compression)  
    mdrc.process(buffer)  

    // 5. AutoGain (AGC Leveler)  
    autoGain.process(buffer)  

    // 6. Virtualizer (Spatial Stereo Widener)  
    virtualizer.process(buffer)  

    // 7. Master Gain & Balance  
    val left = buffer.left  
    val right = buffer.right  
    val mg = masterGainLinear  
    val bL = balanceGainL * mg  
    val bR = balanceGainR * mg  

    for (i in 0 until frames) {  
        left[i] *= bL  
        right[i] *= bR  
    }  

    // 8. Final Mastering Limiter (Brickwall peak ceiling)  
    limiter.process(buffer)  

    // 9. Real-time Telemetry Metering (Peak, RMS, Clipping, Limiting)  
    meter.analyze(buffer)  
}  

fun resetAllStates() {  
    bassBoost.resetStates()  
    tone.resetStates()  
    equalizer32.resetStates()  
    mdrc.resetStates()  
    autoGain.resetStates()  
    virtualizer.resetStates()  
    limiter.resetStates()  
    meter.reset()  
}

}
