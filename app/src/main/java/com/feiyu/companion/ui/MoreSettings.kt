package com.feiyu.companion.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feiyu.companion.CompanionModel
import com.feiyu.companion.DshBridge
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// MARK: - Cards shown on the main settings page

@Composable
fun PictureModelFields(model: CompanionModel) {
    val p = LocalPalette.current
    Field("视觉模型 ID（发图片用）", model.settings.visionModel, { v -> model.updateSettings { it.copy(visionModel = v.trim()) } },
        placeholder = "例如 gpt-4o / qwen-vl-max")
    Field("图片生成模型 ID", model.settings.imageModel, { v -> model.updateSettings { it.copy(imageModel = v.trim()) } },
        placeholder = "例如 gpt-image-1 / dall-e-3")
    Text("发图需要中转站里有能看图的模型；填了图片生成模型后，聊天「+」里会出现「生成图片」。", color = p.secondary, fontSize = 12.sp)
}

@Composable
fun StickerAlbumCard(model: CompanionModel) {
    val p = LocalPalette.current
    var stickers by remember { mutableStateOf(false) }
    var album by remember { mutableStateOf(false) }
    SectionCard("表情与相册", Icons.Filled.EmojiEmotions, "她自己的表情包 · 她能发给你的照片") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("表情包（${model.stickers.size}）", Icons.Filled.EmojiEmotions, prominent = false, modifier = Modifier.weight(1f)) { stickers = true }
            ActionButton("角色相册（${model.album.size}）", Icons.Filled.Collections, prominent = false, modifier = Modifier.weight(1f)) { album = true }
        }
        Text(if (model.stickersReady) "发表情频率：" + when (model.settings.stickerEvery) { 0 -> "不发"; 1 -> "每轮"; else -> "每 ${model.settings.stickerEvery} 轮" }
            else "内置表情包还没生成，打开「表情包」会自动用她的 Live2D 模型拍一套。", color = p.secondary, fontSize = 12.sp)
    }
    if (stickers) StickerPanelDialog(model, onPick = null) { stickers = false }
    if (album) AlbumDialog(model) { album = false }
}

@Composable
fun AvatarCard(model: CompanionModel) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<InlineStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    fun install(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            status = InlineStatus.working("正在导入并检查模型文件…")
            runCatching { block() }
                .onSuccess { status = InlineStatus.ok("已安装「${model.modelInfo?.title}」，回到房间就能看到。") }
                .onFailure { status = InlineStatus.failed(it.message ?: "导入失败") }
            busy = false
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) install { model.installModelFolder(uri) } }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) install { model.installModelZip(uri) } }
    SectionCard("角色形象", Icons.Filled.Person, "Live2D 全身模型（Cubism 3/4）") {
        val info = model.modelInfo
        if (info == null) {
            Text("当前：大肥鱼（内置）", color = p.text, fontSize = 15.sp)
        } else {
            SwitchRow("使用导入的模型", "关闭后换回内置的大肥鱼", model.settings.useImportedModel) { model.useImportedModel(it) }
            Text("「${info.title}」· ${info.fileCount} 个文件 · ${"%.1f".format(info.bytes / 1_048_576.0)} MB", color = p.secondary, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("选择模型文件夹", Icons.Filled.Folder, prominent = info == null, busy = busy, modifier = Modifier.weight(1f)) { folder.launch(null) }
            ActionButton("导入 .zip", Icons.Filled.FolderZip, prominent = false, enabled = !busy, modifier = Modifier.weight(1f)) {
                zip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
        }
        if (info != null) ActionButton("删除导入的模型", Icons.Filled.Delete, prominent = false, destructive = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth()) { confirmRemove = true }
        status?.let { StatusView(it) }
        Text("选包含 .model3.json 的文件夹（或把它压成 zip），贴图、动作、表情会一起导入。表情包始终用大肥鱼拍；导入的模型如果没有同名表情，情绪表情不会显示，但口型、视线和触摸都能用。",
            color = p.secondary, fontSize = 12.sp)
    }
    if (confirmRemove) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("删除导入的模型？") },
            text = { Text("删除后换回内置的大肥鱼。") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmRemove = false; model.removeModel(); status = null }) { Text("删除", color = p.danger) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmRemove = false }) { Text("取消") } })
    }
}

