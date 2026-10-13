package app.convarchive.export

import app.convarchive.model.CanonicalConversation
import app.convarchive.util.Dates

/** Plain-text export: clean transcript with `====` separators between messages. */
object TextExporter {
    fun export(conv: CanonicalConversation): ExportFile {
        val sb = StringBuilder()
        val title = conv.title.ifBlank { "Untitled conversation" }
        sb.append(title).append('\n')
        sb.append("=".repeat(maxOf(title.length, 8))).append("\n\n")
        sb.append("Platform: ").append(conv.platform).append('\n')
        if (!conv.exportedAt.isBlank()) sb.append("Exported: ").append(conv.exportedAt).append('\n')
        if (!conv.createdAt.isNullOrBlank()) sb.append("Created: ").append(conv.createdAt).append('\n')
        if (conv.tags.isNotEmpty()) sb.append("Tags: ").append(conv.tags.joinToString(", ")).append('\n')
        if (conv.notes.isNotBlank()) sb.append("Notes: ").append(conv.notes.trim()).append('\n')
        sb.append('\n')

        for ((index, msg) in conv.messages.withIndex()) {
            if (index > 0) sb.append("\n====\n\n")
            val roleLabel = msg.role.replaceFirstChar { it.uppercase() }
            val ts = Dates.formatForDisplay(msg.createdAt)
            sb.append("---- ").append(roleLabel)
            if (ts.isNotBlank()) sb.append(" (").append(ts).append(')')
            if (!msg.model.isNullOrBlank()) sb.append(" [").append(msg.model).append(']')
            sb.append(" ----\n\n")
            sb.append(msg.text.trimEnd()).append('\n')
            for (block in msg.codeBlocks) {
                sb.append('\n')
                if (!block.language.isNullOrBlank()) sb.append('[').append(block.language).append("]\n")
                sb.append(block.code.trimEnd()).append('\n')
            }
        }
        sb.append('\n')

        return ExportFile(
            fileName = Filenames.forConversation(
                conv.platform, conv.title,
                Filenames.dateOf(conv.exportedAt), ExportFormat.TEXT.ext,
            ),
            bytes = sb.toString().toByteArray(Charsets.UTF_8),
            mimeType = ExportFormat.TEXT.mimeType,
        )
    }
}
