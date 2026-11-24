package com.feiyu.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

enum class StickerDecor { HEARTS, SPARKLES, QUESTION, EXCLAIM, SWEAT, ZZZ, ANGER, NOTES }

data class StickerSpec(val id: String, val caption: String, val decor: StickerDecor, val params: Map<String, Double>) {
    companion object {
        /** Bump to regenerate after changing poses or captions. */
        const val PACK_VERSION = 1

        val pack = listOf(
            StickerSpec("在呢", "在呢♡", StickerDecor.HEARTS, mapOf("EyeSmile" to 1.0, "ParamEyeLOpen" to 0.9, "ParamEyeROpen" to 0.9, "ParamMouthForm" to 1.0,
                "ParamMouthOpenY" to 0.35, "JawOpen" to 0.2, "key7" to 0.5, "AngleZ" to 10.0, "AngleX" to 6.0, "ParamBrowLY" to 0.3, "ParamBrowRY" to 0.3)),
            StickerSpec("嘿嘿", "嘿嘿", StickerDecor.SPARKLES, mapOf("key5" to 1.0, "ParamMouthOpenY" to 0.55, "ParamMouthForm" to 1.0, "AngleZ" to -8.0, "key7" to 0.4)),
            StickerSpec("干嘛", "干嘛", StickerDecor.QUESTION, mapOf("ParamEyeBallX" to 0.5, "ParamMouthForm" to -0.2, "Mouthpucker" to 0.6, "ParamBrowLY" to 0.5,
                "ParamBrowRY" to -0.2, "AngleZ" to 12.0, "AngleX" to -10.0)),
            StickerSpec("哼", "哼！", StickerDecor.ANGER, mapOf("CheeckPuff" to 1.0, "ParamBrowLY" to -0.8, "ParamBrowRY" to -0.8, "ParamEyeLOpen" to 0.65,
                "ParamEyeROpen" to 0.65, "AngleZ" to -12.0, "AngleX" to -18.0, "ParamEyeBallX" to -0.6, "ParamMouthForm" to -0.6)),
            StickerSpec("呜呜", "呜呜…", StickerDecor.SWEAT, mapOf("key2" to 1.0, "BrowInnerUp" to 1.0, "ParamBrowLY" to -0.3, "ParamBrowRY" to -0.3,
                "ParamMouthForm" to -1.0, "ParamMouthOpenY" to 0.3, "AngleY" to -8.0)),
            StickerSpec("爱你", "爱你！", StickerDecor.HEARTS, mapOf("key4" to 1.0, "ParamMouthOpenY" to 0.5, "ParamMouthForm" to 1.0, "key7" to 0.7,
                "Movement2" to 1.0, "AngleZ" to 8.0)),
            StickerSpec("害羞", "害羞", StickerDecor.HEARTS, mapOf("key7" to 1.0, "EyeSmile" to 0.5, "ParamEyeBallY" to -0.6, "ParamEyeBallX" to 0.4,
                "AngleY" to -10.0, "AngleZ" to -10.0, "Movement2" to 1.0, "ParamMouthForm" to 0.4)),
            StickerSpec("思考中", "思考中…", StickerDecor.QUESTION, mapOf("key8" to 1.0, "Movement3" to 1.0, "ParamEyeBallY" to 0.8, "ParamEyeBallX" to -0.5,
                "AngleY" to 8.0, "AngleZ" to 8.0, "Mouthpucker" to 0.5, "ParamBrowLY" to 0.5)),
            StickerSpec("晕了", "晕了", StickerDecor.SWEAT, mapOf("key3" to 1.0, "key13" to 1.0, "ParamMouthOpenY" to 0.4, "ParamMouthForm" to -0.4, "AngleZ" to 14.0)),
            StickerSpec("绝了", "绝了!?", StickerDecor.EXCLAIM, mapOf("ParamEyeLOpen" to 1.35, "ParamEyeROpen" to 1.35, "ParamBrowLY" to 1.0, "ParamBrowRY" to 1.0,
                "ParamMouthOpenY" to 0.9, "JawOpen" to 0.8, "ParamMouthForm" to -0.3, "AngleY" to 6.0)),
            StickerSpec("就这", "就这？", StickerDecor.SPARKLES, mapOf("key6" to 1.0, "ParamMouthForm" to 1.0, "AngleZ" to 12.0, "AngleY" to 6.0,
                "ParamBrowLY" to 0.4, "ParamBrowRY" to -0.3)),
            StickerSpec("无语", "无语…", StickerDecor.SWEAT, mapOf("key1" to 1.0, "ParamEyeLOpen" to 0.45, "ParamEyeROpen" to 0.45, "ParamMouthForm" to -0.5,
                "ParamBrowLY" to -0.3, "ParamBrowRY" to -0.3)),
            StickerSpec("慌了", "慌了慌了", StickerDecor.SWEAT, mapOf("key13" to 1.0, "BrowInnerUp" to 0.8, "ParamMouthForm" to -0.6, "ParamMouthOpenY" to 0.15,
                "AngleX" to 12.0, "ParamEyeBallX" to -0.7)),
            StickerSpec("晚安", "晚安～", StickerDecor.ZZZ, mapOf("ParamEyeLOpen" to 0.0, "ParamEyeROpen" to 0.0, "EyeSmile" to 1.0, "ParamMouthForm" to 0.6,
                "AngleZ" to 16.0, "AngleY" to -6.0, "key7" to 0.4)),
            StickerSpec("收到", "收到！", StickerDecor.SPARKLES, mapOf("ParamEyeLOpen" to 0.0, "ParamEyeROpen" to 1.0, "EyeSmile" to 0.3, "ParamMouthOpenY" to 0.6,
                "ParamMouthForm" to 1.0, "Movement3" to 1.0, "AngleZ" to -6.0)),
            StickerSpec("嘻嘻", "嘻嘻", StickerDecor.NOTES, mapOf("key9" to 1.0, "ParamMouthOpenY" to 0.5, "ParamMouthForm" to 1.0, "EyeSmile" to 0.8)),
            StickerSpec("摸鱼中", "摸鱼中", StickerDecor.NOTES, mapOf("Movement1" to 1.0, "EyeSmile" to 1.0, "ParamMouthForm" to 0.8, "AngleZ" to 10.0, "ParamEyeBallX" to 0.5)),
            StickerSpec("学霸模式", "学霸模式", StickerDecor.SPARKLES, mapOf("key11" to 1.0, "Movement3" to 1.0, "ParamMouthForm" to 0.3, "ParamBrowLY" to 0.4, "ParamBrowRY" to 0.4)),
        )
    }
}

