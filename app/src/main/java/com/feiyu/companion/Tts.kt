package com.feiyu.companion

import android.util.Base64
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object Tts {
    const val VOLCANO_DEFAULT = "zh_female_vv_uranus_bigtts"

    val mimoVoices = listOf("苏打", "茉莉", "白桦")

    val volcanoPresets = listOf(
        PresetVoice("zh_female_vv_uranus_bigtts", "Vivi 2.0", "通用 · 活泼 · 支持方言"),
        PresetVoice("zh_female_sajiaoxuemei_uranus_bigtts", "撒娇学妹 2.0", "角色扮演 · 甜"),
        PresetVoice("zh_female_mengyatou_uranus_bigtts", "萌丫头 2.0", "可爱 · 元气"),
        PresetVoice("zh_female_qiaopinv_uranus_bigtts", "俏皮女声 2.0", "俏皮"),
        PresetVoice("zh_female_yingtaowanzi_uranus_bigtts", "樱桃丸子 2.0", "角色扮演 · 童趣"),
        PresetVoice("zh_female_tianmeitaozi_uranus_bigtts", "甜美桃子 2.0", "甜美"),
        PresetVoice("zh_female_tianmeixiaoyuan_uranus_bigtts", "甜美小源 2.0", "甜美"),
        PresetVoice("zh_female_tianmeiyueyue_uranus_bigtts", "甜美悦悦 2.0", "甜美"),
        PresetVoice("zh_female_linjianvhai_uranus_bigtts", "邻家女孩 2.0", "自然 · 亲切"),
        PresetVoice("zh_female_kailangjiejie_uranus_bigtts", "开朗姐姐 2.0", "开朗"),
        PresetVoice("zh_female_shuangkuaisisi_uranus_bigtts", "爽快思思 2.0", "通用 · 爽快"),
        PresetVoice("zh_female_xiaohe_uranus_bigtts", "小何 2.0", "通用 · 温柔"),
        PresetVoice("zh_female_cancan_uranus_bigtts", "知性灿灿 2.0", "知性"),
        PresetVoice("zh_female_meilinvyou_uranus_bigtts", "魅力女友 2.0", "温柔 · 撩"),
        PresetVoice("zh_female_roumeinvyou_uranus_bigtts", "柔美女友 2.0", "柔美"),
        PresetVoice("zh_female_qingchezizi_uranus_bigtts", "清澈梓梓 2.0", "清澈"),
        PresetVoice("zh_female_gaolengyujie_uranus_bigtts", "高冷御姐 2.0", "御姐"),
        PresetVoice("zh_male_shaonianzixin_uranus_bigtts", "少年梓辛 2.0", "男声 · 少年"),
        PresetVoice("zh_male_m191_uranus_bigtts", "云舟 2.0", "男声 · 通用"),
        PresetVoice("zh_male_taocheng_uranus_bigtts", "小天 2.0", "男声 · 通用"),
    )

    // MARK: Delivery hints

    fun speechStyle(emotion: String?): String = when (emotion) {
        "happy" -> "开心、轻快、带着笑意，语速稍快，尾音上扬。"
        "excited" -> "非常兴奋、元气满满，语速快，音调高，像忍不住要分享好消息。"
        "shy" -> "害羞、小声一点，带点不好意思的笑，语速稍慢。"
        "sad" -> "有点低落和委屈，语速放慢，声音轻柔。"
        "angry" -> "假装生气的傲娇语气，带点抱怨和撒娇，不是真的凶。"
        "surprised" -> "很惊讶，语调突然上扬，带一点惊呼。"
        "smug" -> "得意、调皮、有点小嘚瑟的语气。"
        "confused" -> "疑惑、迷糊，语气上扬，像在歪头想事情。"
        else -> "自然、亲切、俏皮的日常聊天语气，像在和熟悉的朋友说话。"
    }

    fun cloneStyle(emotion: String?): String = when (emotion) {
        null, "", "neutral" -> "完全保持参考音频的音色、口音和说话习惯，自然地说。"
        else -> "完全保持参考音频的音色、口音和说话习惯，语气稍微带一点" + speechStyle(emotion).substringBefore("，") + "。"
    }

    fun mood(emotion: String?): String? = when (emotion) {
        "happy" -> "开心、轻快"
        "excited" -> "兴奋、元气满满"
        "shy" -> "害羞、小声"
        "sad" -> "有点低落、轻柔"
        "angry" -> "傲娇、假装生气"
        "surprised" -> "惊讶"
        "smug" -> "得意、调皮"
        "confused" -> "疑惑、迷糊"
        else -> null
    }

    // MARK: Entry point

    /** Speaks `text` as 24 kHz mono PCM16 chunks. Returns MiMo's finish reason (null for others). */
    suspend fun stream(text: String, target: VoiceTarget, emotion: String?, key: String, onAudio: suspend (ByteArray) -> Unit): String? {
        if (key.isBlank()) throw AppException("请在设置中保存${target.provider.title} API Key。")
        return when (target.provider) {
            VoiceProvider.MIMO -> MiMo.stream(text, target, emotion, key, onAudio)
            VoiceProvider.VOLCANO -> { Volcano.stream(text, target.voiceId, target.clone, emotion, key, onAudio); null }
            VoiceProvider.BREEZE -> { Breeze.stream(text, target.voiceId.ifEmpty { Breeze.defaultVoice(key) }, target.clone, emotion, key, onAudio); null }
        }
    }

    /** Whole sentence as WAV (preview / test). */
    suspend fun synthesize(text: String, target: VoiceTarget, emotion: String?, key: String): ByteArray {
        val collector = PcmCollector()
        stream(text, target, emotion, key) { collector.add(it) }
        if (collector.size < 2_400) throw AppException("${target.provider.title} 没有返回可播放的语音。")
        return Wav.make(collector.bytes())
    }

    suspend fun download(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return Net.run(builder.build()) { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful || bytes.isEmpty()) {
                throw AppException("试听音频下载失败（HTTP ${response.code}）。" + if (response.code in listOf(403, 404)) "链接可能已过期。" else "")
            }
            bytes
        }
    }

    /** Any downloaded audio → 24 kHz WAV for the player. */
    fun playable(data: ByteArray, cacheDir: File): ByteArray {
        if (Wav.isWav(data)) return data
        val file = File(cacheDir, "remote-" + UUID.randomUUID() + ".audio")
        file.writeBytes(data)
        try {
            return Wav.make(Wav.bytes(AudioDecoder.decode(file)))
        } finally {
            file.delete()
        }
    }

    internal fun base64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
}

