package com.feiyu.companion

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.min
import kotlin.math.sqrt

const val SAMPLE_RATE = 24_000

// MARK: - WAV helpers (24 kHz mono PCM16 everywhere, like the iPhone app)

object Wav {
    class Pcm(val sampleRate: Int, val channels: Int, val bits: Int, val data: ByteArray)

    fun make(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE, channels: Int = 1): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(36 + pcm.size); header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()); header.putInt(16); header.putShort(1); header.putShort(channels.toShort())
        header.putInt(sampleRate); header.putInt(sampleRate * channels * 2); header.putShort((channels * 2).toShort()); header.putShort(16)
        header.put("data".toByteArray()); header.putInt(pcm.size)
        return header.array() + pcm
    }

    fun isWav(data: ByteArray) = data.size > 12 && String(data, 0, 4) == "RIFF" && String(data, 8, 4) == "WAVE"

    fun parse(data: ByteArray): Pcm? {
        if (!isWav(data)) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var rate = 0; var channels = 0; var bits = 0
        while (offset + 8 <= data.size) {
            val id = String(data, offset, 4)
            val size = buffer.getInt(offset + 4)
            val body = offset + 8
            if (id == "fmt " && body + 16 <= data.size) {
                channels = buffer.getShort(body + 2).toInt(); rate = buffer.getInt(body + 4); bits = buffer.getShort(body + 14).toInt()
            } else if (id == "data") {
                if (rate <= 0) return null
                val end = if (size < 0) data.size else min(data.size, body + size)
                return Pcm(rate, channels, bits, data.copyOfRange(body, maxOf(body, end)))
            }
            if (size < 0) return null
            offset = body + size + (size and 1)
        }
        return null
    }

    /** Raw PCM payload: strips a WAV header when the server sent one. */
    fun payload(audio: ByteArray): ByteArray = if (audio.size > 44 && isWav(audio)) parse(audio)?.data ?: audio else audio

    fun shorts(pcm: ByteArray): ShortArray {
        val out = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm, 0, out.size * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    fun bytes(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(samples)
        return buffer.array()
    }

    fun resample(samples: ShortArray, from: Int, to: Int = SAMPLE_RATE): ShortArray {
        if (from == to || samples.isEmpty() || from <= 0) return samples
        val count = (samples.size.toLong() * to / from).toInt()
        val out = ShortArray(count)
        val step = from.toDouble() / to
        for (i in 0 until count) {
            val position = i * step
            val index = position.toInt()
            val next = min(index + 1, samples.size - 1)
            val fraction = position - index
            out[i] = (samples[index] * (1 - fraction) + samples[next] * fraction).toInt().toShort()
        }
        return out
    }

    /** Drops leading/trailing silence and keeps at most `maxSeconds`, preferring the densest speech. */
    fun trimReference(samples: ShortArray, sampleRate: Int, maxSeconds: Double): ShortArray {
        val frame = maxOf(1, sampleRate / 50)
        val frames = samples.size / frame
        if (frames <= 25) return samples
        val energy = DoubleArray(frames) { index ->
            var sum = 0.0
            for (j in index * frame until (index + 1) * frame) { val v = samples[j].toDouble(); sum += v * v }
            sqrt(sum / frame)
        }
        val peak = energy.maxOrNull() ?: 0.0
        val threshold = maxOf(250.0, peak * 0.06)
        val first = energy.indexOfFirst { it > threshold }
        val last = energy.indexOfLast { it > threshold }
        if (peak <= 0 || first < 0 || last < 0) return samples
        val start = maxOf(0, first - 10)
        val end = min(frames, last + 15)
        val limit = (maxSeconds * 50).toInt()
        if (end - start > limit) {
            var voiced = (start until start + limit).count { energy[it] > threshold }
            var bestStart = start; var bestVoiced = voiced; var window = start
            while (window + limit < end) {
                if (energy[window] > threshold) voiced--
                if (energy[window + limit] > threshold) voiced++
                window++
                if (voiced > bestVoiced) { bestVoiced = voiced; bestStart = window }
            }
            var cutStart = bestStart
            for (index in bestStart downTo maxOf(start, bestStart - 25) + 1) if (energy[index] < threshold) { cutStart = index; break }
            val windowEnd = min(end, cutStart + limit)
            var best = windowEnd; var quietest = Double.MAX_VALUE
            for (index in maxOf(cutStart + 1, windowEnd - 100) until windowEnd) if (energy[index] < quietest) { quietest = energy[index]; best = index }
            return samples.copyOfRange(cutStart * frame, min(samples.size, best * frame))
        }
        val lower = start * frame
        val upper = min(samples.size, end * frame)
        if (upper - lower < sampleRate) return samples
        return samples.copyOfRange(lower, upper)
    }
}

