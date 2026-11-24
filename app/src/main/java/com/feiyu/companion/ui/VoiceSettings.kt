package com.feiyu.companion.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feiyu.companion.AudioDecoder
import com.feiyu.companion.Breeze
import com.feiyu.companion.CloudVoice
import com.feiyu.companion.CompanionModel
import com.feiyu.companion.PresetVoice
import com.feiyu.companion.Tts
import com.feiyu.companion.VoiceProvider
import com.feiyu.companion.VoiceSample
import com.feiyu.companion.VoiceTarget
import com.feiyu.companion.Volcano
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared "▶ 试听" state: one voice plays at a time; the button shows loading / stop. */
class Previewer(private val scope: CoroutineScope, private val model: CompanionModel) {
    var busyId by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var status by mutableStateOf<InlineStatus?>(null)
    private var job: Job? = null

    fun play(id: String, label: String, action: suspend () -> Unit) {
        if (busyId == id) { job?.cancel(); model.stopSpeaking(); busyId = null; loading = false; return }
        job?.cancel()
        model.stopSpeaking()
        busyId = id
        loading = true
        status = InlineStatus.working("语音生成中…")
        job = scope.launch {
            runCatching { action() }
                .onSuccess { status = InlineStatus.ok("正在试听「$label」") }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) { status = InlineStatus.failed(it.message ?: "试听失败"); busyId = null } }
            loading = false
        }
    }
}

@Composable
fun rememberPreviewer(model: CompanionModel): Previewer {
    val scope = rememberCoroutineScope()
    val previewer = remember { Previewer(scope, model) }
    val playing by model.player.playing.collectAsState()
    LaunchedEffect(playing, previewer.loading) { if (!playing && !previewer.loading) previewer.busyId = null }
    return previewer
}

@Composable
fun PlayButton(previewer: Previewer, id: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    IconButton(onClick = onClick) {
        when {
            previewer.busyId == id && previewer.loading -> CircularProgressIndicator(Modifier.size(20.dp), color = p.accent, strokeWidth = 2.dp)
            previewer.busyId == id -> Icon(Icons.Filled.Stop, "停止", tint = p.accent)
            else -> Icon(Icons.Filled.PlayCircle, "试听", tint = p.accent, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun Header(title: String, detail: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(detail, color = p.secondary, fontSize = 12.sp)
    }
}

private fun menuItem(title: String, action: () -> Unit): Pair<String, () -> Unit> = title to action

@Composable
private fun MoreMenu(items: List<Pair<String, () -> Unit>>) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "更多操作", tint = p.secondary) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (title, action) -> DropdownMenuItem(text = { Text(title) }, onClick = { open = false; action() }) }
        }
    }
}

// MARK: - MiMo

