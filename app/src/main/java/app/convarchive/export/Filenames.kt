package app.convarchive.export

import app.convarchive.util.Dates
import java.time.LocalDate

/**
 * Export file naming: `{PlatformLabel}_{TitleSlug}_{yyyy-MM-dd}.{ext}`,
 * e.g. `AIStudio_Conversation_2026-10-12.json`.
 *
 * Slugs are ASCII-safe: spaces become `_`, illegal characters are stripped.
 * Uniqueness across files is the caller's job (zip entries, SAF overwrite
 * prompts); no counter is appended here.
 */
object Filenames {

    private val PLATFORM_LABELS = mapOf(
        "gemini" to "Gemini",
        "aistudio" to "AIStudio",
        "jules" to "Jules",
        "other" to "Conversation",
    )

    fun platformLabel(platform: String): String =
        PLATFORM_LABELS[platform.lowercase()] ?: "Conversation"

    /** ASCII-safe slug: letters/digits kept, spaces -> `_`, rest stripped. */
    fun sanitize(raw: String): String {
        if (raw.isBlank()) return "Conversation"
        val sb = StringBuilder()
        for (ch in raw.trim()) {
            when {
                ch.isLetterOrDigit() && ch.code < 128 -> sb.append(ch)
                ch == ' ' || ch == '-' || ch == '_' -> sb.append('_')
                // drop everything else (incl. non-ASCII)
            }
        }
        val collapsed = sb.toString().replace(Regex("_+"), "_").trim('_')
        return collapsed.take(60).ifBlank { "Conversation" }
    }

    fun forConversation(
        platform: String,
        title: String,
        date: LocalDate?,
        ext: String,
    ): String {
        val label = platformLabel(platform)
        val slug = sanitize(title)
        val d = (date ?: Dates.today()).toString() // yyyy-MM-dd
        val cleanExt = ext.trimStart('.').lowercase().takeIf { it.isNotBlank() } ?: "dat"
        return "${label}_${slug}_${d}.${cleanExt}"
    }

    /** Date for a filename, taken from an ISO-8601 timestamp or today. */
    fun dateOf(iso: String?): LocalDate =
        Dates.isoToLocalDate(iso) ?: Dates.today()
}
