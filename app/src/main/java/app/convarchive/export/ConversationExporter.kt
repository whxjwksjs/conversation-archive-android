package app.convarchive.export

import app.convarchive.model.CanonicalConversation

/**
 * Single entry point for exports. `exportSingle` renders one conversation to
 * the chosen format; `exportMany` renders each conversation and zips them when
 * there is more than one (a single conversation exports as a plain file).
 */
object ConversationExporter {

    fun exportSingle(conv: CanonicalConversation, format: ExportFormat): ExportFile =
        when (format) {
            ExportFormat.JSON -> JsonExporter.export(conv)
            ExportFormat.MARKDOWN -> MarkdownExporter.export(conv)
            ExportFormat.TEXT -> TextExporter.export(conv)
            ExportFormat.HTML -> HtmlExporter.export(conv)
            ExportFormat.PDF -> PdfExporter.export(conv)
        }

    fun exportMany(convs: List<CanonicalConversation>, format: ExportFormat): ExportFile {
        require(convs.isNotEmpty()) { "no conversations to export" }
        if (convs.size == 1) return exportSingle(convs[0], format)
        val used = mutableSetOf<String>()
        val files = convs.map { conv ->
            val file = exportSingle(conv, format)
            file.copy(fileName = dedupe(file.fileName, used))
        }
        return ZipExporter.zip(files)
    }

    /** Append _2, _3, ... only when a name already occurred in this zip. */
    private fun dedupe(fileName: String, used: MutableSet<String>): String {
        if (used.add(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val base = if (dot < 0) fileName else fileName.substring(0, dot)
        val ext = if (dot < 0) "" else fileName.substring(dot)
        var n = 2
        while (true) {
            val candidate = "${base}_$n$ext"
            if (used.add(candidate)) return candidate
            n++
        }
    }
}
