package app.convarchive.export

/** Export target formats. */
enum class ExportFormat(val ext: String, val label: String, val mimeType: String) {
    JSON("json", "JSON", "application/json"),
    MARKDOWN("md", "Markdown", "text/markdown"),
    TEXT("txt", "Plain text", "text/plain"),
    HTML("html", "HTML", "text/html"),
    PDF("pdf", "PDF", "application/pdf"),
}

/** One rendered export file, ready to hand to SAF / share sheet. */
data class ExportFile(val fileName: String, val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExportFile) return false
        return fileName == other.fileName && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
