package com.naveen.civilscompanion.ui.goals

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Data of the Goals screen and how it is saved as settings (KV keys, shared with the server):
 *   goal.applied  {"appsc-g1": bool, "slprb-si": bool}
 *   si.profile    {"gender","category","dob","local","ex_serviceman","service_years","govt_employee","ncc_instructor","abo_st","degree_done"}
 *   si.checklist  {"degree": bool, ...}
 *   si.pmt        {"height_cm","chest_cm","chest_expanded_cm","weight_kg"}
 *   si.pet_log    [{"id","date","run1600","sprint100","longJump"}]
 * Reading is forgiving: a missing or odd value falls back to the default. Plain Kotlin (no Android), unit tested.
 * Nothing secret is stored here: no passwords, no payment details.
 */
object GoalsData {
    const val KEY_APPLIED = "goal.applied"
    const val KEY_PROFILE = "si.profile"
    const val KEY_CHECKLIST = "si.checklist"
    const val KEY_PMT = "si.pmt"
    const val KEY_PET_LOG = "si.pet_log"

    const val APPLIED_GROUP1 = "appsc-g1"
    const val APPLIED_SI = "slprb-si"

    const val MAX_PET_ENTRIES = 200

    private fun prim(e: JsonElement?): JsonPrimitive? = (e as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun num(e: JsonElement?): Double? = prim(e)?.doubleOrNull?.takeIf { !it.isNaN() && !it.isInfinite() }

    private fun bool(e: JsonElement?, default: Boolean): Boolean = prim(e)?.booleanOrNull ?: default

    private fun str(e: JsonElement?): String? = prim(e)?.contentOrNull

    // ------------------------------------------------------------------ dates and countdowns

    /** Group-I application window (APPSC Brief Notification 07/2026, 15-09-2026). The window closes at 11:59 PM on the last day. */
    val GROUP1_OPENS: LocalDate = LocalDate.of(2026, 10, 6)
    val GROUP1_CLOSES: LocalDate = LocalDate.of(2026, 10, 27)

    /** The Detailed Notification (age limits, physical rules, vacancies) is due on or before this day. */
    val DETAILED_NOTIFICATION_DUE: LocalDate = LocalDate.of(2026, 10, 6)

    /** Whole days from today to the target (negative when it has passed). */
    fun daysUntil(target: LocalDate, today: LocalDate): Int = ChronoUnit.DAYS.between(today, target).toInt()

    private fun daysWord(n: Int): String = if (n == 1) "1 day" else "$n days"

    /** Where we are against an application window. */
    fun windowText(opens: LocalDate, closes: LocalDate, today: LocalDate): String {
        val toOpen = daysUntil(opens, today)
        val toClose = daysUntil(closes, today)
        return when {
            toOpen > 0 -> "Opens in ${daysWord(toOpen)}"
            toClose > 0 -> "Open now. Closes in ${daysWord(toClose)}"
            toClose == 0 -> "Open now. Closes today at 11:59 PM"
            else -> "Closed"
        }
    }

    /** Where we are against a "due on or before" date. */
    fun dueText(due: LocalDate, today: LocalDate): String {
        val d = daysUntil(due, today)
        return when {
            d > 0 -> "Due in ${daysWord(d)}"
            d == 0 -> "Due today"
            else -> "The due date has passed. Look for it on psc.ap.gov.in"
        }
    }

    // ------------------------------------------------------------------ applied ticks

    fun parseApplied(e: JsonElement?): Map<String, Boolean> {
        val o = e as? JsonObject
        return mapOf(
            APPLIED_GROUP1 to bool(o?.get(APPLIED_GROUP1), false),
            APPLIED_SI to bool(o?.get(APPLIED_SI), false),
        )
    }

    fun appliedJson(m: Map<String, Boolean>): JsonObject = JsonObject(
        mapOf<String, JsonElement>(
            APPLIED_GROUP1 to JsonPrimitive(m[APPLIED_GROUP1] ?: false),
            APPLIED_SI to JsonPrimitive(m[APPLIED_SI] ?: false),
        ),
    )

    // ------------------------------------------------------------------ profile

    data class SiProfile(
        val female: Boolean = false,
        /** OC, EWS, BC, SC or ST. */
        val category: String = "OC",
        val dob: LocalDate? = null,
        /** Local candidate (95 percent of the posts are for local candidates). */
        val local: Boolean = true,
        val exServiceman: Boolean = false,
        /** Years of service for ex-serviceman, NCC instructor or State Government employee. */
        val serviceYears: Int = 0,
        val govtEmployee: Boolean = false,
        val nccInstructor: Boolean = false,
        /** ABO-ST candidate from an agency area (lower body measures). */
        val aboSt: Boolean = false,
        val degreeDone: Boolean = false,
    )

    fun parseProfile(e: JsonElement?): SiProfile {
        val o = e as? JsonObject ?: return SiProfile()
        val category = (str(o["category"]) ?: "OC").trim().uppercase()
        val dob = str(o["dob"])?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
        val years = prim(o["service_years"])?.let { p -> p.intOrNull ?: p.doubleOrNull?.toInt() } ?: 0
        return SiProfile(
            female = (str(o["gender"]) ?: "male").trim().lowercase() == "female",
            category = if (category in SiRules.CATEGORIES) category else "OC",
            dob = dob,
            local = bool(o["local"], true),
            exServiceman = bool(o["ex_serviceman"], false),
            serviceYears = years.coerceIn(0, 40),
            govtEmployee = bool(o["govt_employee"], false),
            nccInstructor = bool(o["ncc_instructor"], false),
            aboSt = bool(o["abo_st"], false),
            degreeDone = bool(o["degree_done"], false),
        )
    }

    fun profileJson(p: SiProfile): JsonObject = JsonObject(
        mapOf<String, JsonElement>(
            "gender" to JsonPrimitive(if (p.female) "female" else "male"),
            "category" to JsonPrimitive(p.category),
            "dob" to (p.dob?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
            "local" to JsonPrimitive(p.local),
            "ex_serviceman" to JsonPrimitive(p.exServiceman),
            "service_years" to JsonPrimitive(p.serviceYears),
            "govt_employee" to JsonPrimitive(p.govtEmployee),
            "ncc_instructor" to JsonPrimitive(p.nccInstructor),
            "abo_st" to JsonPrimitive(p.aboSt),
            "degree_done" to JsonPrimitive(p.degreeDone),
        ),
    )

    /** The age result for the profile, or null while the date of birth is not entered. */
    fun ageResult(p: SiProfile): SiRules.AgeCheck? = p.dob?.let {
        SiRules.ageCheck(
            dob = it,
            category = p.category,
            govtEmployee = p.govtEmployee,
            exServiceman = p.exServiceman,
            nccInstructor = p.nccInstructor,
            serviceYears = p.serviceYears,
        )
    }

    fun petProfile(p: SiProfile): SiRules.PetProfile = SiRules.petProfileFor(p.female, p.exServiceman)

    /** Builds a date from three number fields; null when it is not a real date. */
    fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    /** Day, month and year typed in three boxes. Null unless it is a real date with a year from 1900 to 2100. */
    fun dobFromText(day: String, month: String, year: String): LocalDate? {
        val d = day.trim().toIntOrNull() ?: return null
        val m = month.trim().toIntOrNull() ?: return null
        val y = year.trim().toIntOrNull() ?: return null
        if (y < 1900 || y > 2100) return null
        return dateOrNull(y, m, d)
    }

    // ------------------------------------------------------------------ checklist

    data class CheckItem(val key: String, val label: String, val detail: String, val source: String)

    /** The things to have ready. fee = the amount for this candidate (see SiRules.feeFor). */
    fun checklistItems(fee: Int): List<CheckItem> = listOf(
        CheckItem(
            "degree", "Degree certificate",
            "Any degree, completed on or before 01-07-2026. SC/ST: Intermediate plus studying a degree is also accepted.",
            "Notification: eligibility, qualification",
        ),
        CheckItem(
            "ssc", "SSC certificate",
            "Your age is taken from the SSC certificate only.",
            "Notification: eligibility, age",
        ),
        CheckItem(
            "community", "Community certificate issued on or after 01-04-2026",
            "An older certificate will not do. Apply for a new one if yours was issued before that day.",
            "Notification: certificates must be issued on or after 01-04-2026",
        ),
        CheckItem(
            "ncl_ews", "Non-creamy-layer certificate (BC) or EWS certificate",
            "Only if you are BC or EWS. Also issued on or after 01-04-2026.",
            "Notification: Non-creamy-layer certificate (BC), EWS certificate",
        ),
        CheckItem(
            "local", "Local candidate and study certificates",
            "95 percent of the posts are for local candidates and 5 percent for open competition.",
            "Notification: local rule, 5% open competition, 95% local candidates",
        ),
        CheckItem(
            "photo_sign", "Photo and signature scans",
            "Keep clear scans ready. Check the size rules when the application opens.",
            "Notification: apply online at slprb.ap.gov.in",
        ),
        CheckItem(
            "fee", "Fee ready: Rs $fee",
            "You pay it yourself on the official site. This app never asks for payment details.",
            "Notification: application fee Rs 600 (Rs 300 for SC/ST, Rs 600 for non-local)",
        ),
        CheckItem(
            "medical", "Medical fitness check",
            SiRules.VISION_TEXT + " None of these: " + SiRules.DISQUALIFYING_CONDITIONS.joinToString(", ").lowercase() + ".",
            "Notification: medical standards",
        ),
        CheckItem(
            "id", "Aadhaar and ID details",
            "Have your numbers and documents ready. Do not store passwords here.",
            "Notification: application form",
        ),
    )

    fun parseChecklist(e: JsonElement?): Map<String, Boolean> {
        val o = e as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, Boolean>()
        for ((k, v) in o) {
            val b = prim(v)?.booleanOrNull
            if (b != null) out[k] = b
        }
        return out
    }

    fun checklistJson(m: Map<String, Boolean>): JsonObject =
        JsonObject(m.mapValues<String, Boolean, JsonElement> { JsonPrimitive(it.value) })

    fun checklistDone(items: List<CheckItem>, m: Map<String, Boolean>): Int = items.count { m[it.key] == true }

    // ------------------------------------------------------------------ PMT inputs

    data class PmtInput(
        val heightCm: Double? = null,
        val chestCm: Double? = null,
        val chestExpandedCm: Double? = null,
        val weightKg: Double? = null,
    )

    fun parsePmt(e: JsonElement?): PmtInput {
        val o = e as? JsonObject ?: return PmtInput()
        return PmtInput(num(o["height_cm"]), num(o["chest_cm"]), num(o["chest_expanded_cm"]), num(o["weight_kg"]))
    }

    fun pmtJson(p: PmtInput): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        p.heightCm?.let { m["height_cm"] = JsonPrimitive(it) }
        p.chestCm?.let { m["chest_cm"] = JsonPrimitive(it) }
        p.chestExpandedCm?.let { m["chest_expanded_cm"] = JsonPrimitive(it) }
        p.weightKg?.let { m["weight_kg"] = JsonPrimitive(it) }
        return JsonObject(m)
    }

    /** A number as text for an input box: 167.0 -> "167", 167.6 -> "167.6", null -> "". */
    fun numberText(d: Double?): String = when {
        d == null -> ""
        d % 1.0 == 0.0 -> d.toLong().toString()
        else -> d.toString()
    }

    /** A number typed by the owner: "167.6" or "167,6". Null when empty, not a number, zero or negative. */
    fun parseNumber(text: String): Double? {
        val d = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (d.isNaN() || d.isInfinite() || d <= 0.0) null else d
    }

    // ------------------------------------------------------------------ PET log

    data class PetEntry(
        val id: String,
        /** yyyy-MM-dd */
        val date: String,
        val run1600: Double,
        val sprint100: Double? = null,
        val longJump: Double? = null,
    )

    fun parsePetLog(e: JsonElement?): List<PetEntry> {
        val a = e as? JsonArray ?: return emptyList()
        val out = ArrayList<PetEntry>()
        for (item in a) {
            val o = item as? JsonObject ?: continue
            val id = str(o["id"])?.trim().orEmpty()
            val date = str(o["date"])?.trim().orEmpty()
            val run = num(o["run1600"])
            if (id.isEmpty() || date.isEmpty() || run == null || run <= 0.0) continue
            out.add(PetEntry(id, date, run, num(o["sprint100"]), num(o["longJump"])))
        }
        return out.takeLast(MAX_PET_ENTRIES)
    }

    fun petLogJson(list: List<PetEntry>): JsonArray = JsonArray(
        list.takeLast(MAX_PET_ENTRIES).map { p ->
            val m = LinkedHashMap<String, JsonElement>()
            m["id"] = JsonPrimitive(p.id)
            m["date"] = JsonPrimitive(p.date)
            m["run1600"] = JsonPrimitive(p.run1600)
            p.sprint100?.let { m["sprint100"] = JsonPrimitive(it) }
            p.longJump?.let { m["longJump"] = JsonPrimitive(it) }
            JsonObject(m)
        },
    )

    /** The log is kept oldest first (in the order it was added). Only the last 200 entries are kept. */
    fun addPetEntry(list: List<PetEntry>, entry: PetEntry): List<PetEntry> = (list + entry).takeLast(MAX_PET_ENTRIES)

    fun removePetEntry(list: List<PetEntry>, id: String): List<PetEntry> = list.filter { it.id != id }

    /** Newest date first; entries of the same day: the one added last comes first. */
    fun newestFirst(list: List<PetEntry>): List<PetEntry> = list.asReversed().sortedByDescending { it.date }

    data class PetBests(val run1600: Double?, val sprint100: Double?, val longJump: Double?)

    /** Best so far: fastest 1600 m, fastest 100 m, longest jump. */
    fun bests(list: List<PetEntry>): PetBests = PetBests(
        run1600 = list.minOfOrNull { it.run1600 },
        sprint100 = list.mapNotNull { it.sprint100 }.minOrNull(),
        longJump = list.mapNotNull { it.longJump }.maxOrNull(),
    )

    /** Result of reading the "add an entry" form: an entry, or a plain-English problem. */
    data class EntryParse(val entry: PetEntry?, val error: String?)

    /** dateText year-month-day; runText "m:ss" or plain seconds (required); sprintText seconds and jumpText metres (optional). */
    fun buildEntry(id: String, dateText: String, runText: String, sprintText: String, jumpText: String): EntryParse {
        val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
            ?: return EntryParse(null, "Write the date as year-month-day, for example 2026-09-21.")
        val run = SiRules.parseRun(runText)
        if (run == null || run < 150.0 || run > 1800.0) {
            return EntryParse(null, "Write the 1600 m time as minutes:seconds, for example 8:05.")
        }
        var sprint: Double? = null
        if (sprintText.isNotBlank()) {
            sprint = parseNumber(sprintText)
            if (sprint == null || sprint < 5.0 || sprint > 60.0) {
                return EntryParse(null, "Write the 100 m time in seconds, for example 14.5, or leave it empty.")
            }
        }
        var jump: Double? = null
        if (jumpText.isNotBlank()) {
            jump = parseNumber(jumpText)
            if (jump == null || jump < 0.5 || jump > 10.0) {
                return EntryParse(null, "Write the long jump in metres, for example 3.85, or leave it empty.")
            }
        }
        return EntryParse(PetEntry(id, date.toString(), run, sprint, jump), null)
    }

    fun petResult(profile: SiRules.PetProfile, e: PetEntry): SiRules.PetResult =
        SiRules.petPass(profile, e.run1600, e.sprint100, e.longJump)

    fun petProfileName(profile: SiRules.PetProfile): String = when (profile) {
        SiRules.PetProfile.GENERAL -> "General"
        SiRules.PetProfile.EX_SERVICEMAN -> "Ex-serviceman"
        SiRules.PetProfile.WOMEN -> "Women"
    }

    /** "1600 m in 8:00 or faster, and one of: 100 m in 15 s or faster, long jump 3.80 m or more". */
    fun petStandardText(profile: SiRules.PetProfile): String {
        val run = SiRules.RUN_1600_SECONDS.getValue(profile)
        val sprint = SiRules.RUN_100_SECONDS.getValue(profile)
        val jump = SiRules.LONG_JUMP_METRES.getValue(profile)
        return "1600 m in ${SiRules.formatRun(run)} or faster, and one of: 100 m in ${SiRules.fmt(sprint)} s or faster, " +
            "long jump ${String.format(java.util.Locale.ROOT, "%.2f", jump)} m or more"
    }

    /** "1600 m 8:05, 100 m 14.5 s, jump 3.85 m" for a list row. */
    fun petSummary(e: PetEntry): String {
        val parts = ArrayList<String>()
        parts.add("1600 m ${SiRules.formatRun(e.run1600)}")
        e.sprint100?.let { parts.add("100 m ${SiRules.fmt(it)} s") }
        e.longJump?.let { parts.add("jump ${String.format(java.util.Locale.ROOT, "%.2f", it)} m") }
        return parts.joinToString(", ")
    }

    // ------------------------------------------------------------------ prelim calculator

    /** Marks out of 100 typed by the owner. Null when empty, not a number, or outside 0 to 100. */
    fun parseMarks(text: String): Double? {
        val d = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (d.isNaN() || d < 0.0 || d > 100.0) null else d
    }
}
