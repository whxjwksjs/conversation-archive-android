package app.convarchive.model.parsers

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.ImportResult
import kotlinx.serialization.json.JsonObject

/**
 * Best-effort importer for `.md` / `.markdown` files.
 *
 * Splits on ATX headings, `**User:**`-style bold markers, plain `User:`
 * prefixes, and `---` rules (see [TranscriptSplit]); fenced code blocks become
 * structured [app.convarchive.model.CodeBlock]s. When no structure is found the
 * whole document becomes one `role:"event"` message with a warning.
 */
object MarkdownImporter {
    fun parse(text: String, fileName: String = "import.md"): ImportResult {
        if (text.isBlank()) {
            return ImportResult(
                ok = false,
                errors = listOf("markdown file is empty"),
                detectedExporter = EXPORTER_ID,
            )
        }
        val split = TranscriptSplit.split(text, markdownAware = true, fileName = fileName)
        val messages = if (split.foundStructure) {
            split.messages
        } else {
            listOf(
                CanonicalMessage(
                    role = "event",
                    text = text.trim(),
                    markdown = text.trim(),
                    codeBlocks = ParseHelpers.extractCodeBlocks(text),
                    raw = JsonObject(mapOf()),
                ),
            )
        }
        val conv = CanonicalConversation(
            platform = "other",
            title = split.titleHint?.takeIf { it.isNotBlank() } ?: fallbackTitle(fileName),
            exportedAt = ParseHelpers.nowIso(),
            exporter = EXPORTER_ID,
            notes = "Imported from Markdown; message structure is heuristic.",
            messages = messages,
            originalExport = kotlinx.serialization.json.JsonPrimitive(text),
        )
        return ImportResult(
            ok = true,
            conversation = conv,
            warnings = split.notes,
            detectedPlatform = "other",
            detectedExporter = EXPORTER_ID,
        )
    }

    private const val EXPORTER_ID = "convarchive-markdown-importer"
}
