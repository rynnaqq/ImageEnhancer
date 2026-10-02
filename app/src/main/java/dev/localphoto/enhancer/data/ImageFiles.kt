package dev.localphoto.enhancer.data

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dev.localphoto.core.TransformSettings
import dev.localphoto.core.MemoryPolicy
import dev.localphoto.enhancer.ai.DeviceCapabilityDetector
import java.io.File
import java.io.IOException
import kotlin.math.*

class PhotoFailure(val code: String, cause: Throwable? = null) : IOException(code, cause)

class ImageFiles(private val context: Context) {
    val root = File(context.filesDir, "projects").apply { mkdirs() }
    fun directory(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return owned(File(root, id)).apply { mkdirs() }
    }
    fun owned(file: File): File {
        val canonical = file.canonicalFile
        require(canonical.path.startsWith(root.canonicalPath + File.separator)) { "Project path outside private storage" }
        return canonical
    }
    fun availableBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes

    fun import(uri: Uri, id: String): Triple<File, String, Pair<Int, Int>> {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')?.take(180) ?: "Photo"
        if (name.substringAfterLast('.', "").lowercase() in setOf("tiff", "tif")) throw PhotoFailure("tiff_dependency")
        val directory = directory(id)
        val file = File(directory, "original.${name.substringAfterLast('.', "img").filter { it.isLetterOrDigit() }.take(10)}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (availableBytes() < count + 32L * 1024 * 1024) throw PhotoFailure("storage")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw PhotoFailure("decode")
            val size = dimensions(file)
            if (size.first <= 0 || size.second <= 0) throw PhotoFailure("decode")
            // Validate a bounded decode before accepting an import into project storage.
            decode(file, 512L * 512L).recycle()
            return Triple(file, name, size)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            if (failure is PhotoFailure) throw failure
            throw PhotoFailure("decode", failure)
        }
    }

    fun dimensions(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) throw PhotoFailure("decode")
        val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        return if (orientation in setOf(5, 6, 7, 8)) options.outHeight to options.outWidth else options.outWidth to options.outHeight
    }

    /** ImageDecoder honors EXIF, exposes decode size and uses software pixels for inference. */
    fun decode(file: File, maxPixels: Long, maxDimension: Int = 8192): Bitmap {
        require(maxPixels > 0)
        require(maxDimension > 0)
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val ratio = maxOf(sqrt((info.size.width.toLong() * info.size.height).toDouble() / maxPixels),
                    info.size.width.toDouble() / maxDimension, info.size.height.toDouble() / maxDimension, 1.0)
                decoder.setTargetSize(max(1, (info.size.width / ratio).toInt()), max(1, (info.size.height / ratio).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
            }
        } catch (e: OutOfMemoryError) { throw PhotoFailure("memory", e) }
        catch (e: Exception) { throw PhotoFailure("decode", e) }
    }

    /** Exports and stage resume preserve dimensions, or report insufficient memory. */
    fun decodeResult(file: File): Bitmap {
        val (width, height) = dimensions(file)
        val budget = DeviceCapabilityDetector.detect(context).processingBudget
        if (!MemoryPolicy.assess(width, height, 1, budget).isSafe) throw PhotoFailure("memory")
        return decode(file, width.toLong() * height, max(width, height))
    }

    fun transform(bitmap: Bitmap, settings: TransformSettings): Bitmap {
        val t = settings.normalized()
        val left = (bitmap.width * t.cropLeft).toInt().coerceIn(0, bitmap.width - 1)
        val top = (bitmap.height * t.cropTop).toInt().coerceIn(0, bitmap.height - 1)
        val width = ((t.cropRight - t.cropLeft) * bitmap.width).toInt().coerceIn(1, bitmap.width - left)
        val height = ((t.cropBottom - t.cropTop) * bitmap.height).toInt().coerceIn(1, bitmap.height - top)
        val matrix = Matrix().apply {
            postScale(if (t.flipHorizontal) -1f else 1f, if (t.flipVertical) -1f else 1f)
            postRotate(t.rotationDegrees + t.straightenDegrees)
        }
        return Bitmap.createBitmap(bitmap, left, top, width, height, matrix, true)
    }

    fun atomicPng(bitmap: Bitmap, destination: File) {
        owned(destination)
        if (availableBytes() < bitmap.width.toLong() * bitmap.height * 5 + 32L * 1024 * 1024) throw PhotoFailure("storage")
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        try {
            temporary.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw PhotoFailure("export") }
            if (!temporary.renameTo(destination)) throw PhotoFailure("storage")
        } finally { temporary.delete() }
    }

    fun removeTemporary(id: String) {
        File(directory(id), "temporary").takeIf { it.exists() }?.let { owned(it).deleteRecursively() }
    }
}
