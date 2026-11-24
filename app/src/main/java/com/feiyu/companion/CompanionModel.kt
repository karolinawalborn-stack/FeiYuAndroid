package com.feiyu.companion

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

class CompanionModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    val player = PcmPlayer()
    val listener = Listener(app)

    var settings by mutableStateOf(store.settings); private set
    var conversation by mutableStateOf(store.conversation); private set
    var presets by mutableStateOf(store.presets); private set
    var activePresetId by mutableStateOf(store.activePresetId); private set
    var samples by mutableStateOf(store.samples); private set
    var cloudVoices by mutableStateOf(store.cloudVoices); private set
    /** Bumped when an API key changes, so "is it configured" checks recompose. */
    var keysVersion by mutableIntStateOf(0); private set

    var pending by mutableStateOf(false); private set
    var activity by mutableStateOf(""); private set
    var preview by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null)
    var expression by mutableStateOf(""); private set
    var reaction by mutableStateOf<StageReaction?>(null); private set
    var voiceMode by mutableStateOf(false); private set
    /** Voice-only turn (touch reactions): spoken, no caption bubble in the room. */
    var quietTurn by mutableStateOf(false); private set
    var typing by mutableStateOf(false)

    val stickerStore = StickerStore(store.stickersDir, store.prefs)
    var stickers by mutableStateOf(stickerStore.load()); private set
    var album by mutableStateOf(store.album); private set
    var modelInfo by mutableStateOf(ModelLibrary.info(store.modelsDir)); private set
    /** Bumped after a model import so the stage reloads. */
    var stageVersion by mutableIntStateOf(0); private set
    var syncing by mutableStateOf(false); private set
    var dshSession by mutableStateOf(store.dshSession); private set

    private var task: Job? = null
    private var speech: SpeechPipeline? = null
    private var requestId: String? = null
    private var expressionJob: Job? = null
    private var lastTouchAt = 0L
    private var silentTurns = 0

    init {
        Volcano.uid = runCatching { Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull() ?: "feiyu-android"
        Breeze.defaultVoiceStore = { store.breezeDefaultVoice }
        Breeze.saveDefaultVoice = { id, name -> store.breezeDefaultVoice = id; store.rememberVoiceName(VoiceProvider.BREEZE, id, name) }
    }

    override fun onCleared() {
        listener.cancel()
        player.stop()
        super.onCleared()
    }

    // MARK: - Basics

    val persona: PersonaPreset get() = presets.firstOrNull { it.id == activePresetId } ?: presets.first()
    val name: String get() = persona.name

    fun key(name: String): String { keysVersion; return store.secret(name) }

    fun saveKey(name: String, value: String) {
        store.saveSecret(name, value)
        keysVersion++
    }

    val textReady: Boolean get() = key(settings.provider.keyName).isNotEmpty() && settings.model.isNotBlank()
    val voiceReady: Boolean get() = key(settings.voiceProvider.keyName).isNotEmpty()

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
        store.settings = settings
    }

    private fun saveConversation(value: Conversation) {
        conversation = value
        store.conversation = value
    }

    // MARK: - Voices

    fun isClone(provider: VoiceProvider, id: String): Boolean =
        (provider == VoiceProvider.VOLCANO && (id.startsWith("S_") || id.startsWith("feiyu"))) ||
            cloudVoices.any { it.provider == provider && it.id == id }

    fun selectedSample(): VoiceSample? = samples.firstOrNull { it.id == settings.mimoSampleId } ?: samples.firstOrNull()

    fun voiceTarget(provider: VoiceProvider = settings.voiceProvider, overrideId: String? = null): VoiceTarget = when (provider) {
        VoiceProvider.MIMO -> {
            if (overrideId == null && settings.mimoClone) {
                val sample = selectedSample() ?: throw AppException("克隆声音样本已丢失，请在设置中重新选择或关闭克隆音色。")
                VoiceTarget(provider, "", true, store.sampleFile(sample))
            } else VoiceTarget(provider, overrideId ?: settings.mimoVoice, false)
        }
        else -> {
            val id = overrideId ?: settings.cloudVoice(provider)
            VoiceTarget(provider, id, isClone(provider, id))
        }
    }

    fun voiceLabel(): String {
        val provider = settings.voiceProvider
        if (provider == VoiceProvider.MIMO) {
            return if (settings.mimoClone) selectedSample()?.name ?: "克隆音色" else settings.mimoVoice
        }
        val id = settings.cloudVoice(provider)
        if (id.isEmpty()) return if (provider == VoiceProvider.BREEZE) "热门音色" else "默认音色"
        return cloudVoices.firstOrNull { it.provider == provider && it.id == id }?.name
            ?: Tts.volcanoPresets.firstOrNull { it.id == id }?.name
            ?: store.voiceName(provider, id) ?: id
    }

    fun selectCloudVoice(provider: VoiceProvider, id: String, name: String?) {
        name?.let { store.rememberVoiceName(provider, id, it) }
        updateSettings { if (provider == VoiceProvider.VOLCANO) it.copy(volcanoVoice = id) else it.copy(breezeVoice = id) }
    }

    fun upsertCloudVoice(voice: CloudVoice) {
        val list = cloudVoices.toMutableList()
        val index = list.indexOfFirst { it.id == voice.id && it.provider == voice.provider }
        if (index >= 0) list[index] = voice else list.add(voice)
        cloudVoices = list
        store.cloudVoices = list
        store.rememberVoiceName(voice.provider, voice.id, voice.name)
    }

    fun removeCloudVoice(voice: CloudVoice) {
        cloudVoices = cloudVoices.filterNot { it.id == voice.id && it.provider == voice.provider }
        store.cloudVoices = cloudVoices
        if (settings.cloudVoice(voice.provider) == voice.id) {
            updateSettings { if (voice.provider == VoiceProvider.VOLCANO) it.copy(volcanoVoice = Tts.VOLCANO_DEFAULT) else it.copy(breezeVoice = "") }
        }
    }

    suspend fun addSample(uri: Uri, name: String): VoiceSample {
        val source = copyToCache(uri)
        try {
            val (wav, seconds) = withContext(Dispatchers.Default) { AudioDecoder.reference(source, 30.0) }
            val sample = VoiceSample(name = name.trim().take(40).ifEmpty { "我的音色" }, seconds = seconds)
            withContext(Dispatchers.IO) { store.sampleFile(sample).writeBytes(wav) }
            samples = samples + sample
            store.samples = samples
            updateSettings { it.copy(mimoSampleId = sample.id, mimoClone = true) }
            return sample
        } finally {
            source.delete()
        }
    }

    fun renameSample(sample: VoiceSample, name: String) {
        samples = samples.map { if (it.id == sample.id) it.copy(name = name.trim().take(40)) else it }
        store.samples = samples
    }

    fun deleteSample(sample: VoiceSample) {
        store.sampleFile(sample).delete()
        samples = samples.filterNot { it.id == sample.id }
        store.samples = samples
        if (settings.mimoSampleId == sample.id) updateSettings { it.copy(mimoSampleId = samples.firstOrNull()?.id, mimoClone = it.mimoClone && samples.isNotEmpty()) }
    }

    fun selectSample(sample: VoiceSample) = updateSettings { it.copy(mimoSampleId = sample.id, mimoClone = true) }

    // MARK: - Playback helpers for settings

    suspend fun previewLine(target: VoiceTarget, line: String? = null) {
        val key = key(target.provider.keyName)
        val audio = Tts.synthesize(line ?: "你好呀，我是$name。今天过得怎么样？", target, "happy", key)
        player.playWav(audio)
    }

    suspend fun playRemote(url: String, provider: VoiceProvider) {
        val bytes = if (provider == VoiceProvider.BREEZE) Breeze.downloadPreview(url, key(provider.keyName)) else Tts.download(url)
        val wav = withContext(Dispatchers.Default) { Tts.playable(bytes, store.cacheDir) }
        player.playWav(wav)
    }

    fun playFile(file: File) {
        runCatching { player.playWav(file.readBytes()) }.onFailure { error = it.message }
    }

    fun stopSpeaking() {
        speech?.cancel()
        speech = null
        player.stop()
    }

    // MARK: - Files

    fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    suspend fun readBytes(uri: Uri, limit: Long = 32_000_000): ByteArray = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) throw AppException("文件太大了。")
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: throw AppException("无法读取这个文件。")
    }

    suspend fun copyToCache(uri: Uri): File {
        val bytes = readBytes(uri, 60_000_000)
        val ext = displayName(uri).substringAfterLast('.', "audio")
        return withContext(Dispatchers.IO) { File(store.cacheDir, "pick-" + newId() + "." + ext).apply { writeBytes(bytes) } }
    }

    // MARK: - Persona presets

    fun activatePreset(id: String) {
        if (pending) { error = "请等她说完这句，再切换人设。"; return }
        activePresetId = id
        store.activePresetId = id
    }

    fun savePreset(preset: PersonaPreset, activate: Boolean) {
        val checked = preset.copy(
            title = preset.title.trim().ifEmpty { preset.name.trim().ifEmpty { "我的人设" } }.take(30),
            name = preset.name.trim().ifEmpty { DefaultPersona.NAME }.take(40),
            profile = preset.profile.trim(),
            greeting = preset.greeting.trim().ifEmpty { DefaultPersona.GREETING }.take(2_000),
        )
        if (checked.profile.isEmpty()) throw AppException("请填写她的性格。")
        if (checked.profile.length > PersonaPreset.PROFILE_LIMIT) throw AppException("性格最多 ${PersonaPreset.PROFILE_LIMIT} 字。")
        val list = presets.toMutableList()
        val index = list.indexOfFirst { it.id == checked.id }
        if (index >= 0) list[index] = checked else list.add(checked)
        presets = list
        store.presets = list
        if (activate) activatePreset(checked.id)
    }

    fun deletePreset(id: String) {
        if (presets.size <= 1) throw AppException("至少要保留一套人设。")
        presets = presets.filterNot { it.id == id }
        store.presets = presets
        if (activePresetId == id) activatePreset(presets.first().id)
    }

    // MARK: - Memories

    fun deleteMemory(id: String) = saveConversation(conversation.copy(memories = conversation.memories.filterNot { it.id == id }))

    fun addMemory(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        if (conversation.memories.any { it.text == value }) throw AppException("已经记住这条内容了。")
        saveConversation(conversation.copy(memories = conversation.memories + MemoryItem(text = value.take(2_000), manual = true)))
    }

    fun clearChat() {
        cancel()
        conversation.messages.mapNotNull { it.audioFile }.forEach { store.audioFile(it).delete() }
        val kept = album.map { it.path }.toSet()
        conversation.messages.flatMap { it.images }.filter { it.path !in kept }.forEach { image -> store.mediaFile(image.path)?.delete() }
        saveConversation(conversation.copy(messages = emptyList(), summary = null))
    }

    // MARK: - Emotion

    private fun expressionName(mood: String?): String = when (mood) {
        "happy" -> "爱心眼"
        "shy" -> "脸红"
        "sad" -> "流泪"
        "excited" -> "＞＜"
        "angry" -> "鼓脸"
        "surprised" -> "惊讶"
        "smug" -> "屑屑眼"
        "confused" -> "圈圈眼"
        else -> ""
    }

    fun showEmotion(mood: String?) {
        val value = expressionName(mood)
        if (value.isEmpty()) return
        expressionJob?.cancel()
        expression = value
        expressionJob = viewModelScope.launch {
            delay(6_000)
            while (player.isPlaying) delay(400)
            if (expression == value) expression = ""
        }
    }

    fun wave() { reaction = StageReaction("wave") }

    // MARK: - Sending

    fun cancel() {
        requestId = null
        task?.cancel()
        task = null
        speech?.cancel()
        speech = null
        pending = false
        activity = ""
        preview = ""
    }

    fun send(
        value: String,
        readAloud: Boolean = false,
        record: Boolean = true,
        quiet: Boolean = false,
        images: List<MessageImage> = emptyList(),
        stickerId: String? = null,
        generateImage: Boolean = false,
    ): Boolean {
        val text = value.trim()
        if (pending || (text.isEmpty() && images.isEmpty() && stickerId == null)) return false
        val options = settings
        if (images.isNotEmpty() && (options.provider != TextProvider.RELAY || options.visionModel.isBlank())) {
            error = "发图前，请在设置中选择自定义中转站并填写视觉模型 ID。"
            return false
        }
        quietTurn = quiet
        val character = persona
        error = null
        player.stop()
        expression = ""
        val input = ChatMessage(role = "user", text = text, images = images, stickerId = stickerId)
        var current = conversation
        if (record) {
            current = current.copy(messages = current.messages + input)
            saveConversation(current)
        }
        // Touch / greeting prompts guide the model but never appear in the chat.
        val request = if (record) current else current.copy(messages = current.messages + input)
        val id = newId()
        requestId = id
        pending = true
        activity = "正在思考回复"
        // DSH mode: what you type goes to DeepSeek Harness on your computer. Touches, greetings
        // and pictures keep using her normal persona.
        if (options.dshEnabled && record && images.isEmpty() && stickerId == null && !generateImage && text.isNotEmpty()) {
            runDsh(text, id, readAloud, options)
            return true
        }
        val textKey = key(options.provider.keyName)
        val voiceKey = key(options.voiceProvider.keyName)
        // Sticker frequency: offer the pack only when enough replies have passed since her last sticker.
        val every = options.stickerEvery
        val sinceSticker = store.stickerTurns
        val stickerChoices = if (every > 0 && sinceSticker + 1 >= every && stickers.isNotEmpty()) StickerStore.promptList(stickers) else emptyList()
        val albumNow = album.takeLast(30).filter { store.mediaFile(it.path)?.exists() == true }
        val pictures = if (images.isEmpty()) emptyMap() else
            mapOf(input.id to images.mapNotNull { image -> store.mediaFile(image.path)?.takeIf { it.exists() }?.readBytes() })
        if (images.isNotEmpty() && pictures[input.id].orEmpty().size != images.size) {
            pending = false; requestId = null; activity = ""
            error = "部分图片无法读取。"
            return false
        }
        val pipeline: SpeechPipeline? = if (readAloud || (options.autoVoice && voiceKey.isNotEmpty())) {
            runCatching { SpeechPipeline(viewModelScope, player, voiceTarget(), voiceKey, store.audioDir) }
                .onFailure { error = it.message }.getOrNull()
        } else null
        speech?.cancel()
        speech = pipeline
        pipeline?.onStart = { if (requestId == id) activity = "正在说话" }
        task = viewModelScope.launch {
            try {
                val reply = ChatApi.reply(options, textKey, character, request,
                    onPreview = { partial ->
                        withContext(Dispatchers.Main) {
                            if (requestId == id) { preview = partial; pipeline?.update(partial) }
                        }
                    },
                    onEmotion = { mood ->
                        withContext(Dispatchers.Main) {
                            if (requestId == id) { pipeline?.setEmotion(mood); showEmotion(mood) }
                        }
                    },
                    stickers = stickerChoices,
                    albumIds = albumNow.map { it.id + (it.altText?.let { alt -> ": $alt" } ?: "") },
                    pictures = pictures,
                    allowImageGeneration = generateImage)
                if (requestId != id) { pipeline?.cancel(); return@launch }
                preview = ""
                showEmotion(reply.emotion)
                val extras = reply.bubbles.take(2).map(ReplyText::cleanNarration).filter { it.isNotEmpty() }
                val lines = (listOf(reply.text) + extras).filter { it.isNotEmpty() }
                var sticker = if (stickerChoices.isEmpty()) null else StickerStore.resolve(stickers, reply.sticker)
                // Models often skip optional fields: on a sticker turn, pick one that matches her emotion.
                if (sticker == null && stickerChoices.isNotEmpty() && reply.emotion != "sad") sticker = StickerStore.fallback(stickers, reply.emotion)
                if (stickerChoices.isNotEmpty() || every > 0) store.stickerTurns = if (sticker == null) minOf(99, sinceSticker + 1) else 0
                val photo = albumNow.firstOrNull { it.id == reply.imageId }
                if (lines.isEmpty() && sticker == null && photo == null && !generateImage) throw AppException("角色没有返回可显示的内容。")
                val bubbles = lines.map { ChatMessage(role = "assistant", text = it, emotion = reply.emotion) }.toMutableList()
                if (sticker != null || photo != null) {
                    bubbles.add(ChatMessage(role = "assistant", text = "", emotion = reply.emotion,
                        images = listOfNotNull(photo), stickerId = sticker?.id))
                }
                var updated = conversation.copy(messages = conversation.messages + bubbles)
                if (options.autoMemory && record) {
                    updated = updated.withAutomaticMemories(reply.memories)
                    reply.summary?.takeIf { it.isNotBlank() }?.let { updated = updated.copy(summary = it.take(400)) }
                }
                if (record) updated = updated.copy(affinity = minOf(100, updated.affinity + 1))
                saveConversation(updated)
                if (pipeline != null) {
                    if (lines.isEmpty()) pipeline.cancel()
                    else {
                        if (!pipeline.started) activity = "语音生成中…"
                        pipeline.setEmotion(reply.emotion ?: "neutral")
                        pipeline.finish(pipeline.lastText.ifEmpty { reply.text }, extras)
                    }
                }
                if (generateImage) {
                    activity = "正在生成图片"
                    val prompt = reply.imagePrompt?.trim().orEmpty().ifEmpty { text }
                    val bytes = ChatApi.generateImage(options, key("relay"), prompt)
                    if (requestId != id) return@launch
                    val image = withContext(Dispatchers.IO) { Media.saveImage(bytes, store.mediaDir) }
                    saveConversation(conversation.copy(messages = conversation.messages +
                        ChatMessage(role = "assistant", text = "", images = listOf(image))))
                }
                if (pipeline != null && lines.isNotEmpty()) {
                    val saved = pipeline.await()
                    if (requestId != id) return@launch
                    if (saved != null) {
                        val firstId = bubbles.first().id
                        val seconds = (saved.length() - 44) / 48_000.0
                        saveConversation(conversation.copy(messages = conversation.messages.map {
                            if (it.id == firstId) it.copy(audioFile = saved.name, audioDuration = seconds) else it
                        }))
                    } else pipeline.error?.let { if (!pipeline.started) throw it }
                    if (pipeline.failedPieces > 0 && pipeline.started) {
                        error = "有 ${pipeline.failedPieces} 段语音没生成出来（${pipeline.error?.message ?: "未知原因"}），文字已完整显示。"
                    }
                }
            } catch (cancel: CancellationException) {
                pipeline?.cancel()
                throw cancel
            } catch (problem: Throwable) {
                pipeline?.cancel()
                if (requestId == id) {
                    val message = problem.message ?: "出错了，请重试。"
                    error = if (activity.contains("语音")) "语音生成失败：$message" else message
                }
            } finally {
                if (requestId == id) {
                    requestId = null; pending = false; activity = ""; preview = ""; task = null
                    if (speech === pipeline) speech = null
                    if (voiceMode) resumeListeningAfterSpeech()
                }
            }
        }
        return true
    }

    // MARK: - DSH (DeepSeek Harness on your computer)

    private fun runDsh(text: String, id: String, readAloud: Boolean, options: AppSettings) {
        activity = "DSH 已接到任务…"
        val token = key(DshBridge.TOKEN_KEY)
        val session = dshSession
        task = viewModelScope.launch {
            var pipeline: SpeechPipeline? = null
            try {
                val result = DshBridge.chat(text, options.dshUrl, token, session,
                    onStatus = { line -> withContext(Dispatchers.Main) { if (requestId == id) activity = line } },
                    onText = { partial -> withContext(Dispatchers.Main) { if (requestId == id) preview = partial } })
                if (requestId != id) return@launch
                result.sessionId?.let { setDshSession(it) }
                preview = ""
                val message = ChatMessage(role = "assistant", text = result.text, emotion = "dsh")
                saveConversation(conversation.copy(messages = conversation.messages + message))
                val voiceKey = key(options.voiceProvider.keyName)
                if (!(readAloud || (options.autoVoice && voiceKey.isNotEmpty()))) return@launch
                val spoken = DshBridge.speakable(result.text)
                if (spoken.isEmpty()) return@launch
                pipeline = SpeechPipeline(viewModelScope, player, voiceTarget(), voiceKey, store.audioDir).also { speech = it }
                activity = "语音生成中…"
                pipeline.onStart = { if (requestId == id) activity = "正在说话" }
                pipeline.finish(spoken, emptyList())
                val saved = pipeline.await()
                if (requestId != id || saved == null) return@launch
                saveConversation(conversation.copy(messages = conversation.messages.map {
                    if (it.id == message.id) it.copy(audioFile = saved.name, audioDuration = (saved.length() - 44) / 48_000.0) else it
                }))
            } catch (cancel: CancellationException) {
                pipeline?.cancel()
                throw cancel
            } catch (problem: Throwable) {
                pipeline?.cancel()
                if (requestId == id) error = problem.message ?: "DSH 出错了。"
            } finally {
                if (requestId == id) {
                    requestId = null; pending = false; activity = ""; preview = ""; task = null
                    if (speech === pipeline) speech = null
                    if (voiceMode) resumeListeningAfterSpeech()
                }
            }
        }
    }

    fun setDshSession(id: String?) {
        dshSession = id
        store.dshSession = id
    }

    // MARK: - Stickers

    val stickersReady: Boolean get() = stickerStore.builtinReady(stickers)

    fun sticker(id: String?): Sticker? = id?.let { value -> stickers.firstOrNull { it.id == value } }

    fun stickerFile(sticker: Sticker): File = stickerStore.file(sticker)

    fun installBuiltinStickers(made: List<Pair<StickerSpec, ByteArray>>) {
        runCatching { stickers = stickerStore.installBuiltin(stickers, made) }.onFailure { error = it.message }
    }

    suspend fun addStickers(uris: List<Uri>): Int {
        var added = 0
        for (uri in uris) {
            val bytes = runCatching { readBytes(uri) }.getOrNull() ?: continue
            runCatching { withContext(Dispatchers.IO) { stickerStore.addUser(stickers, bytes) } }
                .onSuccess { stickers = it; added++ }.onFailure { error = it.message }
        }
        return added
    }

    fun renameSticker(sticker: Sticker, title: String) {
        runCatching { stickers = stickerStore.rename(stickers, sticker, title) }.onFailure { error = it.message }
    }

    fun deleteSticker(sticker: Sticker) { stickers = stickerStore.delete(stickers, sticker) }

    // MARK: - Pictures and her album

    suspend fun importPhoto(uri: Uri): MessageImage = Media.saveFromUri(getApplication<Application>(), uri, store.mediaDir)

    fun mediaFile(image: MessageImage): File? = store.mediaFile(image.path)

    fun discardPhoto(image: MessageImage) {
        if (album.none { it.path == image.path }) store.mediaFile(image.path)?.let { Media.forget(it) }
    }

    suspend fun addAlbumPhotos(uris: List<Uri>) {
        val added = ArrayList<MessageImage>()
        for (uri in uris) runCatching { importPhoto(uri) }.onSuccess { added.add(it) }.onFailure { error = it.message }
        album = album + added
        store.album = album
    }

    fun removeAlbumPhoto(image: MessageImage) {
        album = album.filterNot { it.id == image.id }
        store.album = album
        // A photo already sent in the chat still belongs to the history.
        if (conversation.messages.none { message -> message.images.any { it.path == image.path } }) store.mediaFile(image.path)?.let { Media.forget(it) }
    }

    fun describeAlbumPhoto(image: MessageImage, description: String) {
        album = album.map { if (it.id == image.id) it.copy(altText = description.trim().take(80).ifEmpty { null }) else it }
        store.album = album
    }

    // MARK: - Imported Live2D model

    val stageModelFile: String? get() = if (settings.useImportedModel) modelInfo?.file else null

    suspend fun installModelFolder(tree: Uri) {
        modelInfo = ModelLibrary.installFolder(getApplication<Application>(), tree, store.modelsDir)
        updateSettings { it.copy(useImportedModel = true) }
        stageVersion++
    }

    suspend fun installModelZip(uri: Uri) {
        modelInfo = ModelLibrary.installZip(getApplication<Application>(), uri, displayName(uri), store.modelsDir)
        updateSettings { it.copy(useImportedModel = true) }
        stageVersion++
    }

    fun removeModel() {
        ModelLibrary.remove(store.modelsDir)
        modelInfo = null
        stageVersion++
    }

    fun useImportedModel(value: Boolean) {
        updateSettings { it.copy(useImportedModel = value) }
        stageVersion++
    }

    // MARK: - Cross-device sync

    fun syncTarget(): Sync.Target = when (store.syncMode) {
        "webdav" -> {
            if (store.webdavUrl.isBlank()) throw AppException("请先填写 WebDAV 地址。")
            Sync.WebDavTarget(store.webdavUrl, store.webdavUser, key("webdav"))
        }
        else -> {
            if (store.syncFolder.isBlank()) throw AppException("请先选择同步文件夹。")
            Sync.FolderTarget(getApplication<Application>(), Uri.parse(store.syncFolder))
        }
    }

    private fun loadArchive(): Sync.Archive = runCatching { Sync.Archive.from(store.syncFile.readBytes()) }.getOrDefault(Sync.Archive())

    suspend fun synchronize(password: String) {
        if (syncing || pending) throw AppException("请等当前操作完成后再同步。")
        if (password.length < 8) throw AppException("同步口令至少 8 个字符。")
        syncing = true
        try {
            val target = syncTarget()
            val device = store.syncDevice
            val seen = store.syncSeenKeys
            val snapshot = loadArchive()
            snapshot.capture(persona, conversation, device, seen)
            val received = Sync.exchange(target, snapshot, password, device)
            // Reconcile edits made while the other phones' files were downloading.
            snapshot.capture(persona, conversation, device, seen)
            snapshot.merge(received)
            val (syncedPersona, merged) = snapshot.materialize()
            withContext(Dispatchers.IO) { store.syncFile.writeBytes(snapshot.bytes()) }
            store.syncSeenKeys = snapshot.liveKeys()
            store.lastSyncAt = System.currentTimeMillis()
            saveConversation(merged.copy(affinity = conversation.affinity, summary = conversation.summary))
            syncedPersona?.let { adoptSyncedPersona(it) }
        } finally {
            syncing = false
        }
    }

    /** A persona edited on another phone: reuse a matching preset, otherwise add one, and use it. */
    private fun adoptSyncedPersona(incoming: PersonaPreset) {
        val active = persona
        if (active.profile.trim() == incoming.profile.trim() && active.name.trim() == incoming.name.trim() &&
            active.greeting.trim() == incoming.greeting.trim()) return
        val match = presets.firstOrNull { it.profile.trim() == incoming.profile.trim() && it.name.trim() == incoming.name.trim() }
        if (match != null) {
            if (match.greeting.trim() != incoming.greeting.trim()) savePreset(match.copy(greeting = incoming.greeting), activate = false)
            activatePreset(match.id)
            return
        }
        savePreset(incoming.copy(id = newId(), title = "同步 · " + incoming.name.take(20)), activate = true)
    }

    fun replay(message: ChatMessage) {
        if (pending || listener.listening) return
        val file = message.audioFile?.let { store.audioFile(it) }
        if (file == null || !file.exists()) { error = "这条语音在本机不可用。"; return }
        playFile(file)
        showEmotion(message.emotion)
    }

    fun touch(zone: String) {
        val now = System.currentTimeMillis()
        if (now - lastTouchAt < 1_250 || pending || player.isPlaying || listener.listening || !textReady) return
        lastTouchAt = now
        val prompt = when (zone) {
            "head" -> "我轻轻摸了摸你的头。请用 2 句角色对白回应：表达你当下的感受，再补一句有个性的回应或小抱怨；只写对白，不写动作旁白。"
            "cheek" -> "我戳了戳你的脸。请用 2–3 句俏皮又有个性的角色对白回应，可以反过来吐槽我，但只写对白，不写动作旁白。"
            else -> "我碰了碰你。请用 2 句自然回应，不要只说谢谢；可以表达你喜欢或不喜欢，并说明一点理由。"
        }
        send(prompt, readAloud = voiceReady, record = false, quiet = voiceReady)
    }

    fun greetIfNeeded() {
        if (!settings.proactive || pending || player.isPlaying || listener.listening) return
        val now = System.currentTimeMillis()
        val lastMessage = conversation.messages.lastOrNull()?.createdAt ?: 0L
        if (now - store.lastGreetingAt < 20 * 60_000L || now - lastMessage < 8 * 60_000L) return
        store.lastGreetingAt = now
        wave()
        if (!textReady) {
            saveConversation(conversation.copy(messages = conversation.messages + ChatMessage(role = "assistant", text = localGreeting(), emotion = "happy")))
            showEmotion("happy")
            return
        }
        val gap = now - lastMessage
        val away = when {
            lastMessage == 0L -> "这是你们第一次见面，先热情地自我介绍一下"
            gap < 3_600_000L -> "距离上次聊天大约 ${gap / 60_000} 分钟"
            gap < 86_400_000L -> "距离上次聊天大约 ${gap / 3_600_000} 小时"
            else -> "已经 ${gap / 86_400_000} 天没聊了，可以表达想念或小小埋怨一下"
        }
        send("（系统提示，不是用户说的话）用户刚刚打开 App 回到你身边。现在是 ${ChatApi.clock()}，$away。请你主动开口打招呼：像真人朋友一样，结合现在的时间、上次聊到的话题或你记得的关于用户的事，说 2–3 句有情绪、有个性的话，可以顺便说说你刚才在做什么，最后自然地抛出一个具体的小话题。不要说“有什么可以帮你”。",
            record = false)
    }

    private fun localGreeting(): String {
        val options = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..10 -> listOf("早呀！你终于醒啦，我都在这儿游了好几圈了～今天打算干点什么？", "早安！太阳都晒到鱼尾巴了，快来陪我说说话嘛。")
            in 11..13 -> listOf("中午好～吃饭了没？别又随便对付一口哦。", "嘿！午休时间到，来跟本鱼聊会儿天吧！")
            in 14..17 -> listOf("下午好呀，是不是有点犯困了？我给你打打气！", "你回来啦！我刚才正想着你什么时候出现呢～")
            in 18..22 -> listOf("晚上好！今天辛苦啦，过来让我听听你的一天～", "你来啦！我等你好久了，今天有没有发生什么好玩的事？")
            else -> listOf("这么晚还没睡呀？那……我就再陪你一会儿吧。", "嘘——夜深啦，小声点聊，好不好？")
        }
        return options.random()
    }

    // MARK: - Voice conversation (listen → answer → listen)

    fun startVoiceMode() {
        if (!voiceReady) { error = "语音对话需要先在设置里保存${settings.voiceProvider.title} API Key，她才能开口说话。"; return }
        if (!textReady) { error = "请先在设置里配置文字模型。"; return }
        error = null
        typing = false
        silentTurns = 0
        voiceMode = true
        listen()
    }

    fun stopVoiceMode() {
        voiceMode = false
        listener.cancel()
    }

    /** The big mic button: stop and send / interrupt her / start listening. */
    fun micTapped() {
        when {
            listener.listening -> listener.stop()
            player.isPlaying || pending -> { cancel(); stopSpeaking(); listen() }
            else -> listen()
        }
    }

    fun listen(sendOnce: Boolean = false) {
        if (pending) return
        if (player.isPlaying) stopSpeaking()
        listener.start(onFinal = { text ->
            if (text.isBlank()) {
                silentTurns++
                if (voiceMode && silentTurns < 3) viewModelScope.launch { delay(350); if (voiceMode && !pending) listen() }
                else if (voiceMode) { voiceMode = false; error = "好一会儿没听到声音，语音模式先暂停啦。" }
            } else {
                silentTurns = 0
                send(text, readAloud = true)
            }
        }, onError = { message ->
            error = message
            if (voiceMode && !sendOnce) voiceMode = false
        })
    }

    private fun resumeListeningAfterSpeech() {
        viewModelScope.launch {
            player.awaitIdle()
            delay(300)
            if (voiceMode && !pending && !listener.listening) listen()
        }
    }
}