data class Sticker(val id: String, val title: String, val file: String, val builtin: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("file", file).put("builtin", builtin)

    companion object {
        fun from(o: JSONObject): Sticker? {
            val id = o.str("id") ?: return null
            val file = o.str("file") ?: return null
            return Sticker(id, o.str("title") ?: id, file, o.optBoolean("builtin"))
        }
    }
}

// MARK: - Composer: white die-cut outline + pose + doodles + caption

object StickerComposer {
    private const val SIDE = 360

    private fun silhouette(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }
        Canvas(out).drawBitmap(source, 0f, 0f, paint)
        return out
    }

    /** Draws `layer` with a white outline of `radius` px around it. */
    private fun drawOutlined(canvas: Canvas, layer: Bitmap, radius: Float, steps: Int, shadow: Boolean) {
        val white = silhouette(layer)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (shadow) {
            val glow = Paint(paint).apply { setShadowLayer(6f, 0f, 0f, Color.argb(46, 0, 0, 0)) }
            canvas.drawBitmap(white, 0f, 0f, glow)
        }
        for (step in 0 until steps) {
            val angle = step.toDouble() / steps * 2 * Math.PI
            canvas.drawBitmap(white, (cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat(), paint)
        }
        canvas.drawBitmap(layer, 0f, 0f, paint)
        white.recycle()
    }

    fun compose(figure: Bitmap, spec: StickerSpec): ByteArray {
        val side = SIDE
        // Pose layer, inset like the iPhone version.
        val pose = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(pose).drawBitmap(figure, null, RectF(14f, 10f, side - 14f, side - 18f), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val result = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        drawOutlined(canvas, pose, 7f, 16, shadow = true)
        pose.recycle()
        // Doodles, each with its own small white outline.
        val doodles = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        drawDecor(Canvas(doodles), spec.decor, side.toFloat())
        drawOutlined(canvas, doodles, 3f, 8, shadow = false)
        doodles.recycle()
        drawCaption(canvas, spec.caption, side.toFloat())
        val stream = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.PNG, 100, stream)
        result.recycle()
        return stream.toByteArray()
    }

    private fun glyph(canvas: Canvas, text: String, size: Float, x: Float, y: Float, angle: Float, color: Int? = null) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (color != null) this.color = color
        }
        canvas.save()
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat(), x, y)
        val metrics = paint.fontMetrics
        canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2, paint)
        canvas.restore()
    }

    private fun drawDecor(canvas: Canvas, decor: StickerDecor, side: Float) {
        val pink = Color.rgb(255, 107, 153)
        val yellow = Color.rgb(255, 199, 51)
        val blue = Color.rgb(89, 173, 255)
        val orange = Color.rgb(255, 153, 38)
        val red = Color.rgb(242, 71, 77)
        val lx = 52f; val ly = 70f; val rx = side - 50f; val ry = 58f; val sx = side - 78f; val sy = 122f
        when (decor) {
            StickerDecor.HEARTS -> { glyph(canvas, "♥", 54f, rx, ry, 0.25f, pink); glyph(canvas, "♥", 36f, lx, ly, -0.3f, pink) }
            StickerDecor.SPARKLES -> { glyph(canvas, "✦", 56f, rx, ry, 0.1f, yellow); glyph(canvas, "✦", 36f, lx, ly, -0.2f, yellow); glyph(canvas, "✦", 26f, sx, sy, 0f, yellow) }
            StickerDecor.QUESTION -> { glyph(canvas, "?", 60f, rx, ry, 0.25f, orange); glyph(canvas, "?", 38f, sx, sy, -0.15f, orange) }
            StickerDecor.EXCLAIM -> { glyph(canvas, "!", 62f, rx, ry, 0.2f, red); glyph(canvas, "?", 42f, side - 88f, 70f, -0.25f, orange); glyph(canvas, "⚡", 32f, lx, ly, -0.3f) }
            StickerDecor.SWEAT -> { glyph(canvas, "💧", 40f, rx, ry, 0.35f, blue); glyph(canvas, "💧", 26f, lx, ly, -0.4f, blue) }
            StickerDecor.ZZZ -> { glyph(canvas, "Zz", 48f, rx, ry, -0.1f, blue); glyph(canvas, "☾", 38f, lx, ly, -0.2f, yellow) }
            StickerDecor.ANGER -> { glyph(canvas, "💢", 44f, rx, ry, 0.2f, red); glyph(canvas, "⚡", 30f, lx, ly, -0.3f) }
            StickerDecor.NOTES -> { glyph(canvas, "♪", 52f, rx, ry, 0.2f, pink); glyph(canvas, "♫", 34f, lx, ly, -0.25f, blue) }
        }
    }

    private fun drawCaption(canvas: Canvas, text: String, side: Float) {
        val typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface; textSize = 54f; textAlign = Paint.Align.CENTER; color = Color.rgb(20, 31, 71)
        }
        val width = fill.measureText(text)
        if (width > side - 40) fill.textSize = 54f * (side - 40) / width
        val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE; strokeWidth = fill.textSize * 0.22f; color = Color.WHITE
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val cx = side / 2; val cy = side - 52f
        canvas.save()
        canvas.rotate(Math.toDegrees(-0.05).toFloat(), cx, cy)
        val baseline = cy - (fill.fontMetrics.ascent + fill.fontMetrics.descent) / 2
        canvas.drawText(text, cx, baseline, stroke)
        canvas.drawText(text, cx, baseline, fill)
        canvas.restore()
    }
}

