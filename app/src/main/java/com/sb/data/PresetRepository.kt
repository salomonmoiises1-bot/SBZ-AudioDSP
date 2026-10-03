package com.sb.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sb.dsp.DspConfig
import com.sb.dsp.EqMode
import com.sb.dsp.MdrcBandConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sb_dsp_preferences")

/**
 * PresetRepository: Gestiona la persistencia de DspConfig y el catálogo de Presets.
 *
 * Características:
 * - Persistencia reactiva con DataStore.
 * - Presets de fábrica especializados: Flat, Clarity, Smooth, Bass Control, Vocal, Loudness.
 * - Operaciones CRUD completas para presets personalizados del usuario.
 * - Serialización e importación JSON íntegra de toda la cadena (Pre-Gain, EQ, MDRC, AutoHeadroom, Limiter, Master).
 */
class PresetRepository(private val context: Context) {

    companion object {
        private val KEY_ACTIVE_CONFIG = stringPreferencesKey("key_active_dsp_config")
        private val KEY_USER_PRESETS = stringPreferencesKey("key_user_presets_json")

        /**
         * Presets de Fábrica iniciales con afinación acústica profesional de MDRC y EQ.
         */
        val FACTORY_PRESETS: List<Preset> = listOf(
            // 1. Flat: Compresión mínima y respuesta neutra
            Preset(
                id = "factory_flat",
                name = "Flat (Neutro)",
                version = 1,
                isFactory = true,
                config = DspConfig.DEFAULT.copy(
                    mdrcEnabled = false
                )
            ),

            // 2. Clarity: Compresión moderada de medios para mejorar estabilidad y separación
            Preset(
                id = "factory_clarity",
                name = "Clarity (Claridad & Separación)",
                version = 1,
                isFactory = true,
                config = DspConfig.DEFAULT.copy(
                    mdrcEnabled = true,
                    mdrcCutoff1 = 160f,
                    mdrcCutoff2 = 800f,
                    mdrcCutoff3 = 3500f,
                    mdrcBand1 = MdrcBandConfig(threshold = -14f, ratio = 2.0f, attackTime = 25f, releaseTime = 120f, kneeWidth = 6f, postGain = 0.5f),
                    mdrcBand2 = MdrcBandConfig(threshold = -18f, ratio = 3.2f, attackTime = 15f, releaseTime = 90f, kneeWidth = 8f, postGain = 1.5f),
                    mdrcBand3 = MdrcBandConfig(threshold = -16f, ratio = 2.8f, attackTime = 10f, releaseTime = 70f, kneeWidth = 8f, postGain = 1.0f),
                    mdrcBand4 = MdrcBandConfig(threshold = -14f, ratio = 2.0f, attackTime = 8f, releaseTime = 60f, kneeWidth = 6f, postGain = 0.5f),
                    toneMid = 1.5f,
                    toneTreble = 1.0f,
                    limiterEnabled = true
                )
            ),

            // 3. Smooth: Compresión suave y progresiva con rodilla ancha
            Preset(
                id = "factory_smooth",
                name = "Smooth (Suave & Progresivo)",
                version = 1,
                isFactory = true,
                config = DspConfig.DEFAULT.copy(
                    mdrcEnabled = true,
                    mdrcCutoff1 = 180f,
                    mdrcCutoff2 = 900f,
                    mdrcCutoff3 = 4500f,
                    mdrcBand1 = MdrcBandConfig(threshold = -18f, ratio = 1.8f, attackTime = 30f, releaseTime = 200f, kneeWidth = 12f, postGain = 0.5f),
                    mdrcBand2 = MdrcBandConfig(threshold = -20f, ratio = 2.0f, attackTime = 20f, releaseTime = 150f, kneeWidth = 12f, postGain = 1.0f),
                    mdrcBand3 = MdrcBandConfig(threshold = -18f, ratio = 2.0f, attackTime = 15f, releaseTime = 120f, kneeWidth = 12f, postGain = 0.5f),
                    mdrcBand4 = MdrcBandConfig(threshold = -16f, ratio = 1.6f, attackTime = 12f, releaseTime = 100f, kneeWidth = 10f, postGain = 0.0f),
                    autoHeadroomEnabled = true
                )
            ),

            // 4. Bass Control: Mayor control dinámico de LOW sin destruir el impacto
            Preset(
                id = "factory_bass_control",
                name = "Bass Control (Pegada & Subgraves)",
                version = 1,
                isFactory = true,
                config = DspConfig.DEFAULT.copy(
                    bassBoostEnabled = true,
                    bassBoostStrength = 550,
                    mdrcEnabled = true,
                    mdrcCutoff1 = 140f,
                    mdrcCutoff2 = 700f,
                    mdrcCutoff3 = 4000f,
                    // Compresión firme en graves con release controlado para evitar bombeo
                    mdrcBand1 = MdrcBandConfig(threshold = -20f, ratio = 4.5f, attackTime = 18f, releaseTime = 160f, kneeWidth = 6f, postGain = 2.0f),
                    mdrcBand2 = MdrcBandConfig(threshold = -15f, ratio = 2.5f, attackTime = 15f, releaseTime = 100f, kneeWidth = 6f, postGain = 0.5f),
                    mdrcBand3 = MdrcBandConfig(threshold = -14f, ratio = 2.0f, attackTime = 10f, releaseTime = 80f, kneeWidth = 6f, postGain = 0f),
                    mdrcBand4 = MdrcBandConfig(threshold = -12f, ratio = 1.8f, attackTime = 8f, releaseTime = 60f, kneeWidth = 6f, postGain = 0f),
                    toneBass = 3.0f,
                    autoHeadroomEnabled = true
                )
            ),

            // 5. Vocal: Control moderado de LOW-MID y MID-HIGH para voces cristalinas
            Preset(
                id = "factory_vocal",
                name = "Vocal (Presencia Vocal & Podcast)",
                version = 1,
                isFactory = true,
                config = DspConfig.DEFAULT.copy(
                    mdrcEnabled = true,
                    mdrcCutoff1 = 200f,
                    mdrcCutoff2 = 1000f,
                    mdrcCutoff3 = 5000f,
                    mdrcBand1 = MdrcBandConfig(threshold = -12f, ratio = 2.0f, attackTime = 25f, releaseTime = 120f, kneeWidth = 6f, postGain = -1.0f),
                    mdrcBand2 = MdrcBandConfig(threshold = -18f, ratio = 3.5f, attackTime = 12f, releaseTime = 90f, kneeWidth = 8f, postGain = 2.0f),
                    mdrcBand3 = MdrcBandConfig(threshold = -20f, ratio = 3.8f, attackTime = 8f, releaseTime = 70f, kneeWidth = 8f, postGain = 2.5f),
                    mdrcBand4 = MdrcBandConfig(threshold = -16f, ratio = 2.2f, attackTime = 6f, releaseTime = 50f, kneeWidth = 6f, postGain = 0.5f),
                    toneMid = 2.5f,
                    toneBass = -1.5f,
                    limiterEnabled = true
                )
            ),

            // 6. Loudness: Compresión intensa manteniendo control riguroso de picos
            Preset(
                id = "factory_loudness",
                name = "Loudness (Máxima Energía)",
                version = 1,
                isFactory = true,
                config = DspConfig.DEFAULT.copy(
                    preGain = 2.0f,
                    mdrcEnabled = true,
                    mdrcCutoff1 = 160f,
                    mdrcCutoff2 = 800f,
                    mdrcCutoff3 = 4000f,
                    mdrcBand1 = MdrcBandConfig(threshold = -18f, ratio = 4.0f, attackTime = 15f, releaseTime = 110f, kneeWidth = 8f, postGain = 2.5f),
                    mdrcBand2 = MdrcBandConfig(threshold = -20f, ratio = 4.5f, attackTime = 10f, releaseTime = 85f, kneeWidth = 8f, postGain = 3.0f),
                    mdrcBand3 = MdrcBandConfig(threshold = -18f, ratio = 4.0f, attackTime = 8f, releaseTime = 70f, kneeWidth = 8f, postGain = 2.5f),
                    mdrcBand4 = MdrcBandConfig(threshold = -16f, ratio = 3.0f, attackTime = 5f, releaseTime = 50f, kneeWidth = 8f, postGain = 2.0f),
                    limiterEnabled = true,
                    limiterThreshold = -0.3f,
                    limiterRelease = 40f,
                    autoGainEnabled = true,
                    autoHeadroomEnabled = true
                )
            )
        )
    }

