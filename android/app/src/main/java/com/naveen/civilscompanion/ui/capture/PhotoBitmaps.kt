package com.naveen.civilscompanion.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import kotlin.math.max

/** Loading pictures and PDF pages as bitmaps (for the preview, for on-device reading and for the Reader's "original page"). */
object PhotoBitmaps {

    /** A photo from a file, turned upright (camera photos often carry a rotation flag) and at most [maxSide] pixels long. */
    fun load(file: File, maxSide: Int = 2000): Bitmap? {
        val path = file.absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded: Bitmap? = try {
            BitmapFactory.decodeFile(path, options)
        } catch (e: OutOfMemoryError) {
            null
        }
        val raw = decoded ?: return null
        val degrees = rotationOf(file)
        val upright = if (degrees == 0) raw else {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            try {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            } catch (e: OutOfMemoryError) {
                raw
            }
        }
        val longest = max(upright.width, upright.height)
        if (longest <= maxSide) return upright
        val k = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(upright, (upright.width * k).toInt().coerceAtLeast(1), (upright.height * k).toInt().coerceAtLeast(1), true)
    }

    private fun rotationOf(file: File): Int = try {
        when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: IOException) {
        0
    }

    /** One page of a PDF file as a picture [targetWidth] pixels wide. [pageIndex] starts at 0. Null if it cannot be drawn. */
    fun renderPdfPage(file: File, pageIndex: Int, targetWidth: Int = 1500): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            val opened = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = opened
            val r = PdfRenderer(opened)
            renderer = r
            if (pageIndex < 0 || pageIndex >= r.pageCount) return null
            val p = r.openPage(pageIndex)
            page = p
            val scale = targetWidth.toFloat() / p.width
            val bitmap = Bitmap.createBitmap(targetWidth, (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } catch (e: IOException) {
            return null
        } catch (e: SecurityException) {
            return null
        } catch (e: IllegalStateException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        } finally {
            try {
                page?.close()
                renderer?.close()
                pfd?.close()
            } catch (e: IOException) {
                // nothing more to do: the file is being closed
            }
        }
    }
}