// MARK: - Store

class StickerStore(private val dir: File, private val prefs: android.content.SharedPreferences) {
    private val index = File(dir, "index.json")

    fun load(): List<Sticker> = runCatching {
        JSONArray(index.readText()).mapObjects { Sticker.from(it) }.filter { File(dir, it.file).exists() }
    }.getOrDefault(emptyList())

    private fun save(items: List<Sticker>) { index.writeText(JSONArray(items.map { it.toJson() }).toString()) }

    fun file(sticker: Sticker) = File(dir, sticker.file)

    fun builtinReady(items: List<Sticker>) = prefs.getInt("stickerPackVersion", 0) >= StickerSpec.PACK_VERSION && items.any { it.builtin }

    fun installBuiltin(current: List<Sticker>, made: List<Pair<StickerSpec, ByteArray>>): List<Sticker> {
        if (made.isEmpty()) throw AppException("没有生成任何表情。")
        current.filter { it.builtin }.forEach { File(dir, it.file).delete() }
        val fresh = made.map { (spec, png) ->
            val name = "builtin-" + UUID.randomUUID() + ".png"
            File(dir, name).writeBytes(png)
            Sticker(spec.id, spec.caption, name, builtin = true)
        }
        val items = fresh + current.filter { !it.builtin }
        save(items)
        prefs.edit().putInt("stickerPackVersion", StickerSpec.PACK_VERSION).apply()
        return items
    }