@Composable
fun BridgeSyncCard(model: CompanionModel, openDsh: () -> Unit, openSync: () -> Unit) {
    val p = LocalPalette.current
    SectionCard("连接", Icons.Filled.Computer, "电脑桥接 · 跨设备同步") {
        SelectRowLink("电脑桥接 · DSH", if (model.settings.dshEnabled) "已开启 · 消息交给电脑上的 DSH" else "未开启", openDsh)
        SelectRowLink("跨设备同步", model.store.lastSyncAt.takeIf { it > 0 }?.let {
            "上次同步 " + SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it))
        } ?: "和 iPhone / 另一台安卓同步聊天、记忆和人设", openSync)
    }
}

@Composable
private fun SelectRowLink(title: String, subtitle: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.field), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = p.secondary, fontSize = 12.sp, maxLines = 1)
        }
        androidx.compose.material3.TextButton(onClick = onClick) { Text("打开", color = p.accent) }
    }
}

// MARK: - DSH page

@Composable
private fun PageScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize()) {
        SeaBackdrop()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = p.text) }
                Text(title, color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
        }
    }
}

@Composable
fun DshScreen(model: CompanionModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(model.settings.dshUrl) }
    var token by remember { mutableStateOf(model.key(DshBridge.TOKEN_KEY)) }
    var status by remember { mutableStateOf<InlineStatus?>(null) }
    var testing by remember { mutableStateOf(false) }
    fun save() {
        model.updateSettings { it.copy(dshUrl = address.trim()) }
        model.saveKey(DshBridge.TOKEN_KEY, token)
    }
    PageScaffold("DSH 连接", onBack = { save(); onBack() }) {
        Text("让${model.name}\n接到 DSH。", color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 38.sp)
        Text("在电脑上运行配套的 DSH 桥接服务，让手机上的${model.name}把任务交给 DeepSeek Harness，指挥它查资料、写文件、跑命令。",
            color = p.secondary, fontSize = 14.sp)
        Glass(Modifier.fillMaxWidth()) {
            SwitchRow("DSH 模式", "开启后，你发的消息会交给电脑上的 DSH，而不是 DeepSeek", model.settings.dshEnabled) { enabled ->
                if (enabled && (address.isBlank() || token.isBlank())) { status = InlineStatus.failed("请先填写电脑地址和连接令牌，并测试连接。"); return@SwitchRow }
                save()
                model.updateSettings { it.copy(dshEnabled = enabled) }
                status = if (enabled) InlineStatus.ok("已开启：之后打字发的消息会交给电脑上的 DSH。") else InlineStatus.ok("已关闭，回到和${model.name}正常聊天。")
            }
        }
        Glass(Modifier.fillMaxWidth()) {
            Text("桥接服务", color = p.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Field("电脑地址", address, { address = it; status = null }, placeholder = "http://192.168.1.10:3210", keyboard = KeyboardType.Uri)
            Text("电脑和手机在同一 Wi-Fi；端口默认 3210，只填 IP 也可以。", color = p.secondary, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field("连接令牌", token, { token = it; status = null }, placeholder = "启动桥接时显示的 token", secret = true) }
                IconButton(onClick = {
                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    clip?.getItemAt(0)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { token = it }
                }) { Icon(Icons.Filled.ContentPaste, "粘贴令牌", tint = p.accent) }
            }
            ActionButton(if (testing) "正在连接…" else "保存并测试连接", Icons.Filled.Verified, busy = testing, modifier = Modifier.fillMaxWidth()) {
                save()
                scope.launch {
                    testing = true
                    status = InlineStatus.working("正在连接电脑…")
                    runCatching { DshBridge.health(address, token) }.onSuccess { health ->
                        val parts = listOfNotNull(health.computer?.let { "电脑 $it" }, health.dsh?.let { "DSH $it" },
                            health.workdir?.let { "工作目录 $it" }, if (health.busy) "正在执行任务" else null)
                        status = InlineStatus.ok("已连接。" + parts.joinToString(" · "))
                    }.onFailure { status = InlineStatus.failed(it.message ?: "连接失败") }
                    testing = false
                }
            }
            status?.let { StatusView(it) }
        }
        if (model.dshSession != null) {
            ActionButton("开始新的 DSH 会话", Icons.Filled.Refresh, prominent = false, modifier = Modifier.fillMaxWidth()) {
                model.updateDshSession(null)
                status = InlineStatus.ok("下一条消息会开始新的 DSH 会话。")
            }
        }
        Text("同一个 DSH 会话会一直续接，想换话题就点「开始新的 DSH 会话」。桥接服务在 dsh-bridge 文件夹里，拷到电脑后双击 start-dsh-bridge.bat 启动（第一次先以管理员身份运行 allow-firewall.bat）。DSH 模式下的回复来自电脑上的 DSH，不再是${model.name}的人格；摸摸她、主动打招呼、发图仍然是她自己。",
            color = p.secondary, fontSize = 12.sp)
        Text("权限很大：能访问这个端口并知道令牌的人，可以在你的电脑上执行 DSH 任务。只在可信 Wi-Fi 使用，令牌保密，不用时关掉桥接窗口。",
            color = p.danger, fontSize = 12.sp)
        Text("POST http://电脑IP:3210/v1/chat\nHeader: X-Bridge-Token: 你的token\n返回: text/event-stream", color = p.secondary, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.field).padding(12.dp))
    }
}

