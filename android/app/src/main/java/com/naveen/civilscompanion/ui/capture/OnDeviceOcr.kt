package com.naveen.civilscompanion.ui.capture

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Reads English (Latin script) text from a picture on this tablet, with no internet. Telugu needs the server's AI. */
object OnDeviceOcr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** The text found, "" when there is none, or null when the reader could not run (for example its model is missing). */
    suspend fun read(bitmap: Bitmap): String? = try {
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text.trim()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