    fun addUser(current: List<Sticker>, bytes: ByteArray): List<Sticker> {
        val bitmap = Media.decode(bytes, 480) ?: throw AppException("这张图片无法作为表情。")
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        val short = UUID.randomUUID().toString().take(6)
        val name = "user-$short-" + UUID.randomUUID() + ".png"
        File(dir, name).writeBytes(stream.toByteArray())
        val number = current.count { !it.builtin } + 1
        val items = current + Sticker("u$short", "表情$number", name, builtin = false)
        save(items)
        return items
    }

    fun rename(current: List<Sticker>, sticker: Sticker, title: String): List<Sticker> {
        val value = title.trim()
        if (value.isEmpty() || value.length > 12) throw AppException("表情名称需为 1–12 个字。")
        val items = current.map { if (it.id == sticker.id) it.copy(title = value) else it }
        save(items)
        return items
    }

    fun delete(current: List<Sticker>, sticker: Sticker): List<Sticker> {
        File(dir, sticker.file).delete()
        val items = current.filterNot { it.id == sticker.id }
        save(items)
        return items
    }

    companion object {
        /** Ids and meanings handed to the model, e.g. "在呢=在呢♡". */
        fun promptList(items: List<Sticker>) = items.map { if (it.id == it.title) it.id else "${it.id}=${it.title}" }

        /** Resolves an id written by the model: exact id, "id=title", or just the title. */
        fun resolve(items: List<Sticker>, raw: String?): Sticker? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            val candidates = listOf(value, value.substringBefore("="), value.substringAfterLast("=")).map { it.trim() }
            for (key in candidates) items.firstOrNull { it.id == key }?.let { return it }
            for (key in candidates) items.firstOrNull { it.title == key }?.let { return it }
            return null
        }

