package com.takekazex.hypertweak.ui.effect

import android.app.WallpaperColors
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import com.takekazex.hypertweak.R
import kotlin.math.max
import kotlin.math.min

internal data class GlassPreviewImage(
    val bitmap: Bitmap,
    val darkTheme: Boolean,
)

/** The bundled picture is the initial backdrop; picker imports replace it for this page session. */
internal fun decodeBundledGlassPreviewSample(context: Context): GlassPreviewImage {
    val bitmap = requireNotNull(BitmapFactory.decodeResource(context.resources, R.drawable.glass_preview_sample)) {
        "Could not decode the bundled glass preview image"
    }
    return analyzeGlassPreviewBitmap(bitmap)
}

/** Decodes a small in-memory preview and uses Android's wallpaper color analysis for contrast. */
internal fun decodeGlassPreviewImage(context: Context, uri: Uri): GlassPreviewImage {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val size = info.size
        val scale = min(1f, MAX_IMAGE_SIDE.toFloat() / max(size.width, size.height).coerceAtLeast(1))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSize(
            (size.width * scale).toInt().coerceAtLeast(1),
            (size.height * scale).toInt().coerceAtLeast(1),
        )
    }
    return analyzeGlassPreviewBitmap(bitmap)
}

private fun analyzeGlassPreviewBitmap(bitmap: Bitmap): GlassPreviewImage {
    val colors = WallpaperColors.fromBitmap(bitmap)
    val darkTheme = (colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) == 0
    return GlassPreviewImage(bitmap, darkTheme)
}

private const val MAX_IMAGE_SIDE = 1440
