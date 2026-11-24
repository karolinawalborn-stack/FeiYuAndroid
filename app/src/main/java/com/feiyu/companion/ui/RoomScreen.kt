package com.feiyu.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.feiyu.companion.AppTheme
import com.feiyu.companion.AvatarStage
import com.feiyu.companion.ChatMessage
import com.feiyu.companion.CompanionModel
import com.feiyu.companion.R
import com.feiyu.companion.StageStatus
import kotlinx.coroutines.delay

fun moodLabel(state: String) = when (state) {
    "listening" -> "正在聆听"
    "thinking" -> "思考中"
    "speaking" -> "说话中"
    "typing" -> "在看你打字…"
    else -> "在线"
}

@Composable
private fun avatarState(model: CompanionModel, playing: Boolean): String = when {
    model.listener.listening -> "listening"
    playing -> "speaking"
    model.pending -> "thinking"
    model.typing -> "typing"
    else -> "idle"
}

@Composable
private fun rememberMicStarter(model: CompanionModel, action: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) action() else model.error = "需要麦克风权限才能语音聊天，请在系统设置里允许。"
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
fun RoomScreen(model: CompanionModel, openChat: () -> Unit, openSettings: () -> Unit) {
    val p = LocalPalette.current
    val playing by model.player.playing.collectAsState()
    val mouth by model.player.mouth.collectAsState()
    var stage by remember { mutableStateOf(StageStatus.LOADING) }
    var attempt by remember { mutableIntStateOf(0) }
    val state = avatarState(model, playing)
    val startVoice = rememberMicStarter(model) { model.startVoiceMode() }
    // First launch: make her sticker pack once, quietly, while she says hello.
    var autoStickers by remember { mutableStateOf(!model.stickersReady && !model.store.prefs.getBoolean("stickerAutoTried", false)) }

    LaunchedEffect(Unit) {
        if (autoStickers) model.store.prefs.edit().putBoolean("stickerAutoTried", true).apply()
        delay(1_200)
        model.greetIfNeeded()
    }

    Box(Modifier.fillMaxSize()) {
        SeaBackdrop()
        if (stage != StageStatus.READY && model.stageModelFile == null) {
            Image(painterResource(R.drawable.portrait), model.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(top = 110.dp, bottom = 170.dp).alpha(if (stage == StageStatus.FAILED) 1f else 0.55f))
        }
        key(attempt, model.stageVersion) {
            AvatarStage(
                mouth = mouth, expression = model.expression, state = state, reaction = model.reaction,
                topInset = 96f, bottomInset = 168f,
                onTouch = { model.touch(it) }, onStatus = { stage = it },
                modifier = Modifier.fillMaxSize(),
                modelFile = model.stageModelFile,
            )
        }

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Orb(Icons.Filled.Waves, state)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.name, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("${model.conversation.relationship} · ${if (model.pending && model.activity.isNotEmpty()) model.activity else moodLabel(state)}",
                        color = p.secondary, fontSize = 12.sp, maxLines = 1)
                }
                RoundIcon(if (model.settings.theme == AppTheme.NIGHT) Icons.Filled.LightMode else Icons.Filled.DarkMode, "切换主题") {
                    model.updateSettings { it.copy(theme = if (it.theme == AppTheme.NIGHT) AppTheme.DAY else AppTheme.NIGHT) }
                }
                Spacer(Modifier.width(8.dp))
                RoundIcon(Icons.Filled.Forum, "聊天记录", openChat)
                Spacer(Modifier.width(8.dp))
                RoundIcon(Icons.Filled.Settings, "设置", openSettings)
            }
            SpeechBubble(model, onClick = openChat)
            model.error?.let { ErrorBanner(it, onSettings = openSettings) { model.error = null } }
        }

        if (stage == StageStatus.FAILED) {
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp)) {
                ActionButton("动画加载失败 · 重试", Icons.Filled.Refresh, prominent = false) { stage = StageStatus.LOADING; attempt++ }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (autoStickers) StickerAutoMaker(model) { autoStickers = false }
            if (model.voiceMode) VoiceDock(model, playing)
            else Composer(model, trailingIdle = { CircleButton(Icons.Filled.GraphicEq, "语音对话", null) { startVoice() } })
        }
    }
}

