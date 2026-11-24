package com.feiyu.companion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.feiyu.companion.CompanionModel
import com.feiyu.companion.MessageImage
import com.feiyu.companion.Sticker
import com.feiyu.companion.StickerSpec
import com.feiyu.companion.StickerStudio
import com.feiyu.companion.TextProvider
import com.feiyu.companion.rememberBitmap
import kotlinx.coroutines.launch

// MARK: - Small pieces

@Composable
fun StickerView(model: CompanionModel, sticker: Sticker?, size: Int) {
    val bitmap by rememberBitmap(sticker?.let { model.stickerFile(it) }, 480)
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, "表情：" + sticker?.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Text("🐟", fontSize = (size * 0.45f).sp)
    }
}

@Composable
fun PhotoThumb(model: CompanionModel, image: MessageImage, size: Int, onClick: (() -> Unit)? = null) {
    val p = LocalPalette.current
    val bitmap by rememberBitmap(model.mediaFile(image), if (size > 120) 900 else 360)
    val base = Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(p.field)
    Box(if (onClick != null) base.clickable(onClick = onClick) else base, contentAlignment = Alignment.Center) {
        val shown = bitmap
        if (shown != null) Image(shown, image.altText ?: "图片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Image, "图片不在本机", tint = p.secondary)
    }
}

@Composable
fun PhotoViewer(model: CompanionModel, image: MessageImage, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val bitmap by rememberBitmap(model.mediaFile(image), 2048)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            bitmap?.let { Image(it, image.altText ?: "图片", Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit) }
                ?: CircularProgressIndicator(color = Color.White)
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Filled.Close, "关闭", tint = Color.White)
            }
        }
    }
}

// MARK: - Composer with pictures, stickers and her album

/**
 * Text field + "+" menu (相册图片 / 表情 / 角色相册 / 生成图片). Pending pictures and a chosen sticker show above it.
 * `trailingIdle` is shown instead of the send button while the field is empty (e.g. the voice button in the room).
 */
@Composable
fun Composer(model: CompanionModel, trailingIdle: (@Composable () -> Unit)? = null, leading: (@Composable () -> Unit)? = null,
             overrideText: String? = null) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<MessageImage>() }
    var sticker by remember { mutableStateOf<Sticker?>(null) }
    var importing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var stickerPanel by remember { mutableStateOf(false) }
    var albumPanel by remember { mutableStateOf(false) }
    val canGenerate = model.settings.provider == TextProvider.RELAY && model.settings.imageModel.isNotBlank()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importing = true
            for (uri in uris.take(6 - attachments.size)) runCatching { model.importPhoto(uri) }
                .onSuccess { attachments.add(it) }.onFailure { model.error = it.message }
            importing = false
        }
    }
    val hasDraft = input.isNotBlank() || attachments.isNotEmpty() || sticker != null
    fun submit(generate: Boolean = false) {
        if (!model.textReady && !model.settings.dshEnabled) { model.error = "请先在设置里填写文字模型的 API Key。"; return }
        if (importing) return
        if (model.send(input, images = attachments.toList(), stickerId = sticker?.id, generateImage = generate)) {
            input = ""; attachments.clear(); sticker = null
        }
    }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.glass).border(0.8.dp, p.stroke, RoundedCornerShape(26.dp)).padding(6.dp)) {
        if (attachments.isNotEmpty() || sticker != null || importing) {
            LazyRow(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(attachments.toList(), key = { it.id }) { image ->
                    Box {
                        PhotoThumb(model, image, 52)
                        Icon(Icons.Filled.Cancel, "移除图片", tint = Color.White,
                            modifier = Modifier.align(Alignment.TopEnd).size(22.dp).clickable { attachments.remove(image); model.discardPhoto(image) })
                    }
                }
                sticker?.let { chosen ->
                    item("sticker") {
                        Box {
                            StickerView(model, chosen, 52)
                            Icon(Icons.Filled.Cancel, "移除表情", tint = p.secondary,
                                modifier = Modifier.align(Alignment.TopEnd).size(22.dp).clickable { sticker = null })
                        }
                    }
                }
                if (importing) item("loading") { Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = p.accent) } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menu = true }, enabled = !model.pending) { Icon(Icons.Filled.AddCircleOutline, "添加附件", tint = p.accent) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("发送相册图片") }, leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                        enabled = attachments.size < 6 && !importing, onClick = {
                            menu = false
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                    DropdownMenuItem(text = { Text("${model.name}的表情") }, leadingIcon = { Icon(Icons.Filled.EmojiEmotions, null) },
                        onClick = { menu = false; stickerPanel = true })
                    DropdownMenuItem(text = { Text("角色相册") }, leadingIcon = { Icon(Icons.Filled.Collections, null) },
                        onClick = { menu = false; albumPanel = true })
                    if (canGenerate) DropdownMenuItem(text = { Text("生成图片（用输入的描述）") }, leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                        enabled = input.isNotBlank() && attachments.isEmpty(), onClick = { menu = false; submit(generate = true) })
                }
            }
            leading?.invoke()
            TextField(
                value = overrideText ?: input, onValueChange = { input = it },
                placeholder = { Text(if (model.settings.dshEnabled) "交给 DSH 的任务…" else "和${model.name}说点什么…", color = p.secondary) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = p.text, unfocusedTextColor = p.text, cursorColor = p.accent),
                modifier = Modifier.weight(1f).onFocusChanged { model.typing = it.isFocused },
            )
            when {
                model.pending -> CircleButton(Icons.Filled.Stop, "停止", p.danger) { model.cancel(); model.stopSpeaking() }
                hasDraft || trailingIdle == null -> CircleButton(Icons.AutoMirrored.Filled.Send, "发送", null) { submit() }
                else -> trailingIdle()
            }
        }
    }
    if (stickerPanel) StickerPanelDialog(model, onPick = { sticker = it; stickerPanel = false }, onClose = { stickerPanel = false })
    if (albumPanel) AlbumDialog(model, onClose = { albumPanel = false })
}