/** Decodes any audio file the phone can play (m4a, mp3, wav, aac, ogg, flac…) to 24 kHz mono PCM16. */
object AudioDecoder {
    fun decode(file: File, maxSeconds: Double = 130.0): ShortArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AppException("这个文件里没有音频。")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw AppException("无法识别音频格式。")
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = ShortArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val changed = codec.outputFormat
                            rate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            isFloat = changed.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                changed.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val ordered = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                                val ch = maxOf(1, channels)
                                if (isFloat) {
                                    val floats = ordered.asFloatBuffer()
                                    while (floats.remaining() >= ch) {
                                        var sum = 0f
                                        repeat(ch) { sum += floats.get() }
                                        out.add(((sum / ch).coerceIn(-1f, 1f) * 32767).toInt().toShort())
                                    }
                                } else {
                                    val shorts = ordered.asShortBuffer()
                                    while (shorts.remaining() >= ch) {
                                        var sum = 0
                                        repeat(ch) { sum += shorts.get() }
                                        out.add((sum / ch).toShort())
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                            if (rate > 0 && out.size > rate * maxSeconds) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            val samples = out.toArray()
            if (samples.isEmpty()) throw AppException("没能解码出声音，请换 M4A、MP3 或 WAV 文件。")
            return Wav.resample(samples, rate)
        } finally {
            extractor.release()
        }
    }

    /** A clean clone reference: decoded, trimmed to the densest `maxSeconds` of speech, as WAV bytes. */
    fun reference(file: File, maxSeconds: Double): Pair<ByteArray, Double> {
        val samples = Wav.trimReference(decode(file), SAMPLE_RATE, maxSeconds)
        val seconds = samples.size.toDouble() / SAMPLE_RATE
        if (seconds < 3) throw AppException("有效人声不足 3 秒，请换一段更长、更清晰的录音。")
        return Wav.make(Wav.bytes(samples)) to seconds
    }
}

class ShortArrayBuilder {
    private var data = ShortArray(48_000)
    var size = 0; private set
    fun add(value: Short) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }
    fun toArray(): ShortArray = data.copyOf(size)
}

// MARK: - Streaming player with lip-sync levels

/** Plays 24 kHz mono PCM16 as it arrives and exposes a mouth-open level synced to what is actually audible. */
class PcmPlayer {
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _mouth = MutableStateFlow(0f)
    val mouth: StateFlow<Float> = _mouth

    private val main = Handler(Looper.getMainLooper())
    private val end = ByteArray(0)
    private var queue = LinkedBlockingQueue<ByteArray>()
    private var track: AudioTrack? = null
    private var writer: Thread? = null
    @Volatile private var generation = 0
    private val levels = ArrayDeque<Pair<Long, Float>>()
    private var smoothed = 0f

    val isPlaying get() = _playing.value

    suspend fun awaitIdle() { playing.first { !it } }

    fun begin() {
        stop()
        val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, SAMPLE_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val mine = ++generation
        val pending = LinkedBlockingQueue<ByteArray>()
        queue = pending
        track = audio
        synchronized(levels) { levels.clear() }
        audio.play()
        _playing.value = true
        writer = Thread({ writeLoop(audio, pending, mine, minimum) }, "feiyu-voice").apply { start() }
        main.post(ticker)
    }

