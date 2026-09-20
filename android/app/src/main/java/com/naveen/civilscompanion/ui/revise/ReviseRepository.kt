package com.naveen.civilscompanion.ui.revise

import com.naveen.civilscompanion.data.model.Card
import com.naveen.civilscompanion.data.model.Review
import com.naveen.civilscompanion.data.model.RevisionOrder
import com.naveen.civilscompanion.data.model.RevisionRule
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.KvRepository
import com.naveen.civilscompanion.srs.CardMemory
import com.naveen.civilscompanion.srs.Fsrs
import com.naveen.civilscompanion.srs.FsrsTime
import com.naveen.civilscompanion.srs.RevisionQueue
import com.naveen.civilscompanion.srs.Strength
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** What the tablet needs to grade cards offline: the queue, FSRS grading, topic strength, snooze, My order, rules. */
@Singleton
class ReviseRepository @Inject constructor(
    private val store: RecordStore,
    private val kv: KvRepository,
) {
    // ------------------------------------------------------------------ settings
    private fun int(e: JsonElement?, default: Int): Int =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: default

    private fun settingsOf(max: JsonElement?, newPerDay: JsonElement?, sunday: JsonElement?, slot: JsonElement?, retention: JsonElement?): RevisionSettings =
        RevisionSettings(
            maxCards = int(max, 80),
            newPerDay = int(newPerDay, 20),
            sundayReview = (sunday as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull ?: true,
            slotTime = (slot as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: "18:00",
            retention = (retention as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull ?: Fsrs.DEFAULT_RETENTION,
        )

    fun observeSettings(): Flow<RevisionSettings> = combine(
        kv.observe(KEY_MAX), kv.observe(KEY_NEW), kv.observe(KEY_SUNDAY), kv.observe(KEY_SLOT), kv.observe(KEY_RETENTION),
    ) { a, b, c, d, e -> settingsOf(a, b, c, d, e) }

    suspend fun settingsOnce(): RevisionSettings = observeSettings().first()

    suspend fun saveMaxCards(n: Int) { kv.put(KEY_MAX, JsonPrimitive(n.coerceIn(5, 500))) }

    suspend fun saveSundayReview(on: Boolean) { kv.put(KEY_SUNDAY, JsonPrimitive(on)) }

    suspend fun saveSlot(time: String) { kv.put(KEY_SLOT, JsonPrimitive(time)) }

    // ------------------------------------------------------------------ the queue
    /** The day's queue; updates whenever cards, topics, exams, rules, the order or the settings change. */
    fun observeQueue(): Flow<QueueState> = combine(
        store.observe(Tables.Cards, RecordQuery(limit = 20000)),
        store.observe(Tables.Topics),
        store.observe(Tables.Exams),
        store.observe(Tables.RevisionRules),
        combine(store.observe(Tables.RevisionOrders), observeSettings()) { orders, s -> orders to s },
    ) { cards, topics, exams, rules, os ->
        ReviseData.buildQueue(cards, topics, exams, rules, os.first, os.second, Instant.now())
    }

    suspend fun snapshot(): QueueState = observeQueue().first()

    // ------------------------------------------------------------------ grading
    /** Applies a grade (1..4): saves a Review (with the state BEFORE it, for undo) and the card's new memory state. */
    suspend fun grade(card: Card, grade: Int, retention: Double, now: Instant = Instant.now()): GradeResult {
        val engine = Fsrs.forRetention(retention)
        val before = card.fsrsState?.let { CardMemory.fromJson(it) }
        val after = engine.review(before, grade, now)
        val reviewId = TimeUtil.newId()
        store.save(
            Tables.Reviews,
            Review(id = reviewId, cardId = card.id, grade = grade, reviewedAt = FsrsTime.format(now), stateJson = card.fsrsState),
        )
        val due = after.due?.let { FsrsTime.format(it) }
        val updated = store.update(Tables.Cards, card.id) { it.copy(fsrsState = after.toJson(), dueAt = due) } ?: card
        card.topicId?.let { refreshTopic(it, engine, now) }
        return GradeResult(reviewId, card, updated)
    }

    /** Takes back a grade: restores the card as it was and removes the review. */
    suspend fun undo(result: GradeResult, retention: Double) {
        val restore = result.before
        store.update(Tables.Cards, restore.id) { it.copy(fsrsState = restore.fsrsState, dueAt = restore.dueAt) }
        store.delete(Tables.Reviews, result.reviewId)
        restore.topicId?.let { refreshTopic(it, Fsrs.forRetention(retention), Instant.now()) }
    }

    /** Topic strength and status from the memory of all its cards (status only ever moves up). */
    private suspend fun refreshTopic(topicId: String, engine: Fsrs, now: Instant) {
        val cards = store.list(Tables.Cards, RecordQuery(k1 = topicId))
        val mem = Strength.topicMemory(engine, cards.map { c -> c.fsrsState?.let { CardMemory.fromJson(it) } }, now) ?: return
        store.update(Tables.Topics, topicId) { t ->
            t.copy(strength = mem.strength, status = Strength.statusFor(t.status, mem))
        }
    }

    // ------------------------------------------------------------------ snooze and order
    /** Moves the group's cards to the next 2 to 3 days (within the max-cards limit). */
    suspend fun snooze(cardIds: List<String>, maxPerDay: Int) {
        val today = java.time.LocalDate.now(ReviseData.INDIA)
        val offsets = RevisionQueue.snoozeOffsets(cardIds.size, maxPerDay)
        cardIds.forEachIndexed { i, id ->
            val due = ReviseData.snoozeDue(today, offsets[i])
            store.update(Tables.Cards, id) { it.copy(dueAt = due) }
        }
    }

    /** Saves "My order" for today (keys of the groups, first to last). */
    suspend fun saveOrder(keys: List<String>) {
        val today = java.time.LocalDate.now(ReviseData.INDIA).toString()
        val existing = store.list(Tables.RevisionOrders, RecordQuery(k1 = today)).firstOrNull()
        if (existing != null) {
            store.update(Tables.RevisionOrders, existing.id) { it.copy(groupOrder = keys) }
        } else {
            store.save(Tables.RevisionOrders, RevisionOrder(id = TimeUtil.newId(), date = today, groupOrder = keys))
        }
    }

    /** "Smart order": forget today's My order. */
    suspend fun clearOrder() {
        val today = java.time.LocalDate.now(ReviseData.INDIA).toString()
        store.list(Tables.RevisionOrders, RecordQuery(k1 = today)).forEach { store.delete(Tables.RevisionOrders, it.id) }
    }

    // ------------------------------------------------------------------ rules
    fun observeRules(): Flow<List<RevisionRule>> = store.observe(Tables.RevisionRules)

    /** Creates or updates the one rule row of a type. */
    suspend fun saveRule(type: String, params: JsonObject, enabled: Boolean) {
        val existing = store.list(Tables.RevisionRules, RecordQuery(k1 = type)).firstOrNull()
        if (existing != null) {
            store.update(Tables.RevisionRules, existing.id) { it.copy(params = params, enabled = enabled) }
        } else {
            store.save(Tables.RevisionRules, RevisionRule(id = TimeUtil.newId(), type = type, params = params, enabled = enabled))
        }
    }

    fun observeSlot(): Flow<String> = observeSettings().map { it.slotTime }

    companion object {
        const val KEY_MAX = "revision.max_cards"
        const val KEY_NEW = "revision.new_per_day"
        const val KEY_SUNDAY = "revision.sunday_review"
        const val KEY_SLOT = "revision.slot_time"
        const val KEY_RETENTION = "revision.retention"
    }
}

/** What grading returned: the review row made, the card as it was, and as it is now. */
data class GradeResult(val reviewId: String, val before: Card, val after: Card)
