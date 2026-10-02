package dev.localphoto.enhancer.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import dev.localphoto.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GalleryExporter(private val context: Context, private val files: ImageFiles) {
    fun export(project: PhotoProject, bitmap: Bitmap, settings: OutputSettings): Uri {
        val extension = when (settings.format) { OutputFormat.JPEG -> "jpg"; OutputFormat.PNG -> "png"; OutputFormat.WEBP -> "webp" }
        val mime = "image/${if (extension == "jpg") "jpeg" else extension}"
        val stem = project.name.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(80).ifBlank { "Photo" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${stem}_enhanced_${timestamp}_${project.id.take(8)}.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LocalPhotoEnhancer/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            put(MediaStore.Images.Media.WIDTH, bitmap.width); put(MediaStore.Images.Media.HEIGHT, bitmap.height)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw PhotoFailure("storage")
        try {
            resolver.openOutputStream(uri, "w")?.use {
                if (!bitmap.compress(compression(settings.format), settings.quality.coerceIn(1, 100), it)) throw PhotoFailure("export")
            } ?: throw PhotoFailure("storage")
            if (!settings.stripMetadata && settings.format == OutputFormat.JPEG) {
                resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                    val input = runCatching { ExifInterface(File(project.sourcePath)) }.getOrNull()
                    val output = ExifInterface(descriptor.fileDescriptor)
                    for (tag in listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME,
                        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL)) {
                        input?.getAttribute(tag)?.let { output.setAttribute(tag, it) }
                    }
                    output.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                    output.saveAttributes()
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            if (failure is PhotoFailure) throw failure
            throw PhotoFailure("export", failure)
        }
    }

    fun shareCopy(project: PhotoProject): Uri {
        val path = project.outputPath ?: throw PhotoFailure("export")
        val bitmap = files.decodeResult(files.owned(File(path)))
        val directory = File(context.cacheDir, "share").apply { mkdirs() }
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
        val copy = File(directory, "${project.id}_enhanced.png")
        try { copy.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw PhotoFailure("export") } }
        finally { bitmap.recycle() }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
    }

    @Suppress("DEPRECATION")
    private fun compression(format: OutputFormat): Bitmap.CompressFormat = when (format) {
        OutputFormat.JPEG -> Bitmap.CompressFormat.JPEG
        OutputFormat.PNG -> Bitmap.CompressFormat.PNG
        OutputFormat.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    }
}