/** Keeps PCM16 chunks on whole samples when the server splits mid-sample. */
private class PcmAligner {
    private var carry: Byte? = null
    fun push(data: ByteArray): ByteArray {
        var bytes = data
        carry?.let { bytes = byteArrayOf(it) + data; carry = null }
        if (bytes.size % 2 == 1) { carry = bytes.last(); bytes = bytes.copyOf(bytes.size - 1) }
        return bytes
    }
}

// MARK: - 小米 MiMo

object MiMo {
    private const val URL = "https://api.xiaomimimo.com/v1/chat/completions"
    private val referenceCache = HashMap<String, String>()

    /** The clone reference is uploaded with each request: a trimmed 12 s clip keeps it small. */
    @Synchronized
    fun reference(file: File): String {
        val stamp = file.path + "|" + file.lastModified() + "|" + file.length()
        referenceCache[stamp]?.let { return it }
        var bytes = file.readBytes()
        Wav.parse(bytes)?.let { pcm ->
            if (pcm.bits == 16 && pcm.channels == 1) {
                bytes = Wav.make(Wav.bytes(Wav.trimReference(Wav.shorts(pcm.data), pcm.sampleRate, 12.0)), pcm.sampleRate)
            }
        }
        if (bytes.size > 7_500_000) throw AppException("语音样本过大，请使用较短的录音。")
        val value = "data:audio/wav;base64," + Tts.base64(bytes)
        if (referenceCache.size > 6) referenceCache.clear()
        referenceCache[stamp] = value
        return value
    }