    fun write(pcm: ByteArray) {
        if (track != null && pcm.isNotEmpty()) queue.put(pcm)
    }

    fun finish() { if (track != null) queue.put(end) }

    fun stop() {
        generation++
        main.removeCallbacks(ticker)
        writer?.interrupt()
        writer = null
        track?.let { audio ->
            runCatching { audio.pause(); audio.flush(); audio.stop() }
            audio.release()
        }
        track = null
        queue.clear()
        synchronized(levels) { levels.clear() }
        smoothed = 0f
        _mouth.value = 0f
        _playing.value = false
    }

    /** Plays a saved WAV (replay). */
    fun playWav(bytes: ByteArray) {
        val pcm = Wav.parse(bytes) ?: throw AppException("这条语音文件已损坏。")
        var samples = Wav.shorts(pcm.data)
        if (pcm.channels == 2) samples = ShortArray(samples.size / 2) { ((samples[it * 2] + samples[it * 2 + 1]) / 2).toShort() }
        samples = Wav.resample(samples, pcm.sampleRate)
        begin()
        write(Wav.bytes(samples))
        finish()
    }

    private fun writeLoop(audio: AudioTrack, pending: LinkedBlockingQueue<ByteArray>, mine: Int, minimum: Int) {
        var written = 0L
        var carry: Byte? = null
        try {
            while (mine == generation) {
                val chunk = pending.take()
                if (chunk === end) break
                var data = chunk
                carry?.let { data = byteArrayOf(it) + chunk; carry = null }
                if (data.size % 2 == 1) { carry = data.last(); data = data.copyOf(data.size - 1) }
                if (data.isEmpty()) continue
                record(data, written)
                var offset = 0
                while (offset < data.size && mine == generation) {
                    val n = audio.write(data, offset, data.size - offset)
                    if (n <= 0) break
                    offset += n
                }
                written += data.size / 2
            }
            if (mine == generation) {
                // Push the tail out of the buffer, then wait until it has been heard.
                audio.write(ByteArray(minimum), 0, minimum)
                val deadline = System.currentTimeMillis() + written * 1000 / SAMPLE_RATE + 4_000
                while (mine == generation && (audio.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < written &&
                    System.currentTimeMillis() < deadline) Thread.sleep(25)
            }
        } catch (_: InterruptedException) {
        } catch (_: IllegalStateException) {
        } finally {
            main.post { if (mine == generation) stop() }
        }
    }

    private fun record(data: ByteArray, startFrame: Long) {
        val samples = Wav.shorts(data)
        val window = 400
        var index = 0
        val entries = ArrayList<Pair<Long, Float>>()
        while (index < samples.size) {
            val upper = min(samples.size, index + window)
            var sum = 0.0
            for (i in index until upper) { val v = samples[i] / 32768.0; sum += v * v }
            val rms = sqrt(sum / (upper - index))
            val level = ((rms - 0.012) * 7.5).coerceIn(0.0, 1.0).toFloat()
            entries.add(startFrame + upper to level)
            index = upper
        }
        synchronized(levels) { levels.addAll(entries) }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val audio = track ?: return
            val head = runCatching { audio.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
            var target = 0f
            synchronized(levels) {
                while (levels.size > 1 && levels.first().first < head) levels.removeFirst()
                levels.firstOrNull()?.let { if (it.first - head < 2_400) target = it.second }
            }
            smoothed = if (target > smoothed) smoothed * 0.35f + target * 0.65f else smoothed * 0.6f + target * 0.4f
            _mouth.value = if (smoothed < 0.02f) 0f else smoothed
            main.postDelayed(this, 33)
        }
    }
}

/** Collects PCM for saving the whole utterance as one WAV (replay later). */
class PcmCollector {
    private val buffer = ByteArrayOutputStream()
    val size get() = buffer.size()
    fun add(data: ByteArray) { if (buffer.size() < 40_000_000) buffer.write(data) }
    fun bytes(): ByteArray = buffer.toByteArray()
}