// MARK: - Stickers

private val frequencies = listOf(1 to "每轮对话", 2 to "每两轮对话", 3 to "每三轮对话", 5 to "每五轮对话", 10 to "每十轮对话", 0 to "不发")

@Composable
fun StickerPanelDialog(model: CompanionModel, onPick: ((Sticker) -> Unit)?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            SeaBackdrop()
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭", tint = LocalPalette.current.text) }
                    Text("表情", color = LocalPalette.current.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                StickerPanel(model, onPick)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerPanel(model: CompanionModel, onPick: ((Sticker) -> Unit)?) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var generating by remember { mutableStateOf(!model.stickersReady) }
    var progress by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<InlineStatus?>(null) }
    var menuFor by remember { mutableStateOf<Sticker?>(null) }
    var renaming by remember { mutableStateOf<Sticker?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            status = InlineStatus.working("正在添加…")
            val added = model.addStickers(uris)
            status = if (added > 0) InlineStatus.ok("已添加 $added 张。长按表情可以改名，她会按名字来挑。") else InlineStatus.failed("没有添加成功。")
        }
    }
    LazyVerticalGrid(GridCells.Adaptive(84.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${model.name}的表情", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text(if (onPick == null) "这些是她自己的表情，聊天时会挑合适的发给你。也可以给她加几张。" else "点一张发给她；长按可以改名或删除。",
                    color = p.secondary, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Glass(Modifier.fillMaxWidth()) {
                val current = model.settings.stickerEvery
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("发表情频率", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(frequencies.firstOrNull { it.first == current }?.second ?: "每三轮对话", color = p.accent, fontWeight = FontWeight.Bold)
                }
                frequencies.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (value, label) ->
                            val selected = value == current
                            Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (selected) p.accent.copy(alpha = if (p.dark) 0.35f else 0.2f) else p.field)
                                .clickable { model.updateSettings { it.copy(stickerEvery = value) } }, contentAlignment = Alignment.Center) {
                                Text(label, color = if (selected) p.text else p.secondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                    }
                }
                Text(if (current == 0) "她不会主动发表情，你仍然可以发给她。" else "每 $current 轮对话最多一张；到点后她也可以选择不发。", color = p.secondary, fontSize = 12.sp)
            }
        }
        if (generating || !model.stickersReady) item(span = { GridItemSpan(maxLineSpan) }) {
            Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(92.dp).clip(RoundedCornerShape(16.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                        if (generating) StickerStudio(onProgress = { progress = it }, onFinish = { made, problem ->
                            generating = false
                            if (made.isNotEmpty()) model.installBuiltinStickers(made)
                            status = when {
                                problem != null && made.size < StickerSpec.pack.size -> InlineStatus.failed(problem + if (made.isEmpty()) "" else "（已保存 ${made.size} 张）")
                                made.isNotEmpty() -> InlineStatus.ok("已生成 ${made.size} 张${model.name}的表情。")
                                else -> status
                            }
                        }, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Filled.PhotoCamera, null, tint = p.accent)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (generating) "正在给${model.name}拍表情包…" else "还没有内置表情包", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (generating) {
                            LinearProgressIndicator(progress = { progress / StickerSpec.pack.size.toFloat() }, color = p.accent, modifier = Modifier.fillMaxWidth())
                            Text("$progress / ${StickerSpec.pack.size} · 用她的 Live2D 模型现拍，只需要一次", color = p.secondary, fontSize = 12.sp)
                        } else TextButton(onClick = { status = null; progress = 0; generating = true }) { Text("开始生成") }
                    }
                }
            }
        }
        status?.let { item(span = { GridItemSpan(maxLineSpan) }) { StatusView(it) } }
        item {
            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(18.dp)).border(1.2.dp, p.secondary.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Add, "添加表情", tint = p.accent)
                    Text("添加", color = p.accent, fontSize = 12.sp)
                }
            }
        }
        items(model.stickers, key = { it.id }) { item ->
            Box {
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = if (p.dark) 0.92f else 1f))
                    .combinedClickable(onClick = { onPick?.invoke(item) }, onLongClick = { menuFor = item }).padding(4.dp), contentAlignment = Alignment.Center) {
                    StickerView(model, item, 84)
                }
                DropdownMenu(expanded = menuFor?.id == item.id, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text(item.title, color = p.secondary) }, onClick = {}, enabled = false)
                    DropdownMenuItem(text = { Text("改名") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menuFor = null; renaming = item })
                    DropdownMenuItem(text = { Text("删除") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menuFor = null; model.deleteSticker(item) })
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("她会按表情的名字挑选，名字起得越准越会用（长按可改名）。自己添加的图片会缩小到 480 像素保存在本机。", color = p.secondary, fontSize = 12.sp)
                if (model.stickersReady && !generating) {
                    ActionButton("重新生成内置表情", Icons.Filled.Refresh, prominent = false, modifier = Modifier.fillMaxWidth()) {
                        status = null; progress = 0; generating = true
                    }
                }
            }
        }
    }
    renaming?.let { item ->
        TextDialog("给表情改名", "比如：在呢、晚安、委屈", item.title, limit = 12, onDismiss = { renaming = null }) { name ->
            model.renameSticker(item, name); renaming = null
        }
    }
}