    suspend fun stream(text: String, target: VoiceTarget, emotion: String?, key: String, onAudio: suspend (ByteArray) -> Unit): String? {
        val sample = target.sampleFile
        val voice = if (target.clone && sample != null) reference(sample) else target.voiceId.ifEmpty { "苏打" }
        val style = if (target.clone) Tts.cloneStyle(emotion) else Tts.speechStyle(emotion)
        val body = JSONObject()
            .put("model", if (target.clone) "mimo-v2.5-tts-voiceclone" else "mimo-v2.5-tts")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "user").put("content", style))
                .put(JSONObject().put("role", "assistant").put("content", text)))
            .put("audio", JSONObject().put("format", "pcm16").put("voice", voice))
            .put("stream", true)
        val request = Request.Builder().url(URL).header("Authorization", "Bearer $key")
            .header("Accept", "text/event-stream").post(Net.json(body)).build()
        return Net.run(request) { response ->
            val type = response.header("Content-Type").orEmpty().lowercase()
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                throw AppException("MiMo 语音失败（HTTP ${response.code}）：" + Net.errorText(raw, "请检查密钥与额度。").replace(key, "[API Key]"))
            }
            if (!type.contains("event-stream")) {
                val raw = response.body?.string().orEmpty()
                val choice = runCatching { JSONObject(raw).getJSONArray("choices").getJSONObject(0) }.getOrNull()
                val encoded = choice?.optJSONObject("message")?.optJSONObject("audio")?.str("data")
                    ?: throw AppException("MiMo 没有返回可播放的语音。")
                onAudio(Wav.payload(Base64.decode(encoded, Base64.DEFAULT)))
                return@run choice?.str("finish_reason")
            }
            val source = response.body!!.source()
            val aligner = PcmAligner()
            var received = false
            var reason: String? = null
            while (true) {
                ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload == "[DONE]") break
                val event = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                event.optJSONObject("error")?.str("message")?.let { throw AppException(it.replace(key, "[API Key]").take(300)) }
                val choice = event.optJSONArray("choices")?.optJSONObject(0) ?: continue
                choice.str("finish_reason")?.let { reason = it }
                val encoded = choice.optJSONObject("delta")?.optJSONObject("audio")?.str("data")
                    ?: choice.optJSONObject("message")?.optJSONObject("audio")?.str("data") ?: continue
                val audio = aligner.push(Wav.payload(Base64.decode(encoded, Base64.DEFAULT)))
                if (audio.isNotEmpty()) { received = true; onAudio(audio) }
            }
            if (!received) throw AppException("MiMo 没有返回可播放的语音。")
            reason
        }
    }
}

// MARK: - 火山引擎（豆包语音）

object Volcano {
    private const val TTS = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
    private const val CLONE = "https://openspeech.bytedance.com/api/v3/tts/voice_clone"
    private const val STATUS = "https://openspeech.bytedance.com/api/v3/tts/get_voice"
    var uid = "feiyu-android"

    /** New console: one API Key. Old console: "APP ID:Access Token". */
    private fun authorize(builder: Request.Builder, key: String, clone: Boolean): Request.Builder {
        builder.header("X-Api-Request-Id", UUID.randomUUID().toString())
        val colon = key.indexOf(':')
        if (colon > 0) {
            builder.header(if (clone) "X-Api-App-Key" else "X-Api-App-Id", key.substring(0, colon).trim())
            builder.header("X-Api-Access-Key", key.substring(colon + 1).trim())
        } else builder.header("X-Api-Key", key)
        return builder
    }

    fun resources(voice: String, clone: Boolean): List<String> = when {
        clone || voice.startsWith("S_") -> listOf("seed-icl-2.0", "seed-icl-1.0")
        voice.contains("_uranus_") || voice.contains("_saturn_") -> listOf("seed-tts-2.0", "seed-tts-1.0")
        else -> listOf("seed-tts-1.0", "seed-tts-2.0")
    }

    private class Failure(message: String, val received: Boolean) : Exception(message)

    suspend fun stream(text: String, voice: String, clone: Boolean, emotion: String?, key: String, onAudio: suspend (ByteArray) -> Unit) {
        val speaker = voice.ifEmpty { Tts.VOLCANO_DEFAULT }
        var last: Exception? = null
        for (resource in resources(speaker, clone)) {
            try {
                attempt(text, speaker, resource, clone, emotion, key, onAudio)
                return
            } catch (failure: Failure) {
                if (failure.received) throw AppException(failure.message ?: "火山引擎语音中断。")
                last = failure
            }
        }
        throw AppException(last?.message ?: "火山引擎没有返回语音。")
    }

