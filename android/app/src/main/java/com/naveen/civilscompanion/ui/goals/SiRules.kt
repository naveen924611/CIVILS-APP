package com.naveen.civilscompanion.ui.goals

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The rules of the AP SLPRB Sub-Inspector (Civil) recruitment, Post Code 11, as plain Kotlin (no Android classes, unit tested).
 *
 * Source of every number: data/exam-specs/slprb_si_2026.json, which was read from SLPRB_AP_SI_Notification_2026.pdf,
 * Rc.No.81/SLPRB/Rect.1/2026 dated 16-09-2026, on 2026-09-21. Do not change a number here without checking that PDF.
 *   eligibility.age_reference_date   2026-07-01
 *   eligibility.age_min / age_max    21 / 27
 *   eligibility.born_between         1999-07-02 to 2005-07-01
 *   eligibility.age_relaxation_years EWS 5, BC 5, SC 5, ST 5, govt_employee 5, ex_serviceman 3, ncc_instructor 3
 *   stages_pc11 PMT, PET and Prelim qualifying_percent (OC 40, EWS 40, BC 35, SC 30, ST 30)
 *   application fee_general 600, fee_sc_st 300, fee_non_local 600
 */
object SiRules {
    val REFERENCE_DATE: LocalDate = LocalDate.of(2026, 7, 1)
    const val AGE_MIN = 21
    const val AGE_MAX = 27

    /** Categories in the order the profile screen offers them. */
    val CATEGORIES = listOf("OC", "EWS", "BC", "SC", "ST")

    /** Certificates for community, NCL and EWS must be issued on or after this day. */
    val CERTIFICATE_ISSUED_ON_OR_AFTER: LocalDate = LocalDate.of(2026, 4, 1)

    const val VISION_TEXT = "Vision 6/6 in each eye (distant) and 0/5 Snellen (near)."

    val DISQUALIFYING_CONDITIONS = listOf(
        "Colour blindness", "Squint", "Physically handicapped", "Knock-knees", "Pigeon chest", "Flat feet",
        "Varicose veins", "Hammer toes", "Fractured limbs", "Decayed teeth", "Stammering", "Hard of hearing",
        "Abnormal psychological behaviour",
    )

    // ------------------------------------------------------------------ age

    /** Completed years on the reference date (or another day). */
    fun ageOn(dob: LocalDate, ref: LocalDate = REFERENCE_DATE): Int = ChronoUnit.YEARS.between(dob, ref).toInt()

    /** Result of the age check. relaxationYears is the one relaxation that was used (0 = none). */
    data class AgeCheck(val eligible: Boolean, val ageYears: Int, val relaxationYears: Int, val reason: String)

    private val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    private fun longDate(d: LocalDate): String = d.format(LONG_DATE)

    /** Relaxation years by category: EWS, BC, SC and ST get 5, OC gets 0. */
    fun categoryRelaxation(category: String): Int = when (category.trim().uppercase(Locale.ROOT)) {
        "EWS", "BC", "SC", "ST" -> 5
        else -> 0
    }

    /**
     * The window from the notification: born from 2 July 1999 to 1 July 2005 (aged at least 21 and not yet 27 on 1 July 2026).
     * A relaxation moves the older end back by that many years. Relaxations are NOT added together: the largest one applies.
     * serviceYears is the length of service for the special statuses: ex-serviceman gets 3 years in addition to it,
     * an NCC whole-time instructor gets 3 years in addition to it, and a State Government employee gets the length
     * of regular service, up to 5 years.
     */
    fun ageCheck(
        dob: LocalDate,
        category: String,
        govtEmployee: Boolean = false,
        exServiceman: Boolean = false,
        nccInstructor: Boolean = false,
        serviceYears: Int = 0,
        ref: LocalDate = REFERENCE_DATE,
    ): AgeCheck {
        val service = serviceYears.coerceAtLeast(0)
        val options = ArrayList<Pair<String, Int>>()
        val cat = category.trim().uppercase(Locale.ROOT)
        val catYears = categoryRelaxation(cat)
        if (catYears > 0) options.add("category $cat" to catYears)
        if (exServiceman) options.add("ex-serviceman (3 years plus service)" to 3 + service)
        if (nccInstructor) options.add("NCC instructor (3 years plus service)" to 3 + service)
        if (govtEmployee) options.add("State Government employee (length of service, up to 5)" to Math.min(service, 5))
        var best: Pair<String, Int>? = null
        for (o in options) {
            val current = best
            if (current == null || o.second > current.second) best = o
        }
        val chosen = best
        val relaxation = chosen?.second ?: 0
        val age = ageOn(dob, ref)
        // Youngest allowed: 21 completed years on the reference date, so born on or before ref minus 21 years.
        val latestBorn = ref.minusYears(AGE_MIN.toLong())
        // Oldest allowed: not yet 27 (plus relaxation), so born after ref minus (27 + relaxation) years.
        val earliestBorn = ref.minusYears((AGE_MAX + relaxation).toLong()).plusDays(1)
        val refText = longDate(ref)
        val relaxNote = if (chosen != null && relaxation > 0) {
            " Relaxation used: ${chosen.second} years (${chosen.first}). Relaxations are not added together, only the largest one counts."
        } else {
            ""
        }
        return when {
            dob.isAfter(latestBorn) -> AgeCheck(
                false, age, relaxation,
                "Too young. You are $age on $refText and the minimum is $AGE_MIN (born on or before ${longDate(latestBorn)}).",
            )
            dob.isBefore(earliestBorn) -> AgeCheck(
                false, age, relaxation,
                "Too old. You are $age on $refText. You must be born on or after ${longDate(earliestBorn)}." + relaxNote,
            )
            else -> AgeCheck(
                true, age, relaxation,
                "Age is fine. You are $age on $refText. Allowed: born from ${longDate(earliestBorn)} to ${longDate(latestBorn)}." + relaxNote,
            )
        }
    }

