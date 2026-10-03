package com.sbznext.audio

import android.content.Context
import com.sbznext.data.DspStore
import com.sbznext.dsp.DspConfig
import com.sbznext.dsp.DspEngine

object DspRuntime {
    @Volatile private var config = DspConfig()
    @Volatile private var engine: DspEngine? = null

    fun config(): DspConfig = config

    fun configure(context: Context) {
        config = DspStore.load(context)
    }

    /** Realtime-only update. Persistence is intentionally separate from the audio path. */
    fun update(c: DspConfig) {
        config = c
        engine?.update(c)
    }

    fun save(context: Context) {
        DspStore.save(context, config)
    }

    fun attach(e: DspEngine) {
        engine = e
        e.update(config)
    }

    fun detach() {
        engine = null
    }
}
