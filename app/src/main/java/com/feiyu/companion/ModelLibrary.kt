package com.feiyu.companion

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** Imported Live2D (Cubism 3/4) models. One model is kept at a time in files/models/custom. */
object ModelLibrary {
    const val ID = "custom"
    private const val MAX_BYTES = 700_000_000L
    private const val MAX_FILES = 5_000

    data class Info(val title: String, val file: String, val fileCount: Int, val bytes: Long)

    private fun folder(root: File) = File(root, ID)

    fun info(root: File): Info? {
        val dir = folder(root)
        val model = dir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".model3.json") } ?: return null
        if (model.parentFile != dir) return null
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val title = File(dir, ".title").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } ?: model.name.removeSuffix(".model3.json")
        return Info(title, model.name, files.size, files.sumOf { it.length() })
    }

    fun remove(root: File) { folder(root).deleteRecursively() }

    /** Folder picked with the system file picker. */
    suspend fun installFolder(context: Context, tree: Uri, root: File): Info = withContext(Dispatchers.IO) {
        val source = DocumentFile.fromTreeUri(context, tree) ?: throw AppException("无法读取这个文件夹。")
        val work = File(root, ".install-" + UUID.randomUUID())
        try {
            var count = 0
            var total = 0L
            fun copy(dir: DocumentFile, target: File, depth: Int) {
                if (depth > 12) throw AppException("模型文件夹层级太深。")
                target.mkdirs()
                for (child in dir.listFiles()) {
                    val name = child.name ?: continue
                    if (name.startsWith(".") || name.contains('/') || name == "..") continue
                    if (child.isDirectory) { copy(child, File(target, name), depth + 1); continue }
                    count++; total += child.length()
                    if (count > MAX_FILES || total > MAX_BYTES) throw AppException("Live2D 模型最多 5000 个文件、700 MB。")
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        File(target, name).outputStream().use { input.copyTo(it) }
                    } ?: throw AppException("无法读取文件：$name")
                }
            }
            copy(source, work, 0)
            finish(work, root, source.name ?: "导入的模型")
        } finally {
            work.deleteRecursively()
        }
    }

    /** A .zip containing one model (folder inside the zip is fine). */
    suspend fun installZip(context: Context, uri: Uri, title: String, root: File): Info = withContext(Dispatchers.IO) {
        val work = File(root, ".install-" + UUID.randomUUID())
        try {
            work.mkdirs()
            val base = work.canonicalPath + File.separator
            var count = 0
            var total = 0L
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        if (name.split('/').any { it == ".." } || name.startsWith("/") || name.startsWith("__MACOSX") ||
                            name.split('/').any { it.startsWith(".") && it.isNotEmpty() }) { zip.closeEntry(); continue }
                        val target = File(work, name)
                        if (!target.canonicalPath.startsWith(base)) throw AppException("压缩包含越界路径。")
                        if (entry.isDirectory) { target.mkdirs(); continue }
                        count++
                        if (count > MAX_FILES) throw AppException("Live2D 模型最多 5000 个文件、700 MB。")
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) throw AppException("Live2D 模型最多 5000 个文件、700 MB。")
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                }
            } ?: throw AppException("无法读取这个压缩包。")
            finish(work, root, title.removeSuffix(".zip"))
        } finally {
            work.deleteRecursively()
        }
    }

    /** Validates the copied files and moves the model's folder into place. */
    private fun finish(work: File, root: File, title: String): Info {
        val models = work.walkTopDown().filter { it.isFile && it.name.endsWith(".model3.json") }.toList()
        if (models.size != 1) throw AppException(if (models.isEmpty()) "里面没有 .model3.json 模型文件（需要 Cubism 3/4 模型，旧的 .model.json 不支持）。" else "里面有多个模型，请只选择包含一个模型的文件夹。")
        val model = models[0]
        val base = model.parentFile!!.canonicalFile
        val json = runCatching { JSONObject(model.readText()) }.getOrNull() ?: throw AppException("模型清单格式不正确。")
        val refs = json.optJSONObject("FileReferences") ?: throw AppException("模型清单缺少 FileReferences。")
        val moc = refs.str("Moc") ?: throw AppException("模型清单缺少 moc3 引用。")
        val textures = refs.optJSONArray("Textures")?.strings().orEmpty()
        if (textures.isEmpty()) throw AppException("模型清单缺少纹理引用。")
        val required = ArrayList<String>().apply {
            add(moc); addAll(textures)
            for (key in listOf("Physics", "Pose", "DisplayInfo", "UserData")) refs.str(key)?.let { add(it) }
            refs.optJSONObject("Motions")?.let { motions ->
                for (group in motions.keys()) motions.optJSONArray(group)?.mapObjects { it.str("File") }?.let { addAll(it) }
            }
            refs.optJSONArray("Expressions")?.mapObjects { it.str("File") }?.let { addAll(it) }
        }
        for (relative in required) {
            if (relative.startsWith("/") || relative.contains('\\') || relative.split('/').any { it == ".." }) throw AppException("模型清单含越界资源路径。")
            val asset = File(base, relative).canonicalFile
            if (!asset.path.startsWith(base.path + File.separator) || !asset.isFile) throw AppException("模型缺少资源文件：$relative")
        }
        val destination = folder(root)
        val old = File(root, ".old-" + UUID.randomUUID())
        val hadOld = destination.exists()
        if (hadOld && !destination.renameTo(old)) throw AppException("无法替换旧模型。")
        if (!base.renameTo(destination)) {
            // Different file system → copy instead of move.
            runCatching { base.copyRecursively(destination, overwrite = true) }.onFailure {
                destination.deleteRecursively()
                if (hadOld) old.renameTo(destination)
                throw AppException("模型安装失败：" + (it.message ?: "无法写入"))
            }
        }
        old.deleteRecursively()
        File(destination, ".title").writeText(title.take(60))
        return info(root) ?: throw AppException("模型安装失败。")
    }
}