    private fun explain(status: Int, detail: String, resource: String): String {
        var text = "火山引擎语音失败" + (if (status != 200) "（HTTP $status）" else "") + "：" + detail
        val lower = detail.lowercase()
        if (status == 401 || lower.contains("auth") || lower.contains("key")) text += "。请检查 API Key 是否正确。"
        else if (status == 403 || lower.contains("resource") || lower.contains("grant") || lower.contains("speaker")) {
            text += if (resource.startsWith("seed-icl")) "。请确认已开通「声音复刻」，且这个音色已训练完成。"
            else "。请确认已开通「豆包语音合成 2.0」，音色 ID 填写正确。"
        }
        return text
    }

    private suspend fun attempt(text: String, speaker: String, resource: String, clone: Boolean, emotion: String?, key: String,
                                onAudio: suspend (ByteArray) -> Unit) {
        val additions = JSONObject().put("disable_markdown_filter", true)
        val mood = Tts.mood(emotion)
        if (!clone && resource == "seed-tts-2.0" && mood != null) additions.put("context_texts", JSONArray().put("你可以用${mood}的语气说话吗？"))
        val body = JSONObject()
            .put("user", JSONObject().put("uid", uid))
            .put("req_params", JSONObject()
                .put("text", text).put("speaker", speaker)
                .put("audio_params", JSONObject().put("format", "pcm").put("sample_rate", SAMPLE_RATE))
                .put("additions", additions.toString()))
        val request = authorize(Request.Builder().url(TTS), key, clone = false)
            .header("X-Api-Resource-Id", resource).post(Net.json(body)).build()
        Net.run(request) { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                throw Failure(explain(response.code, Net.errorText(raw, "HTTP ${response.code}"), resource), received = false)
            }
            // The body is a run of JSON objects; split them by brace depth (newlines are not guaranteed).
            val input = response.body!!.byteStream()
            val aligner = PcmAligner()
            var received = false
            val current = java.io.ByteArrayOutputStream(96_000)
            var depth = 0; var inString = false; var escaped = false
            val chunk = ByteArray(16_384)
            reading@ while (true) {
                ensureActive()
                val n = input.read(chunk)
                if (n < 0) break
                for (i in 0 until n) {
                    val b = chunk[i].toInt()
                    if (depth == 0) {
                        if (b == '{'.code) { depth = 1; current.reset(); current.write(b) }
                        continue
                    }
                    current.write(b)
                    if (inString) {
                        if (escaped) escaped = false else if (b == '\\'.code) escaped = true else if (b == '"'.code) inString = false
                        continue
                    }
                    when (b) {
                        '"'.code -> inString = true
                        '{'.code -> depth++
                        '}'.code -> {
                            depth--
                            if (depth == 0) {
                                val o = runCatching { JSONObject(current.toString("UTF-8")) }.getOrNull() ?: continue
                                val code = o.optInt("code", 0)
                                if (code == 20_000_000) break@reading
                                if (code != 0) throw Failure(explain(200, "$code · " + (o.str("message") ?: "错误码 $code"), resource), received)
                                o.str("data")?.let { encoded ->
                                    val audio = aligner.push(Base64.decode(encoded, Base64.DEFAULT))
                                    if (audio.isNotEmpty()) { received = true; onAudio(audio) }
                                }
                            }
                        }
                    }
                }
            }
            if (!received) throw Failure("火山引擎没有返回语音（$resource）。", received = false)
        }
    }

    // MARK: Cloning

    data class CloneStatus(val speakerId: String, val status: Int, val demoUrl: String?) {
        val ready get() = status == 2 || status == 4
        val failed get() = status == 3
    }

    private fun parseStatus(raw: String, fallback: String): CloneStatus {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: throw AppException("火山引擎返回了无法识别的结果。")
        val list = o.optJSONArray("speaker_status")?.mapObjects { it } ?: emptyList()
        val preferred = list.firstOrNull { it.optInt("model_type") in listOf(4, 5) } ?: list.firstOrNull()
        return CloneStatus(o.str("speaker_id") ?: fallback, o.optInt("status", 0), preferred?.str("demo_audio"))
    }

    private suspend fun post(url: String, body: JSONObject, key: String): String {
        val request = authorize(Request.Builder().url(url), key, clone = true).post(Net.json(body)).build()
        return Net.run(request) { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = Net.errorText(raw, "HTTP ${response.code}")
                var text = "火山引擎声音复刻失败（HTTP ${response.code}）：$detail"
                if (detail.contains("45001109")) text += "。录音内容和朗读文本差异过大。"
                else if (response.code == 401 || response.code == 403) text += "。请确认 API Key 正确，且已开通「声音复刻 2.0」。"
                throw AppException(text)
            }
            raw
        }
    }

    /** Uploads one sample and starts training. Empty `speakerId` = post-paid voice with a generated custom ID. */
    suspend fun clone(wav: ByteArray, speakerId: String, key: String): Pair<CloneStatus, Boolean> {
        if (wav.size > 10_000_000) throw AppException("样本超过 10 MB，请换一段更短的录音。")
        val given = speakerId.trim()
        val custom = "feiyu" + UUID.randomUUID().toString().replace("-", "").take(12)
        val body = JSONObject()
            .put("speaker_id", given.ifEmpty { "custom_speaker_id" })
            .put("audio", JSONObject().put("data", Tts.base64(wav)).put("format", "wav"))
            .put("language", 0)
            .put("extra_params", JSONObject().put("demo_text", "你好呀，我是你的小伙伴，今天也要开开心心的哦！").put("enable_audio_denoise", false))
        if (given.isEmpty()) body.put("custom_speaker_id", custom)
        val raw = post(CLONE, body, key)
        return parseStatus(raw, given.ifEmpty { custom }) to given.isEmpty()
    }

    suspend fun status(speakerId: String, key: String): CloneStatus =
        parseStatus(post(STATUS, JSONObject().put("speaker_id", speakerId), key), speakerId)

    suspend fun waitUntilReady(speakerId: String, key: String, progress: suspend (Int) -> Unit): CloneStatus {
        val started = System.currentTimeMillis()
        while (true) {
            val current = status(speakerId, key)
            if (current.ready) return current
            if (current.failed) throw AppException("火山引擎训练失败，请换一段更清晰、噪音更少的录音再试。")
            val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt()
            if (elapsed > 180) throw AppException("训练时间过长，稍后可在列表里点「刷新状态」。")
            progress(elapsed)
            kotlinx.coroutines.delay(3_000)
        }
    }
}

