package com.sbznext.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DspEngineTest {
    @Test fun flatConfigPreservesFiniteSamples(){
        val e=DspEngine(48000); val b=FloatArray(2048){if(it%2==0)0.2f else -0.2f}; e.process(b); assertTrue(b.all{it.isFinite()})
    }
    @Test fun limiterKeepsOutputBounded(){
        val e=DspEngine(48000); val b=FloatArray(2048){if(it%2==0)4f else -4f}; e.process(b); assertTrue(b.all{abs(it)<=1f})
    }
    @Test fun eqCanChangeSignal(){
        val e=DspEngine(48000); val a=FloatArray(2048){0.1f}; val g=FloatArray(32);g[17]=12f;e.update(DspConfig(eqGainsDb=g,autoGainEnabled=false));val before=a.copyOf();e.process(a);assertTrue(a.indices.any{abs(a[it]-before[it])>1e-5f})
    }
}
