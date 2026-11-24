package com.feiyu.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.math.abs

enum class StageStatus { LOADING, READY, FAILED }

/** One-off gesture for the stage (wave, happy, surprised…). A new token triggers it once. */
data class StageReaction(val kind: String, val token: String = newId())

/**
 * The same Live2D stage as on iPhone (pixi + cubism4 + stage.js/director.js), served from the APK's assets.
 * Sizes are CSS px, which equal dp in the WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AvatarStage(
    mouth: Float,
    expression: String,
    state: String,
    reaction: StageReaction?,
    topInset: Float,
    bottomInset: Float,
    onTouch: (String) -> Unit,
    onStatus: (StageStatus) -> Unit,
    modifier: Modifier = Modifier,
    /** File name of an imported .model3.json (files/models/custom), or null for 大肥鱼. */
    modelFile: String? = null,
) {
    val context = LocalContext.current
    val touch by rememberUpdatedState(onTouch)
    val status by rememberUpdatedState(onStatus)
    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            createStage(ctx, modelFile,
                onReady = { ok -> ready = ok; status(if (ok) StageStatus.READY else StageStatus.FAILED) },
                onTouch = { zone -> touch(zone) },
            ).also { web = it }
        },
    )

    LaunchedEffect(web) {
        status(StageStatus.LOADING)
        delay(20_000)
        if (!ready) status(StageStatus.FAILED)
    }

    LaunchedEffect(ready, mouth, expression, state, reaction?.token, topInset, bottomInset) {
        if (!ready) return@LaunchedEffect
        val args = JSONArray()
            .put(mouth.toDouble()).put(expression).put(false).put(topInset.toDouble()).put(bottomInset.toDouble())
            .put(reaction?.kind ?: "").put(reaction?.token ?: "").put(0.0).put(state)
        web?.evaluateJavascript("window.updateAvatar&&window.updateAvatar.apply(null,$args)", null)
    }

    DisposableEffect(web) {
        val view = web
        val tilt = if (view != null) DeviceTilt(context) { x, y ->
            if (ready) view.evaluateJavascript("window.setAvatarTilt&&window.setAvatarTilt(%.3f,%.3f)".format(java.util.Locale.US, x, y), null)
        } else null
        tilt?.start()
        onDispose {
            tilt?.stop()
            view?.let {
                it.stopLoading()
                it.removeJavascriptInterface("AndroidStage")
                it.destroy()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
private fun createStage(context: Context, modelFile: String?, onReady: (Boolean) -> Unit, onTouch: (String) -> Unit): WebView {
    val main = Handler(Looper.getMainLooper())
    val models = java.io.File(context.filesDir, "models").apply { mkdirs() }
    // Imported models are served from app storage at the path stage.js expects (../Models/<id>/<file>).
    val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/Models/", WebViewAssetLoader.InternalStoragePathHandler(context, models))
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    return WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.setSupportZoom(false)
        addJavascriptInterface(object {
            @JavascriptInterface
            fun post(message: String) {
                val value = runCatching { JSONTokener(message).nextValue() }.getOrNull()
                main.post {
                    when (value) {
                        is String -> onReady(value == "ready")
                        is JSONObject -> value.str("touchZone")?.takeIf { it in setOf("head", "cheek", "body") }?.let(onTouch)
                    }
                }
            }
        }, "AndroidStage")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != "appassets.androidplatform.net"

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Never let a crashed renderer take the whole app down; the room shows a retry button.
                main.post { onReady(false) }
                return true
            }
        }
        val address = android.net.Uri.parse("https://appassets.androidplatform.net/assets/AvatarStage/index.html").buildUpon()
        if (modelFile != null && modelFile.endsWith(".model3.json") && !modelFile.contains('/')) {
            address.appendQueryParameter("model", ModelLibrary.ID).appendQueryParameter("file", modelFile)
        }
        loadUrl(address.build().toString())
    }
}

/** Phone tilt → her gaze and body (same scale as the iPhone: x roll, y relative to a normal reading angle, both -1…1). */
private class DeviceTilt(context: Context, private val push: (Double, Double) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var lastX = 9.0
    private var lastY = 9.0
    private var lastAt = 0L
    private var gx = 0.0
    private var gz = -5.9

    fun start() { sensor?.let { manager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
    fun stop() { manager?.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent) {
        // Android reports the opposite sign of iOS gravity; smooth raw accelerometer readings.
        gx = gx * 0.8 + event.values[0] * 0.2
        gz = gz * 0.8 + event.values[2] * 0.2
        val now = System.currentTimeMillis()
        if (now - lastAt < 66) return
        lastAt = now
        val x = (-gx / SensorManager.GRAVITY_EARTH / 0.45).coerceIn(-1.0, 1.0)
        val y = ((-gz / SensorManager.GRAVITY_EARTH + 0.6) / 0.4).coerceIn(-1.0, 1.0)
        if (abs(x - lastX) < 0.01 && abs(y - lastY) < 0.01) return
        lastX = x; lastY = y
        push(x, y)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
