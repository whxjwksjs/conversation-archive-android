package app.convarchive.model.parsers

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.ImportResult
import app.convarchive.model.parsers.ParseHelpers.arr
import app.convarchive.model.parsers.ParseHelpers.fail
import app.convarchive.model.parsers.ParseHelpers.lenientJson
import app.convarchive.model.parsers.ParseHelpers.obj
import app.convarchive.model.parsers.ParseHelpers.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generic importer for Google Takeout "My Activity" JSON covering Gemini Apps.
 *
 * Takeout's My Activity schema varies (it is an activity log, not a chat
 * transcript export): files are usually a top-level JSON array of entries
 * shaped like `{ "header", "title", "titleUrl", "subtitles": [...],
 * "time", "products": [...], "details": [...] }`. Because the schema is not
 * stable, this parser is deliberately generic:
 *
 * - each entry becomes one `role:"event"` message with its title, subtitles,
 *   and details as text and its `time` as the timestamp;
 * - entries that do not look like activity entries are skipped with a warning;
 * - the file always carries a warning that Takeout is an activity log and
 *   may not contain full conversation text.
 *
 * The untouched input is preserved in `originalExport`.
 */
object TakeoutParser {
    fun parse(text: String, fileName: String = "takeout.json"): ImportResult {
        val root = try {
            lenientJson.parseToJsonElement(text)
        } catch (e: Exception) {
            return fail(
                listOf("Takeout JSON parse error: ${e.message?.lineSequence()?.firstOrNull() ?: e}"),
                fileName, EXPORTER_ID,
            )
        }
        val entries: List<JsonElement> = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> listOf(root)
            else -> return fail(listOf("Takeout JSON must be an object or an array"), fileName, EXPORTER_ID)
        }
        val warnings = mutableListOf(
            "Google Takeout schema varies; parsed generically as an activity log",
            "Takeout entries may not contain full conversation text",
        )
        val messages = mutableListOf<CanonicalMessage>()
        var skipped = 0
        for ((index, el) in entries.withIndex()) {
            val e = obj(el)
            if (e == null || !looksLikeActivityEntry(e)) {
                skipped++
                continue
            }
            val title = str(e, "title") ?: "(untitled activity)"
            val header = str(e, "header")
            val parts = mutableListOf<String>()
            if (header != null && header != title) parts += header
            parts += title
            val subtitles = arr(e, "subtitles")
            subtitles?.forEach { s ->
                obj(s)?.let { so -> str(so, "name")?.let { parts += it } }
            }
            val details = arr(e, "details")
            details?.forEach { d ->
                obj(d)?.let { dobj -> str(dobj, "name")?.let { parts += it } }
            }
            val body = parts.filter { it.isNotBlank() }.joinToString("\n")
            if (body.isBlank()) {
                skipped++
                continue
            }
            val unknown = e.filterKeys { it !in ENTRY_KNOWN_KEYS }
            messages += CanonicalMessage(
                role = "event",
                text = body,
                createdAt = str(e, "time", "timestamp"),
                raw = JsonObject(
                    unknown + mapOf(
                        "titleUrl" to (e["titleUrl"] ?: JsonPrimitive("")),
                    ).filterValues { it !is JsonPrimitive || it.content.isNotEmpty() },
                ),
            )
            if (index == 0) {
                // keep first-entry header for the conversation title below
            }
        }
        if (skipped > 0) warnings += "$skipped ${if (skipped == 1) "entry" else "entries"} did not look like activity entries and were skipped"
        if (messages.isEmpty()) {
            return fail(
                listOf("no activity entries found in Takeout file"),
                fileName, EXPORTER_ID,
            )
        }
        val firstTitle = entries.firstNotNullOfOrNull { obj(it)?.let { e -> str(e, "title") } }
        val conv = CanonicalConversation(
            platform = "gemini",
            title = firstTitle?.take(80) ?: fallbackTitle(fileName),
            exportedAt = ParseHelpers.nowIso(),
            exporter = EXPORTER_ID,
            notes = "Imported from Google Takeout (My Activity > Gemini Apps); activity-log view, best-effort.",
            messages = messages,
            metadata = JsonObject(
                mapOf("entryCount" to JsonPrimitive(messages.size)),
            ),
            originalExport = root,
        )
        return ImportResult(
            ok = true,
            conversation = conv,
            warnings = warnings,
            detectedPlatform = "gemini",
            detectedExporter = EXPORTER_ID,
        )
    }

    /** Characteristic Takeout My Activity entry keys. */
    fun looksLikeActivityEntry(e: JsonObject): Boolean {
        val hasHeaderTitleTime = e.containsKey("header") && e.containsKey("title") && e.containsKey("time")
        val hasTitleUrl = e.containsKey("titleUrl")
        val geminiProduct = (e["products"] as? JsonArray)?.any {
            (it as? JsonPrimitive)?.content?.contains("gemini", ignoreCase = true) == true
        } == true
        return (hasHeaderTitleTime || hasTitleUrl) && (geminiProduct || e.containsKey("activityControls"))
    }

    fun looksLikeTakeoutFile(root: JsonElement): Boolean = when (root) {
        is JsonArray -> {
            val first = root.firstOrNull()?.let { obj(it) }
            first != null && looksLikeActivityEntry(first)
        }
        is JsonObject -> looksLikeActivityEntry(root)
        else -> false
    }

    private const val EXPORTER_ID = "google-takeout"

    private val ENTRY_KNOWN_KEYS = setOf(
        "header", "title", "titleUrl", "subtitles", "time", "timestamp",
        "products", "details", "activityControls",
    )
}
