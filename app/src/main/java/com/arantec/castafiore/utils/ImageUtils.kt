package com.arantec.castafiore.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.palette.graphics.Palette

object ImageUtils {
    // Downscale the bitmap to a small, fixed size to stabilize Palette results
    fun downscaleForPalette(src: Bitmap, target: Int = 128): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= target && h <= target) return src
        val scale = minOf(target.toFloat() / w, target.toFloat() / h)
        val matrix = Matrix().apply { setScale(scale, scale) }
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, matrix, null)
        return out
    }

    // Extract a consistent background color using a stable priority chain
    fun extractBackgroundColor(src: Bitmap): Int? {
        val bmp = downscaleForPalette(src)
        val palette = Palette.from(bmp).clearFilters().generate()
        return palette.dominantSwatch?.rgb
            ?: palette.vibrantSwatch?.rgb
            ?: palette.darkVibrantSwatch?.rgb
            ?: palette.mutedSwatch?.rgb
            ?: palette.darkMutedSwatch?.rgb
    }
}