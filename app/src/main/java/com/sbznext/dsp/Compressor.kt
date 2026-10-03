package com.sbznext.dsp

import kotlin.math.*

class Compressor(private val sampleRate:Float) {
    private var env=0f
    private var gain=1f
    fun reset(){env=0f;gain=1f}
    fun processGain(l:Float,r:Float,p:MdrcBand):Float{
        val level=max(abs(l),abs(r)).coerceAtLeast(1e-8f)
        val attack=exp((-1f/(p.attackMs.coerceAtLeast(0.5f)*0.001f*sampleRate)).toDouble()).toFloat()
        val release=exp((-1f/(p.releaseMs.coerceAtLeast(1f)*0.001f*sampleRate)).toDouble()).toFloat()
        env=if(level>env) attack*env+(1-attack)*level else release*env+(1-release)*level
        val db=20*log10(env.coerceAtLeast(1e-8f))
        val over=(db-p.thresholdDb).coerceAtLeast(0f)
        val reductionDb=over*(1f-1f/p.ratio.coerceAtLeast(1f))
        val target=10.0.pow((p.makeupDb-reductionDb)/20.0).toFloat()
        val smooth=if(target<gain) attack else release
        gain=smooth*gain+(1-smooth)*target
        return gain
    }
}
