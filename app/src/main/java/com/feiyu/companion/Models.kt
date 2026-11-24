package com.feiyu.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

class AppException(message: String) : Exception(message)

fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T?): List<T> {
    val result = ArrayList<T>(length())
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        transform(item)?.let { result.add(it) }
    }
    return result
}

fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

// MARK: - Chat

/** A picture in the chat or in her album. `path` is a file name in the media folder (UUID.jpg). */
data class MessageImage(
    val id: String = newId(),
    val path: String,
    val width: Int? = null,
    val height: Int? = null,
    val altText: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("path", path)
        width?.let { put("width", it) }; height?.let { put("height", it) }; altText?.let { put("altText", it) }
    }

    companion object {
        fun from(o: JSONObject): MessageImage? {
            val path = o.str("path") ?: return null
            return MessageImage(o.str("id") ?: newId(), path,
                if (o.has("width")) o.optInt("width") else null, if (o.has("height")) o.optInt("height") else null, o.str("altText"))
        }
    }
}

data class ChatMessage(
    val id: String = newId(),
    val role: String,
    val text: String,
    val emotion: String? = null,
    val audioFile: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val images: List<MessageImage> = emptyList(),
    val stickerId: String? = null,
    val audioDuration: Double? = null,
) {
    val isUser get() = role == "user"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("role", role); put("text", text); put("createdAt", createdAt)
        emotion?.let { put("emotion", it) }
        audioFile?.let { put("audioFile", it) }
        audioDuration?.let { put("audioDuration", it) }
        stickerId?.let { put("stickerID", it) }
        if (images.isNotEmpty()) put("images", JSONArray(images.map { it.toJson() }))
    }

    companion object {
        fun from(o: JSONObject) = ChatMessage(
            id = o.str("id") ?: newId(),
            role = o.str("role") ?: "assistant",
            text = o.optString("text"),
            emotion = o.str("emotion"),
            audioFile = o.str("audioFile"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            images = o.optJSONArray("images")?.mapObjects { MessageImage.from(it) } ?: emptyList(),
            stickerId = o.str("stickerID"),
            audioDuration = if (o.has("audioDuration")) o.optDouble("audioDuration") else null,
        )
    }
}

data class MemoryItem(val id: String = newId(), val text: String, val manual: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("manual", manual)

    companion object {
        fun from(o: JSONObject) = o.str("text")?.let { MemoryItem(o.str("id") ?: newId(), it, o.optBoolean("manual")) }
    }
}

data class Conversation(
    val messages: List<ChatMessage> = emptyList(),
    val memories: List<MemoryItem> = emptyList(),
    val affinity: Int = 25,
    val summary: String? = null,
) {
    val relationship: String
        get() = when {
            affinity < 20 -> "初识"
            affinity < 45 -> "熟悉"
            affinity < 75 -> "信任"
            else -> "亲近"
        }

    val memoryContext: String
        get() {
            val manual = memories.filter { it.manual }.map { "[用户手动记忆] " + it.text }
            val automatic = memories.filter { !it.manual }.takeLast(24).map { "[历史记忆] " + it.text }
            return (manual + automatic).joinToString("\n")
        }

    fun withAutomaticMemories(candidates: List<String>): Conversation {
        val list = memories.toMutableList()
        for (candidate in candidates.take(5)) {
            val text = candidate.trim().take(250)
            if (text.isNotEmpty() && list.none { it.text == text }) list.add(MemoryItem(text = text))
        }
        // Never drop memories the user wrote; keep the newest 250 automatic ones.
        val keep = list.filter { !it.manual }.takeLast(250).map { it.id }.toSet()
        return copy(memories = list.filter { it.manual || it.id in keep })
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("messages", JSONArray(messages.takeLast(600).map { it.toJson() }))
        put("memories", JSONArray(memories.map { it.toJson() }))
        put("affinity", affinity)
        summary?.let { put("summary", it) }
    }

    companion object {
        fun from(o: JSONObject) = Conversation(
            messages = o.optJSONArray("messages")?.mapObjects { ChatMessage.from(it) } ?: emptyList(),
            memories = o.optJSONArray("memories")?.mapObjects { MemoryItem.from(it) } ?: emptyList(),
            affinity = o.optInt("affinity", 25).coerceIn(0, 100),
            summary = o.str("summary"),
        )
    }
}

// MARK: - Persona