    /**
     * Flujo de la configuración activa persistida.
     */
    val activeConfigFlow: Flow<DspConfig> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[KEY_ACTIVE_CONFIG]
        if (jsonStr != null) {
            deserializeConfig(jsonStr)
        } else {
            DspConfig.DEFAULT
        }
    }

    /**
     * Flujo de presets de usuario persistidos.
     */
    val userPresetsFlow: Flow<List<Preset>> = context.dataStore.data.map { prefs ->
        val jsonStr = prefs[KEY_USER_PRESETS]
        if (jsonStr != null) {
            deserializePresets(jsonStr)
        } else {
            emptyList()
        }
    }

    suspend fun saveActiveConfig(config: DspConfig) {
        val serialized = serializeConfig(config.validate())
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_CONFIG] = serialized
        }
    }

    suspend fun saveUserPresets(presets: List<Preset>) {
        val customOnly = presets.filter { !it.isFactory }
        val serialized = serializePresets(customOnly)
        context.dataStore.edit { prefs ->
            prefs[KEY_USER_PRESETS] = serialized
        }
    }

    // ==========================================
    // SERIALIZACIÓN / DESERIALIZACIÓN JSON
    // ==========================================

    fun serializeConfig(config: DspConfig): String {
        val root = JSONObject().apply {
            put("dspEnabled", config.dspEnabled)
            put("preGain", config.preGain.toDouble())
            put("bassBoostEnabled", config.bassBoostEnabled)
            put("bassBoostStrength", config.bassBoostStrength)
            put("toneBass", config.toneBass.toDouble())
            put("toneMid", config.toneMid.toDouble())
            put("toneTreble", config.toneTreble.toDouble())
            put("eqMode", config.eqMode.name)
            put("eq10Gains", JSONArray(config.eq10Gains.map { it.toDouble() }))
            put("eq20Gains", JSONArray(config.eq20Gains.map { it.toDouble() }))
            put("eq32Gains", JSONArray(config.eq32Gains.map { it.toDouble() }))
            put("mdrcEnabled", config.mdrcEnabled)

            put("mdrcBand1", serializeBand(config.mdrcBand1))
            put("mdrcBand2", serializeBand(config.mdrcBand2))
            put("mdrcBand3", serializeBand(config.mdrcBand3))
            put("mdrcBand4", serializeBand(config.mdrcBand4))

            put("mdrcCutoff1", config.mdrcCutoff1.toDouble())
            put("mdrcCutoff2", config.mdrcCutoff2.toDouble())
            put("mdrcCutoff3", config.mdrcCutoff3.toDouble())
            put("mdrcCutoff4", config.mdrcCutoff4.toDouble())

            put("autoGainEnabled", config.autoGainEnabled)
            put("autoGainTarget", config.autoGainTarget.toDouble())
            put("autoHeadroomEnabled", config.autoHeadroomEnabled)
            put("limiterEnabled", config.limiterEnabled)
            put("limiterThreshold", config.limiterThreshold.toDouble())
            put("limiterAttack", config.limiterAttack.toDouble())
            put("limiterRelease", config.limiterRelease.toDouble())
            put("limiterRatio", config.limiterRatio.toDouble())
            put("virtualizerEnabled", config.virtualizerEnabled)
            put("virtualizerStrength", config.virtualizerStrength)
            put("masterGain", config.masterGain.toDouble())
            put("balance", config.balance.toDouble())
        }
        return root.toString(2)
    }

    fun deserializeConfig(jsonStr: String): DspConfig {
        return try {
            val root = JSONObject(jsonStr)
            val eqMode = EqMode.EQ32

            val eq10 = parseGainsArray(root.optJSONArray("eq10Gains"), 10)
            val eq20 = parseGainsArray(root.optJSONArray("eq20Gains"), 20)
            val eq32 = parseGainsArray(root.optJSONArray("eq32Gains"), 32)

            DspConfig(
                dspEnabled = root.optBoolean("dspEnabled", true),
                preGain = root.optDouble("preGain", 0.0).toFloat(),
                bassBoostEnabled = root.optBoolean("bassBoostEnabled", false),
                bassBoostStrength = root.optInt("bassBoostStrength", 0),
                toneBass = root.optDouble("toneBass", 0.0).toFloat(),
                toneMid = root.optDouble("toneMid", 0.0).toFloat(),
                toneTreble = root.optDouble("toneTreble", 0.0).toFloat(),
                eqMode = eqMode,
                eq10Gains = eq10,
                eq20Gains = eq20,
                eq32Gains = eq32,
                mdrcEnabled = root.optBoolean("mdrcEnabled", false),
                mdrcBand1 = deserializeBand(root.optJSONObject("mdrcBand1")),
                mdrcBand2 = deserializeBand(root.optJSONObject("mdrcBand2")),
                mdrcBand3 = deserializeBand(root.optJSONObject("mdrcBand3")),
                mdrcBand4 = deserializeBand(root.optJSONObject("mdrcBand4")),
                mdrcCutoff1 = root.optDouble("mdrcCutoff1", 160.0).toFloat(),
                mdrcCutoff2 = root.optDouble("mdrcCutoff2", 800.0).toFloat(),
                mdrcCutoff3 = root.optDouble("mdrcCutoff3", 4000.0).toFloat(),
                mdrcCutoff4 = root.optDouble("mdrcCutoff4", 20000.0).toFloat(),
                autoGainEnabled = root.optBoolean("autoGainEnabled", false),
                autoGainTarget = root.optDouble("autoGainTarget", -14.0).toFloat(),
                autoHeadroomEnabled = root.optBoolean("autoHeadroomEnabled", true),
                limiterEnabled = root.optBoolean("limiterEnabled", true),
                limiterThreshold = root.optDouble("limiterThreshold", -0.5).toFloat(),
                limiterAttack = root.optDouble("limiterAttack", 1.5).toFloat(),
                limiterRelease = root.optDouble("limiterRelease", 50.0).toFloat(),
                limiterRatio = root.optDouble("limiterRatio", 10.0).toFloat(),
                virtualizerEnabled = root.optBoolean("virtualizerEnabled", false),
                virtualizerStrength = root.optInt("virtualizerStrength", 0),
                masterGain = root.optDouble("masterGain", 0.0).toFloat(),
                balance = root.optDouble("balance", 0.0).toFloat()
            ).validate()
        } catch (e: Exception) {
            DspConfig.DEFAULT
        }
    }

    private fun serializeBand(band: MdrcBandConfig): JSONObject = JSONObject().apply {
        put("enabled", band.enabled)
        put("threshold", band.threshold.toDouble())
        put("ratio", band.ratio.toDouble())
        put("attackTime", band.attackTime.toDouble())
        put("releaseTime", band.releaseTime.toDouble())
        put("kneeWidth", band.kneeWidth.toDouble())
        put("preGain", band.preGain.toDouble())
        put("postGain", band.postGain.toDouble())
    }

    private fun deserializeBand(json: JSONObject?): MdrcBandConfig {
        if (json == null) return MdrcBandConfig()
        return MdrcBandConfig(
            enabled = json.optBoolean("enabled", true),
            threshold = json.optDouble("threshold", -12.0).toFloat(),
            ratio = json.optDouble("ratio", 2.5).toFloat(),
            attackTime = json.optDouble("attackTime", 15.0).toFloat(),
            releaseTime = json.optDouble("releaseTime", 120.0).toFloat(),
            kneeWidth = json.optDouble("kneeWidth", 6.0).toFloat(),
            preGain = json.optDouble("preGain", 0.0).toFloat(),
            postGain = json.optDouble("postGain", 0.0).toFloat()
        ).validate()
    }

    private fun parseGainsArray(arr: JSONArray?, defaultSize: Int): List<Float> {
        if (arr == null) return List(defaultSize) { 0f }
        val list = mutableListOf<Float>()
        for (i in 0 until arr.length()) {
            list.add(arr.optDouble(i, 0.0).toFloat())
        }
        return list
    }

    fun serializePresets(presets: List<Preset>): String {
        val array = JSONArray()
        for (p in presets) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("version", p.version)
                put("timestamp", p.timestamp)
                put("isFactory", p.isFactory)
                put("config", JSONObject(serializeConfig(p.config)))
            }
            array.put(obj)
        }
        return array.toString(2)
    }

    fun deserializePresets(jsonStr: String): List<Preset> {
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Preset>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val configObj = obj.getJSONObject("config")
                val config = deserializeConfig(configObj.toString())
                list.add(
                    Preset(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        version = obj.optInt("version", 1),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isFactory = obj.optBoolean("isFactory", false),
                        config = config
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
