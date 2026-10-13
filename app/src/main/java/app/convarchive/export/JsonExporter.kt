package app.convarchive.export

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalJson

/** Canonical JSON export: pretty-printed schema v1, `originalExport` preserved. */
object JsonExporter {
    fun export(conv: CanonicalConversation): ExportFile {
        val bytes = CanonicalJson.encode(conv).toByteArray(Charsets.UTF_8)
        return ExportFile(
            fileName = Filenames.forConversation(
                conv.platform, conv.title,
                Filenames.dateOf(conv.exportedAt), ExportFormat.JSON.ext,
            ),
            bytes = bytes,
            mimeType = ExportFormat.JSON.mimeType,
        )
    }
}