@Composable
private fun Orb(icon: ImageVector, state: String) {
    val p = LocalPalette.current
    val pulse = rememberInfiniteTransition(label = "orb")
    val scale by pulse.animateFloat(1f, if (state == "idle") 1.04f else 1.12f,
        infiniteRepeatable(tween(if (state == "idle") 2_200 else 900), RepeatMode.Reverse), label = "s")
    Box(Modifier.size(42.dp).scale(scale).clip(CircleShape).background(p.accentBrush), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(42.dp).clip(CircleShape).background(p.glass).border(0.8.dp, p.stroke, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = p.text, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SpeechBubble(model: CompanionModel, onClick: () -> Unit) {
    val p = LocalPalette.current
    val messages = model.conversation.messages
    val last = messages.lastOrNull()
    val muted = model.voiceMode || model.quietTurn
    val recent = last != null && !last.isUser && System.currentTimeMillis() - last.createdAt < 10 * 60_000L
    // Her latest turn: up to three bubbles plus a sticker.
    val turn = if (recent) messages.takeLastWhile { !it.isUser }.takeLast(4) else emptyList()
    val sticker = if (muted || model.pending) null else turn.lastOrNull { it.stickerId != null }?.let { model.sticker(it.stickerId) }
    val text = when {
        // Voice conversation and touch reactions are heard, not read.
        muted -> ""
        model.pending && model.preview.isNotBlank() -> model.preview
        model.pending -> "…"
        recent -> turn.map { it.text }.filter { it.isNotBlank() }.takeLast(3).joinToString("\n")
        else -> ""
    }
    AnimatedVisibility(text.isNotBlank() || sticker != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
            val shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
            Box(Modifier.widthIn(max = 330.dp).clip(shape).background(Color.White.copy(alpha = if (p.dark) 0.07f else 0.30f))
                .border(0.8.dp, androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.White.copy(alpha = if (p.dark) 0.38f else 0.95f),
                    Color.White.copy(alpha = if (p.dark) 0.06f else 0.35f))), shape)
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (text.isNotBlank()) Text(text, color = p.text, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    if (sticker != null) StickerView(model, sticker, 96)
                }
            }
        }
    }
}

@Composable
fun ErrorBanner(text: String, onSettings: (() -> Unit)? = null, onClose: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.danger.copy(alpha = 0.16f))
        .border(0.8.dp, p.danger.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = p.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (onSettings != null && (text.contains("设置") || text.contains("Key"))) {
            Text("去设置", color = p.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onSettings).padding(8.dp))
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭提示", tint = p.secondary) }
    }
}

@Composable
fun CircleButton(icon: ImageVector, description: String, tint: Color?, size: Int = 46, onClick: () -> Unit) {
    val p = LocalPalette.current
    val base = Modifier.size(size.dp).clip(CircleShape)
    Box((if (tint == null) base.background(p.accentBrush) else base.background(tint)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size((size * 0.46f).dp))
    }
}

@Composable
private fun VoiceDock(model: CompanionModel, playing: Boolean) {
    val p = LocalPalette.current
    val listening = model.listener.listening
    val pulse = rememberInfiniteTransition(label = "mic")
    val ring by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "r")
    val status = when {
        listening && model.listener.transcript.isNotBlank() -> model.listener.transcript
        listening -> "正在听你说…说完停一下就会自动发送"
        model.pending -> model.activity.ifEmpty { "思考中…" }
        playing -> "${model.name}正在说话 · 点麦克风可以打断她"
        else -> "点麦克风开始说话"
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(p.glass).border(0.8.dp, p.stroke, RoundedCornerShape(28.dp))
        .padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(status, color = p.text, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            LabeledCircle(Icons.Filled.CallEnd, "结束", p.danger) { model.stopVoiceMode(); model.stopSpeaking() }
            Box(contentAlignment = Alignment.Center) {
                if (listening) Box(Modifier.size(84.dp).scale(ring).clip(CircleShape).background(p.accent.copy(alpha = 0.25f)))
                CircleButton(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "说完了" else "开始说话", null, size = 72) { model.micTapped() }
            }
            LabeledCircle(Icons.Filled.Stop, "停止语音", p.secondary.copy(alpha = 0.6f)) { model.stopSpeaking() }
        }
    }
}

