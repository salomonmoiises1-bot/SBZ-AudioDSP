package com.audiodsp.enginepro.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.audiodsp.enginepro.dsp.core.AudioDspEngine
import com.audiodsp.enginepro.dsp.equalizer.Equalizer32Band
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dspDataStore: DataStore<Preferences> by preferencesDataStore(name = "audiodsp_prefs")

/**
 * Persists all AudioDSP settings using Android Jetpack DataStore Preferences.
 */
class DspPreferencesRepository(private val context: Context) {

    companion object {
        val KEY_BYPASS = booleanPreferencesKey("dsp_bypass")
        val KEY_MASTER_GAIN = floatPreferencesKey("dsp_master_gain")
        val KEY_BALANCE = floatPreferencesKey("dsp_balance")

        val KEY_PRE_GAIN = floatPreferencesKey("dsp_pre_gain")
        val KEY_BASS_BOOST = floatPreferencesKey("dsp_bass_boost")
        val KEY_TONE_BASS = floatPreferencesKey("dsp_tone_bass")
        val KEY_TONE_MID = floatPreferencesKey("dsp_tone_mid")
        val KEY_TONE_TREBLE = floatPreferencesKey("dsp_tone_treble")
        val KEY_VIRTUALIZER = floatPreferencesKey("dsp_virtualizer")

        val KEY_MDRC_ENABLED = booleanPreferencesKey("dsp_mdrc_enabled")
        val KEY_AUTOGAIN_ENABLED = booleanPreferencesKey("dsp_autogain_enabled")
        val KEY_LIMITER_CEILING = floatPreferencesKey("dsp_limiter_ceiling")
        val KEY_SELECTED_PRESET = stringPreferencesKey("dsp_selected_preset")

        fun eqBandKey(index: Int) = floatPreferencesKey("dsp_eq_band_$index")
    }

    data class SavedDspSettings(
        val isBypass: Boolean,
        val masterGainDb: Float,
        val balance: Float,
        val preGainDb: Float,
        val bassBoostDb: Float,
        val toneBassDb: Float,
        val toneMidDb: Float,
        val toneTrebleDb: Float,
        val virtualizer: Float,
        val mdrcEnabled: Boolean,
        val autoGainEnabled: Boolean,
        val limiterCeilingDb: Float,
        val selectedPresetId: String,
        val eqGains: FloatArray
    )

    val settingsFlow: Flow<SavedDspSettings> = context.dspDataStore.data.map { prefs ->
        val eq = FloatArray(Equalizer32Band.BAND_COUNT) { index ->
            prefs[eqBandKey(index)] ?: 0.0f
        }
        SavedDspSettings(
            isBypass = prefs[KEY_BYPASS] ?: false,
            masterGainDb = prefs[KEY_MASTER_GAIN] ?: 0.0f,
            balance = prefs[KEY_BALANCE] ?: 0.0f,
            preGainDb = prefs[KEY_PRE_GAIN] ?: 0.0f,
            bassBoostDb = prefs[KEY_BASS_BOOST] ?: 0.0f,
            toneBassDb = prefs[KEY_TONE_BASS] ?: 0.0f,
            toneMidDb = prefs[KEY_TONE_MID] ?: 0.0f,
            toneTrebleDb = prefs[KEY_TONE_TREBLE] ?: 0.0f,
            virtualizer = prefs[KEY_VIRTUALIZER] ?: 0.0f,
            mdrcEnabled = prefs[KEY_MDRC_ENABLED] ?: true,
            autoGainEnabled = prefs[KEY_AUTOGAIN_ENABLED] ?: false,
            limiterCeilingDb = prefs[KEY_LIMITER_CEILING] ?: -0.3f,
            selectedPresetId = prefs[KEY_SELECTED_PRESET] ?: "flat",
            eqGains = eq
        )
    }

    fun applySettings(engine: AudioDspEngine, settings: SavedDspSettings) {
        engine.isBypassGlobal = settings.isBypass
        engine.masterGainDb = settings.masterGainDb
        engine.balance = settings.balance
        engine.equalizer32.setPreGain(settings.preGainDb)
        engine.bassBoost.setStrength(settings.bassBoostDb)
        engine.tone.setBass(settings.toneBassDb)
        engine.tone.setMid(settings.toneMidDb)
        engine.tone.setTreble(settings.toneTrebleDb)
        engine.virtualizer.setStrength(settings.virtualizer)
        engine.mdrc.isEnabled = settings.mdrcEnabled
        engine.autoGain.isEnabled = settings.autoGainEnabled
        engine.limiter.ceilingDb = settings.limiterCeilingDb
        for (i in settings.eqGains.indices) engine.equalizer32.setBandGain(i, settings.eqGains[i])
    }

    suspend fun saveEngineState(engine: AudioDspEngine, currentPresetId: String) {
        context.dspDataStore.edit { prefs ->
            prefs[KEY_BYPASS] = engine.isBypassGlobal
            prefs[KEY_MASTER_GAIN] = engine.masterGainDb
            prefs[KEY_BALANCE] = engine.balance
            prefs[KEY_PRE_GAIN] = engine.equalizer32.preGainDb
            prefs[KEY_BASS_BOOST] = engine.bassBoost.strengthDb
            prefs[KEY_TONE_BASS] = engine.tone.bassDb
            prefs[KEY_TONE_MID] = engine.tone.midDb
            prefs[KEY_TONE_TREBLE] = engine.tone.trebleDb
            prefs[KEY_VIRTUALIZER] = engine.virtualizer.strength
            prefs[KEY_MDRC_ENABLED] = engine.mdrc.isEnabled
            prefs[KEY_AUTOGAIN_ENABLED] = engine.autoGain.isEnabled
            prefs[KEY_LIMITER_CEILING] = engine.limiter.ceilingDb
            prefs[KEY_SELECTED_PRESET] = currentPresetId

            for (i in 0 until Equalizer32Band.BAND_COUNT) {
                prefs[eqBandKey(i)] = engine.equalizer32.getBandGain(i)
            }
        }
    }

    suspend fun saveBandGain(index: Int, gainDb: Float) {
        context.dspDataStore.edit { prefs ->
            prefs[eqBandKey(index)] = gainDb
        }
    }
}
