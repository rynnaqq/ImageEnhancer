package dev.localphoto.enhancer.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import dev.localphoto.core.TransformSettings
import dev.localphoto.enhancer.R
import dev.localphoto.enhancer.data.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

@Composable fun rememberPhotoBitmap(path: String?, files: ImageFiles,
    transform: TransformSettings? = null, maxPixels: Long = 2_000_000): Bitmap? {
    val bitmapState = remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path, transform, maxPixels) {
        bitmapState.value = null
        if (path == null) return@LaunchedEffect
        delay(80)
        // Retain ownership across dispatcher cancellation; an undelivered decode
        // must be recycled even when the user leaves before IO resumes this effect.
        var pending: Bitmap? = null
        try {
            withContext(Dispatchers.IO) {
                pending = runCatching {
                    val source = files.decode(File(path), maxPixels, maxDimension = 2560)
                    try {
                        if (transform != null) {
                            val edited = files.transform(source, transform)
                            if (edited !== source) source.recycle()
                            edited
                        } else source
                    } catch (failure: Throwable) { source.recycle(); throw failure }
                }.getOrNull()
            }
            ensureActive()
            bitmapState.value = pending
            pending = null
        } finally { pending?.takeIf { !it.isRecycled }?.recycle() }
    }
    val bitmap = bitmapState.value
    DisposableEffect(bitmap) { onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() } }
    return bitmap
}

@Composable fun PhotoThumbnail(path: String?, files: ImageFiles, modifier: Modifier = Modifier,
    scale: ContentScale = ContentScale.Crop) {
    val bitmap = rememberPhotoBitmap(path, files, maxPixels = 400_000)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), stringResource(R.string.photo_preview),
            Modifier.fillMaxSize(), contentScale = scale)
        else Icon(Icons.Outlined.Image, stringResource(R.string.photo_preview), tint = MaterialTheme.colorScheme.outline)
    }
}