@Composable
private fun LabeledCircle(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircleButton(icon, label, tint, size = 50, onClick = onClick)
        Spacer(Modifier.heightIn(min = 4.dp))
        Text(label, color = p.secondary, fontSize = 11.sp)
    }
}

// MARK: - Chat history

@Composable
fun ChatScreen(model: CompanionModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    BackHandler(onBack = onBack)
    val playing by model.player.playing.collectAsState()
    val dictate = rememberMicStarter(model) { model.listen(sendOnce = true) }
    var viewing by remember { mutableStateOf<com.feiyu.companion.MessageImage?>(null) }
    Box(Modifier.fillMaxSize()) {
        SeaBackdrop()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回房间", tint = p.text) }
                Column(Modifier.weight(1f)) {
                    Text(model.name, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("${model.conversation.relationship} · ${moodLabel(avatarState(model, playing))}", color = p.secondary, fontSize = 12.sp)
                }
                if (playing) IconButton(onClick = { model.stopSpeaking() }) { Icon(Icons.Filled.Stop, "停止语音", tint = p.text) }
            }
            model.error?.let { Box(Modifier.padding(horizontal = 14.dp)) { ErrorBanner(it, onClose = { model.error = null }) } }
            val messages = model.conversation.messages
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.pending) item("pending") {
                    Bubble(model, ChatMessage(id = "preview", role = "assistant", text = model.preview.ifBlank { model.activity.ifEmpty { "…" } }), playable = false, onView = {}) {}
                }
                items(messages.asReversed(), key = { it.id }) { message ->
                    Bubble(model, message, playable = message.audioFile != null, onView = { viewing = it }) { model.replay(message) }
                }
                if (messages.isEmpty() && !model.pending) item("empty") {
                    Text("还没有聊天记录，跟${model.name}打个招呼吧～", color = p.secondary, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(24.dp))
                }
            }
            Box(Modifier.padding(10.dp)) {
                Composer(model, leading = {
                    IconButton(onClick = { if (model.listener.listening) model.listener.stop() else dictate() }) {
                        Icon(if (model.listener.listening) Icons.Filled.Stop else Icons.Filled.Mic, "语音输入", tint = p.accent)
                    }
                }, overrideText = if (model.listener.listening) model.listener.transcript else null)
            }
        }
    }
    viewing?.let { PhotoViewer(model, it) { viewing = null } }
}

@Composable
private fun Bubble(model: CompanionModel, message: ChatMessage, playable: Boolean, onView: (com.feiyu.companion.MessageImage) -> Unit, onPlay: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!message.isUser && message.emotion == "dsh") Text("DSH · 电脑", color = p.secondary, fontSize = 11.sp)
        message.images.forEach { image -> PhotoThumb(model, image, 150) { onView(image) } }
        model.sticker(message.stickerId)?.let { StickerView(model, it, 124) }
        if (message.text.isBlank()) return@Column
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start) {
        // Transparent bubble: faint tint, hairline light edge, tighter corner on the speaker's side.
        val shape = if (message.isUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
        val fill = if (message.isUser) p.accent.copy(alpha = if (p.dark) 0.20f else 0.14f)
            else Color.White.copy(alpha = if (p.dark) 0.07f else 0.30f)
        val edge = if (message.isUser) androidx.compose.ui.graphics.Brush.linearGradient(listOf(p.accent.copy(alpha = 0.85f), p.accent2.copy(alpha = 0.35f)))
            else androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.White.copy(alpha = if (p.dark) 0.38f else 0.95f), Color.White.copy(alpha = if (p.dark) 0.06f else 0.35f)))
        val styled = Modifier.widthIn(max = 300.dp).clip(shape).background(fill).border(if (message.isUser) 1.dp else 0.8.dp, edge, shape)
        Row(styled.clickable(enabled = playable, onClick = onPlay).padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (playable) {
                Icon(Icons.Filled.PlayArrow, "播放语音", tint = p.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(message.text, color = p.text, fontSize = 15.sp, lineHeight = 21.sp,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(
                    (if (p.dark) Color.Black else Color.White).copy(alpha = if (p.dark) 0.55f else 0.8f), blurRadius = 4f)))
        }
    }
    }
}