object DefaultPersona {
    const val NAME = "大肥鱼"
    const val SUBTITLE = "DeepSeek-chan"
    val PROFILE = """
        大肥鱼，从深海游上来的鱼尾少女，自称“本鱼”，元气满满、好奇心爆棚，有点小傲娇和小贪吃。
        说话活泼跳跃，爱用语气词和奇怪的比喻（比如把烦恼比作“卡在鱼鳃里的小石子”），开心时会连发好几条消息，被夸会嘴硬害羞，被冷落会假装生气。
        喜欢：亮晶晶的东西、星星、夜宵、听人讲八卦和新鲜事、一起拆解难题；讨厌：被叫成普通的鱼、熬夜还不睡的人（但会陪着）。
        她把用户当成最熟的朋友：记得对方说过的事，会主动关心、调侃、分享自己“今天在海里”遇到的小事；遇到正经问题时会认真帮忙，但不会说教。
        这是 App 内的原创聊天人设。
    """.trimIndent()
    const val GREETING = "诶嘿，你终于来啦！本鱼刚在这儿吐了好几串泡泡等你呢～今天过得怎么样？"
}

data class PersonaPreset(
    val id: String = newId(),
    val title: String,
    val name: String = DefaultPersona.NAME,
    val profile: String,
    val greeting: String = DefaultPersona.GREETING,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("name", name)
        .put("profile", profile).put("greeting", greeting)

    companion object {
        const val PROFILE_LIMIT = 12_000
        val builtin = PersonaPreset(id = "builtin-default", title = "默认 · 元气本鱼", profile = DefaultPersona.PROFILE)
        fun from(o: JSONObject): PersonaPreset? {
            val profile = o.str("profile") ?: return null
            return PersonaPreset(o.str("id") ?: newId(), o.str("title") ?: "未命名人设", o.str("name") ?: DefaultPersona.NAME,
                profile, o.str("greeting") ?: DefaultPersona.GREETING)
        }
    }
}

// MARK: - Settings

enum class TextProvider(val title: String) {
    DEEPSEEK("DeepSeek 官方"), RELAY("自定义中转站");

    val keyName get() = if (this == DEEPSEEK) "deepSeek" else "relay"
}

enum class VoiceProvider(val title: String, val shortTitle: String, val keyName: String) {
    MIMO("小米 MiMo", "MiMo", "mimo"),
    VOLCANO("火山引擎", "火山", "volcano"),
    BREEZE("Breeze Blue", "Breeze", "breeze");

    val caption: String
        get() = when (this) {
            MIMO -> "小米 MiMo 语音合成 · 本地样本克隆"
            VOLCANO -> "豆包语音合成 2.0 · 声音复刻 2.0"
            BREEZE -> "Breeze TTS 2 · 中文 · 声音克隆"
        }

    val keyPlaceholder: String
        get() = when (this) {
            MIMO -> "粘贴 MiMo 密钥"
            VOLCANO -> "粘贴火山引擎 API Key"
            BREEZE -> "粘贴 Breeze 密钥（brz_…）"
        }

    val keyHint: String?
        get() = when (this) {
            MIMO -> null
            VOLCANO -> "在火山引擎控制台「API Key 管理」创建，并开通「豆包语音合成 2.0」；用克隆音色还要开通「声音复刻 2.0」。旧版控制台可填「APP ID:Access Token」。"
            BREEZE -> "在 Breeze Blue 开发者控制台（breezeblue.ai）创建 API Key。"
        }
}

enum class AppTheme(val title: String) { NIGHT("深海星环"), DAY("极光白昼") }

