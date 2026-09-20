package com.naveen.civilscompanion.data

import com.naveen.civilscompanion.data.remote.dto.BriefSlotDto
import java.time.ZoneId
import java.time.ZonedDateTime

/** Brief times are clock times in India, the same zone the server uses. */
object BriefTimes {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

    /** The next moment this slot is due after [now], or null if it is off or has no days. */
    fun nextOccurrence(slot: BriefSlotDto, now: ZonedDateTime): ZonedDateTime? {
        if (!slot.enabled || slot.days.isEmpty()) return null
        val (hh, mm) = parseTime(slot.time) ?: return null
        val local = now.withZoneSameInstant(ZONE)
        for (offset in 0L..7L) {
            val day = local.toLocalDate().plusDays(offset)
            val candidate = day.atTime(hh, mm).atZone(ZONE)
            val weekday = candidate.dayOfWeek.value - 1 // Monday = 0, like the server
            if (candidate.isAfter(local) && weekday in slot.days) return candidate
        }
        return null
    }

    fun parseTime(text: String): Pair<Int, Int>? {
        val parts = text.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h to m else null
    }

    fun format(hh: Int, mm: Int) = "%02d:%02d".format(hh, mm)

    /** "7:00 AM" for people; the server keeps "07:00". */
    fun display(time: String): String {
        val (h, m) = parseTime(time) ?: return time
        val suffix = if (h < 12) "AM" else "PM"
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, m, suffix)
    }

    /** Adds minutes to a "HH:MM" time, wrapping around midnight. */
    fun shift(time: String, minutes: Int): String {
        val (h, m) = parseTime(time) ?: return time
        val total = ((h * 60 + m + minutes) % 1440 + 1440) % 1440
        return format(total / 60, total % 60)
    }

    fun label(slotId: String) = when (slotId) {
        "morning" -> "Morning brief"
        "evening" -> "Evening brief"
        else -> "Extra brief"
    }
}
