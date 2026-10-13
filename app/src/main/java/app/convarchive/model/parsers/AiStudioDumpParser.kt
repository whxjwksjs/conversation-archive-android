package app.convarchive.model.parsers

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.ImportResult
import app.convarchive.model.parsers.ParseHelpers.arr
import app.convarchive.model.parsers.ParseHelpers.fail
import app.convarchive.model.parsers.ParseHelpers.lenientJson
import app.convarchive.model.parsers.ParseHelpers.obj
import app.convarchive.model.parsers.ParseHelpers.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parser for Tokisaki-Galaxy/aistudio-dump-script ("AI Studio Chat Exporter",
 * AGPL-3.0) dumps.
 *
 * Schema basis (verified against the script's published v5.0 source by the
 * research pass; the script's README was unreachable at implementation time):
 * `{ "system_instruction": string|null, "messages": [{ "role": "user"|"assistant",
 * "content": string }] }`. The script reads rendered `ms-chat-turn` elements,
 * explicitly skips MS-THOUGHT-CHUNK elements, and exports no timestamps,
 * attachments, or message ids.
 *
 * Written from the documented schema only; no script source was copied or
 * adapted (AGPL-3.0). Parsing a file format is not adaptation.
 */
object AiStudioDumpParser {
    fun parse(text: String, fileName: String = "aistudio-dump.json"): ImportResult {
        val root = try {
            lenientJson.parseToJsonElement(text)
        } catch (e: Exception) {
            return fail(
                listOf("AI Studio dump JSON parse error: ${e.message?.lineSequence()?.firstOrNull() ?: e}"),
                fileName, EXPORTER_ID,
            )
        }
        val rootObj = obj(root)
            ?: return fail(listOf("AI Studio dump must be a JSON object"), fileName, EXPORTER_ID)

        val warnings = mutableListOf<String>()
        val items = arr(rootObj, "messages")
            ?: return fail(
                listOf("AI Studio dump has no \"messages\" array"),
                fileName, EXPORTER_ID,
            )

        val messages = items.mapIndexedNotNull { index, el ->
            val m = obj(el)
            if (m == null) {
                warnings += "message #$index was not an object and was skipped"
                return@mapIndexedNotNull null
            }
            val content = str(m, "content", "text", "message", "body")
            if (content == null) {
                warnings += "message #$index had no readable text and was skipped"
                return@mapIndexedNotNull null
            }
            val unknown = m.filterKeys { it !in ITEM_KNOWN_KEYS }
            CanonicalMessage(
                role = ParseHelpers.normRole(str(m, "role", "sender")),
                text = content,
                markdown = content,
                codeBlocks = ParseHelpers.extractCodeBlocks(content),
                raw = JsonObject(unknown),
            )
        }

        val systemInstruction = str(rootObj, "system_instruction", "systemInstruction")
        val unknownTop = rootObj.filterKeys { it !in TOP_KNOWN_KEYS }
        val conv = CanonicalConversation(
            platform = "aistudio",
            title = str(rootObj, "title", "name") ?: fallbackTitle(fileName),
            exportedAt = str(rootObj, "exportedAt", "exported_at", "dumpedAt") ?: ParseHelpers.nowIso(),
            exporter = EXPORTER_ID,
            messages = messages,
            metadata = JsonObject(
                unknownTop + (systemInstruction?.let {
                    mapOf("systemInstruction" to JsonPrimitive(it))
                } ?: emptyMap()),
            ),
            originalExport = root,
        )
        if (systemInstruction.isNullOrBlank()) {
            warnings += "no system instruction found in dump (the script only exports one when set)"
        }
        warnings += "dump exports contain no timestamps (not provided by the script)"
        if (messages.isEmpty()) warnings += "no messages could be extracted"
        return ImportResult(
            ok = true,
            conversation = conv,
            warnings = warnings,
            detectedPlatform = "aistudio",
            detectedExporter = EXPORTER_ID,
        )
    }

    private const val EXPORTER_ID = "tokisaki-galaxy-aistudio-dump-script"

    private val TOP_KNOWN_KEYS = setOf(
        "messages", "system_instruction", "systemInstruction", "title", "name",
        "exportedAt", "exported_at", "dumpedAt",
    )
    private val ITEM_KNOWN_KEYS = setOf("role", "sender", "content", "text", "message", "body")
}
