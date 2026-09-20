package com.naveen.civilscompanion.ui.capture

/**
 * The Reader sets this before opening Capture so the new photo becomes the next page of the scan being read.
 * Capture reads it once when it opens and clears it. (A route argument is not used because the navigation
 * rail decides which item to highlight from the route text.)
 */
object CaptureTarget {
    @Volatile
    var documentId: String? = null

    fun take(): String? {
        val id = documentId
        documentId = null
        return id
    }
}