/** First launch: makes the built-in pack once, shown as a small card in the room. */
@Composable
fun StickerAutoMaker(model: CompanionModel, onFinish: () -> Unit) {
    val p = LocalPalette.current
    var progress by remember { mutableIntStateOf(0) }
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(p.glass).border(0.8.dp, p.stroke, RoundedCornerShape(20.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)) {
            StickerStudio(onProgress = { progress = it }, onFinish = { made, _ ->
                if (made.isNotEmpty()) model.installBuiltinStickers(made)
                onFinish()
            }, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(190.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${model.name}正在拍表情包…", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { progress / StickerSpec.pack.size.toFloat() }, color = p.accent, modifier = Modifier.fillMaxWidth())
            Text("$progress / ${StickerSpec.pack.size} · 只需要一次", color = p.secondary, fontSize = 12.sp)
        }
    }
}

// MARK: - Her album (photos she can choose to "send" you)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumDialog(model: CompanionModel, onClose: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<MessageImage?>(null) }
    var menuFor by remember { mutableStateOf<MessageImage?>(null) }
    var describing by remember { mutableStateOf<MessageImage?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) scope.launch { adding = true; model.addAlbumPhotos(uris); adding = false }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            SeaBackdrop()
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭", tint = p.text) }
                    Text("角色相册", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (adding) CircularProgressIndicator(Modifier.size(20.dp), color = p.accent, strokeWidth = 2.dp)
                    IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Filled.Add, "添加相册图片", tint = p.accent)
                    }
                }
                Text("放进这里的照片，${model.name}聊天时会挑合适的一张发给你（比如“我今天的样子”）。长按可以写一句描述，她会按描述来选。",
                    color = p.secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
                LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (model.album.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("相册还是空的，点右上角 + 添加。", color = p.secondary, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
                    }
                    items(model.album, key = { it.id }) { image ->
                        Box {
                            Box(Modifier.combinedClickable(onClick = { viewing = image }, onLongClick = { menuFor = image })) { PhotoThumb(model, image, 104) }
                            image.altText?.let {
                                Text(it, color = Color.White, fontSize = 11.sp, maxLines = 1,
                                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(4.dp))
                            }
                            DropdownMenu(expanded = menuFor?.id == image.id, onDismissRequest = { menuFor = null }) {
                                DropdownMenuItem(text = { Text("写描述") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menuFor = null; describing = image })
                                DropdownMenuItem(text = { Text("删除") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menuFor = null; model.removeAlbumPhoto(image) })
                            }
                        }
                    }
                }
            }
        }
    }
    viewing?.let { PhotoViewer(model, it) { viewing = null } }
    describing?.let { image ->
        TextDialog("这张照片是什么？", "比如：海边自拍、今天的晚饭", image.altText ?: "", limit = 80, onDismiss = { describing = null }) { text ->
            model.describeAlbumPhoto(image, text); describing = null
        }
    }
}