    // ------------------------------------------------------------------ PMT (body measurements)

    /** One measurement against its limit. value null = not entered yet. shortfall is 0 when it passes. */
    data class Measure(
        val name: String,
        val unit: String,
        val required: Double,
        val value: Double?,
        val pass: Boolean,
        val shortfall: Double,
    ) {
        /** "Height 167.6 cm: pass" or "Height 167.5 cm: short by 0.1 cm (need 167.6 cm)". */
        fun text(): String = when {
            value == null -> "$name: not entered (need ${SiRules.fmt(required)} $unit)"
            pass -> "$name ${SiRules.fmt(value)} $unit: pass"
            else -> "$name ${SiRules.fmt(value)} $unit: short by ${SiRules.fmt(shortfall)} $unit (need ${SiRules.fmt(required)} $unit)"
        }
    }

    data class PmtCheck(val measures: List<Measure>) {
        /** True only when every measure was entered and passes. */
        val allPass: Boolean get() = measures.isNotEmpty() && measures.all { it.pass }
    }

    fun round1(x: Double): Double = Math.round(x * 10.0) / 10.0

    private fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    /** "167.6", "86", "4.5": at most one decimal, no trailing zero. */
    fun fmt(x: Double): String {
        val r = round1(x)
        return if (r % 1.0 == 0.0) r.toLong().toString() else String.format(Locale.ROOT, "%.1f", r)
    }

    private fun measure(name: String, unit: String, required: Double, value: Double?): Measure {
        if (value == null) return Measure(name, unit, required, null, false, 0.0)
        val ok = value >= required - 1e-9
        return Measure(name, unit, required, value, ok, if (ok) 0.0 else round1(required - value))
    }

    /**
     * Men: height, chest, chest expansion (expanded chest minus chest). Women: height and weight.
     * Standard: men 167.6 cm, 86.3 cm chest, 5 cm expansion; women 152.5 cm, 40 kg.
     * ABO-ST agency-area candidates: men 160 cm, 80 cm chest, 3 cm expansion; women 150 cm, 38 kg.
     */
    fun pmtCheck(
        female: Boolean,
        aboSt: Boolean,
        heightCm: Double?,
        chestCm: Double?,
        chestExpandedCm: Double?,
        weightKg: Double?,
    ): PmtCheck {
        if (female) {
            val h = if (aboSt) 150.0 else 152.5
            val w = if (aboSt) 38.0 else 40.0
            return PmtCheck(listOf(measure("Height", "cm", h, heightCm), measure("Weight", "kg", w, weightKg)))
        }
        val h = if (aboSt) 160.0 else 167.6
        val c = if (aboSt) 80.0 else 86.3
        val e = if (aboSt) 3.0 else 5.0
        val expansion = if (chestCm != null && chestExpandedCm != null) round2(chestExpandedCm - chestCm) else null
        return PmtCheck(
            listOf(
                measure("Height", "cm", h, heightCm),
                measure("Chest", "cm", c, chestCm),
                measure("Chest expansion", "cm", e, expansion),
            ),
        )
    }

    // ------------------------------------------------------------------ PET (running and jumping)

    enum class PetProfile { GENERAL, EX_SERVICEMAN, WOMEN }

    /** Women use the women's table; men who are ex-servicemen use the ex-servicemen's table; everybody else the general one. */
    fun petProfileFor(female: Boolean, exServiceman: Boolean): PetProfile = when {
        female -> PetProfile.WOMEN
        exServiceman -> PetProfile.EX_SERVICEMAN
        else -> PetProfile.GENERAL
    }

    /** 1600 m, seconds (8:00 = 480, 9:30 = 570, 10:30 = 630). Faster or equal passes. */
    val RUN_1600_SECONDS: Map<PetProfile, Double> =
        mapOf(PetProfile.GENERAL to 480.0, PetProfile.EX_SERVICEMAN to 570.0, PetProfile.WOMEN to 630.0)

    /** 100 m, seconds. Faster or equal passes. */
    val RUN_100_SECONDS: Map<PetProfile, Double> =
        mapOf(PetProfile.GENERAL to 15.0, PetProfile.EX_SERVICEMAN to 16.5, PetProfile.WOMEN to 18.0)

