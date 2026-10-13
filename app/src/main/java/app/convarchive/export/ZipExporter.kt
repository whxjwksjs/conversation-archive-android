package app.convarchive.export

import app.convarchive.util.Dates
import app.convarchive.util.Io

/** Multi-conversation export: one file per conversation inside a `.zip`. */
object ZipExporter {
    fun zip(files: List<ExportFile>): ExportFile {
        require(files.isNotEmpty()) { "nothing to zip" }
        val entries = files.map { it.fileName to it.bytes }
        return ExportFile(
            fileName = "convarchive_export_${Dates.today()}.zip",
            bytes = Io.writeZip(entries),
            mimeType = "application/zip",
        )
    }
}
