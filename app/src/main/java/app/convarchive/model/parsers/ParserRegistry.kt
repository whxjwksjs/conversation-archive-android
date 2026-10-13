package app.convarchive.model.parsers

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.ImportResult
import app.convarchive.model.parsers.ParseHelpers.lenientJson
import app.convarchive.model.parsers.ParseHelpers.obj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sniffs an import file by extension, then by JSON shape, and dispatches to
 * the right parser. Every JSON parser preserves the untouched input in
 * `conversation.originalExport` and stashes unrecognized fields in
 * message.raw / conversation.metadata instead of dropping them.
 */
object ParserRegistry {

    /** Dispatch on file extension; falls back to JSON sniffing for unknown extensions. */
    fun parseFile(fileName: String, bytes: ByteArray): ImportResult {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "json", "jsonc" -> parseJson(bytes.toString(Charsets.UTF_8), fileName)
            "ndjson", "jsonl" -> parseNdjson(bytes.toString(Charsets.UTF_8), fileName)
            "md", "markdown" -> MarkdownImporter.parse(bytes.toString(Charsets.UTF_8), fileName)
            "txt", "text" -> TextImporter.parse(bytes.toString(Charsets.UTF_8), fileName)
            "html", "htm" -> HtmlImporter.parse(bytes.toString(Charsets.UTF_8), fileName)
            "pdf" -> PdfImporter.parse(bytes, fileName)
            else -> {
                // Unknown extension: try JSON first, then plain text.
                val text = bytes.toString(Charsets.UTF_8)
                val asJson = runCatching { parseJson(text, fileName) }.getOrNull()
                if (asJson?.ok == true) asJson
                else TextImporter.parse(text, fileName)
            }
        }
    }

    /**
     * Detect the JSON flavor by shape and dispatch:
     * canonical (schemaVersion==1 or platform+messages) -> Voyager
     * (`format` ~ "*-voyager*" or `items`) -> rmtd418 ai-studio-conversation-exporter
     * (`turns`/`entries`) -> Tokisaki-Galaxy aistudio-dump-script
     * (`system_instruction` + `messages` of role/content, no platform/schemaVersion)
     * -> Google Takeout My Activity (array of header/title/time entries)
     * -> generic fallback (whole JSON wrapped as one event message).
     */
    fun parseJson(text: String, fileName: String = "import.json"): ImportResult {
        val root: JsonElement = try {
            lenientJson.parseToJsonElement(text)
        } catch (e: Exception) {
            return ImportResult(
                ok = false,
                errors = listOf(jsonErrorMessage(e)),
                detectedExporter = "unknown",
            )
        }
        val o = obj(root)
        if (o != null) {
            // 1. Canonical schema v1.
            val schemaVersion = (o["schemaVersion"] as? JsonPrimitive)?.content
            val hasPlatformMessages = o.containsKey("platform") && o["messages"] is JsonArray
            if (schemaVersion == "1" || hasPlatformMessages || isConvarchiveExporter(o)) {
                return CanonicalParser.parse(text, fileName)
            }
            // 2. Voyager.
            if (isVoyager(o)) return VoyagerParser.parse(text, fileName)
            // 3. rmtd418 ai-studio-conversation-exporter.
            if (o.containsKey("turns") || o.containsKey("entries")) {
                return AiStudioExporterParser.parse(text, fileName)
            }
            // 4. Tokisaki-Galaxy aistudio-dump-script.
            if (isDumpScript(o)) return AiStudioDumpParser.parse(text, fileName)
            // 5. Google Takeout.
            if (TakeoutParser.looksLikeTakeoutFile(root)) return TakeoutParser.parse(text, fileName)
            // 6. Multi-conversation exports (e.g. caffeineGMT/gemini-export):
            //    { "conversations": [{ "title", "url", "messages": [...] }, ...] }.
            //    ImportResult holds one conversation, so the first is imported
            //    and the rest are reported in warnings.
            val convList = o["conversations"]
            if (convList is JsonArray && convList.isNotEmpty()) {
                return parseConversationList(convList, root, text, fileName)
            }
            // 7. Generic single-object fallback.
            return genericFallback(root, text, fileName)
        }
        if (root is JsonArray) {
            // Top-level array: Takeout shape, or an array of canonical/voyager docs.
            if (TakeoutParser.looksLikeTakeoutFile(root)) return TakeoutParser.parse(text, fileName)
            val firstObj = root.firstOrNull()?.let { obj(it) }
            if (firstObj != null) {
                if (isVoyager(firstObj)) return VoyagerParser.parse(text, fileName)
                if (firstObj.containsKey("schemaVersion") || firstObj.containsKey("platform")) {
                    return CanonicalParser.parse(text, fileName)
                }
            }
            return genericFallback(root, text, fileName)
        }
        return genericFallback(root, text, fileName)
    }

    /** Newline-delimited JSON: parse each line, merge into one conversation. */
    private fun parseNdjson(text: String, fileName: String): ImportResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return ImportResult(
                ok = false,
                errors = listOf("NDJSON file has no non-empty lines"),
                detectedExporter = "unknown",
            )
        }
        val warnings = mutableListOf<String>()
        val messages = mutableListOf<CanonicalMessage>()
        var platform = "other"
        var title: String? = null
        var ok = false
        for ((index, line) in lines.withIndex()) {
            val r = parseJson(line, "$fileName#line${index + 1}")
            val c = r.conversation
            if (!r.ok || c == null) {
                warnings += "line ${index + 1} skipped: ${r.errors.firstOrNull() ?: "unparseable"}"
                continue
            }
            ok = true
            if (title == null && c.title.isNotBlank()) title = c.title
            if (platform == "other" && c.platform != "other") platform = c.platform
            messages += c.messages
            warnings += r.warnings
        }
        if (!ok) {
            return ImportResult(
                ok = false,
                errors = listOf("no NDJSON lines could be parsed") + warnings,
                detectedExporter = "unknown",
            )
        }
        val conv = CanonicalConversation(
            platform = platform,
            title = title ?: fallbackTitle(fileName),
            exportedAt = ParseHelpers.nowIso(),
            exporter = "convarchive-ndjson-importer",
            messages = messages,
            originalExport = JsonPrimitive(text),
        )
        return ImportResult(
            ok = true,
            conversation = conv,
            warnings = warnings,
            detectedPlatform = platform,
            detectedExporter = "convarchive-ndjson-importer",
        )
    }

    /** Multi-conversation file: import the first conversation, report the rest. */
    private fun parseConversationList(
        convList: JsonArray,
        root: JsonElement,
        text: String,
        fileName: String,
    ): ImportResult {
        val first = obj(convList[0])
            ?: return genericFallback(root, text, fileName)
        val warnings = mutableListOf<String>()
        if (convList.size > 1) {
            warnings += "file contains ${convList.size} conversations; imported the first one only " +
                "(\"${((first["title"] as? JsonPrimitive)?.content ?: "").take(60)}\"). " +
                "Split the file to import the rest."
        }
        val messages = mutableListOf<CanonicalMessage>()
        val items = first["messages"] as? JsonArray
        items?.forEachIndexed { index, el ->
            val m = obj(el)
            if (m == null) {
                warnings += "message #$index was not an object and was skipped"
                return@forEachIndexed
            }
            val content = ((m["content"] as? JsonPrimitive)?.content
                ?: (m["text"] as? JsonPrimitive)?.content)
            if (content == null) {
                warnings += "message #$index had no readable text and was skipped"
                return@forEachIndexed
            }
            messages += CanonicalMessage(
                role = ParseHelpers.normRole((m["role"] as? JsonPrimitive)?.content),
                text = content,
                markdown = content,
                codeBlocks = ParseHelpers.extractCodeBlocks(content),
                raw = JsonObject(m.filterKeys { it !in CONV_LIST_ITEM_KEYS }),
            )
        }
        val url = (first["url"] as? JsonPrimitive)?.content
        val conv = CanonicalConversation(
            platform = "gemini",
            title = ((first["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() })
                ?: fallbackTitle(fileName),
            sourceUrl = url,
            exportedAt = ((root as? JsonObject)?.get("exported_at") as? JsonPrimitive)?.content
                ?: ParseHelpers.nowIso(),
            exporter = "gemini-export-multi",
            messages = messages,
            metadata = JsonObject(
                mapOf("conversationIndex" to JsonPrimitive(0)) +
                    (obj(root)?.filterKeys { it != "conversations" } ?: emptyMap()),
            ),
            originalExport = root,
        )
        if (messages.isEmpty()) warnings += "no messages could be extracted"
        return ImportResult(
            ok = true,
            conversation = conv,
            warnings = warnings,
            detectedPlatform = "gemini",
            detectedExporter = "gemini-export-multi",
        )
    }

    private val CONV_LIST_ITEM_KEYS = setOf("role", "content", "text")

    /** Last resort: keep the whole JSON verbatim as one event message. */
    private fun genericFallback(root: JsonElement, text: String, fileName: String): ImportResult {
        val (body, truncated) = ParseHelpers.truncate(text)
        val warnings = mutableListOf(
            "unrecognized JSON shape; stored the whole document as one record",
        )
        if (truncated) warnings += "document text truncated to 200,000 characters in the message body (originalExport keeps the full input)"
        val titleGuess = (obj(root))?.let { o ->
            ((o["title"] as? JsonPrimitive)?.content
                ?: (o["name"] as? JsonPrimitive)?.content)
                ?.takeIf { it.isNotBlank() }
        } ?: fallbackTitle(fileName)
        val conv = CanonicalConversation(
            platform = "other",
            title = titleGuess,
            exportedAt = ParseHelpers.nowIso(),
            exporter = "convarchive-generic-importer",
            notes = "Unrecognized JSON shape; the full document is preserved in originalExport.",
            messages = listOf(
                CanonicalMessage(
                    role = "event",
                    text = body,
                    raw = JsonObject(mapOf("unrecognizedShape" to JsonPrimitive(true))),
                ),
            ),
            originalExport = root,
        )
        return ImportResult(
            ok = true,
            conversation = conv,
            warnings = warnings,
            detectedPlatform = "other",
            detectedExporter = "generic",
        )
    }

    private fun isVoyager(o: JsonObject): Boolean {
        val format = (o["format"] as? JsonPrimitive)?.content.orEmpty()
        if ("voyager" in format.lowercase()) return true
        // Tolerant: items/messages arrays of role+content-ish objects without
        // any other known marker.
        if (o.containsKey("items") && o["items"] is JsonArray) return true
        return false
    }

    private fun isDumpScript(o: JsonObject): Boolean {
        if (o.containsKey("platform") || o.containsKey("schemaVersion")) return false
        if (!o.containsKey("messages")) return false
        if (o["messages"] !is JsonArray) return false
        if (!o.containsKey("system_instruction") && !o.containsKey("systemInstruction")) {
            // Without the marker key, require role/content-shaped messages.
            val first = (o["messages"] as JsonArray).firstOrNull()?.let { obj(it) } ?: return false
            val hasContent = first.containsKey("content") && !first.containsKey("text")
            if (!hasContent) return false
        }
        return true
    }

    private fun isConvarchiveExporter(o: JsonObject): Boolean {
        val exporter = (o["exporter"] as? JsonPrimitive)?.content.orEmpty()
        return exporter.startsWith("convarchive-")
    }

    private fun jsonErrorMessage(e: Exception): String {
        val firstLine = e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        return if (firstLine.isNotEmpty()) "JSON parse error: $firstLine"
        else "JSON parse error: ${e::class.simpleName}"
    }
}
