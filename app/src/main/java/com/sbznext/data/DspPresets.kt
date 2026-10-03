package com.sbznext.data

import com.sbznext.dsp.DspConfig

object DspPresets {
    fun flat()=DspConfig()
    fun clarity():DspConfig{
        val eq=FloatArray(32); eq[0]=1f;eq[1]=1f;eq[2]=1f;eq[3]=0.5f;eq[16]=1f;eq[19]=1.5f;eq[22]=2f;eq[26]=1.5f;eq[30]=0.5f
        return DspConfig(preGainDb=-2f,eqGainsDb=eq,autoGainHeadroomDb=1.5f,limiterCeilingDb=-1f)
    }
    fun smooth():DspConfig{
        val eq=FloatArray(32); eq[4]=1.5f;eq[7]=1f;eq[8]=1f;eq[23]=-1f;eq[26]=-1.5f;eq[30]=-2f;eq[31]=-2f
        return DspConfig(preGainDb=-2f,bassBoostDb=1.5f,eqGainsDb=eq,autoGainHeadroomDb=2f,limiterCeilingDb=-1f)
    }
}
