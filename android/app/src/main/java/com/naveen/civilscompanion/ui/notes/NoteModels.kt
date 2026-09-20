package com.naveen.civilscompanion.ui.notes

import com.naveen.civilscompanion.data.model.Card
import com.naveen.civilscompanion.data.model.Mcq
import com.naveen.civilscompanion.data.model.Note
import com.naveen.civilscompanion.data.model.Topic
import com.naveen.civilscompanion.data.records.Table
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A question the owner wants to ask later ("My doubts" tab). Private to this tablet: it is never sent to the server. */
@Serializable
data class Doubt(
    val id: String,
    @SerialName("topic_id") val topicId: String = "",
    val text: String = "",
    val resolved: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val deleted: Boolean = false,
)

object NoteTables {
    val Doubts = Table("local_doubts", Doubt.serializer(), { it.id }, k1 = "topic_id", text = listOf("text"), localOnly = true)
}

/** The newest AI job for one topic (make notes, add text, report error), for the status line above the note. */
data class TopicJob(val id: String, val mode: String, val status: String, val error: String, val summary: String)

/** A topic found by the search box: by its title or by words inside its note. */
data class SearchHit(val topic: Topic, val snippet: String)

/** Everything the note page needs for the selected topic. */
data class DetailState(
    val topic: Topic? = null,
    val note: Note? = null,
    val cards: List<Card> = emptyList(),
    val mcqs: List<Mcq> = emptyList(),
    val doubts: List<Doubt> = emptyList(),
    val job: TopicJob? = null,
) {
    /** Cards that have been forgotten more than once (FSRS `lapses` in the card state), a rough "weak cards" count. */
    val weakCards: Int get() = cards.count { c ->
        val lapses = (c.fsrsState?.get("lapses") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
        lapses >= 2
    }
}

/** What the "earlier versions" box shows. */
data class VersionsUi(
    val noteId: String,
    val loading: Boolean = true,
    val current: Int = 1,
    val versions: List<VersionInfo> = emptyList(),
    val preview: VersionText? = null,
    val error: String? = null,
)
