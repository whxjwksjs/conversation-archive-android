package app.convarchive.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** ISO-8601 <-> epoch millis helpers. Lenient on input; null-safe. */
object Dates {
    private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun nowIso(): String = Instant.now().toString()

    /** Parse an ISO-8601 instant/date string to epoch millis, or null if unparseable. */
    fun isoToEpochMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val s = iso.trim()
        // Try full instant first.
        runCatching { return Instant.parse(s).toEpochMilli() }
        // Offset date-time without 'Z'.
        runCatching { return ISO_FORMATTER.parse(s, Instant::from).toEpochMilli() }
        // Plain local date-time: assume UTC.
        runCatching {
            return java.time.LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        // Plain date: start of day UTC.
        runCatching {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        // Epoch millis as a bare number.
        runCatching { return s.toLong() }
        return null
    }

    fun epochMillisToIso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    /** Extract a [LocalDate] from an ISO string, or null. */
    fun isoToLocalDate(iso: String?): LocalDate? {
        val ms = isoToEpochMillis(iso) ?: return null
        return Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    }

    /** "2026-10-12" style date for filenames. */
    fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())

    /**
     * Display timestamp for a message: "2026-10-12 10:00" in the device zone,
     * or the raw string when it cannot be parsed.
     */
    fun formatForDisplay(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        val ms = isoToEpochMillis(iso) ?: return iso
        val dt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
}
