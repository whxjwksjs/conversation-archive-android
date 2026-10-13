package app.convarchive.model.parsers

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.ImportResult
import app.convarchive.model.parsers.ParseHelpers.arr
import app.convarchive.model.parsers.ParseHelpers.fail
import app.convarchive.model.parsers.ParseHelpers.lenientJson
import app.convarchive.model.parsers.ParseHelpers.obj
import app.convarchive.model.parsers.ParseHelpers.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Parser for Voyager (voyager-crew/voyager, GPL-3.0) chat exports.
 *
 * Schema basis: the project's own docs describe the JSON export produced by
 * ConversationExportService.toJSON as roughly
 * `{ format: 'gemini-voyager.chat.v1', url, exportedAt, count, items }` where
 * each item carries `{ role, content }`. The exact item fields and timestamp
 * placement were NOT line-by-line reviewed, so this parser is deliberately
 * tolerant: it also accepts `role`/`text`, `sender`/`message`, and top-level
 * `messages`/`conversations` arrays, plus a generic `{title, messages:[{role,text}]}`
 * fallback shape. Unrecognized fields are stashed in message.raw /
 * conversation.metadata. Written from the documented schema only; no Voyager
 * source code was copied or adapted (GPL-3.0).
 */
object VoyagerParser {
    fun parse(text: String, fileName: String = "voyager.json"): ImportResult {
        val root = try {
            lenientJson.parseToJsonElement(text)
        } catch (e: Exception) {
            return fail(
                listOf("Voyager JSON parse error: ${e.message?.lineSequence()?.firstOrNull() ?: e}"),
                fileName, "voyager",
            )
        }
        val rootObj = obj(root)
            ?: return fail(listOf("Voyager export must be a JSON object"), fileName, "voyager")

        val warnings = mutableListOf<String>()
        val items = arr(rootObj, "items")
            ?: arr(rootObj, "messages")
            ?: arr(rootObj, "conversations")?.let { convs ->
                // Some exports nest one conversation object; take the first.
                val first = convs.firstOrNull()?.let { obj(it) }
                first?.let { arr(it, "messages") ?: arr(it, "items") }
            }
        if (items == null) {
            return fail(
                listOf("Voyager export has no recognizable message list (looked for \"items\", \"messages\", \"conversations\")"),
                fileName, "voyager",
            )
        }

        val messages = items.mapIndexedNotNull { index, el ->
            val m = obj(el) ?: return@mapIndexedNotNull null
            val role = ParseHelpers.normRole(str(m, "role", "sender", "author"))
            val content = str(m, "content", "text", "message", "body")
            if (content == null) {
                warnings += "message #$index had no readable text and was skipped"
                return@mapIndexedNotNull null
            }
            val unknown = m.filterKeys { it !in VOYAGER_KNOWN_ITEM_KEYS }
            CanonicalMessage(
                role = role,
                text = content,
                markdown = content,
                createdAt = str(m, "createdAt", "timestamp", "time", "date"),
                model = str(m, "model"),
                codeBlocks = ParseHelpers.extractCodeBlocks(content),
                raw = JsonObject(unknown),
            )
        }

        val url = str(rootObj, "url", "sourceUrl", "link")
        val platform = when {
            url?.contains("aistudio.google.com", ignoreCase = true) == true -> "aistudio"
            url?.contains("gemini.google.com", ignoreCase = true) == true -> "gemini"
            else -> "gemini" // Voyager's export feature is Gemini/AI-Studio focused
        }
        val title = str(rootObj, "title", "name")
            ?: fallbackTitle(fileName)
        val unknownTop = rootObj.filterKeys { it !in VOYAGER_KNOWN_TOP_KEYS }

        val conv = CanonicalConversation(
            platform = platform,
            title = title,
            sourceUrl = url,
            exportedAt = str(rootObj, "exportedAt", "exported_at", "exportDate")
                ?: ParseHelpers.nowIso(),
            exporter = "voyager",
            messages = messages,
            metadata = JsonObject(
                unknownTop + mapOf(
                    "voyagerFormat" to (rootObj["format"] ?: rootObj["version"] ?: kotlinx.serialization.json.JsonNull),
                ).filterValues { it !is kotlinx.serialization.json.JsonNull },
            ),
            originalExport = root,
        )
        if (messages.isEmpty()) warnings += "no messages could be extracted"
        return ImportResult(
            ok = true,
            conversation = conv,
            warnings = warnings,
            detectedPlatform = platform,
            detectedExporter = "voyager",
        )
    }

    private val VOYAGER_KNOWN_TOP_KEYS = setOf(
        "format", "version", "url", "sourceUrl", "link", "exportedAt", "exported_at",
        "exportDate", "count", "totalMessages", "title", "name", "items",
        "messages", "conversations",
    )
    private val VOYAGER_KNOWN_ITEM_KEYS = setOf(
        "role", "sender", "author", "content", "text", "message", "body",
        "createdAt", "timestamp", "time", "date", "model", "id",
    )
}
