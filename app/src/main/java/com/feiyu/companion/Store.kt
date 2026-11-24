package com.feiyu.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything is stored privately on the phone (app sandbox). */
class Store(context: Context) {
    val prefs = context.getSharedPreferences("feiyu", Context.MODE_PRIVATE)
    private val keys = context.getSharedPreferences("feiyu_keys", Context.MODE_PRIVATE)
    val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
    val samplesDir = File(context.filesDir, "voice_samples").apply { mkdirs() }
    val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
    val stickersDir = File(context.filesDir, "stickers").apply { mkdirs() }
    val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
    val syncFile = File(context.filesDir, "sync-archive.json")
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

    var album: List<MessageImage>
        get() = arr("album")?.mapObjects { MessageImage.from(it) }?.filter { mediaFile(it.path)?.exists() == true } ?: emptyList()
        set(value) = put("album", JSONArray(value.map { it.toJson() }).toString())

    /** Replies since her last sticker. */
    var stickerTurns: Int
        get() = prefs.getInt("stickerTurns", 99)
        set(value) = prefs.edit().putInt("stickerTurns", value).apply()

    var dshSession: String?
        get() = prefs.getString("dshSession", null)
        set(value) = prefs.edit().apply { if (value == null) remove("dshSession") else putString("dshSession", value) }.apply()

    /** Stable device ID for sync (uppercase UUID, like the iPhone app). */
    val syncDevice: String
        get() = prefs.getString("syncDevice", null) ?: java.util.UUID.randomUUID().toString().uppercase().also { put("syncDevice", it) }

    var syncSeenKeys: Set<String>
        get() = prefs.getStringSet("syncSeenKeys", emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("syncSeenKeys", value).apply()

    var syncFolder: String
        get() = prefs.getString("syncFolder", "").orEmpty()
        set(value) = put("syncFolder", value)

    var syncFolderName: String
        get() = prefs.getString("syncFolderName", "").orEmpty()
        set(value) = put("syncFolderName", value)

    var webdavUrl: String
        get() = prefs.getString("webdavUrl", "").orEmpty()
        set(value) = put("webdavUrl", value)

    var webdavUser: String
        get() = prefs.getString("webdavUser", "").orEmpty()
        set(value) = put("webdavUser", value)

    /** "folder" or "webdav". */
    var syncMode: String
        get() = prefs.getString("syncMode", "folder").orEmpty()
        set(value) = put("syncMode", value)

    var lastSyncAt: Long
        get() = prefs.getLong("lastSyncAt", 0)
        set(value) = prefs.edit().putLong("lastSyncAt", value).apply()

    fun sampleFile(sample: VoiceSample) = File(samplesDir, sample.id + ".wav")

    /** Only plain names inside the media folder (no paths). */
    fun mediaFile(name: String): File? {
        val clean = name.substringAfterLast('/')
        if (clean.isEmpty() || clean.startsWith(".") || !(clean.endsWith(".jpg") || clean.endsWith(".wav"))) return null
        return File(mediaDir, clean)
    }
    fun audioFile(name: String) = File(audioDir, name)
}
