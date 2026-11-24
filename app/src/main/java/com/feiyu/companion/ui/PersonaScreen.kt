package com.feiyu.companion.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.feiyu.companion.CharacterCard
import com.feiyu.companion.CompanionModel
import com.feiyu.companion.DefaultPersona
import com.feiyu.companion.PersonaPreset
import com.feiyu.companion.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PersonaEditor(model: CompanionModel, presetId: String?, onBack: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    val existing = model.presets.firstOrNull { it.id == presetId }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var profile by remember { mutableStateOf(existing?.profile ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: model.name) }
    var greeting by remember { mutableStateOf(existing?.greeting ?: DefaultPersona.GREETING) }
    var useNow by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<InlineStatus?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val display = name.trim().ifEmpty { model.name }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            status = InlineStatus.working("正在读取人格卡…")
            runCatching {
                val bytes = model.readBytes(uri)
                val file = model.displayName(uri)
                withContext(Dispatchers.Default) { CharacterCard.load(bytes, file, display) }
            }.onSuccess { card ->
                profile = card.profile
                if (title.isBlank()) card.title?.let { title = it.take(30) }
                var message = "已导入性格（${card.profile.length} 字），名字和形象保持不变。"
                if (card.truncated) message += "内容过长，已截取前 ${PersonaPreset.PROFILE_LIMIT} 字。"
                card.greeting?.takeIf { it.isNotBlank() }?.let { greeting = it.take(2_000); message += "开场白也一起导入了。" }
                status = InlineStatus.ok("$message 点「保存设定」生效。")
            }.onFailure { status = InlineStatus.failed(it.message ?: "导入失败") }
        }
    }

    Box(Modifier.fillMaxSize()) {
        SeaBackdrop()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = p.text) }
                Text("${display}的设定", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    runCatching {
                        model.savePreset(PersonaPreset(id = existing?.id ?: newId(), title = title, name = name, profile = profile, greeting = greeting),
                            activate = existing == null && useNow)
                    }.onSuccess { onBack() }.onFailure { status = InlineStatus.failed(it.message ?: "保存失败") }
                }) { Text("保存", color = p.accent, fontWeight = FontWeight.SemiBold) }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("只有一条${display}。\n换的是她的性格。", color = p.text, fontSize = 26.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp)
                Text("这是一张性格卡。你写得越具体，她越像你心里的那个${display}。", color = p.secondary, fontSize = 14.sp)
                status?.let { StatusView(it) }
                Glass(Modifier.fillMaxWidth()) {
                    Field("人设标题", title, { if (it.length <= 30) title = it }, placeholder = "比如：元气本鱼 / 温柔学姐 / 毒舌损友")
                    Field("${display}的性格（最多 ${PersonaPreset.PROFILE_LIMIT} 字）", profile, { profile = it },
                        placeholder = "你可以写她的身份、语气、边界、在意的事", singleLine = false, minLines = 8)
                    Text("${profile.length} / ${PersonaPreset.PROFILE_LIMIT}", color = if (profile.length > PersonaPreset.PROFILE_LIMIT) p.danger else p.secondary,
                        fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
                }
                Glass(Modifier.fillMaxWidth()) {
                    Field("她的名字 / 自称", name, { if (it.length <= 40) name = it }, placeholder = DefaultPersona.NAME)
                    Field("开场白（全新对话的第一句）", greeting, { if (it.length <= 2_000) greeting = it }, singleLine = false, minLines = 3)
                    if (existing == null) SwitchRow("保存后立即使用", null, useNow) { useNow = it }
                }
                ActionButton("保存设定", Icons.Filled.Check, modifier = Modifier.fillMaxWidth()) {
                    runCatching {
                        model.savePreset(PersonaPreset(id = existing?.id ?: newId(), title = title, name = name, profile = profile, greeting = greeting),
                            activate = existing == null && useNow)
                    }.onSuccess { onBack() }.onFailure { status = InlineStatus.failed(it.message ?: "保存失败") }
                }
                ActionButton("从人格卡导入（只替换性格，不换人）", Icons.Filled.FileDownload, prominent = false, modifier = Modifier.fillMaxWidth()) {
                    picker.launch(arrayOf("*/*"))
                }
                Text("支持 SillyTavern / TavernAI 的 JSON 或 PNG 人格卡，也可以导入 TXT、MD 文本（UTF-8 或 GBK 都行）。",
                    color = p.secondary, fontSize = 12.sp)
                ActionButton("填入默认性格", Icons.Filled.Refresh, prominent = false, modifier = Modifier.fillMaxWidth()) {
                    profile = DefaultPersona.PROFILE; name = DefaultPersona.NAME; greeting = DefaultPersona.GREETING
                    if (title.isBlank()) title = "默认 · 元气本鱼"
                    status = InlineStatus.ok("已填入默认性格，点「保存设定」生效。")
                }
                if (existing != null && model.presets.size > 1) {
                    ActionButton("删除「${existing.title}」", Icons.Filled.Delete, prominent = false, destructive = true, modifier = Modifier.fillMaxWidth()) {
                        confirmDelete = true
                    }
                }
            }
        }
    }
    if (confirmDelete && existing != null) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这套人设？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    runCatching { model.deletePreset(existing.id) }.onSuccess { onBack() }.onFailure { status = InlineStatus.failed(it.message ?: "删除失败") }
                }) { Text("删除", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    }
}

sealed interface Screen {
    data object Room : Screen
    data object Chat : Screen
    data object Settings : Screen
    data class Persona(val id: String?) : Screen
    data object Dsh : Screen
    data object Sync : Screen
}

@Composable
fun AppRoot(model: CompanionModel) {
    var screen by remember { mutableStateOf<Screen>(Screen.Room) }
    when (val current = screen) {
        Screen.Room -> RoomScreen(model, openChat = { screen = Screen.Chat }, openSettings = { model.stopVoiceMode(); screen = Screen.Settings })
        Screen.Chat -> ChatScreen(model, onBack = { screen = Screen.Room })
        Screen.Settings -> SettingsScreen(model, onBack = { model.stopSpeaking(); screen = Screen.Room }, editPreset = { screen = Screen.Persona(it) },
            openDsh = { screen = Screen.Dsh }, openSync = { screen = Screen.Sync })
        is Screen.Persona -> PersonaEditor(model, current.id, onBack = { screen = Screen.Settings })
        Screen.Dsh -> DshScreen(model, onBack = { screen = Screen.Settings })
        Screen.Sync -> SyncScreen(model, onBack = { screen = Screen.Settings })
    }
}
