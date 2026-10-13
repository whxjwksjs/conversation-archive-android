package app.convarchive.export

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.util.Dates

/**
 * Lays a conversation out as pages of [TextRun] lines and renders them with
 * [PdfWriter]: title header, metadata, then per message a bold role heading,
 * a timestamp line, wrapped body text, and code blocks in indented Courier.
 *
 * Non-WinAnsi characters are replaced with '?' by [PdfWriter] (decision D1);
 * all other export formats preserve Unicode fully.
 */
object PdfExporter {

    private const val TITLE_SIZE = 18f
    private const val HEADING_SIZE = 13f
    private const val BODY_SIZE = 10.5f
    private const val SMALL_SIZE = 9f
    private const val CODE_SIZE = 9f

    fun export(conv: CanonicalConversation): ExportFile {
        val bytes = PdfWriter.writeDocument(layout(conv))
        return ExportFile(
            fileName = Filenames.forConversation(
                conv.platform, conv.title,
                Filenames.dateOf(conv.exportedAt), ExportFormat.PDF.ext,
            ),
            bytes = bytes,
            mimeType = ExportFormat.PDF.mimeType,
        )
    }

    /** Lay out a conversation as pages of lines (pure function, testable). */
    internal fun layout(conv: CanonicalConversation): List<List<TextRun>> {
        val pages = mutableListOf<MutableList<TextRun>>()
        var page = mutableListOf<TextRun>()
        pages += page

        var y = PdfWriter.PAGE_HEIGHT - PdfWriter.MARGIN
        fun lineHeightOf(line: List<TextRun>): Float =
            (line.maxOfOrNull { it.size } ?: BODY_SIZE) * 1.25f

        fun emit(line: List<TextRun>) {
            val h = lineHeightOf(line)
            if (y - h < PdfWriter.MARGIN) {
                page = mutableListOf()
                pages += page
                y = PdfWriter.PAGE_HEIGHT - PdfWriter.MARGIN
            }
            // Keep a role heading with at least one following line when possible.
            page += line
            y -= h
        }

        fun wrappedRuns(
            text: String,
            size: Float,
            bold: Boolean,
            mono: Boolean,
            indent: Float = 0f,
        ): List<List<TextRun>> {
            val maxWidth = PdfWriter.PAGE_WIDTH - 2 * PdfWriter.MARGIN - indent
            val out = mutableListOf<List<TextRun>>()
            for (para in text.split('\n')) {
                if (para.isBlank()) {
                    out += listOf(TextRun(" ", size, bold, mono))
                    continue
                }
                var line = StringBuilder()
                for (word in para.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (PdfWriter.textWidth(candidate, size, mono) <= maxWidth || line.isEmpty()) {
                        line = StringBuilder(candidate)
                    } else {
                        out += listOf(TextRun(line.toString(), size, bold, mono))
                        // Hard-break words longer than a full line.
                        var rest = word
                        while (PdfWriter.textWidth(rest, size, mono) > maxWidth && rest.length > 1) {
                            var cut = rest.length
                            while (cut > 1 && PdfWriter.textWidth(rest.substring(0, cut), size, mono) > maxWidth) cut--
                            out += listOf(TextRun(rest.substring(0, cut), size, bold, mono))
                            rest = rest.substring(cut)
                        }
                        line = StringBuilder(rest)
                    }
                }
                if (line.isNotEmpty()) out += listOf(TextRun(line.toString(), size, bold, mono))
            }
            return out
        }

        fun emitWrapped(text: String, size: Float, bold: Boolean, mono: Boolean, indent: Float = 0f) {
            for (line in wrappedRuns(text, size, bold, mono, indent)) emit(line)
        }

        // Title + metadata.
        val title = conv.title.ifBlank { "Untitled conversation" }
        emitWrapped(title, TITLE_SIZE, bold = true, mono = false)
        emit(listOf(TextRun(" ", BODY_SIZE, bold = false, mono = false)))
        val meta = buildList {
            add("Platform: ${conv.platform}")
            if (conv.exportedAt.isNotBlank()) add("Exported: ${conv.exportedAt}")
            if (!conv.createdAt.isNullOrBlank()) add("Created: ${conv.createdAt}")
            if (conv.tags.isNotEmpty()) add("Tags: ${conv.tags.joinToString(", ")}")
            add("Messages: ${conv.messages.size}")
        }
        for (m in meta) emitWrapped(m, SMALL_SIZE, bold = false, mono = false)
        if (conv.notes.isNotBlank()) {
            emit(listOf(TextRun(" ", BODY_SIZE, bold = false, mono = false)))
            emitWrapped("Notes: ${conv.notes.trim()}", SMALL_SIZE, bold = false, mono = false)
        }
        emit(listOf(TextRun(" ", BODY_SIZE, bold = false, mono = false)))

        // Messages.
        for ((index, msg) in conv.messages.withIndex()) {
            if (index > 0) emit(listOf(TextRun(" ", BODY_SIZE, bold = false, mono = false)))
            val roleLabel = msg.role.replaceFirstChar { it.uppercase() }
            val ts = Dates.formatForDisplay(msg.createdAt)
            val heading = buildString {
                append(roleLabel)
                if (ts.isNotBlank()) append(" - ").append(ts)
                if (!msg.model.isNullOrBlank()) append(" [").append(msg.model).append(']')
            }
            emitWrapped(heading, HEADING_SIZE, bold = true, mono = false)
            emitWrapped(msg.text.trimEnd(), BODY_SIZE, bold = false, mono = false)
            for (block in msg.codeBlocks) {
                emit(listOf(TextRun(" ", SMALL_SIZE, bold = false, mono = false)))
                if (!block.language.isNullOrBlank()) {
                    emitWrapped("[${block.language}]", SMALL_SIZE, bold = true, mono = true)
                }
                // Indent code: prepend spaces (PdfWriter has no indent param).
                val indented = block.code.trimEnd().lines()
                    .joinToString("\n") { "    $it" }
                emitWrapped(indented, CODE_SIZE, bold = false, mono = true)
            }
        }

        return pages
    }
}
