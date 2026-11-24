package com.feiyu.companion

import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Client for the "肥鱼伴侣 · DSH 电脑桥接" service running next to DeepSeek Harness on the user's computer. */
object DshBridge {
    const val TOKEN_KEY = "dshBridge"

    data class Health(val bridge: String?, val dsh: String?, val busy: Boolean, val workdir: String?, val computer: String?)
    data class Result(val text: String, val sessionId: String?, val seconds: Int?)

    // Long tasks: DSH can run for minutes between events.
    private val client: OkHttpClient = Net.client.newBuilder().readTimeout(10, TimeUnit.MINUTES).build()

    /** Accepts "192.168.1.10", "192.168.1.10:3210" or a full http(s) URL. */
    fun endpoint(raw: String): HttpUrl {
        var value = raw.trim()
        if (value.isEmpty()) throw AppException("请先填写电脑地址。")
        if (!value.lowercase().startsWith("http://") && !value.lowercase().startsWith("https://")) value = "http://$value"
        val parsed = value.toHttpUrlOrNull() ?: throw AppException("电脑地址格式不对，例如 http://192.168.1.10:3210")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) throw AppException("电脑地址格式不对，例如 http://192.168.1.10:3210")
        // No explicit port in the text → the bridge's default 3210.
        val hasPort = Regex("^https?://[^/]+:\\d+", RegexOption.IGNORE_CASE).containsMatchIn(value)
        var path = parsed.encodedPath.trimEnd('/')
        for (suffix in listOf("/v1/chat", "/v1/health", "/v1")) if (path.endsWith(suffix)) { path = path.dropLast(suffix.length); break }
        return parsed.newBuilder().port(if (hasPort) parsed.port else 3210).encodedPath(path.ifEmpty { "/" }).query(null).fragment(null).build()
    }

    private fun url(base: HttpUrl, path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()

    private fun explain(error: Throwable): Throwable = when (error) {
        is ConnectException, is NoRouteToHostException, is SocketTimeoutException, is UnknownHostException ->
            AppException("连不上电脑：请确认桥接窗口开着、手机和电脑在同一 Wi-Fi、防火墙已放行 3210 端口。")
        is IOException -> if (error.message?.contains("CLEARTEXT", ignoreCase = true) == true)
            AppException("系统阻止了 http 连接，请改用局域网 IP 地址（例如 http://192.168.1.10:3210）。") else error
        else -> error
    }

    private fun failure(status: Int, body: String): String {
        val detail = runCatching { JSONObject(body).str("error") }.getOrNull()
        return when (status) {
            401 -> "连接令牌不对，请对照电脑上桥接窗口显示的令牌。"
            409 -> detail ?: "DSH 正在处理上一个任务，请稍后再试。"
            429 -> detail ?: "令牌错误次数太多，请 1 分钟后再试。"
            else -> detail ?: "桥接服务返回错误（HTTP $status）。"
        }
    }

    suspend fun health(address: String, token: String): Health {
        val base = endpoint(address)
        val key = token.trim()
        if (key.isEmpty()) throw AppException("请先填写连接令牌。")
        val request = Request.Builder().url(url(base, "v1/health")).header("X-Bridge-Token", key).get().build()
        try {
            return Net.run(request) { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw AppException(failure(response.code, body))
                val o = runCatching { JSONObject(body) }.getOrNull()
                if (o == null || !o.optBoolean("ok")) throw AppException("这个地址不是肥鱼伴侣的 DSH 桥接服务。")
                Health(o.str("bridge"), o.str("dsh"), o.optBoolean("busy"), o.str("workdir"), o.str("computer"))
            }
        } catch (error: Throwable) { throw explain(error) }
    }

    /** Sends one task and streams progress. `onStatus` gets short progress lines, `onText` the answer so far. */
    suspend fun chat(message: String, address: String, token: String, sessionId: String?,
                     onStatus: suspend (String) -> Unit, onText: suspend (String) -> Unit): Result {
        val base = endpoint(address)
        val key = token.trim()
        if (key.isEmpty()) throw AppException("DSH 模式需要连接令牌，请在设置里填写。")
        val body = JSONObject().put("message", message)
        sessionId?.let { body.put("sessionId", it) }
        val request = Request.Builder().url(url(base, "v1/chat")).header("X-Bridge-Token", key)
            .header("Accept", "text/event-stream").post(Net.json(body)).build()
        try {
            return Net.run(request, client) { response ->
                if (!response.isSuccessful) throw AppException(failure(response.code, response.body?.string().orEmpty()))
                val source = response.body!!.source()
                val text = StringBuilder()
                var session = sessionId
                var seconds: Int? = null
                var finished = false
                var lastShown = 0L
                while (true) {
                    ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val event = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    when (event.optString("type")) {
                        "status" -> event.str("text")?.let { onStatus(it.take(80)) }
                        "delta" -> {
                            text.append(event.optString("text"))
                            val now = System.currentTimeMillis()
                            if (now - lastShown > 80) { lastShown = now; onText(text.toString()) }
                        }
                        "session" -> event.str("id")?.let { session = it }
                        "done" -> {
                            finished = true
                            event.str("text")?.let { text.setLength(0); text.append(it) }
                            event.str("sessionId")?.let { session = it }
                            if (event.has("seconds")) seconds = event.optInt("seconds")
                        }
                        "error" -> throw AppException("DSH：" + (event.str("message") ?: "执行失败"))
                    }
                }
                val answer = text.toString().trim()
                if (!finished && answer.isEmpty()) throw AppException("DSH 没有返回结果，连接可能中断了。")
                onText(answer)
                Result(answer.ifEmpty { "（DSH 执行完成，没有输出文字）" }, session, seconds)
            }
        } catch (error: Throwable) { throw explain(error) }
    }

    /** Plain text suitable for TTS: no code blocks, links or markdown symbols, at most ~260 characters. */
    fun speakable(text: String): String {
        var value = text.replace(Regex("```[\\s\\S]*?```"), "（代码略）")
        value = value.replace(Regex("`[^`]*`"), "")
        value = value.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        value = value.replace(Regex("https?://\\S+"), "链接")
        value = value.replace(Regex("[#*_>|~-]+"), " ")
        value = value.replace(Regex("\\s+"), " ").trim()
        if (value.length <= 260) return value
        val cut = value.take(260)
        val end = cut.indexOfLast { it in "。！？!?；;" }
        return if (end >= 0) cut.substring(0, end + 1) + "详细内容在聊天里。" else "$cut……详细内容在聊天里。"
    }
}