        /** A built-in sticker that fits her emotion, for when the model forgot to choose one. */
        fun fallback(items: List<Sticker>, emotion: String?): Sticker? {
            val wanted = when (emotion) {
                "happy" -> listOf("嘿嘿", "在呢", "嘻嘻")
                "excited" -> listOf("绝了", "嘿嘿", "收到")
                "shy" -> listOf("害羞", "爱你")
                "angry" -> listOf("哼")
                "surprised" -> listOf("绝了", "慌了")
                "smug" -> listOf("就这", "学霸模式")
                "confused" -> listOf("干嘛", "思考中", "晕了")
                "sad" -> listOf("呜呜")
                else -> listOf("在呢", "嘻嘻", "摸鱼中", "收到")
            }
            for (id in wanted.shuffled()) items.firstOrNull { it.id == id }?.let { return it }
            return items.filter { it.builtin }.randomOrNull() ?: items.randomOrNull()
        }
    }
}

// MARK: - Studio: poses the built-in model in a small WebView and captures the pack

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun StickerStudio(onProgress: (Int) -> Unit, onFinish: (List<Pair<StickerSpec, ByteArray>>, String?) -> Unit, modifier: Modifier = Modifier) {
    val progress by rememberUpdatedState(onProgress)
    val finish by rememberUpdatedState(onFinish)
    var web by remember { mutableStateOf<WebView?>(null) }
    AndroidView(modifier = modifier, factory = { context ->
        createStudio(context, onProgress = { progress(it) }, onFinish = { made, error -> finish(made, error) }).also { web = it }
    })
    DisposableEffect(web) {
        onDispose {
            web?.let {
                it.stopLoading()
                it.removeJavascriptInterface("AndroidStage")
                it.destroy()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun createStudio(context: Context, onProgress: (Int) -> Unit, onFinish: (List<Pair<StickerSpec, ByteArray>>, String?) -> Unit): WebView {
    val main = Handler(Looper.getMainLooper())
    val made = ArrayList<Pair<StickerSpec, ByteArray>>()
    // One worker thread: messages (ready → sticker… → done) are handled strictly in order.
    val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    var finished = false
    fun done(error: String?) {
        if (finished) return
        finished = true
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        onFinish(made.toList(), error)
    }
    val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    val view = WebView(context)
    view.apply {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        addJavascriptInterface(object {
            @JavascriptInterface
            fun post(message: String) {
                val body = runCatching { JSONObject(message) }.getOrNull() ?: return
                // Decoding and composing are heavy: do them off the UI thread, report on it.
                if (worker.isShutdown) return
                worker.execute {
                    when (body.optString("studio")) {
                        "ready" -> {
                            val payload = JSONArray(StickerSpec.pack.map { spec ->
                                JSONObject().put("id", spec.id).put("params", JSONObject(spec.params))
                            })
                            main.post { view.evaluateJavascript("window.runStickerStudio($payload)", null) }
                        }
                        "sticker" -> {
                            val spec = StickerSpec.pack.firstOrNull { it.id == body.optString("id") }
                            val data = body.optString("data").substringAfter(",", "")
                            val png = runCatching {
                                val raw = Base64.decode(data, Base64.DEFAULT)
                                val figure = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                                StickerComposer.compose(figure, spec!!).also { figure.recycle() }
                            }.getOrNull()
                            if (spec != null && png != null) main.post {
                                if (!finished) { made.add(spec to png); onProgress(made.size) }
                            }
                        }
                        "done" -> main.post { done(if (made.isEmpty()) "没有生成任何表情。" else null) }
                        else -> main.post { done("表情生成失败：" + body.optString("message", "未知错误")) }
                    }
                }
            }
        }, "AndroidStage")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                main.post { done("生成时内存不足，请关掉其他 App 后重试。") }
                return true
            }
        }
        loadUrl("https://appassets.androidplatform.net/assets/AvatarStage/index.html?studio=1")
    }
    main.postDelayed({ done("生成超时了，请重试。") }, 120_000)
    return view
}
