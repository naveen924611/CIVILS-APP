package com.naveen.civilscompanion.ui.today

import com.naveen.civilscompanion.data.model.DailyPlan
import com.naveen.civilscompanion.data.records.RecordQuery
import com.naveen.civilscompanion.data.records.RecordStore
import com.naveen.civilscompanion.data.records.Tables
import com.naveen.civilscompanion.data.records.TimeUtil
import com.naveen.civilscompanion.data.repo.SyncRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

/** Reads the day plans that sync brings down, ticks blocks off, and asks the server for a fresh plan. */
@Singleton
class PlanRepository @Inject constructor(
    private val store: RecordStore,
    private val api: PlannerApi,
    private val sync: SyncRepository,
) {
    fun observeAll(): Flow<List<DailyPlan>> = store.observe(Tables.DailyPlans, RecordQuery(limit = 90))

    fun observeDay(date: String): Flow<List<DailyPlan>> = store.observe(Tables.DailyPlans, RecordQuery(k1 = date))

    fun observeTicks(date: String): Flow<List<PlanTick>> = store.observe(LocalPlanTables.Ticks, RecordQuery(k1 = date))

    /** Marks a block "done" (or clears the mark). planId = the server plan row, or null for the tablet's fallback plan. */
    suspend fun setDone(planId: String?, date: String, blockId: String, done: Boolean) {
        if (planId != null) {
            store.update(Tables.DailyPlans, planId) { p ->
                val next = HashMap<String, kotlinx.serialization.json.JsonElement>(p.completion)
                if (done) next[blockId] = JsonPrimitive(PlanBlocks.DONE) else next.remove(blockId)
                p.copy(completion = JsonObject(next))
            }
        } else {
            val id = "$date|$blockId"
            if (done) {
                store.save(LocalPlanTables.Ticks, PlanTick(id = id, date = date, blockId = blockId, status = PlanBlocks.DONE))
            } else {
                store.delete(LocalPlanTables.Ticks, id)
            }
        }
    }

    /**
     * Asks the server to plan the next days again, then syncs so the plans arrive. Returns a short message for the owner,
     * or null when it worked and nothing needs saying.
     */
    suspend fun replan(days: Int = 7): String? {
        return try {
            val result = api.regenerate(RegenerateBody(days))
            runCatching { sync.sync() }
            if (result.missedDays >= 3) "You missed ${result.missedDays} days, so the week is planned again from today. Take it one block at a time." else null
        } catch (e: IOException) {
            "Could not reach the server. Your saved plan is unchanged."
        } catch (e: HttpException) {
            "The server could not plan right now. Try again later."
        } catch (e: SerializationException) {
            "The server answered in a way this app did not understand."
        }
    }

    /** True when the server has sent a plan with blocks for `date`. */
    suspend fun hasServerPlan(date: String): Boolean =
        store.list(Tables.DailyPlans, RecordQuery(k1 = date)).any { it.blocks.isNotEmpty() }

    companion object {
        fun today(): String = TimeUtil.today()
    }
}