@Composable
fun MimoVoiceCard(model: CompanionModel) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val previewer = rememberPreviewer(model)
    val settings = model.settings
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var converting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<VoiceSample?>(null) }
    var deleting by remember { mutableStateOf<VoiceSample?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingName = model.displayName(uri).substringBeforeLast('.').take(40)
            pendingUri = uri
        }
    }
    val selected = model.selectedSample()
    SectionCard("音色与克隆", Icons.Filled.RecordVoiceOver, "MiMo · 内置音色，或用一段录音克隆（可存多个）") {
        Header("内置音色", "")
        Tts.mimoVoices.forEach { voice ->
            SelectRow(!settings.mimoClone && settings.mimoVoice == voice, voice, null,
                onSelect = { model.updateSettings { it.copy(mimoVoice = voice, mimoClone = false) } }) {
                PlayButton(previewer, "mimo:$voice") { previewer.play("mimo:$voice", voice) { model.previewLine(VoiceTarget(VoiceProvider.MIMO, voice, false)) } }
            }
        }
        HorizontalDivider(color = p.stroke)
        SwitchRow("使用克隆音色", if (model.samples.isEmpty()) "先添加一个声音样本" else "关闭后使用内置音色", settings.mimoClone,
            enabled = model.samples.isNotEmpty()) { v -> model.updateSettings { it.copy(mimoClone = v) } }
        model.samples.forEach { sample ->
            SelectRow(settings.mimoClone && selected?.id == sample.id, sample.name, "%.1f 秒".format(sample.seconds), onSelect = { model.selectSample(sample) }) {
                PlayButton(previewer, sample.id) {
                    previewer.play(sample.id, sample.name) { model.previewLine(VoiceTarget(VoiceProvider.MIMO, "", true, model.store.sampleFile(sample))) }
                }
                MoreMenu(listOf(
                    menuItem("播放原始录音") { model.playFile(model.store.sampleFile(sample)) },
                    menuItem("重命名") { renaming = sample },
                    menuItem("删除") { deleting = sample },
                ))
            }
        }
        ActionButton(if (converting) "正在处理样本…" else "添加克隆音色", Icons.Filled.Add, prominent = model.samples.isEmpty(), busy = converting,
            modifier = Modifier.fillMaxWidth()) { picker.launch(arrayOf("audio/*")) }
        previewer.status?.let { StatusView(it) }
        Text("支持 M4A、MP3、WAV、AAC 等。会自动裁掉首尾静音，只发送最清晰的约 12 秒，速度更快。建议 5–15 秒清晰人声。",
            color = p.secondary, fontSize = 12.sp)
    }
    pendingUri?.let { uri ->
        TextDialog("命名音色", "给它起个名字", pendingName, message = "保存后会自动选中这个音色。", onDismiss = { pendingUri = null }) { name ->
            pendingUri = null
            scope.launch {
                converting = true
                previewer.status = InlineStatus.working("正在处理样本…")
                runCatching { model.addSample(uri, name) }
                    .onSuccess { previewer.status = InlineStatus.ok("已保存并选中「${it.name}」。") }
                    .onFailure { previewer.status = InlineStatus.failed(it.message ?: "样本处理失败") }
                converting = false
            }
        }
    }
    renaming?.let { sample ->
        TextDialog("重命名音色", "音色名称", sample.name, onDismiss = { renaming = null }) { name -> model.renameSample(sample, name); renaming = null }
    }
    deleting?.let { sample ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除「${sample.name}」？") },
            confirmButton = { TextButton(onClick = { model.deleteSample(sample); deleting = null }) { Text("删除", color = p.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

// MARK: - 火山引擎 / Breeze Blue

@Composable
fun CloudVoiceCard(model: CompanionModel, provider: VoiceProvider) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val previewer = rememberPreviewer(model)
    val key = model.key(provider.keyName)
    val clones = model.cloudVoices.filter { it.provider == provider }.sortedBy { it.created }
    val selected = model.settings.cloudVoice(provider)
    var catalog by remember(provider) { mutableStateOf<List<PresetVoice>>(emptyList()) }
    var loadingCatalog by remember { mutableStateOf(false) }
    var showAll by remember(provider) { mutableStateOf(false) }
    var customId by remember { mutableStateOf("") }
    var cloning by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CloudVoice?>(null) }
    var deleting by remember { mutableStateOf<CloudVoice?>(null) }
    val presets = if (provider == VoiceProvider.VOLCANO) Tts.volcanoPresets else catalog

    fun needKey(): Boolean {
        if (key.isBlank()) { previewer.status = InlineStatus.failed("请先在上方填写 ${provider.title} API Key 并点「保存并测试」。"); return false }
        return true
    }
    fun choose(id: String, name: String) {
        model.selectCloudVoice(provider, id, name)
        previewer.status = InlineStatus.ok("已选用「$name」。")
    }
    fun previewClone(voice: CloudVoice, speak: Boolean) {
        if (!needKey()) return
        previewer.play(voice.id + if (speak) ":say" else "", voice.name) {
            if (!speak && provider == VoiceProvider.VOLCANO) {
                // Volcano's own demo is free; a real synthesis with a post-paid voice is billed.
                val demo = runCatching { Volcano.status(voice.id, key).demoUrl }.getOrNull()
                if (demo != null) { model.playRemote(demo, provider); return@play }
            }
            if (!speak && provider == VoiceProvider.BREEZE && voice.demoUrl != null) {
                runCatching { model.playRemote(voice.demoUrl, provider) }.onSuccess { return@play }
            }
            model.previewLine(VoiceTarget(provider, voice.id, true))
        }
    }

    SectionCard("音色与克隆", Icons.Filled.RecordVoiceOver, "${provider.title} · 可保存多个克隆音色，点选使用") {
        Header("我的克隆音色", if (clones.isEmpty()) "还没有" else "${clones.size} 个")
        if (clones.isEmpty()) {
            Text(if (provider == VoiceProvider.VOLCANO) "上传一段 5–25 秒的清晰人声，火山引擎会训练出专属音色；也可以直接填入控制台里已经复刻好的音色 ID。"
                else "上传一段 3–20 秒的清晰人声，Breeze 会先生成试听，满意再保存。", color = p.secondary, fontSize = 12.sp)
        }
        clones.forEach { voice ->
            SelectRow(voice.id == selected, voice.name, voice.id + if (voice.postpaid) " · 后付费" else "", onSelect = { choose(voice.id, voice.name) }) {
                PlayButton(previewer, voice.id) { previewClone(voice, speak = false) }
                val actions = mutableListOf(menuItem("用她的声音说一句") { previewClone(voice, speak = true) })
                if (provider == VoiceProvider.VOLCANO) actions += menuItem("刷新训练状态") {
                    if (needKey()) scope.launch {
                        previewer.status = InlineStatus.working("正在查询训练状态…")
                        runCatching { Volcano.status(voice.id, key) }.onSuccess { result ->
                            result.demoUrl?.let { model.upsertCloudVoice(voice.copy(demoUrl = it)) }
                            previewer.status = when (result.status) {
                                2, 4 -> InlineStatus.ok("「${voice.name}」已就绪，可以使用。")
                                1 -> InlineStatus.ok("还在训练中，请稍后再看。")
                                3 -> InlineStatus.failed("训练失败，请换一段更清晰的录音重新复刻。")
                                else -> InlineStatus.failed("没有找到这个音色。")
                            }
                        }.onFailure { previewer.status = InlineStatus.failed(it.message ?: "查询失败") }
                    }
                }
                actions += menuItem("重命名") { renaming = voice }
                actions += menuItem("删除") { deleting = voice }
                MoreMenu(actions)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("添加克隆音色", Icons.Filled.Add, prominent = clones.isEmpty(), modifier = Modifier.weight(1f)) {
                if (needKey()) { model.stopSpeaking(); cloning = true }
            }
            if (provider == VoiceProvider.VOLCANO) {
                ActionButton("填入已有 ID", Icons.Filled.Tag, prominent = false, modifier = Modifier.weight(1f)) { if (needKey()) importing = true }
            } else {
                ActionButton("同步账号音色", Icons.Filled.Sync, prominent = false, modifier = Modifier.weight(1f)) {
                    if (needKey()) scope.launch {
                        previewer.status = InlineStatus.working("正在读取账号里的音色…")
                        runCatching { Breeze.personal(key) }.onSuccess { remote ->
                            val known = clones.map { it.id }.toSet()
                            remote.filter { it.id !in known }.forEach { model.upsertCloudVoice(it) }
                            previewer.status = InlineStatus.ok(if (remote.isEmpty()) "账号里还没有保存的音色。" else "已同步，共 ${remote.size} 个，新增 ${remote.count { it.id !in known }} 个。")
                        }.onFailure { previewer.status = InlineStatus.failed(it.message ?: "同步失败") }
                    }
                }
            }
        }
        HorizontalDivider(color = p.stroke)
        Header(if (provider == VoiceProvider.VOLCANO) "官方音色" else "热门公开音色",
            if (provider == VoiceProvider.VOLCANO) "豆包语音合成 2.0" else if (catalog.isEmpty()) "" else "${catalog.size} 个")
        if (provider == VoiceProvider.BREEZE && catalog.isEmpty()) {
            ActionButton(if (loadingCatalog) "正在加载…" else "加载 Breeze 中文音色", Icons.Filled.List, prominent = false, busy = loadingCatalog,
                modifier = Modifier.fillMaxWidth()) {
                if (needKey()) scope.launch {
                    loadingCatalog = true
                    runCatching { Breeze.catalog(key) }.onSuccess {
                        catalog = it
                        previewer.status = if (it.isEmpty()) InlineStatus.failed("没有找到中文公开音色。") else null
                    }.onFailure { previewer.status = InlineStatus.failed(it.message ?: "加载失败") }
                    loadingCatalog = false
                }
            }
            if (selected.isEmpty()) Text("未选择时，会自动使用 Breeze 最热门的中文音色。", color = p.secondary, fontSize = 12.sp)
        }
        (if (showAll) presets else presets.take(6)).forEach { preset ->
            SelectRow(preset.id == selected, preset.name, preset.note, onSelect = { choose(preset.id, preset.name) }) {
                PlayButton(previewer, preset.id) {
                    if (needKey()) previewer.play(preset.id, preset.name) {
                        val link = preset.previewUrl
                        if (link != null) model.playRemote(link, provider)
                        else model.previewLine(VoiceTarget(provider, preset.id, false))
                    }
                }
            }
        }
        if (presets.size > 6) {
            Row(Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(8.dp), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (showAll) "收起" else "展开全部 ${presets.size} 个音色", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Icon(if (showAll) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = p.accent)
            }
        }
        if (provider == VoiceProvider.VOLCANO) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field("其他音色 ID（可选）", customId, { customId = it }, placeholder = "zh_female_…_bigtts 或 S_…") }
                Spacer(Modifier.width(8.dp))
                ActionButton("使用", enabled = customId.isNotBlank()) {
                    val id = customId.trim()
                    if (id.startsWith("S_")) model.upsertCloudVoice(CloudVoice(id, provider, "复刻音色 " + id.takeLast(6)))
                    choose(id, model.cloudVoices.firstOrNull { it.id == id }?.name ?: id)
                    customId = ""
                }
            }
        }
        previewer.status?.let { StatusView(it) }
        Text(if (provider == VoiceProvider.VOLCANO)
            "克隆：在控制台「声音复刻」购买的音色槽位（S_ 开头）可直接填入；留空则用后付费自定义音色——训练和官方试听不收费，第一次正式说话时火山会收取音色费用。删除只会从本机列表移除。"
        else "克隆需要 3 秒以上清晰人声（小于 5 MB），保存后占用账号里的一个音色位。删除会同时从 Breeze 账号里删除。",
            color = p.secondary, fontSize = 12.sp)
    }

    if (cloning) {
        CloneDialog(model, provider, onDismiss = { cloning = false }) { voice ->
            cloning = false
            previewer.status = InlineStatus.ok("已保存并选中「${voice.name}」。")
        }
    }
    if (importing) {
        TextDialog("填入已有音色 ID", "例如 S_xxxxxxxx", "", confirm = "添加", limit = 256,
            message = "在火山引擎控制台「声音复刻」里复刻完成的音色 ID。", onDismiss = { importing = false }) { id ->
            importing = false
            scope.launch {
                previewer.status = InlineStatus.working("正在查询音色状态…")
                runCatching { Volcano.status(id, key) }.onSuccess { result ->
                    if (result.status == 0) { previewer.status = InlineStatus.failed("没有找到这个音色 ID，请确认已在控制台复刻。"); return@onSuccess }
                    val voice = CloudVoice(id, VoiceProvider.VOLCANO, "复刻音色 " + id.takeLast(6), result.demoUrl, postpaid = !id.startsWith("S_"))
                    model.upsertCloudVoice(voice)
                    if (result.ready) { choose(id, voice.name); previewer.status = InlineStatus.ok("已添加并选中「${voice.name}」，可在 ⋮ 里重命名。") }
                    else previewer.status = if (result.failed) InlineStatus.failed("这个音色训练失败了，请在控制台重新复刻。")
                        else InlineStatus.ok("已添加，音色还在训练中，稍后点 ⋮ →「刷新训练状态」。")
                }.onFailure { previewer.status = InlineStatus.failed(it.message ?: "查询失败") }
            }
        }
    }
    renaming?.let { voice ->
        TextDialog("重命名音色", "音色名称", voice.name, onDismiss = { renaming = null }) { name ->
            model.upsertCloudVoice(voice.copy(name = name.take(40)))
            renaming = null
        }
    }
    deleting?.let { voice ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除「${voice.name}」？") },
            text = { Text(if (provider == VoiceProvider.BREEZE) "会同时从你的 Breeze 账号里删除。" else "只从本机列表移除，火山控制台里的音色不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        if (provider == VoiceProvider.BREEZE && key.isNotBlank()) {
                            val failure = runCatching { Breeze.delete(voice.id, key) }.exceptionOrNull()
                            if (failure != null && failure.message?.contains("404") != true) {
                                previewer.status = InlineStatus.failed("删除失败：" + failure.message); return@launch
                            }
                        }
                        model.removeCloudVoice(voice)
                        previewer.status = InlineStatus.ok("已删除「${voice.name}」。")
                    }
                }) { Text("删除", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

// MARK: - Add a cloned voice

@Composable
private fun CloneDialog(model: CompanionModel, provider: VoiceProvider, onDismiss: () -> Unit, onDone: (CloudVoice) -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val key = model.key(provider.keyName)
    val maxSeconds = if (provider == VoiceProvider.VOLCANO) 25.0 else 20.0
    val playing by model.player.playing.collectAsState()
    var name by remember { mutableStateOf("${model.name} 的声音 ${model.cloudVoices.count { it.provider == provider } + 1}") }
    var speakerId by remember { mutableStateOf("") }
    var wav by remember { mutableStateOf<ByteArray?>(null) }
    var seconds by remember { mutableStateOf(0.0) }
    var label by remember { mutableStateOf("") }
    var preparing by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<InlineStatus?>(null) }
    var previewId by remember { mutableStateOf<String?>(null) }
    var previewAudio by remember { mutableStateOf<ByteArray?>(null) }
    var result by remember { mutableStateOf<CloudVoice?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var showStored by remember { mutableStateOf(false) }

    fun prepare(load: suspend () -> Pair<ByteArray, Double>, title: String) {
        scope.launch {
            preparing = true; status = null
            runCatching { load() }.onSuccess { (bytes, length) -> wav = bytes; seconds = length; label = title }
                .onFailure { status = InlineStatus.failed(it.message ?: "样本处理失败") }
            preparing = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepare({
            val file = model.copyToCache(uri)
            try { withContext(Dispatchers.Default) { AudioDecoder.reference(file, maxSeconds) } } finally { file.delete() }
        }, model.displayName(uri))
    }
    fun listen(voice: CloudVoice) {
        if (playing) { model.stopSpeaking(); return }
        scope.launch {
            status = InlineStatus.working("语音生成中…")
            runCatching {
                val demo = voice.demoUrl
                val cached = previewAudio
                when {
                    provider == VoiceProvider.VOLCANO && demo != null -> model.playRemote(demo, provider)
                    provider == VoiceProvider.BREEZE && cached != null -> model.player.playWav(cached)
                    else -> model.previewLine(VoiceTarget(provider, voice.id, true), "你好呀，我是${model.name}，以后就用这个声音陪你聊天啦！")
                }
            }.onSuccess { status = null }.onFailure { status = InlineStatus.failed(it.message ?: "试听失败") }
        }
    }
    fun close() { job?.cancel(); model.stopSpeaking(); result?.let(onDone) ?: onDismiss() }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize()) {
            SeaBackdrop()
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { close() }) { Icon(Icons.Filled.Close, if (result == null) "取消" else "关闭", tint = p.text) }
                    Text("添加${provider.title}克隆音色", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Glass(Modifier.fillMaxWidth()) {
                        Field("音色名称", name, { if (it.length <= 40) name = it }, placeholder = "例如：甜甜的肥鱼")
                        if (provider == VoiceProvider.VOLCANO) {
                            Field("音色 ID（可选）", speakerId, { speakerId = it.trim() }, placeholder = "S_ 开头；留空 = 后付费自定义音色")
                            Text("在控制台「声音复刻」下单得到的音色槽位填在这里；留空会自动生成一个后付费音色 ID（训练免费，第一次正式说话时计费）。",
                                color = p.secondary, fontSize = 12.sp)
                        }
                    }
                    Glass(Modifier.fillMaxWidth()) {
                        Text("声音样本", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        val sample = wav
                        if (sample != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(label, color = p.text, fontSize = 14.sp, maxLines = 1)
                                    Text("有效人声 %.1f 秒".format(seconds), color = p.secondary, fontSize = 12.sp)
                                }
                                IconButton(onClick = { if (playing) model.stopSpeaking() else model.player.playWav(sample) }) {
                                    Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, "播放样本", tint = p.accent)
                                }
                            }
                        } else {
                            Text("建议 10–20 秒、只有一个人说话、没有背景音乐。会自动裁掉首尾静音，最多保留 ${maxSeconds.toInt()} 秒。",
                                color = p.secondary, fontSize = 12.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton("从文件选择", Icons.Filled.FolderOpen, prominent = false, enabled = !working && !preparing,
                                modifier = Modifier.weight(1f)) { picker.launch(arrayOf("audio/*")) }
                            if (model.samples.isNotEmpty()) {
                                Box(Modifier.weight(1f)) {
                                    ActionButton("用已存的样本", Icons.Filled.Inventory2, prominent = false, enabled = !working && !preparing,
                                        modifier = Modifier.fillMaxWidth()) { showStored = true }
                                    DropdownMenu(expanded = showStored, onDismissRequest = { showStored = false }) {
                                        model.samples.forEach { stored ->
                                            DropdownMenuItem(text = { Text(stored.name) }, onClick = {
                                                showStored = false
                                                if (name.startsWith(model.name + " 的声音")) name = stored.name
                                                val file = model.store.sampleFile(stored)
                                                prepare({ withContext(Dispatchers.Default) { AudioDecoder.reference(file, maxSeconds) } }, stored.name)
                                            })
                                        }
                                    }
                                }
                            }
                        }
                        if (preparing) StatusView(InlineStatus.working("正在处理样本…"))
                    }
                    val done = result
                    val generated = previewId
                    when {
                        done != null -> Glass(Modifier.fillMaxWidth()) {
                            StatusView(InlineStatus.ok("「${done.name}」已保存并选中，她之后就用这个声音说话。"))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ActionButton(if (playing) "停止" else "试听", if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, prominent = false,
                                    modifier = Modifier.weight(1f)) { listen(done) }
                                ActionButton("完成", Icons.Filled.Check, modifier = Modifier.weight(1f)) { model.stopSpeaking(); onDone(done) }
                            }
                        }
                        generated != null -> Glass(Modifier.fillMaxWidth()) {
                            Text("试听生成好了，满意就保存。", color = p.text, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                ActionButton(if (playing) "停止" else "再听一次", if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, prominent = false,
                                    enabled = !working, modifier = Modifier.weight(1f)) {
                                    if (playing) model.stopSpeaking() else previewAudio?.let { model.player.playWav(it) }
                                }
                                ActionButton("保存并使用", Icons.Filled.Check, busy = working, modifier = Modifier.weight(1f)) {
                                    model.stopSpeaking()
                                    job = scope.launch {
                                        working = true
                                        status = InlineStatus.working("正在保存到 Breeze 账号…")
                                        runCatching { Breeze.savePreview(generated, name.trim().ifEmpty { "我的音色" }, key) }.onSuccess { voice ->
                                            model.upsertCloudVoice(voice)
                                            model.selectCloudVoice(provider, voice.id, voice.name)
                                            result = voice; previewId = null; status = null
                                        }.onFailure { status = InlineStatus.failed(it.message ?: "保存失败") }
                                        working = false
                                    }
                                }
                            }
                            TextButton(onClick = { previewId = null; previewAudio = null; status = null }, enabled = !working) {
                                Text("不满意，换个样本重来", color = p.secondary)
                            }
                        }
                        else -> ActionButton(if (working) "正在克隆…" else "开始克隆", Icons.Filled.AutoAwesome, busy = working,
                            enabled = wav != null && name.isNotBlank() && key.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            val sample = wav ?: return@ActionButton
                            model.stopSpeaking()
                            job = scope.launch {
                                working = true
                                status = InlineStatus.working("正在上传样本…")
                                runCatching {
                                    if (provider == VoiceProvider.VOLCANO) {
                                        val (started, postpaid) = Volcano.clone(sample, speakerId, key)
                                        var current = started
                                        if (!current.ready) {
                                            status = InlineStatus.working("火山引擎正在训练音色…")
                                            current = Volcano.waitUntilReady(current.speakerId, key) { elapsed ->
                                                status = InlineStatus.working("火山引擎正在训练音色… $elapsed 秒")
                                            }
                                        }
                                        val voice = CloudVoice(current.speakerId, provider, name.trim().take(40), current.demoUrl, postpaid)
                                        model.upsertCloudVoice(voice)
                                        model.selectCloudVoice(provider, voice.id, voice.name)
                                        result = voice
                                        status = null
                                        listen(voice)
                                    } else {
                                        val generatedId = Breeze.createClonePreview(sample, name.trim(), key)
                                        status = InlineStatus.working("正在生成试听…")
                                        val raw = Breeze.previewAudio(generatedId, key)
                                        val audio = withContext(Dispatchers.Default) { Tts.playable(raw, model.store.cacheDir) }
                                        previewAudio = audio
                                        previewId = generatedId
                                        status = null
                                        model.player.playWav(audio)
                                    }
                                }.onFailure { if (it !is kotlinx.coroutines.CancellationException) status = InlineStatus.failed(it.message ?: "克隆失败") }
                                working = false
                            }
                        }
                    }
                    status?.let { StatusView(it) }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}
