package com.naveen.civilscompanion.ui.answers

import android.content.Context
import java.io.IOException

/** Thin loader: reads the bundled prompt file from the app assets. Works offline. Call it off the main thread. */
object WritingPromptLoader {
    fun load(context: Context): List<WritingPrompt> = try {
        val text = context.assets.open(WritingPrompts.ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        WritingPrompts.parse(text)
    } catch (e: IOException) {
        emptyList()
    }
}