// MARK: - Sync page

@Composable
fun SyncScreen(model: CompanionModel, onBack: () -> Unit) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = model.store
    var mode by remember { mutableStateOf(store.syncMode) }
    var folderName by remember { mutableStateOf(store.syncFolderName) }
    var webdavUrl by remember { mutableStateOf(store.webdavUrl) }
    var webdavUser by remember { mutableStateOf(store.webdavUser) }
    var webdavPassword by remember { mutableStateOf(model.key("webdav")) }
    var password by remember { mutableStateOf(model.key("syncPassword")) }
    var status by remember { mutableStateOf<InlineStatus?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            store.syncFolder = uri.toString()
            folderName = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: "已选择的文件夹"
            store.syncFolderName = folderName
            status = null
        }
    }
    fun saveWebdav() {
        store.webdavUrl = webdavUrl.trim(); store.webdavUser = webdavUser.trim(); model.saveKey("webdav", webdavPassword)
    }
    PageScaffold("跨设备同步", onBack = { saveWebdav(); onBack() }) {
        Text("在每台设备上选同一个同步位置、输入同一个口令，再点「立即同步」。和 iPhone 版用的是同一种加密文件，可以互相同步。",
            color = p.secondary, fontSize = 14.sp)
        Glass(Modifier.fillMaxWidth()) {
            Text("同步位置", color = p.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Segmented(listOf("folder", "webdav"), mode, { if (it == "folder") "手机文件夹" else "WebDAV 网盘" }) { mode = it; store.syncMode = it; status = null }
            if (mode == "folder") {
                ActionButton(if (folderName.isEmpty()) "选择同步文件夹" else folderName, Icons.Filled.Folder, prominent = folderName.isEmpty(),
                    modifier = Modifier.fillMaxWidth()) { picker.launch(null) }
                Text("选一个专用文件夹，并让网盘 App（或 Syncthing 等同步工具）把它同步到云端，另一台设备选同一个云端文件夹。", color = p.secondary, fontSize = 12.sp)
            } else {
                Field("WebDAV 地址", webdavUrl, { webdavUrl = it }, placeholder = "https://dav.jianguoyun.com/dav/肥鱼同步/", keyboard = KeyboardType.Uri)
                Field("账号", webdavUser, { webdavUser = it }, placeholder = "邮箱或用户名")
                Field("应用密码", webdavPassword, { webdavPassword = it }, placeholder = "坚果云请用「第三方应用密码」", secret = true)
                Text("坚果云、Nextcloud、群晖等都支持 WebDAV。文件夹要先在网盘里建好。", color = p.secondary, fontSize = 12.sp)
            }
        }
        Glass(Modifier.fillMaxWidth()) {
            Text("同步口令", color = p.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Field("口令（至少 8 个字符，各设备相同）", password, { password = it; status = null }, secret = true)
            ActionButton(if (model.syncing) "正在同步…" else "立即同步", Icons.Filled.Sync, busy = model.syncing,
                enabled = password.length >= 8 && !model.pending, modifier = Modifier.fillMaxWidth()) {
                saveWebdav()
                model.saveKey("syncPassword", password)
                scope.launch {
                    status = InlineStatus.working("正在加密并合并…")
                    runCatching { model.synchronize(password) }
                        .onSuccess { status = InlineStatus.ok("同步完成：已合并聊天、记忆和人设。") }
                        .onFailure { status = InlineStatus.failed(it.message ?: "同步失败") }
                }
            }
            status?.let { StatusView(it) }
        }
        Text("只同步加密后的聊天、记忆和当前人设。API 密钥、声音样本、表情包、图片文件和主题不上传；在别的设备上发的图片会显示成占位图。冲突按最后修改时间合并，删除也会同步。",
            color = p.secondary, fontSize = 12.sp)
    }
}
