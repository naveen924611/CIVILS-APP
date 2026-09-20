package com.naveen.civilscompanion.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Photos taken with the camera app: ActivityResultContracts.TakePicture needs a file the camera may write to.
 *   val (file, uri) = CaptureFiles.newPhoto(context)
 *   takePicture.launch(uri)   // when the result is true, read `file`
 * Files live in the cache folder and can be deleted any time (clean() removes old ones).
 * FOUNDATION FILE: do not edit.
 */
object CaptureFiles {
    fun authority(context: Context) = "${context.packageName}.files"

    fun newPhoto(context: Context, prefix: String = "photo"): Pair<File, Uri> {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File.createTempFile("${prefix}_", ".jpg", dir)
        return file to FileProvider.getUriForFile(context, authority(context), file)
    }

    /** Shares a file from the app's private folder (for example an exported PDF) with another app. */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    fun clean(context: Context, olderThanMillis: Long = 24L * 60 * 60 * 1000) {
        val dir = File(context.cacheDir, "captures")
        val limit = System.currentTimeMillis() - olderThanMillis
        dir.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
    }
}
