package com.naveen.civilscompanion.ui.settings

/** Numbers and words for the Storage section. Plain Kotlin, tested. */
object StorageLogic {
    const val DEFAULT_LIMIT_GB = 20
    const val MIN_LIMIT_GB = 5
    const val MAX_LIMIT_GB = 200
    const val KEY_LIMIT = "storage.limit_gb"

    /** "0 B", "12 KB", "3.4 MB", "1.25 GB". */
    fun bytes(n: Long): String {
        val v = n.coerceAtLeast(0L).toDouble()
        return when {
            v < 1024 -> "${v.toLong()} B"
            v < 1024 * 1024 -> "%.0f KB".format(v / 1024)
            v < 1024.0 * 1024 * 1024 -> "%.1f MB".format(v / (1024 * 1024))
            else -> "%.2f GB".format(v / (1024.0 * 1024 * 1024))
        }
    }

    fun limitBytes(gb: Int): Long = gb.coerceIn(MIN_LIMIT_GB, MAX_LIMIT_GB) * 1024L * 1024L * 1024L

    fun stepLimit(gb: Int, delta: Int): Int = (gb + delta).coerceIn(MIN_LIMIT_GB, MAX_LIMIT_GB)

    /** How full the limit is, 0.0 to 1.0. */
    fun fraction(used: Long, limitGb: Int): Float = (used.toDouble() / limitBytes(limitGb).toDouble()).coerceIn(0.0, 1.0).toFloat()

    /** One part of the usage bar. */
    data class Part(val label: String, val bytes: Long)

    /** Parts in a fixed order; empty parts are dropped. */
    fun parts(audio: Long, documents: Long, studyData: Long, waiting: Long, backups: Long): List<Part> =
        listOf(
            Part("Audio", audio), Part("Documents", documents), Part("Study data", studyData),
            Part("Waiting to send", waiting), Part("Backups", backups),
        ).filter { it.bytes > 0L }

    fun percent(fraction: Double): String = "${(fraction * 100).toInt().coerceIn(0, 100)}%"

    /** Short name for a backup file "civils-backup-20260921-030000.tar.gz" -> "21 Sep 2026, 03:00". */
    fun backupLabel(name: String): String {
        val m = Regex("civils-backup-(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})").find(name) ?: return name
        val (y, mo, d, h, mi) = m.destructured
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val month = months.getOrNull(mo.toInt() - 1) ?: return name
        return "${d.toInt()} $month $y, $h:$mi"
    }
}