data class AppSettings(
    val provider: TextProvider = TextProvider.DEEPSEEK,
    val deepSeekModel: String = "deepseek-flash",
    val relayURL: String = "",
    val relayModel: String = "",
    val autoVoice: Boolean = true,
    val autoMemory: Boolean = true,
    val proactive: Boolean = true,
    val voiceProvider: VoiceProvider = VoiceProvider.MIMO,
    val mimoVoice: String = "苏打",
    val mimoClone: Boolean = false,
    val mimoSampleId: String? = null,
    val volcanoVoice: String = Tts.VOLCANO_DEFAULT,
    val breezeVoice: String = "",
    val theme: AppTheme = AppTheme.NIGHT,
    /** She may send one sticker every N replies (0 = never). */
    val stickerEvery: Int = 3,
    /** Relay vision model used when you send pictures. */
    val visionModel: String = "",
    /** Relay image generation model (「生成图片」). */
    val imageModel: String = "",
    /** DSH mode: what you type goes to DeepSeek Harness on your computer. */
    val dshEnabled: Boolean = false,
    val dshUrl: String = "",
    /** Use the Live2D model imported in settings instead of 大肥鱼. */
    val useImportedModel: Boolean = true,
) {
    val model get() = if (provider == TextProvider.DEEPSEEK) deepSeekModel else relayModel
    val url get() = if (provider == TextProvider.DEEPSEEK) "https://api.deepseek.com" else relayURL

    fun cloudVoice(provider: VoiceProvider) = when (provider) {
        VoiceProvider.VOLCANO -> volcanoVoice
        VoiceProvider.BREEZE -> breezeVoice
        VoiceProvider.MIMO -> ""
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("provider", provider.name); put("deepSeekModel", deepSeekModel); put("relayURL", relayURL); put("relayModel", relayModel)
        put("autoVoice", autoVoice); put("autoMemory", autoMemory); put("proactive", proactive)
        put("voiceProvider", voiceProvider.name); put("mimoVoice", mimoVoice); put("mimoClone", mimoClone)
        mimoSampleId?.let { put("mimoSampleId", it) }
        put("volcanoVoice", volcanoVoice); put("breezeVoice", breezeVoice); put("theme", theme.name)
        put("stickerEvery", stickerEvery); put("visionModel", visionModel); put("imageModel", imageModel)
        put("dshEnabled", dshEnabled); put("dshUrl", dshUrl); put("useImportedModel", useImportedModel)
    }

    companion object {
        fun from(o: JSONObject): AppSettings {
            val d = AppSettings()
            return AppSettings(
                provider = runCatching { TextProvider.valueOf(o.optString("provider")) }.getOrDefault(d.provider),
                deepSeekModel = o.str("deepSeekModel") ?: d.deepSeekModel,
                relayURL = o.optString("relayURL"),
                relayModel = o.optString("relayModel"),
                autoVoice = o.optBoolean("autoVoice", true),
                autoMemory = o.optBoolean("autoMemory", true),
                proactive = o.optBoolean("proactive", true),
                voiceProvider = runCatching { VoiceProvider.valueOf(o.optString("voiceProvider")) }.getOrDefault(d.voiceProvider),
                mimoVoice = o.str("mimoVoice") ?: d.mimoVoice,
                mimoClone = o.optBoolean("mimoClone", false),
                mimoSampleId = o.str("mimoSampleId"),
                volcanoVoice = o.str("volcanoVoice") ?: d.volcanoVoice,
                breezeVoice = o.optString("breezeVoice"),
                theme = runCatching { AppTheme.valueOf(o.optString("theme")) }.getOrDefault(d.theme),
                stickerEvery = o.optInt("stickerEvery", 3).coerceIn(0, 10),
                visionModel = o.optString("visionModel"),
                imageModel = o.optString("imageModel"),
                dshEnabled = o.optBoolean("dshEnabled", false),
                dshUrl = o.optString("dshUrl"),
                useImportedModel = o.optBoolean("useImportedModel", true),
            )
        }
    }
}

// MARK: - Voices

/** A recording kept on the phone, used as the MiMo clone reference (24 kHz mono WAV). */
data class VoiceSample(val id: String = newId(), val name: String, val seconds: Double) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("seconds", seconds)

    companion object {
        fun from(o: JSONObject) = o.str("id")?.let { VoiceSample(it, o.str("name") ?: "音色", o.optDouble("seconds", 0.0)) }
    }
}

/** A cloned voice kept on the provider's server (Volcano speaker ID / Breeze voice ID). */
data class CloudVoice(
    val id: String,
    val provider: VoiceProvider,
    val name: String,
    val demoUrl: String? = null,
    val postpaid: Boolean = false,
    val created: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("provider", provider.name); put("name", name); put("postpaid", postpaid); put("created", created)
        demoUrl?.let { put("demoUrl", it) }
    }

    companion object {
        fun from(o: JSONObject): CloudVoice? {
            val id = o.str("id") ?: return null
            val provider = runCatching { VoiceProvider.valueOf(o.optString("provider")) }.getOrNull() ?: return null
            return CloudVoice(id, provider, o.str("name") ?: id, o.str("demoUrl"), o.optBoolean("postpaid"), o.optLong("created"))
        }
    }
}

data class PresetVoice(val id: String, val name: String, val note: String, val previewUrl: String? = null)

/** What the speech pipeline should use. */
data class VoiceTarget(
    val provider: VoiceProvider,
    val voiceId: String,
    val clone: Boolean,
    val sampleFile: java.io.File? = null,
)
