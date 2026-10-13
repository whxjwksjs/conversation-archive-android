package app.convarchive.export

import app.convarchive.model.CanonicalConversation
import app.convarchive.util.Dates

/**
 * Markdown export: `# Title`, a metadata block, then one `##` section per
 * message with role + timestamp, fenced code blocks with language tags.
 * ASCII-only structure (no emoji markers).
 */
object MarkdownExporter {
    fun export(conv: CanonicalConversation): ExportFile {
        val sb = StringBuilder()
        sb.append("# ").append(conv.title.ifBlank { "Untitled conversation" }).append("\n\n")

        // Metadata block.
        sb.append("- Platform: ").append(conv.platform).append('\n')
        if (!conv.exportedAt.isBlank()) sb.append("- Exported: ").append(conv.exportedAt).append('\n')
        if (!conv.createdAt.isNullOrBlank()) sb.append("- Created: ").append(conv.createdAt).append('\n')
        if (!conv.sourceUrl.isNullOrBlank()) sb.append("- Source: ").append(conv.sourceUrl).append('\n')
        if (conv.tags.isNotEmpty()) sb.append("- Tags: ").append(conv.tags.joinToString(", ")).append('\n')
        if (conv.favorite) sb.append("- Favorite: yes\n")
        if (conv.notes.isNotBlank()) sb.append("- Notes: ").append(conv.notes.trim()).append('\n')
        sb.append("- Messages: ").append(conv.messages.size).append('\n')
        sb.append('\n')

        for (msg in conv.messages) {
            val roleLabel = msg.role.replaceFirstChar { it.uppercase() }
            val ts = Dates.formatForDisplay(msg.createdAt)
            sb.append("## ").append(roleLabel)
            if (ts.isNotBlank()) sb.append(" - ").append(ts)
            if (!msg.model.isNullOrBlank()) sb.append(" (").append(msg.model).append(')')
            sb.append("\n\n")
            val body = (msg.markdown ?: msg.text).trimEnd()
            sb.append(body).append("\n\n")
            for (block in msg.codeBlocks) {
                sb.append("```").append(block.language ?: "").append('\n')
                sb.append(block.code.trimEnd()).append('\n')
                sb.append("```\n\n")
            }
        }

        return ExportFile(
            fileName = Filenames.forConversation(
                conv.platform, conv.title,
                Filenames.dateOf(conv.exportedAt), ExportFormat.MARKDOWN.ext,
            ),
            bytes = sb.toString().toByteArray(Charsets.UTF_8),
            mimeType = ExportFormat.MARKDOWN.mimeType,
        )
    }
}
