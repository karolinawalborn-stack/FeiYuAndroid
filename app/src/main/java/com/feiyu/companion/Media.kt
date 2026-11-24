package com.feiyu.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max

/** Chat pictures: shrunk to 1600 px, rotated upright, JPEG ≤ 1 MB (same limits as the iPhone app). */
object Media {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching {
            when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        if (rotation == 0f && scale == 1f) return decoded
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }

    fun saveImage(bytes: ByteArray, dir: File): MessageImage {
        if (bytes.size > 25_000_000) throw AppException("图片超过 25 MB。")
        val bitmap = decode(bytes, 1600) ?: throw AppException("图片无法读取。")
        var output: ByteArray? = null
        for (quality in listOf(82, 62, 45)) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (stream.size() <= 1_000_000) { output = stream.toByteArray(); break }
        }
        val data = output ?: throw AppException("图片压缩后仍超过 1 MB，请选择更小的图片。")
        val name = UUID.randomUUID().toString().uppercase() + ".jpg"
        File(dir, name).writeBytes(data)
        val image = MessageImage(path = name, width = bitmap.width, height = bitmap.height)
        bitmap.recycle()
        return image
    }

    suspend fun saveFromUri(context: Context, uri: Uri, dir: File): MessageImage = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw AppException("无法读取相册图片。")
        saveImage(bytes, dir)
    }

    /** Small, upright, cached bitmap for display. */
    fun load(file: File, maxSide: Int): Bitmap? {
        val key = file.path + "@" + maxSide + "@" + file.lastModified()
        cache.get(key)?.let { return it }
        if (!file.exists()) return null
        val bitmap = runCatching { decode(file.readBytes(), maxSide) }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    fun forget(file: File) { cache.evictAll() ; file.delete() }
}

@Composable
fun rememberBitmap(file: File?, maxSide: Int = 720): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, file?.path, file?.lastModified(), maxSide) {
        value = if (file == null) null else withContext(Dispatchers.IO) { Media.load(file, maxSide)?.asImageBitmap() }
    }
