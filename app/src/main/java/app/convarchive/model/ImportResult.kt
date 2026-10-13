package app.convarchive.model

/**
 * Outcome of importing one file.
 *
 * `duplicateOfId` is intentionally NOT set here; the repository layer assigns
 * it after the duplicate hash check.
 */
data class ImportResult(
    val ok: Boolean,
    val conversation: CanonicalConversation? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val detectedPlatform: String = "other",
    val detectedExporter: String = "unknown",
)
