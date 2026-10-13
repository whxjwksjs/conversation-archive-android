package app.convarchive.model.parsers

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.CodeBlock
import app.convarchive.model.ImportResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Best-effort importer for `.html` / `.htm` files (e.g. a chat page saved
 * from a browser).
 *
 * - `<script>`, `<style>`, `<noscript>` and comments are removed.
 * - `<pre>` blocks become structured code blocks (kept out of the body text).
 * - Headings (`<h1>`-`<h6>`) act as message-split hints; other block tags
 *   (`p`, `div`, `li`, `br`, `tr`...) become line breaks.
 * - HTML entities are decoded.
 *
 * Message-structure reconstruction is best-effort; the import always carries
 * a warning saying so. The untouched HTML is kept in `originalExport`.
 */
object HtmlImporter {
    fun parse(text: String, fileName: String = "import.html"): ImportResult {
        if (text.isBlank()) {
            return ImportResult(
                ok = false,
                errors = listOf("HTML file is empty"),
                detectedExporter = EXPORTER_ID,
            )
        }
        val warnings = mutableListOf(
            "message structure reconstruction from HTML is best-effort",
        )

        var html = text
        // Drop scripts, styles, comments, head metadata that is never content.
        html = Regex("(?is)<script.*?</script>").replace(html, " ")
        html = Regex("(?is)<style.*?</style>").replace(html, " ")
        html = Regex("(?is)<noscript.*?</noscript>").replace(html, " ")
        html = Regex("(?is)<!--.*?-->").replace(html, " ")
        html = Regex("(?is)<head.*?</head>").replace(html, " ")

        // Harvest <pre> blocks as code before tag-stripping.
        val codeBlocks = mutableListOf<CodeBlock>()
        html = Regex("(?is)<pre[^>]*>(.*?)</pre>").replace(html) { m ->
            val inner = decodeEntities(stripTags(m.groupValues[1])).trim('\n')
            val lang = Regex("(?i)language-([a-z0-9+#_.-]+)").find(m.value)?.groupValues?.get(1)
            codeBlocks += CodeBlock(language = lang?.lowercase(), code = inner)
            "\n[code block ${codeBlocks.size}]\n"
        }

        // Title from <title> or first heading.
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(text)
            ?.groupValues?.get(1)?.let { decodeEntities(stripTags(it)).trim() }
            ?.takeIf { it.isNotBlank() }

        // Headings become split markers; block tags become newlines.
        html = Regex("(?i)<h[1-6][^>]*>").replace(html, "\n## ")
        html = Regex("(?i)</h[1-6\\s]*>").replace(html, "\n")
        html = Regex("(?i)<(br|p|div|li|tr|section|article|blockquote)[^>]*>").replace(html, "\n")
        html = Regex("(?i)</(p|div|li|tr|section|article|blockquote|ul|ol|table)>").replace(html, "\n")
        val bodyText = decodeEntities(stripTags(html))
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        if (bodyText.isBlank() && codeBlocks.isEmpty()) {
            return ImportResult(
                ok = false,
                errors = listOf("no readable text found in HTML"),
                detectedExporter = EXPORTER_ID,
            )
        }

        // Reuse the markdown splitter on the "## "-prefixed heading markers.
        val split = TranscriptSplit.split(bodyText, markdownAware = true, fileName = fileName)
        val messages = if (split.foundStructure && split.messages.isNotEmpty()) {
            split.messages
        } else {
            listOf(
                CanonicalMessage(
                    role = "event",
                    text = bodyText,
                    markdown = bodyText,
                    raw = JsonObject(mapOf("heuristicSplit" to JsonPrimitive(false))),
                ),
            )
        }
        if (!split.foundStructure) {
            warnings += "no message structure detected; stored as one document"
        }
        // Attach harvested <pre> blocks to the last message (or the single one).
        val withCode = if (codeBlocks.isNotEmpty() && messages.isNotEmpty()) {
            val last = messages.last()
            messages.dropLast(1) + last.copy(codeBlocks = last.codeBlocks + codeBlocks)
        } else messages

        val conv = CanonicalConversation(
            platform = "other",
            title = title ?: split.titleHint?.takeIf { it.isNotBlank() } ?: fallbackTitle(fileName),
            exportedAt = ParseHelpers.nowIso(),
            exporter = EXPORTER_ID,
            notes = "Imported from HTML; message structure is best-effort.",
            messages = withCode,
            originalExport = JsonPrimitive(text),
        )
        return ImportResult(
            ok = true,
            conversation = conv,
            warnings = warnings,
            detectedPlatform = "other",
            detectedExporter = EXPORTER_ID,
        )
    }

    private fun stripTags(s: String): String = Regex("<[^>]+>").replace(s, "")

    private fun decodeEntities(s: String): String {
        var out = s
        for ((name, ch) in NAMED_ENTITIES) out = out.replace("&$name;", ch)
        out = Regex("&#(\\d+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        out = Regex("(?i)&#x([0-9a-f]+);").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        return out
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "nbsp" to " ", "copy" to "©", "reg" to "®",
        "hellip" to "…", "mdash" to "—", "ndash" to "–",
    )

    private const val EXPORTER_ID = "convarchive-html-importer"
}
