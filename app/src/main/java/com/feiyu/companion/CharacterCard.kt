package com.feiyu.companion

import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** SillyTavern / TavernAI cards (JSON or PNG) or plain text, rewritten for the current character. */
object CharacterCard {
    data class Imported(val title: String?, val profile: String, val greeting: String?, val truncated: Boolean)

    fun load(data: ByteArray, fileName: String, characterName: String): Imported {
        if (data.size > 30_000_000) throw AppException("人格卡文件太大了。")
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val png = data.size > 8 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte()
        if (ext == "png" || png) {
            val json = pngCharacterJson(data) ?: throw AppException("这张图片里没有找到人格卡数据。")
            return fromJson(json, characterName)
        }
        val text = decodeText(data)
            ?: throw AppException("无法识别这个文件的文字编码。请用「UTF-8」重新保存这个 TXT，或选择 JSON / PNG 人格卡。")
        if (text.isBlank()) throw AppException("这个文件是空的。")
        if (text.trimStart().startsWith("{")) runCatching { return fromJson(text, characterName) }
        return finish(fileName.substringBeforeLast('.'), text, null, null, characterName)
    }

    private fun fromJson(raw: String, characterName: String): Imported {
        val o = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: throw AppException("人格卡格式不正确。")
        val root = o.optJSONObject("data") ?: o
        fun field(vararg keys: String): String = keys.firstNotNullOfOrNull { root.str(it)?.trim()?.takeIf { v -> v.isNotEmpty() } } ?: ""
        val cardName = field("name", "char_name")
        val parts = ArrayList<String>()
        field("description", "char_persona", "persona", "profile").takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        field("personality").takeIf { it.isNotEmpty() }?.let { parts.add("性格：$it") }
        field("scenario", "world_scenario").takeIf { it.isNotEmpty() }?.let { parts.add("背景：$it") }
        field("mes_example", "example_dialogue").takeIf { it.isNotEmpty() }?.let { parts.add("说话示例：\n" + it.take(2_000)) }
        if (parts.isEmpty()) throw AppException("人格卡里没有找到性格描述。")
        val greeting = field("first_mes", "greeting").ifEmpty { null }
        return finish(cardName.ifEmpty { null }, parts.joinToString("\n\n"), greeting, cardName.ifEmpty { null }, characterName)
    }

    private fun finish(title: String?, profile: String, greeting: String?, cardName: String?, characterName: String): Imported {
        fun rewrite(value: String): String {
            var text = value
            for (token in listOf("{{char}}", "{{Char}}", "<BOT>", "<bot>")) text = text.replace(token, characterName)
            for (token in listOf("{{user}}", "{{User}}", "<USER>", "<user>")) text = text.replace(token, "你")
            if (cardName != null && cardName.length >= 2 && cardName != characterName) text = text.replace(cardName, characterName)
            return text
        }
        var result = rewrite(profile).trim()
        val truncated = result.length > PersonaPreset.PROFILE_LIMIT
        if (truncated) result = result.take(PersonaPreset.PROFILE_LIMIT)
        return Imported(title, result, greeting?.let(::rewrite), truncated)
    }

    /** TXT made on Windows is often GBK, not UTF-8: try UTF-8 → UTF-16 (BOM) → GB18030 → Big5. */
    fun decodeText(data: ByteArray): String? {
        fun strict(charset: Charset): String? = runCatching {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString()
        }.getOrNull()
        fun clean(value: String): String? {
            val text = value.replace("\uFEFF", "").replace("\r\n", "\n")
            val bad = text.count { it == '\uFFFD' || (it.code < 0x20 && it != '\n' && it != '\r' && it != '\t') }
            return if (bad * 50 > maxOf(text.length, 1)) null else text
        }
        if (data.size >= 2 && ((data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) || (data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()))) {
            return clean(String(data, Charsets.UTF_16))
        }
        strict(Charsets.UTF_8)?.let { s -> clean(s)?.let { return it } }
        for (name in listOf("GB18030", "Big5-HKSCS", "Big5")) {
            val charset = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            strict(charset)?.let { s -> clean(s)?.let { return it } }
        }
        return null
    }

    /** Character JSON stored in a PNG tEXt/iTXt chunk named "chara" (v2) or "ccv3" (v3), base64-encoded. */
    fun pngCharacterJson(data: ByteArray): String? {
        if (data.size < 8) return null
        val found = HashMap<String, String>()
        var offset = 8
        val buffer = ByteBuffer.wrap(data)
        while (offset + 12 <= data.size) {
            val length = buffer.getInt(offset)
            if (length < 0 || offset + 12 + length > data.size) break
            val type = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            val body = data.copyOfRange(offset + 8, offset + 8 + length)
            if (type == "tEXt" || type == "iTXt") {
                val zero = body.indexOf(0.toByte())
                if (zero > 0) {
                    val keyword = String(body, 0, zero, Charsets.ISO_8859_1).lowercase()
                    var text = if (type == "tEXt") String(body, zero + 1, body.size - zero - 1, Charsets.ISO_8859_1) else {
                        // iTXt: keyword\0 flag method language\0 translated\0 text
                        if (body.size > zero + 2 && body[zero + 1] == 0.toByte()) {
                            var p = zero + 3
                            repeat(2) { while (p < body.size && body[p] != 0.toByte()) p++; p++ }
                            if (p <= body.size) String(body, p, body.size - p, Charsets.UTF_8) else ""
                        } else ""
                    }
                    text = text.trim()
                    if (keyword == "chara" || keyword == "ccv3") found[keyword] = text
                }
            }
            if (type == "IEND") break
            offset += 12 + length
        }
        for (key in listOf("ccv3", "chara")) {
            val text = found[key] ?: continue
            val decoded = runCatching { String(Base64.decode(text, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
            if (decoded != null && decoded.trimStart().startsWith("{")) return decoded
            if (text.startsWith("{")) return text
        }
        return null
    }
}
