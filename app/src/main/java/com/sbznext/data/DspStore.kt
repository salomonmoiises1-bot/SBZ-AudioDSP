package com.sbznext.data

import android.content.Context
import com.sbznext.dsp.DspConfig
import com.sbznext.dsp.MdrcBand

object DspStore {
    private const val PREF="sbz_next_dsp"
    fun load(context:Context):DspConfig{
        val p=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        val eq=FloatArray(32){p.getFloat("eq_$it",0f)}
        val cuts=FloatArray(4){p.getFloat("cut_$it",DspConfig().mdrcCutoffsHz[it])}
        val bands=Array(4){i->MdrcBand(p.getFloat("thr_$i",-18f),p.getFloat("ratio_$i",2f),p.getFloat("att_$i",10f),p.getFloat("rel_$i",80f),p.getFloat("make_$i",0f))}
        return DspConfig(p.getBoolean("enabled",true),p.getFloat("pre",0f),p.getFloat("bass",0f),p.getFloat("tbass",0f),p.getFloat("tmid",0f),p.getFloat("ttreble",0f),p.getBoolean("eq",true),eq,p.getBoolean("mdrc",true),cuts,bands,p.getBoolean("auto",true),p.getFloat("headroom",1f),p.getBoolean("limiter",true),p.getFloat("ceiling",-1f),p.getBoolean("spatial",false),p.getFloat("width",0.15f),p.getFloat("master",0f),p.getFloat("balance",0f))
    }
    fun save(context:Context,c:DspConfig){
        val e=context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putBoolean("enabled",c.enabled).putFloat("pre",c.preGainDb).putFloat("bass",c.bassBoostDb).putFloat("tbass",c.toneBassDb).putFloat("tmid",c.toneMidDb).putFloat("ttreble",c.toneTrebleDb).putBoolean("eq",c.eqEnabled).putBoolean("mdrc",c.mdrcEnabled).putBoolean("auto",c.autoGainEnabled).putFloat("headroom",c.autoGainHeadroomDb).putBoolean("limiter",c.limiterEnabled).putFloat("ceiling",c.limiterCeilingDb).putBoolean("spatial",c.spatialEnabled).putFloat("width",c.spatialWidth).putFloat("master",c.masterGainDb).putFloat("balance",c.balance)
        c.eqGainsDb.forEachIndexed{ i,v->e.putFloat("eq_$i",v)}; c.mdrcCutoffsHz.forEachIndexed{i,v->e.putFloat("cut_$i",v)}; c.mdrcBands.forEachIndexed{i,b->e.putFloat("thr_$i",b.thresholdDb).putFloat("ratio_$i",b.ratio).putFloat("att_$i",b.attackMs).putFloat("rel_$i",b.releaseMs).putFloat("make_$i",b.makeupDb)};e.apply()
    }
    fun flat(context:Context){save(context,DspConfig())}
}
