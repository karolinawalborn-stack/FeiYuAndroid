package com.feiyu.companion

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cross-device sync, byte-compatible with the iPhone app (SyncArchive.swift / FolderSync.swift):
 * every device writes an encrypted `companion-<device>.pcsync` into a shared folder and merges everyone else's.
 * Records are last-writer-wins by modification time; deletions travel as records without a body.
 */
object Sync {
    const val ROLE = "deepseekChan"
    private const val REFERENCE_EPOCH = 978_307_200.0   // Swift Date: seconds since 2001-01-01
    private const val ITERATIONS = 210_000

    fun swiftTime(millis: Long): Double = millis / 1000.0 - REFERENCE_EPOCH
    fun millis(swift: Double): Long = ((swift + REFERENCE_EPOCH) * 1000).toLong()
    private fun now() = swiftTime(System.currentTimeMillis())

    fun upperUuid(id: String): String = runCatching { UUID.fromString(id).toString().uppercase() }
        .getOrElse { UUID.nameUUIDFromBytes(id.toByteArray()).toString().uppercase() }

    private fun isUuid(value: String) = runCatching { UUID.fromString(value) }.isSuccess && value.length == 36

    // MARK: Canonical JSON (sorted keys, plain numbers) so unchanged data encodes identically every time.

    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> out.append("null")
            is String -> quote(out, value)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value.toString())
            is Double -> out.append(if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else BigDecimal.valueOf(value).toPlainString())
            is Float -> write(out, value.toDouble())
            is Number -> out.append(value.toString())
            is JSONObject -> {
                out.append('{')
                value.keys().asSequence().toList().sorted().forEachIndexed { i, k ->
                    if (i > 0) out.append(','); quote(out, k); out.append(':'); write(out, value.opt(k))
                }
                out.append('}')
            }
            is JSONArray -> {
                out.append('[')
                for (i in 0 until value.length()) { if (i > 0) out.append(','); write(out, value.opt(i)) }
                out.append(']')
            }
            is Map<*, *> -> write(out, JSONObject(value))
            is List<*> -> write(out, JSONArray(value))
            else -> quote(out, value.toString())
        }
    }

    private fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c.code < 0x20 -> out.append(String.format("\\u%04x", c.code))
            else -> out.append(c)
        }
        out.append('"')
    }

    private fun canonical(bytes: ByteArray?): String? = bytes?.let { b ->
        runCatching { encode(JSONObject(String(b, Charsets.UTF_8))) }.getOrNull()
    }

    // MARK: Records

    data class Record(val key: String, val role: String, val kind: String, val created: Double, val modified: Double,
                      val device: String, val body: ByteArray?) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("key", key); put("role", role); put("kind", kind); put("created", created); put("modified", modified); put("device", device)
            body?.let { put("body", Base64.encodeToString(it, Base64.NO_WRAP)) }
        }

        companion object {
            fun from(o: JSONObject): Record = Record(
                o.getString("key"), o.getString("role"), o.getString("kind"), o.getDouble("created"), o.getDouble("modified"),
                o.getString("device"), o.str("body")?.let { Base64.decode(it, Base64.DEFAULT) })
        }
    }

    class Archive(val records: MutableMap<String, Record> = LinkedHashMap()) {
        fun toJson(): JSONObject = JSONObject().put("version", 1)
            .put("records", JSONObject().apply { records.forEach { (k, v) -> put(k, v.toJson()) } })

        fun bytes(): ByteArray = encode(toJson()).toByteArray(Charsets.UTF_8)

        fun copy() = Archive(LinkedHashMap(records))

        /** Writes or updates one record; unchanged content keeps its timestamp. */
        fun put(kind: String, id: String, body: JSONObject?, device: String, created: Double? = null) {
            val key = "$kind/$ROLE/$id"
            val bytes = body?.let { encode(it).toByteArray(Charsets.UTF_8) }
            val old = records[key]
            if (old != null && canonical(old.body) == canonical(bytes)) return
            val stamp = maxOf(now(), (old?.modified ?: 0.0) + 0.001)
            records[key] = Record(key, ROLE, kind, old?.created ?: created ?: stamp, stamp, device, bytes)
        }

        /** Snapshot of this phone's chat, memories and active persona. */
        fun capture(persona: PersonaPreset, conversation: Conversation, device: String, previouslySeen: Set<String>) {
            val present = HashSet<String>()
            put("persona", "profile", personaJson(persona), device)
            present += "persona/$ROLE/profile"
            for (message in conversation.messages) {
                if (message.text.isBlank() && message.images.isEmpty() && message.stickerId == null) continue
                val id = upperUuid(message.id)
                val body = messageJson(message, id)
                // Keep the other phone's more precise timestamp when it is the same moment (no endless rewrites).
                records["message/$ROLE/$id"]?.body?.let { old ->
                    val previous = runCatching { JSONObject(String(old, Charsets.UTF_8)).optDouble("createdAt") }.getOrNull()
                    if (previous != null && !previous.isNaN() && kotlin.math.abs(previous - body.getDouble("createdAt")) < 0.002) {
                        body.put("createdAt", previous)
                    }
                }
                put("message", id, body, device, swiftTime(message.createdAt))
                present += "message/$ROLE/$id"
            }
            for (memory in conversation.memories) {
                val id = upperUuid(memory.id)
                put("memory", id, JSONObject().put("id", id).put("text", memory.text).put("manual", memory.manual), device)
                present += "memory/$ROLE/$id"
            }
            // Gone since the last sync → deletion marker.
            for (key in previouslySeen) {
                if (key in present) continue
                val old = records[key] ?: continue
                if (old.body == null) continue
                records[key] = old.copy(body = null, modified = maxOf(now(), old.modified + 0.001), device = device)
            }
        }

        fun merge(other: Archive) {
            if (other.records.size > 100_000) throw AppException("同步文件版本或大小不受支持。")
            for ((key, record) in other.records) {
                val tokens = key.split("/")
                val roleOk = record.role == ROLE || record.role == "nari" || record.role == "yume" ||
                    (record.role.startsWith("custom-") && isUuid(record.role.removePrefix("custom-")))
                val idOk = if (record.kind == "persona") tokens.getOrNull(2) == "profile" else tokens.getOrNull(2)?.let(::isUuid) == true
                if (!roleOk || key != record.key || tokens.size != 3 || tokens[0] != record.kind || tokens[1] != record.role ||
                    record.kind !in setOf("persona", "message", "memory") || !isUuid(record.device) || !idOk ||
                    record.modified - now() >= 86_400 || (record.body?.size ?: 0) >= 2_000_000) {
                    throw AppException("同步文件包含无效记录。")
                }
                val old = records[key]
                if (old != null && (record.modified < old.modified || (record.modified == old.modified && record.device <= old.device))) continue
                records[key] = record
            }
        }

        /** Keys this phone shows (used to detect deletions next time). */
        fun liveKeys(): Set<String> = records.values.filter { it.role == ROLE && it.body != null }.map { it.key }.toSet()

        fun materialize(): Pair<PersonaPreset?, Conversation> {
            var persona: PersonaPreset? = null
            val messages = ArrayList<ChatMessage>()
            val memories = ArrayList<MemoryItem>()
            for (record in records.values.filter { it.role == ROLE && it.body != null }.sortedWith(compareBy({ it.created }, { it.key }))) {
                val o = runCatching { JSONObject(String(record.body!!, Charsets.UTF_8)) }.getOrNull() ?: continue
                when (record.kind) {
                    "persona" -> persona = PersonaPreset(id = "", title = "", name = o.optString("name", DefaultPersona.NAME),
                        profile = o.optString("profile"), greeting = o.optString("greeting", DefaultPersona.GREETING))
                    "message" -> messageFrom(o)?.let { messages.add(it) }
                    "memory" -> o.str("text")?.let { memories.add(MemoryItem(o.str("id") ?: newId(), it, o.optBoolean("manual"))) }
                }
            }
            messages.sortBy { it.createdAt }
            return persona to Conversation(messages = messages, memories = memories)
        }

        companion object {
            fun from(bytes: ByteArray): Archive {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                if (o.optInt("version") != 1) throw AppException("同步文件版本不受支持。")
                val records = o.optJSONObject("records") ?: JSONObject()
                val map = LinkedHashMap<String, Record>()
                for (key in records.keys()) map[key] = Record.from(records.getJSONObject(key))
                return Archive(map)
            }
        }
    }

    private fun personaJson(persona: PersonaPreset) = JSONObject()
        .put("name", persona.name.trim().ifEmpty { DefaultPersona.NAME }.take(40))
        .put("subtitle", DefaultPersona.SUBTITLE)
        .put("profile", persona.profile.trim().ifEmpty { DefaultPersona.PROFILE }.take(PersonaPreset.PROFILE_LIMIT))
        .put("greeting", persona.greeting.trim().ifEmpty { DefaultPersona.GREETING }.take(2_000))
        .put("appearance", ROLE)

    private fun messageJson(message: ChatMessage, id: String) = JSONObject().apply {
        put("id", id); put("role", if (message.isUser) "user" else "assistant"); put("text", message.text)
        put("createdAt", swiftTime(message.createdAt))
        put("images", JSONArray(message.images.map { image ->
            JSONObject().apply {
                put("id", upperUuid(image.id)); put("path", image.path)
                image.width?.let { put("width", it) }; image.height?.let { put("height", it) }; image.altText?.let { put("altText", it) }
            }
        }))
        message.stickerId?.let { put("stickerID", it) }
        message.audioFile?.let { put("audioFile", it) }
        message.audioDuration?.let { put("audioDuration", it) }
        message.emotion?.let { put("emotion", it) }
    }

    private fun messageFrom(o: JSONObject): ChatMessage? {
        val id = o.str("id") ?: return null
        val role = o.optString("role")
        if (role != "user" && role != "assistant") return null
        return ChatMessage(
            id = id, role = role, text = o.optString("text"), emotion = o.str("emotion"), audioFile = o.str("audioFile"),
            createdAt = if (o.has("createdAt")) millis(o.optDouble("createdAt")) else System.currentTimeMillis(),
            images = o.optJSONArray("images")?.mapObjects { MessageImage.from(it) } ?: emptyList(),
            stickerId = o.str("stickerID"),
            audioDuration = if (o.has("audioDuration")) o.optDouble("audioDuration") else null,
        )
    }

    // MARK: Encryption (PBKDF2-HMAC-SHA256 × 210 000 → AES-256-GCM, CryptoKit "combined" layout)

    private fun derive(password: String, salt: ByteArray): ByteArray {
        if (password.length < 8 || salt.size != 16) throw AppException("同步口令至少 8 个字符。")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        repeat(ITERATIONS - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        return t
    }

    fun seal(archive: Archive, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(password, salt), "AES"), GCMParameterSpec(128, nonce))
        val combined = nonce + cipher.doFinal(archive.bytes())
        val envelope = JSONObject().put("version", 1)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("sealed", Base64.encodeToString(combined, Base64.NO_WRAP))
        return encode(envelope).toByteArray(Charsets.UTF_8)
    }

    fun open(data: ByteArray, password: String): Archive {
        if (data.size >= 60_000_000) throw AppException("同步文件过大。")
        try {
            val envelope = JSONObject(String(data, Charsets.UTF_8))
            if (envelope.optInt("version") != 1) throw AppException("同步文件版本不受支持。")
            val salt = Base64.decode(envelope.getString("salt"), Base64.DEFAULT)
            val combined = Base64.decode(envelope.getString("sealed"), Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(password, salt), "AES"), GCMParameterSpec(128, combined, 0, 12))
            val plain = cipher.doFinal(combined, 12, combined.size - 12)
            return Archive().also { it.merge(Archive.from(plain)) }
        } catch (error: AppException) {
            throw if (error.message?.contains("版本") == true) error else AppException("同步口令不一致，或文件尚未完整下载。原有数据已保留。")
        } catch (_: Throwable) {
            throw AppException("同步口令不一致，或文件尚未完整下载。原有数据已保留。")
        }
    }

    // MARK: Where the files live

    interface Target {
        /** All `companion-*.pcsync` files in the shared place. */
        suspend fun read(): List<ByteArray>
        suspend fun write(name: String, data: ByteArray)
    }

    private fun isSyncFile(name: String) = name.startsWith("companion-") && name.endsWith(".pcsync")

    /** A folder picked with the system file picker (local folder kept in sync by a cloud-drive app, SD card…). */
    class FolderTarget(private val context: Context, private val tree: Uri) : Target {
        private fun root(): DocumentFile = DocumentFile.fromTreeUri(context, tree)?.takeIf { it.canRead() }
            ?: throw AppException("同步文件夹无法访问了，请重新选择。")

        override suspend fun read(): List<ByteArray> = withContext(Dispatchers.IO) {
            val files = root().listFiles().filter { it.isFile && isSyncFile(it.name.orEmpty()) }
            if (files.size > 100) throw AppException("同步设备文件过多，请使用专用同步文件夹。")
            files.map { file ->
                if (file.length() >= 60_000_000) throw AppException("同步文件不受支持。")
                context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: throw AppException("云端文件还没有准备好，请稍后重试。")
            }
        }

        override suspend fun write(name: String, data: ByteArray) = withContext(Dispatchers.IO) {
            val folder = root()
            val file = folder.findFile(name) ?: folder.createFile("application/octet-stream", name)
                ?: throw AppException("无法在同步文件夹里写入文件。")
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(data) } ?: throw AppException("无法写入同步文件。")
            Unit
        }
    }

    /** WebDAV folder (坚果云、Nextcloud、群晖等). */
    class WebDavTarget(rawUrl: String, private val user: String, private val password: String) : Target {
        private val base: String = rawUrl.trim().let { if (it.endsWith("/")) it else "$it/" }

        init {
            if (!base.startsWith("https://") && !base.startsWith("http://")) throw AppException("WebDAV 地址需以 https:// 开头。")
        }

        private fun auth(builder: Request.Builder) = builder.header("Authorization", Credentials.basic(user, password, Charsets.UTF_8))

        private fun fail(code: Int): AppException = AppException(when (code) {
            401, 403 -> "WebDAV 账号或应用密码不对（HTTP $code）。坚果云要用「第三方应用密码」。"
            404, 409 -> "WebDAV 文件夹不存在（HTTP $code），请先在网盘里建好这个文件夹。"
            else -> "WebDAV 请求失败（HTTP $code）。"
        })

        override suspend fun read(): List<ByteArray> {
            val propfind = auth(Request.Builder().url(base)).header("Depth", "1")
                .method("PROPFIND", "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/></d:prop></d:propfind>"
                    .toRequestBody("application/xml; charset=utf-8".toMediaType())).build()
            val listing = Net.run(propfind) { response ->
                if (response.code != 207 && !response.isSuccessful) throw fail(response.code)
                response.body?.string().orEmpty()
            }
            val names = Regex("<(?:[a-zA-Z]+:)?href>([^<]+)</(?:[a-zA-Z]+:)?href>").findAll(listing)
                .map { URLDecoder.decode(it.groupValues[1].trimEnd('/'), "UTF-8").substringAfterLast('/') }
                .filter(::isSyncFile).distinct().toList()
            if (names.size > 100) throw AppException("同步设备文件过多，请使用专用同步文件夹。")
            return names.map { name ->
                val get = auth(Request.Builder().url(base + android.net.Uri.encode(name))).get().build()
                Net.run(get) { response ->
                    if (!response.isSuccessful) throw fail(response.code)
                    response.body?.bytes() ?: ByteArray(0)
                }
            }
        }

        override suspend fun write(name: String, data: ByteArray) {
            val put = auth(Request.Builder().url(base + android.net.Uri.encode(name)))
                .put(data.toRequestBody("application/octet-stream".toMediaType())).build()
            Net.run(put) { response -> if (!response.isSuccessful) throw fail(response.code) }
        }
    }

    /** Reads everyone's files, merges, writes ours back. Returns the merged archive. */
    suspend fun exchange(target: Target, archive: Archive, password: String, device: String): Archive {
        val files = target.read()
        val merged = archive.copy()
        withContext(Dispatchers.Default) { for (file in files) merged.merge(open(file, password)) }
        if (merged.records.size > 100_000) throw AppException("同步数据已超过容量上限。")
        val sealed = withContext(Dispatchers.Default) { seal(merged, password) }
        if (sealed.size >= 60_000_000) throw AppException("同步数据已超过容量上限。")
        target.write("companion-$device.pcsync", sealed)
        return merged
    }
}
