package com.feiyu.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feiyu.companion.AppTheme
import com.feiyu.companion.ChatApi
import com.feiyu.companion.CompanionModel
import com.feiyu.companion.TextProvider
import com.feiyu.companion.Tts
import com.feiyu.companion.VoiceProvider
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(model: CompanionModel, onBack: () -> Unit, editPreset: (String?) -> Unit, openDsh: () -> Unit, openSync: () -> Unit) {
    val p = LocalPalette.current
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        SeaBackdrop()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = p.text) }
                Text("设置", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(horizontal = 14.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OverviewCard(model)
                PersonaCard(model, editPreset)
                TextModelCard(model)
                VoiceCard(model)
                when (model.settings.voiceProvider) {
                    VoiceProvider.MIMO -> MimoVoiceCard(model)
                    else -> CloudVoiceCard(model, model.settings.voiceProvider)
                }
                StickerAlbumCard(model)
                AvatarCard(model)
                CompanionCard(model)
                BridgeSyncCard(model, openDsh, openSync)
                AppearanceCard(model)
                Text("肥鱼伴侣 Android 0.2.0 · 原画及拆分：神宫凉子 · Live2D 建模：霓虹猫_NeonNeko",
                    color = p.secondary, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun OverviewCard(model: CompanionModel) {
    val p = LocalPalette.current
    Glass(Modifier.fillMaxWidth()) {
        Text(model.name, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("当前人设 · ${model.persona.title}", color = p.secondary, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("文字模型 " + if (model.textReady) "已就绪" else "未配置", if (model.textReady) p.success else androidx.compose.ui.graphics.Color(0xFFFFA726))
            Chip("语音 " + if (model.voiceReady) "已就绪" else "未配置", if (model.voiceReady) p.success else androidx.compose.ui.graphics.Color(0xFFFFA726))
        }
        Chip(model.settings.voiceProvider.shortTitle + " · " + model.voiceLabel(), p.accent2)
    }
}

@Composable
private fun PersonaCard(model: CompanionModel, editPreset: (String?) -> Unit) {
    val p = LocalPalette.current
    SectionCard("人设", Icons.Filled.Person, "保存多套性格，勾选哪套就用哪套 · 聊天记录和记忆不会清空") {
        model.presets.forEach { preset ->
            val active = preset.id == model.activePresetId
            SelectRow(active, preset.title, preset.profile.replace("\n", " ").take(40), onSelect = { model.activatePreset(preset.id) }) {
                IconButton(onClick = { editPreset(preset.id) }) { Icon(Icons.Filled.Edit, "编辑 ${preset.title}", tint = p.secondary) }
            }
        }
        ActionButton("新建人设 / 导入人格卡", Icons.Filled.Add, prominent = false, modifier = Modifier.fillMaxWidth()) { editPreset(null) }
    }
}

@Composable
fun SelectRow(selected: Boolean, title: String, subtitle: String?, onSelect: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) p.softAccent else p.field)
            .clickable(onClick = onSelect).padding(start = 12.dp).heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
            tint = if (selected) p.accent else p.secondary.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = p.secondary, fontSize = 12.sp, maxLines = 1)
        }
        trailing()
    }
}

