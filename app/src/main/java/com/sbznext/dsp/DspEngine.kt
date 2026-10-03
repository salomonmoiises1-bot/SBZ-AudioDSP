package com.sbznext.dsp

import kotlin.math.*

/** Real-time, allocation-free stereo DSP engine. The audio thread calls process() only. */
class DspEngine(private val sampleRate:Int) {
    companion object { private const val EQ_Q=4.318f }
    val frequencies=DspConfig.FREQUENCIES
    private val eq=Array(32){Biquad(sampleRate.toFloat())}
    private val bass=Biquad(sampleRate.toFloat()); private val toneMid=Biquad(sampleRate.toFloat()); private val treble=Biquad(sampleRate.toFloat())
    private val low= Biquad(sampleRate.toFloat())
    private val c1L=Biquad(sampleRate.toFloat()); private val c1H=Biquad(sampleRate.toFloat())
    private val c2L=Biquad(sampleRate.toFloat()); private val c2H=Biquad(sampleRate.toFloat())
    private val c3L=Biquad(sampleRate.toFloat()); private val c3H=Biquad(sampleRate.toFloat())
    private val high=Biquad(sampleRate.toFloat())
    private val comps=Array(4){Compressor(sampleRate.toFloat())}
    @Volatile private var config=DspConfig()
    @Volatile private var pendingConfig:DspConfig?=null
    @Volatile var lastPeak=0f; @Volatile var lastAutoGainDb=0f; @Volatile var clipping=false
    private var autoEnvelope=0f; private var autoGain=1f

    init { apply(config) }
    fun update(c:DspConfig){ pendingConfig=c }
    fun snapshot():DspConfig=config

    private fun apply(c:DspConfig){
        for(i in eq.indices) eq[i].peaking(frequencies[i],EQ_Q,if(c.eqEnabled)c.eqGainsDb[i] else 0f)
        bass.lowShelf(100f,0.8f,c.bassBoostDb+c.toneBassDb)
        toneMid.peaking(1000f,0.9f,c.toneMidDb)
        treble.highShelf(6000f,0.8f,c.toneTrebleDb)
        val x=c.mdrcCutoffsHz
        low.lowPass(x[0]); c1L.lowPass(x[1]); c1H.highPass(x[0]); c2L.lowPass(x[2]); c2H.highPass(x[1]); c3L.lowPass(x[3]); c3H.highPass(x[2]); high.highPass(x[3]); high2.highPass(x[3])
        autoEnvelope=0f; autoGain=1f
    }

    fun process(buf:FloatArray){
        val pending=pendingConfig
        if(pending!=null){ config=pending; pendingConfig=null; apply(pending) }
        val c=config
        if(!c.enabled) return
        val n=buf.size and -2
        var peak=0f
        var i=0
        while(i<n){
            var l=buf[i]; var r=buf[i+1]
            val pre=10.0.pow(c.preGainDb/20.0).toFloat(); l*=pre; r*=pre
            l=bass.processL(l); r=bass.processR(r)
            l=toneMid.processL(l); r=toneMid.processR(r); l=treble.processL(l); r=treble.processR(r)
            if(c.eqEnabled){for(f in eq.indices){l=eq[f].processL(l); r=eq[f].processR(r)}}
            if(c.mdrcEnabled){
                val l0=low.processL(l); val r0=low.processR(r)
                val l1=c1L.processL(l); val r1=c1L.processR(r); val hp1=c1H.processL(l); val rhp1=c1H.processR(r)
                val l2=c2L.processL(hp1); val r2=c2L.processR(rhp1); val hp2=c2H.processL(l); val rhp2=c2H.processR(r)
                val l3=c3L.processL(hp2); val r3=c3L.processR(rhp2); val l4=high.processL(l); val r4=high.processR(r)
                val g0=comps[0].processGain(l0,r0,c.mdrcBands[0]); val b1l=l1-l0; val b1r=r1-r0; val g1=comps[1].processGain(b1l,b1r,c.mdrcBands[1]); val b2l=l2-l1; val b2r=r2-r1; val g2=comps[2].processGain(b2l,b2r,c.mdrcBands[2]); val b3l=l3-l2; val b3r=r3-r2; val g3=comps[3].processGain(b3l,b3r,c.mdrcBands[3]);
                l=l0*g0+b1l*g1+b2l*g2+b3l*g3+l4; r=r0*g0+b1r*g1+b2r*g2+b3r*g3+r4
            }
            if(c.autoGainEnabled){
                val inst=max(abs(l),abs(r)); val a=exp((-1f/(10f*0.001f*sampleRate)).toDouble()).toFloat(); val rel=exp((-1f/(250f*0.001f*sampleRate)).toDouble()).toFloat(); autoEnvelope=if(inst>autoEnvelope)a*autoEnvelope+(1-a)*inst else rel*autoEnvelope+(1-rel)*inst
                val targetDb=-(20*log10(autoEnvelope.coerceAtLeast(1e-6f))+c.autoGainHeadroomDb).coerceAtLeast(0f); val target=10.0.pow(targetDb/20.0).toFloat(); autoGain=if(target<autoGain)0.98f*autoGain+0.02f*target else 0.999f*autoGain+0.001f*target; l*=autoGain; r*=autoGain
            }
            if(c.spatialEnabled){ val mid=(l+r)*0.5f; val side=(l-r)*0.5f*c.spatialWidth.coerceIn(0f,1f); l=mid+side; r=mid-side }
            val bal=c.balance.coerceIn(-1f,1f); val lg=if(bal>0)1-bal else 1f; val rg=if(bal<0)1+bal else 1f; l*=lg; r*=rg
            val master=10.0.pow(c.masterGainDb/20.0).toFloat(); l*=master; r*=master
            if(c.limiterEnabled){ val ceiling=10.0.pow(c.limiterCeilingDb/20.0).toFloat(); l=tanh(l/ceiling)*ceiling; r=tanh(r/ceiling)*ceiling }
            if(!l.isFinite())l=0f; if(!r.isFinite())r=0f; l=l.coerceIn(-1f,1f); r=r.coerceIn(-1f,1f)
            peak=max(peak,max(abs(l),abs(r))); buf[i]=l; buf[i+1]=r; i+=2
        }
        lastPeak=peak; lastAutoGainDb=20*log10(autoGain.coerceAtLeast(1e-6f)); clipping=peak>=0.999f
    }
}
