package com.feiyu.companion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Speaks a reply with as little delay as possible: the first sentence goes to the voice service while the text
 * model is still writing, later sentences are synthesized ahead and played strictly in order.
 * All public methods are called on the main thread.
 */
class SpeechPipeline(
    private val scope: CoroutineScope,
    private val player: PcmPlayer,
    private val target: VoiceTarget,
    private val key: String,
    private val audioDir: File,
    private var emotion: String? = null,
) {
    private class Piece(val text: String) {
        val audio = Channel<ByteArray>(Channel.UNLIMITED)
        @Volatile var sent = false
    }

    private val mimoClone = target.provider == VoiceProvider.MIMO && target.clone
    private val chunker = SpeechChunker()
    private val pieces = Channel<Piece>(Channel.UNLIMITED)
    private val permits: Semaphore
    private val jobs = ArrayList<Job>()
    private val collected = PcmCollector()
    private val result = CompletableDeferred<File?>()
    private var closed = false
    private var cancelled = false
    private val consumer: Job

    var started = false; private set
    var error: Throwable? = null; private set
    var failedPieces = 0; private set
    var lastText = ""; private set
    var onStart: (() -> Unit)? = null

    init {
        // MiMo clones: one request at a time and short-ish pieces (long text gets cut off).
        // Breeze plans allow few concurrent generations.
        val parallel = if (mimoClone || target.provider == VoiceProvider.BREEZE) 1 else 2
        permits = Semaphore(parallel)
        if (mimoClone) { chunker.firstMinimum = 8; chunker.laterMinimum = 22; chunker.laterLimit = 56 }
        consumer = scope.launch { consume() }
    }

    fun setEmotion(value: String) { if (emotion == null) emotion = value }

    fun update(text: String) {
        if (closed || cancelled) return
        lastText = text
        chunker.take(text, final = false).forEach(::enqueue)
    }

    fun finish(text: String, extra: List<String>) {
        if (closed || cancelled) return
        val followUps = extra.map { it.trim() }.filter { it.isNotEmpty() }
        val whole = if (followUps.isEmpty()) text else text + "\n" + followUps.joinToString("\n")
        chunker.take(whole, final = true).forEach(::enqueue)
        closed = true
        pieces.close()
    }

    suspend fun await(): File? = result.await()

    fun cancel(stopAudio: Boolean = true) {
        if (cancelled) return
        cancelled = true
        jobs.forEach { it.cancel() }
        pieces.close()
        consumer.cancel()
        if (stopAudio && started) player.stop()
        result.complete(null)
    }

    private fun enqueue(raw: String) {
        val text = ReplyText.cleanNarration(raw)
        if (SpeechChunker.speakable(text) == 0) return
        val piece = Piece(text)
        pieces.trySend(piece)
        val mood = emotion
        jobs += scope.launch {
            try {
                permits.withPermit {
                    var failure: Throwable? = null
                    val waits = longArrayOf(700, 1_600, 3_200)
                    for (attempt in 0..waits.size) {
                        try {
                            if (mimoClone) {
                                val data = completeClone(piece.text, mood, 0)
                                piece.sent = true
                                piece.audio.send(data)
                            } else {
                                Tts.stream(piece.text, target, mood, key) { chunk -> piece.sent = true; piece.audio.send(chunk) }
                            }
                            failure = null
                            break
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (problem: Throwable) {
                            failure = problem
                            // Retry only if nothing of this piece reached the speaker (otherwise it would repeat).
                            if (attempt == waits.size || piece.sent) break
                            delay(waits[attempt])
                        }
                    }
                    if (failure != null) {
                        if (error == null) error = failure
                        failedPieces++
                    }
                }
            } finally {
                piece.audio.close()
            }
        }
    }

    private suspend fun consume() {
        try {
            for (piece in pieces) {
                for (chunk in piece.audio) {
                    if (cancelled) return
                    if (!started) {
                        player.begin()
                        started = true
                        onStart?.invoke()
                    } else if (!player.isPlaying) {
                        // The user stopped playback (or started talking): stop synthesizing too.
                        cancel(stopAudio = false)
                        return
                    }
                    collected.add(chunk)
                    player.write(chunk)
                }
            }
            if (started) player.finish()
            val file = if (collected.size > 4_800) withContext(Dispatchers.IO) {
                runCatching { File(audioDir, UUID.randomUUID().toString() + ".wav").apply { writeBytes(Wav.make(collected.bytes())) } }.getOrNull()
            } else null
            result.complete(file)
        } finally {
            if (!result.isCompleted) result.complete(null)
        }
    }

    /** MiMo clones arrive in one piece: check it is as long as the text needs, otherwise split and redo. */
    private suspend fun completeClone(text: String, mood: String?, depth: Int): ByteArray {
        val box = PcmCollector()
        val reason = Tts.stream(text, target, mood, key) { box.add(it) }
        val seconds = box.size / 48_000.0
        val characters = SpeechChunker.speakable(text)
        val truncated = reason == "length" || (characters >= 6 && seconds < characters * 0.12)
        if (truncated && depth < 2) {
            halves(text)?.let { (first, second) ->
                return completeClone(first, mood, depth + 1) + completeClone(second, mood, depth + 1)
            }
        }
        if (box.size == 0) throw AppException("MiMo 没有返回可播放的语音。")
        return box.bytes()
    }

    private fun halves(text: String): Pair<String, String>? {
        if (SpeechChunker.speakable(text) < 6 || text.length < 4) return null
        val middle = text.length / 2
        val marks = setOf('。', '！', '？', '!', '?', '；', ';', '，', ',', '、', '…', '～', '~', '：', ' ')
        var best: Int? = null
        loop@ for (distance in 0 until text.length / 2) {
            for (index in intArrayOf(middle + distance, middle - distance)) {
                if (index > 1 && index < text.length - 1 && text[index] in marks) { best = index + 1; break@loop }
            }
        }
        val cut = best ?: middle
        val first = text.substring(0, cut).trim()
        val second = text.substring(cut).trim()
        if (SpeechChunker.speakable(first) == 0 || SpeechChunker.speakable(second) == 0) return null
        return first to second
    }
}

/** System speech recognition (zh-CN). Phones without a recognition service get a clear message instead. */
class Listener(private val context: Context) {
    var transcript by mutableStateOf("")
        private set
    var listening by mutableStateOf(false)
        private set
    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(onFinal: (String) -> Unit, onError: (String) -> Unit) {
        cancel()
        if (!available()) {
            onError("这台手机没有可用的系统语音识别服务。可以在键盘上用语音输入（如搜狗、百度、讯飞输入法的麦克风），说完点发送。")
            return
        }
        val engine = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = engine
        transcript = ""
        var delivered = false
        fun deliver(text: String) {
            if (delivered) return
            delivered = true
            listening = false
            engine.destroy()
            if (recognizer === engine) recognizer = null
            onFinal(text.trim())
        }
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) transcript = it }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                deliver(if (text.isNullOrBlank()) transcript else text)
            }
            override fun onError(error: Int) {
                if (delivered) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> deliver(transcript)
                    else -> {
                        delivered = true
                        listening = false
                        engine.destroy()
                        if (recognizer === engine) recognizer = null
                        onError(describe(error))
                    }
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_400L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        }
        listening = true
        runCatching { engine.startListening(intent) }.onFailure {
            listening = false
            engine.destroy()
            recognizer = null
            onError("无法启动语音识别：" + (it.message ?: "未知错误"))
        }
    }

    /** Stop now and use what was heard. */
    fun stop() { recognizer?.stopListening() }

    fun cancel() {
        recognizer?.let { runCatching { it.cancel() }; it.destroy() }
        recognizer = null
        listening = false
    }

    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限才能听你说话，请在系统设置里允许。"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别需要联网，请检查网络后再试。"
        SpeechRecognizer.ERROR_AUDIO -> "麦克风被占用或录音失败。"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，请稍后再试。"
        SpeechRecognizer.ERROR_CLIENT -> "语音识别被中断了。"
        SpeechRecognizer.ERROR_SERVER -> "语音识别服务出错，请稍后再试。"
        else -> "语音识别失败（$code）。"
    }
}
