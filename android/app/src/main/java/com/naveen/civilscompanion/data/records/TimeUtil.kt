package com.naveen.civilscompanion.data.records

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Time and id helpers shared by every feature. Timestamps are ISO-8601 in UTC, ending in Z (as on the server). */
object TimeUtil {
    val india: ZoneId = ZoneId.of("Asia/Kolkata")

    fun nowIso(): String = Instant.now().toString()

    fun newId(): String = UUID.randomUUID().toString()

    fun toIso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    fun parse(text: String?): Long? =
        text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** Today's date in India as YYYY-MM-DD (the study day used by plans and revision orders). */
    fun today(zone: ZoneId = india): String = LocalDate.now(zone).toString()

    fun dateOf(epochMillis: Long, zone: ZoneId = india): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    /** Start of a YYYY-MM-DD day (in India) as epoch milliseconds. */
    fun startOfDay(date: String, zone: ZoneId = india): Long =
        LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
}
