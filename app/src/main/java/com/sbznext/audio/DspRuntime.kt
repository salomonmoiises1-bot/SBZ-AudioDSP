package com.sbznext.audio

import android.content.Context
import com.sbznext.data.DspStore
import com.sbznext.dsp.DspConfig
import com.sbznext.dsp.DspEngine

object DspRuntime {
    @Volatile private var config=DspConfig()
    @Volatile private var engine:DspEngine?=null
    fun config():DspConfig=config
    fun configure(context:Context){config=DspStore.load(context)}
    fun update(context:Context,c:DspConfig){config=c;DspStore.save(context,c);engine?.update(c)}
    fun attach(e:DspEngine){engine=e;e.update(config)}
    fun detach(){engine=null}
}