    /** Long jump, metres. Longer or equal passes. */
    val LONG_JUMP_METRES: Map<PetProfile, Double> =
        mapOf(PetProfile.GENERAL to 3.80, PetProfile.EX_SERVICEMAN to 3.65, PetProfile.WOMEN to 2.75)

    /**
     * run1600Pass: the 1600 m met the standard. sprintPass / longJumpPass: null = not entered.
     * passed: 1600 m met AND at least one of 100 m or long jump met. missing: plain sentences for what is not met yet.
     */
    data class PetResult(
        val run1600Pass: Boolean,
        val sprintPass: Boolean?,
        val longJumpPass: Boolean?,
        val passed: Boolean,
        val missing: List<String>,
    )

    fun petPass(profile: PetProfile, run1600Seconds: Double, sprint100Seconds: Double? = null, longJumpMetres: Double? = null): PetResult {
        val runLimit = RUN_1600_SECONDS.getValue(profile)
        val sprintLimit = RUN_100_SECONDS.getValue(profile)
        val jumpLimit = LONG_JUMP_METRES.getValue(profile)
        val runOk = run1600Seconds <= runLimit + 1e-9
        val sprintOk = sprint100Seconds?.let { it <= sprintLimit + 1e-9 }
        val jumpOk = longJumpMetres?.let { it >= jumpLimit - 1e-9 }
        val missing = ArrayList<String>()
        if (!runOk) missing.add("1600 m must be ${formatRun(runLimit)} or faster (you ran ${formatRun(run1600Seconds)}).")
        if (sprintOk != true && jumpOk != true) {
            val parts = ArrayList<String>()
            if (sprintOk == false) parts.add("100 m was ${fmt(sprint100Seconds ?: 0.0)} s, need ${fmt(sprintLimit)} s or faster")
            if (jumpOk == false) parts.add("long jump was ${String.format(Locale.ROOT, "%.2f", longJumpMetres ?: 0.0)} m, need ${String.format(Locale.ROOT, "%.2f", jumpLimit)} m")
            if (parts.isEmpty()) parts.add("enter a 100 m time or a long jump")
            missing.add("You need one of 100 m or long jump: " + parts.joinToString("; ") + ".")
        }
        return PetResult(runOk, sprintOk, jumpOk, runOk && (sprintOk == true || jumpOk == true), missing)
    }

    /** 480.0 -> "8:00", 485.0 -> "8:05", 485.5 -> "8:05.5". Rounded to a tenth of a second. */
    fun formatRun(seconds: Double): String {
        val tenths = Math.round(Math.max(seconds, 0.0) * 10.0)
        val minutes = tenths / 600
        val rest = tenths % 600
        val secs = rest / 10
        val tenth = rest % 10
        return if (tenth == 0L) {
            String.format(Locale.ROOT, "%d:%02d", minutes, secs)
        } else {
            String.format(Locale.ROOT, "%d:%02d.%d", minutes, secs, tenth)
        }
    }

    private val MIN_SEC = Regex("^(\\d{1,3}):([0-5]?\\d(?:\\.\\d+)?)$")
    private val PLAIN = Regex("^\\d+(?:\\.\\d+)?$")

    /** "8:05" -> 485.0, "485" -> 485.0. Only minutes:seconds and plain seconds are accepted; anything else gives null. */
    fun parseRun(text: String): Double? {
        val t = text.trim()
        val m = MIN_SEC.matchEntire(t)
        if (m != null) {
            val minutes = m.groupValues[1].toDouble()
            val seconds = m.groupValues[2].toDouble()
            return minutes * 60.0 + seconds
        }
        if (PLAIN.matches(t)) return t.toDoubleOrNull()?.takeIf { it > 0.0 }
        return null
    }

    // ------------------------------------------------------------------ Prelims and fee

    /** Qualifying percent in EACH paper: OC and EWS 40, BC 35, SC and ST 30. An unknown category is treated as OC. */
    fun prelimCutoffPercent(category: String): Int = when (category.trim().uppercase(Locale.ROOT)) {
        "BC" -> 35
        "SC", "ST" -> 30
        else -> 40
    }

    /** Marks needed in one paper of 100. */
    fun prelimCutoffMarks(category: String): Double = prelimCutoffPercent(category).toDouble()

    fun prelimPaperPassed(category: String, marks: Double): Boolean = marks >= prelimCutoffMarks(category) - 1e-9

    /** Each paper is out of 100 and each must reach the percentage. Prelim marks do not count for the final merit. */
    fun prelimPassed(category: String, paper1Marks: Double, paper2Marks: Double): Boolean =
        prelimPaperPassed(category, paper1Marks) && prelimPaperPassed(category, paper2Marks)

    /** Application fee in rupees: Rs 300 for SC and ST, Rs 600 for everybody else and for candidates who are not local. */
    fun feeFor(category: String, local: Boolean): Int {
        if (!local) return 600
        return when (category.trim().uppercase(Locale.ROOT)) {
            "SC", "ST" -> 300
            else -> 600
        }
    }
}
