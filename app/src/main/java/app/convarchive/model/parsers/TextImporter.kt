package app.convarchive.model.parsers

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.ImportResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Best-effort importer for `.txt` files. Same heuristic as the Markdown
 * importer but without heading/bold-marker handling (see [TranscriptSplit]).
 * No structure found -> one `role:"event"` message with a warning.
 */
object TextImporter {
    fun parse(text: String, fileName: String = "import.txt"): ImportResult {
        if (text.isBlank()) {
            return ImportResult(
                ok = false,
                errors = listOf("text file is empty"),
                detectedExporter = EXPORTER_ID,
            )
        }
        val split = TranscriptSplit.split(text, markdownAware = false, fileName = fileName)
        val messages = if (split.foundStructure) {
            split.messages
        } else {
            listOf(
                CanonicalMessage(
                    role = "event",
                    text = text.trim(),
                    codeBlocks = ParseHelpers.extractCodeBlocks(text),
                    raw = JsonObject(mapOf()),
                ),
            )
        }
        val conv = CanonicalConversation(
            platform = "other",
            title = fallbackTitle(fileName),
            exportedAt = ParseHelpers.nowIso(),
            exporter = EXPORTER_ID,
            notes = "Imported from plain text; message structure is heuristic.",
            messages = messages,
            originalExport = JsonPrimitive(text),
        )
        return ImportResult(
            ok = true,
            conversation = conv,
            warnings = split.notes,
            detectedPlatform = "other",
            detectedExporter = EXPORTER_ID,
        )
    }

    private const val EXPORTER_ID = "convarchive-text-importer"
}
