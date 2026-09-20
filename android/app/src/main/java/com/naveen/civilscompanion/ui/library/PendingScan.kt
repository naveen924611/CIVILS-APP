package com.naveen.civilscompanion.ui.library

import com.naveen.civilscompanion.data.records.Table
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A camera photo waiting to be sent to the server (the tablet may be offline). Stays on this tablet only.
 * [batch] groups the pages of one scan session so they end up in the same document.
 */
@Serializable
data class PendingScan(
    val id: String,
    @SerialName("file_path") val filePath: String = "",
    val text: String = "",
    @SerialName("document_id") val documentId: String? = null,
    val batch: String = "",
    val telugu: Boolean = false,
    /** waiting | failed */
    val state: String = "waiting",
    val message: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

object LocalTables {
    val PendingScans = Table(
        "local_pending_scans", PendingScan.serializer(), { it.id },
        k1 = "state", n1 = "created_at", localOnly = true,
    )
}
