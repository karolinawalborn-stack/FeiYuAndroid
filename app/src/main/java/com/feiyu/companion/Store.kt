package com.feiyu.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything is stored privately on the phone (app sandbox). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("feiyu", Context.MODE_PRIVATE)
    private val keys = context.getSharedPreferences("feiyu_keys", Context.MODE_PRIVATE)
    val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
    val samplesDir = File(context.filesDir, "voice_samples").apply { mkdirs() }
    val cacheDir: File = context.cacheDir

    fun secret(name: String): String = keys.getString(name, "")?.trim().orEmpty()

    fun saveSecret(name: String, value: String) {
        val trimmed = value.trim()
        keys.edit().apply { if (trimmed.isEmpty()) remove(name) else putString(name, trimmed) }.apply()
    }

    private fun obj(key: String): JSONObject? = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun arr(key: String): JSONArray? = prefs.getString(key, null)?.let { runCatching { JSONArray(it) }.getOrNull() }
    private fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()

    var settings: AppSettings
        get() = obj("settings")?.let { AppSettings.from(it) } ?: AppSettings()
        set(value) = put("settings", value.toJson().toString())

    var conversation: Conversation
        get() = obj("conversation")?.let { Conversation.from(it) } ?: Conversation()
        set(value) = put("conversation", value.toJson().toString())

    var presets: List<PersonaPreset>
        get() = arr("presets")?.mapObjects { PersonaPreset.from(it) }?.takeIf { it.isNotEmpty() } ?: listOf(PersonaPreset.builtin)
        set(value) = put("presets", JSONArray(value.map { it.toJson() }).toString())

    var activePresetId: String
        get() = prefs.getString("activePreset", null) ?: PersonaPreset.builtin.id
        set(value) = put("activePreset", value)

    var samples: List<VoiceSample>
        get() = arr("samples")?.mapObjects { VoiceSample.from(it) }?.filter { sampleFile(it).exists() } ?: emptyList()
        set(value) = put("samples", JSONArray(value.map { it.toJson() }).toString())

    var cloudVoices: List<CloudVoice>
        get() = arr("cloudVoices")?.mapObjects { CloudVoice.from(it) } ?: emptyList()
        set(value) = put("cloudVoices", JSONArray(value.map { it.toJson() }).toString())

    var lastGreetingAt: Long
        get() = prefs.getLong("lastGreetingAt", 0)
        set(value) = prefs.edit().putLong("lastGreetingAt", value).apply()

    var breezeDefaultVoice: String
        get() = prefs.getString("breezeDefaultVoice", "").orEmpty()
        set(value) = put("breezeDefaultVoice", value)

    /** Names of voices picked from catalogs, so the status line can show a name instead of an ID. */
    fun voiceName(provider: VoiceProvider, id: String): String? = prefs.getString("voiceName:${provider.name}:$id", null)
    fun rememberVoiceName(provider: VoiceProvider, id: String, name: String) = put("voiceName:${provider.name}:$id", name)

    fun sampleFile(sample: VoiceSample) = File(samplesDir, sample.id + ".wav")
    fun audioFile(name: String) = File(audioDir, name)
}
