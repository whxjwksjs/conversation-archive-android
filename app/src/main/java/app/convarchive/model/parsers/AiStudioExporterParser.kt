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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parser for rmtd418/ai-studio-conversation-exporter (MIT) JSON exports.
 *
 * README-verified facts: the extension reads AI Studio's conversation resource
 * network request and its JSON export "places the ordered conversation in
 * `turns` and `entries`", preserving real order (user message -> model
 * thought -> model answer) and keeping multiple model responses.
 *
 * *** INFERRED DETAIL *** The extension source was not line-by-line reviewed,
 * so the exact per-turn/entry field names (`user`, `thought`, `responses`,
 * `role`, `content`) come from the project's README claims plus an authored
 * fixture. This parser therefore prefers the flat `entries` list when present
 * and otherwise expands `turns`, and it tolerates several field-name aliases.
 * Unrecognized fields land in message.raw / conversation.metadata.
 */
object AiStudioExporterParser {
    fun parse(text: String, fileName: String = "aistudio-export.json"): ImportResult {
        val root = try {
            lenientJson.parseToJsonElement(text)
        } catch (e: Exception) {
            return fail(
                listOf("AI Studio exporter JSON parse error: ${e.message?.lineSequence()?.firstOrNull() ?: e}"),
                fileName, EXPORTER_ID,
            )
        }
        val rootObj = obj(root)
            ?: return fail(listOf("AI Studio exporter export must be a JSON object"), fileName, EXPORTER_ID)

        val warnings = mutableListOf<String>()
        val messages = mutableListOf<CanonicalMessage>()

        val entries = arr(rootObj, "entries")
        if (entries != null) {
            entries.forEachIndexed { index, el ->
                val m = obj(el)
                if (m == null) {
                    warnings += "entry #$index was not an object and was skipped"
                    return@forEachIndexed
                }
                messages += entryToMessage(m, index, warnings) ?: return@forEachIndexed
            }
        } else {
            val turns = arr(rootObj, "turns")
                ?: return fail(
                    listOf("AI Studio exporter export has neither \"entries\" nor \"turns\""),
                    fileName, EXPORTER_ID,
                )
            val firstTurn = turns.firstOrNull()?.let { obj(it) }
            if (firstTurn != null && firstTurn.containsKey("role")) {
                // Alternate "turns" shape: a flat list of {role, content}
                // entries (seen in NDJSON-per-line exports). Treat as entries.
                warnings += "\"turns\" holds role/content entries; parsed as a flat message list"
                turns.forEachIndexed { index, el ->
                    val m = obj(el) ?: return@forEachIndexed
                    entryToMessage(m, index, warnings)?.let { messages += it }
                }
            } else {
                warnings += "\"entries\" list not found; reconstructed message order from \"turns\" (inferred schema)"
            turns.forEachIndexed { ti, el ->
                val t = obj(el)
                if (t == null) {
                    warnings += "turn #$ti was not an object and was skipped"
                    return@forEachIndexed
                }
                str(t, "user", "userMessage", "prompt")?.let { userText ->
                    messages += CanonicalMessage(
                        role = "user",
                        text = userText,
                        markdown = userText,
                        codeBlocks = ParseHelpers.extractCodeBlocks(userText),
                        raw = JsonObject(t.filterKeys { it !in TURN_KNOWN_KEYS }),
                    )
                }
                str(t, "thought", "modelThought", "thinking")?.let { thought ->
                    messages += CanonicalMessage(
                        role = "assistant",
                        text = thought,
                        markdown = thought,
                        codeBlocks = ParseHelpers.extractCodeBlocks(thought),
                        raw = JsonObject(mapOf("originalRole" to JsonPrimitive("thought"))),
                    )
                }
                val responses = when (val r = t["responses"] ?: t["answers"] ?: t["modelResponses"]) {
                    is JsonArray -> r.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.let { o -> str(o, "content", "text") } }
                    is JsonPrimitive -> listOfNotNull(r.contentOrNull)
                    else -> emptyList()
                }
                responses.forEach { resp ->
                    messages += CanonicalMessage(
                        role = "assistant",
                        text = resp,
                        markdown = resp,
                        codeBlocks = ParseHelpers.extractCodeBlocks(resp),
                    )
                }
            }
        }
        }

        val unknownTop = rootObj.filterKeys { it !in TOP_KNOWN_KEYS }
        val conv = CanonicalConversation(
            platform = "aistudio",
            title = str(rootObj, "title", "name") ?: fallbackTitle(fileName),
            sourceId = str(rootObj, "promptId", "conversationId", "id"),
            sourceUrl = str(rootObj, "url", "sourceUrl"),
            createdAt = str(rootObj, "createdAt", "created_at", "startedAt"),
            exportedAt = str(rootObj, "exportedAt", "exported_at") ?: ParseHelpers.nowIso(),
            exporter = EXPORTER_ID,
            messages = messages,
            metadata = JsonObject(
                unknownTop +
                    (str(rootObj, "model", "modelName")?.let { mapOf("model" to JsonPrimitive(it)) } ?: emptyMap()) +
                    (str(rootObj, "systemInstruction", "system_instruction")?.let {
                        mapOf("systemInstruction" to JsonPrimitive(it))
                    } ?: emptyMap()),
            ),
            originalExport = root,
        )
        if (messages.isEmpty()) warnings += "no messages could be extracted"
        return ImportResult(
            ok = true,
            conversation = conv,
            warnings = warnings,
            detectedPlatform = "aistudio",
            detectedExporter = EXPORTER_ID,
        )
    }

    private fun entryToMessage(m: JsonObject, index: Int, warnings: MutableList<String>): CanonicalMessage? {
        val rawRole = str(m, "role", "sender", "author")
        val content = str(m, "content", "text", "message", "body")
        if (content == null) {
            warnings += "entry #$index had no readable text and was skipped"
            return null
        }
        // Model "thoughts" are reasoning traces, not answers: keep them visible
        // as assistant messages but tag the original role so the UI can label them.
        val isThought = rawRole?.equals("thought", ignoreCase = true) == true
        val unknown = m.filterKeys { it !in ENTRY_KNOWN_KEYS }
        val raw = JsonObject(
            unknown + if (isThought) mapOf("originalRole" to JsonPrimitive("thought")) else emptyMap(),
        )
        return CanonicalMessage(
            role = if (isThought) "assistant" else ParseHelpers.normRole(rawRole),
            text = content,
            markdown = content,
            createdAt = str(m, "createdAt", "timestamp", "time"),
            model = str(m, "model"),
            codeBlocks = ParseHelpers.extractCodeBlocks(content),
            raw = raw,
        )
    }

    private const val EXPORTER_ID = "rmtd418-ai-studio-conversation-exporter"

    private val TOP_KNOWN_KEYS = setOf(
        "entries", "turns", "title", "name", "promptId", "conversationId", "id",
        "url", "sourceUrl", "createdAt", "created_at", "startedAt",
        "exportedAt", "exported_at", "model", "modelName",
        "systemInstruction", "system_instruction",
    )
    private val ENTRY_KNOWN_KEYS = setOf(
        "role", "sender", "author", "content", "text", "message", "body",
        "createdAt", "timestamp", "time", "model",
    )
    private val TURN_KNOWN_KEYS = setOf(
        "user", "userMessage", "prompt", "thought", "modelThought", "thinking",
        "responses", "answers", "modelResponses",
    )
}