// MARK: - Breeze Blue

object Breeze {
    private const val BASE = "https://api.breeze.blue/v1"
    var defaultVoiceStore: (() -> String)? = null
    var saveDefaultVoice: ((String, String) -> Unit)? = null

    private fun request(path: String, key: String, query: Map<String, String> = emptyMap()): Request.Builder {
        val url = (BASE + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return Request.Builder().url(url).header("xi-api-key", key)
    }

    private fun fail(code: Int, raw: String): AppException {
        val detail = Net.errorText(raw, "HTTP $code")
        var text = "Breeze Blue 请求失败（HTTP $code）：$detail"
        when {
            code == 401 -> text += "。请检查 API Key。"
            code == 402 -> text += "。账户额度不足。"
            code == 403 && detail.lowercase().contains("slot") -> text += "。已保存的音色数量到上限，请先删除不用的音色。"
            code == 429 -> text += "。请求太频繁或并发已满，请稍后再试。"
        }
        return AppException(text)
    }

    private suspend fun send(request: Request): ByteArray = Net.run(request) { response ->
        val bytes = response.body?.bytes() ?: ByteArray(0)
        if (!response.isSuccessful) throw fail(response.code, String(bytes))
        bytes
    }

    private suspend fun sendJson(request: Request): JSONObject =
        runCatching { JSONObject(String(send(request))) }.getOrElse { if (it is AppException) throw it else throw AppException("Breeze 返回了无法识别的结果。") }

    suspend fun stream(text: String, voice: String, clone: Boolean, emotion: String?, key: String, onAudio: suspend (ByteArray) -> Unit) {
        val body = JSONObject().put("text", text).put("language_code", "zh")
        if (clone) {
            body.put("voice_settings", JSONObject().put("use_speaker_boost", true))
            Tts.mood(emotion)?.let { body.put("instructions", "保持原本的音色，语气带一点$it。") }
        } else body.put("instructions", Tts.speechStyle(emotion))
        val request = request("/text-to-speech/$voice/stream", key, mapOf("output_format" to "pcm_24000"))
            .post(Net.json(body)).build()
        Net.run(request) { response ->
            val type = response.header("Content-Type").orEmpty().lowercase()
            if (!response.isSuccessful || type.contains("json")) throw fail(response.code, response.body?.string().orEmpty())
            val input = response.body!!.byteStream()
            val buffer = ByteArray(9_600)
            val aligner = PcmAligner()
            var received = false
            while (true) {
                ensureActive()
                var filled = 0
                while (filled < buffer.size) {
                    val n = input.read(buffer, filled, buffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled > 0) {
                    val audio = aligner.push(buffer.copyOf(filled))
                    if (audio.isNotEmpty()) { received = true; onAudio(audio) }
                }
                if (filled < buffer.size) break
            }
            if (!received) throw AppException("Breeze Blue 没有返回语音。")
        }
    }

    private fun note(o: JSONObject): String {
        val gender = mapOf("female" to "女声", "male" to "男声", "neutral" to "中性")[o.optString("gender")]
        val tones = o.optJSONArray("tone")?.strings()?.take(2)?.joinToString("·").orEmpty()
        val parts = listOfNotNull(gender, tones.ifEmpty { null })
        return if (parts.isNotEmpty()) parts.joinToString(" · ") else o.optString("description").take(24)
    }

    suspend fun catalog(key: String): List<PresetVoice> {
        val o = sendJson(request("/voices", key, mapOf("voice_type" to "default", "language_code" to "zh", "sort" to "trend", "page_size" to "40")).get().build())
        return o.optJSONArray("voices")?.mapObjects { v ->
            v.str("voice_id")?.let { PresetVoice(it, v.optString("name", it), note(v), v.str("preview_url")) }
        } ?: emptyList()
    }

    suspend fun personal(key: String): List<CloudVoice> {
        val o = sendJson(request("/voices", key, mapOf("voice_type" to "personal", "page_size" to "100")).get().build())
        return o.optJSONArray("voices")?.mapObjects { v ->
            v.str("voice_id")?.let { CloudVoice(it, VoiceProvider.BREEZE, v.optString("name", it).take(40), v.str("preview_url")) }
        } ?: emptyList()
    }

    suspend fun defaultVoice(key: String): String {
        defaultVoiceStore?.invoke()?.takeIf { it.isNotEmpty() }?.let { return it }
        val first = catalog(key).firstOrNull() ?: throw AppException("Breeze 没有可用的中文音色，请在设置里选择一个音色。")
        saveDefaultVoice?.invoke(first.id, first.name)
        return first.id
    }

    suspend fun createClonePreview(wav: ByteArray, name: String, key: String): String {
        if (wav.size > 5_000_000) throw AppException("Breeze 的样本需小于 5 MB，请换短一点的录音。")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("name", name.take(80))
            .addFormDataPart("reference_language_code", "zh")
            .addFormDataPart("remove_background_noise", "false")
            .addFormDataPart("files", "sample.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val o = sendJson(request("/voice-previews/clone", key).post(body).build())
        return o.str("generated_voice_id") ?: throw AppException("Breeze 没有返回克隆试听。")
    }

    suspend fun previewAudio(generatedId: String, key: String): ByteArray =
        send(request("/voice-previews/$generatedId/stream", key, mapOf("output_format" to "wav")).get().build())

    suspend fun savePreview(generatedId: String, name: String, key: String): CloudVoice {
        val o = sendJson(request("/voice-previews/$generatedId/save", key)
            .post(Net.json(JSONObject().put("voice_name", name.take(80)).put("language_code", "zh"))).build())
        val id = o.str("voice_id") ?: throw AppException("Breeze 保存音色失败。")
        return CloudVoice(id, VoiceProvider.BREEZE, name.take(40), o.str("preview_url"))
    }

    suspend fun delete(voiceId: String, key: String) {
        send(request("/voices/$voiceId", key).delete().build())
    }

    suspend fun downloadPreview(url: String, key: String): ByteArray =
        Tts.download(url, if (url.contains("breeze.blue")) mapOf("xi-api-key" to key) else emptyMap())
}