@Composable
private fun TextModelCard(model: CompanionModel) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val settings = model.settings
    var key by remember(settings.provider) { mutableStateOf(model.key(settings.provider.keyName)) }
    var status by remember { mutableStateOf<InlineStatus?>(null) }
    var testing by remember { mutableStateOf(false) }
    var models by remember(settings.provider) { mutableStateOf<List<String>>(emptyList()) }
    var showModels by remember { mutableStateOf(false) }
    SectionCard("文字模型", Icons.Filled.AutoAwesome, "她怎么思考和说话") {
        Segmented(TextProvider.entries, settings.provider, { it.title }) { value -> model.updateSettings { it.copy(provider = value) }; status = null }
        Field("API Key", key, { key = it; status = null }, placeholder = "粘贴${settings.provider.title}密钥", secret = true)
        if (settings.provider == TextProvider.RELAY) {
            Field("接口地址（Base URL）", settings.relayURL, { v -> model.updateSettings { it.copy(relayURL = v.trim()) } },
                placeholder = "https://example.com/v1", keyboard = KeyboardType.Uri)
        }
        var showPictures by remember { mutableStateOf(false) }
        Box {
            Field("模型 ID", settings.model, { v ->
                model.updateSettings { if (it.provider == TextProvider.DEEPSEEK) it.copy(deepSeekModel = v.trim()) else it.copy(relayModel = v.trim()) }
            }, placeholder = if (settings.provider == TextProvider.DEEPSEEK) "deepseek-flash" else "gpt-4o-mini")
            DropdownMenu(expanded = showModels, onDismissRequest = { showModels = false }) {
                models.take(80).forEach { id ->
                    DropdownMenuItem(text = { Text(id) }, onClick = {
                        model.updateSettings { if (it.provider == TextProvider.DEEPSEEK) it.copy(deepSeekModel = id) else it.copy(relayModel = id) }
                        showModels = false
                    })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("模型列表", Icons.Filled.List, prominent = false, enabled = key.isNotBlank(), modifier = Modifier.weight(1f)) {
                scope.launch {
                    status = InlineStatus.working("正在获取模型列表…")
                    runCatching {
                        model.saveKey(settings.provider.keyName, key)
                        ChatApi.models(model.settings, key.trim())
                    }.onSuccess {
                        models = it
                        status = if (it.isEmpty()) InlineStatus.failed("没有拿到模型，请手动填写模型 ID。") else null
                        showModels = it.isNotEmpty()
                    }.onFailure { status = InlineStatus.failed(it.message ?: "获取失败") }
                }
            }
            ActionButton(if (testing) "测试中…" else "保存并测试", Icons.Filled.Verified, busy = testing, enabled = key.isNotBlank(), modifier = Modifier.weight(1.3f)) {
                scope.launch {
                    testing = true
                    status = InlineStatus.working("正在连接 ${settings.provider.title}…")
                    val started = System.currentTimeMillis()
                    runCatching {
                        model.saveKey(settings.provider.keyName, key)
                        ChatApi.test(model.settings, key.trim())
                    }.onSuccess {
                        status = InlineStatus.ok("连接成功 · ${model.settings.model} · 响应 ${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)} 秒。密钥已保存。")
                    }.onFailure { status = InlineStatus.failed("连接失败：" + (it.message ?: "未知错误")) }
                    testing = false
                }
            }
        }
        status?.let { StatusView(it) }
        if (settings.provider == TextProvider.RELAY) {
            Row(Modifier.fillMaxWidth().clickable { showPictures = !showPictures }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("图片相关（可选）", color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(if (showPictures) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = p.secondary)
            }
            if (showPictures) PictureModelFields(model)
        } else {
            Text("发图片、生成图片需要切换到「自定义中转站」并填写视觉 / 图片模型。", color = p.secondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun VoiceCard(model: CompanionModel) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val provider = model.settings.voiceProvider
    val playing by model.player.playing.collectAsState()
    var key by remember(provider) { mutableStateOf(model.key(provider.keyName)) }
    var status by remember(provider) { mutableStateOf<InlineStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    SectionCard("语音回复", Icons.Filled.Waves, provider.caption) {
        Text("语音服务", color = p.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Segmented(VoiceProvider.entries, provider, { it.title }, enabled = !busy) { value ->
            model.stopSpeaking()
            model.updateSettings { it.copy(voiceProvider = value) }
        }
        Field("${provider.title} API Key", key, { key = it; status = null }, placeholder = provider.keyPlaceholder, secret = true)
        provider.keyHint?.let { Text(it, color = p.secondary, fontSize = 12.sp) }
        SwitchRow("自动播放回复", "她回复时直接念出来", model.settings.autoVoice) { v -> model.updateSettings { it.copy(autoVoice = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(if (busy) "测试中…" else "保存并测试", Icons.Filled.Verified, busy = busy, enabled = key.isNotBlank(), modifier = Modifier.weight(1.4f)) {
                scope.launch {
                    busy = true
                    status = InlineStatus.working("正在连接 ${provider.title} 语音…")
                    val started = System.currentTimeMillis()
                    runCatching {
                        model.saveKey(provider.keyName, key)
                        Tts.synthesize("嗯，你好。", model.voiceTarget(provider), null, key.trim())
                    }.onSuccess {
                        status = InlineStatus.ok("语音服务正常 · 合成用时 ${"%.1f".format((System.currentTimeMillis() - started) / 1000.0)} 秒。密钥已保存。")
                    }.onFailure { status = InlineStatus.failed("语音测试失败：" + (it.message ?: "未知错误")) }
                    busy = false
                }
            }
            ActionButton(if (playing) "停止" else "试听", if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, prominent = false,
                enabled = key.isNotBlank() && !busy, modifier = Modifier.weight(1f)) {
                if (playing) { model.stopSpeaking(); return@ActionButton }
                scope.launch {
                    busy = true
                    status = InlineStatus.working("语音生成中…")
                    runCatching {
                        model.saveKey(provider.keyName, key)
                        model.previewLine(model.voiceTarget(provider), "你好，我是${model.name}。你能听到我吗？")
                    }.onSuccess { status = InlineStatus.ok("正在试听：${model.voiceLabel()}") }
                        .onFailure { status = InlineStatus.failed(it.message ?: "试听失败") }
                    busy = false
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.RecordVoiceOver, null, tint = p.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("当前音色：" + model.voiceLabel(), color = p.secondary, fontSize = 12.sp, maxLines = 1)
        }
        status?.let { StatusView(it) }
    }
}

@Composable
private fun CompanionCard(model: CompanionModel) {
    val p = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    SectionCard("陪伴", Icons.Filled.Favorite, "主动聊天 · 记忆") {
        SwitchRow("主动打招呼", "打开 App 时她会先开口", model.settings.proactive) { v -> model.updateSettings { it.copy(proactive = v) } }
        SwitchRow("自动记住重要的事", "从聊天里提取值得长期记住的事实", model.settings.autoMemory) { v -> model.updateSettings { it.copy(autoMemory = v) } }
        val memories = model.conversation.memories
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("她记得的事（${memories.size}）", color = p.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = p.secondary)
        }
        if (expanded) {
            if (memories.isEmpty()) Text("还没有记忆。", color = p.secondary, fontSize = 13.sp)
            memories.asReversed().take(60).forEach { memory ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.field).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text((if (memory.manual) "✎ " else "") + memory.text, color = p.text, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    IconButton(onClick = { model.deleteMemory(memory.id) }) { Icon(Icons.Filled.Delete, "删除这条记忆", tint = p.secondary) }
                }
            }
            ActionButton("手动添加一条", Icons.Filled.Add, prominent = false, modifier = Modifier.fillMaxWidth()) { adding = true }
        }
        ActionButton("清空聊天记录", Icons.Filled.Delete, prominent = false, destructive = true, modifier = Modifier.fillMaxWidth()) { confirmClear = true }
    }
    if (adding) {
        TextDialog("让她记住", "比如：我对芒果过敏", "", confirm = "记住", limit = 2_000, onDismiss = { adding = false }) { text ->
            runCatching { model.addMemory(text) }.onFailure { model.error = it.message }
            adding = false
        }
    }
    if (confirmClear) {
        AlertDialog(onDismissRequest = { confirmClear = false },
            title = { Text("清空聊天记录？") },
            text = { Text("会删除所有消息和语音，记忆和人设保留。") },
            confirmButton = { TextButton(onClick = { model.clearChat(); confirmClear = false }) { Text("清空", color = p.danger) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    }
}

@Composable
private fun AppearanceCard(model: CompanionModel) {
    SectionCard("外观", Icons.Filled.ColorLens, null) {
        Segmented(AppTheme.entries, model.settings.theme, { it.title }) { value -> model.updateSettings { it.copy(theme = value) } }
    }
}

/** Name / ID / memory entry. Prefilled text is visible and editable (unlike the iOS alert field). */
@Composable
fun TextDialog(
    title: String,
    placeholder: String,
    initial: String,
    confirm: String = "保存",
    limit: Int = 40,
    message: String? = null,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val p = LocalPalette.current
    var text by remember { mutableStateOf(initial.take(limit)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message != null) Text(message, fontSize = 13.sp, color = p.secondary)
                OutlinedTextField(value = text, onValueChange = { if (it.length <= limit) text = it }, placeholder = { Text(placeholder) },
                    singleLine = limit <= 300, modifier = Modifier.fillMaxWidth())
                Text("${text.length} / $limit", fontSize = 11.sp, color = p.secondary)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun LaunchOnce(key: Any, block: suspend () -> Unit) {
    LaunchedEffect(key) { block() }
}
