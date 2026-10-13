package app.convarchive.model.parsers

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalJson
import app.convarchive.model.CanonicalValidation
import app.convarchive.model.ImportResult
import app.convarchive.util.Dates
import kotlinx.serialization.json.contentOrNull

/**
 * Parses canonical schema v1 JSON (produced by this app or its companion
 * userscripts). Tolerant: unknown fields are preserved via the
 * metadata/raw catch-alls, missing `messages` becomes an empty list.
 */
object CanonicalParser {
    fun parse(text: String, sourceName: String = "import.json"): ImportResult {
        val element = try {
            CanonicalJson.json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ImportResult(
                ok = false,
                errors = listOf("JSON parse error: ${e.message?.lineSequence()?.firstOrNull() ?: e}"),
                detectedExporter = "canonical?",
            )
        }
        if (element !is kotlinx.serialization.json.JsonObject) {
            return ImportResult(
                ok = false,
                errors = listOf("canonical import must be a JSON object"),
                detectedExporter = "canonical",
            )
        }

        val conv = try {
            CanonicalJson.decode(text)
        } catch (e: Exception) {
            return ImportResult(
                ok = false,
                errors = listOf("canonical decode failed: ${e.message?.lineSequence()?.firstOrNull() ?: e}"),
                detectedExporter = "canonical",
            )
        }

        val warnings = mutableListOf<String>()
        if (!element.containsKey("messages")) warnings += "missing \"messages\"; imported with an empty message list"
        val platformRaw = (element["platform"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull?.trim()?.lowercase()
        if (platformRaw != null && platformRaw !in setOf("gemini", "aistudio", "jules", "other")) {
            warnings += "unknown platform \"$platformRaw\" mapped to \"other\""
        }
        warnings += CanonicalValidation.validate(conv)

        val fixed = conv.copy(
            originalExport = element,
            exportedAt = conv.exportedAt.ifBlank { Dates.nowIso() },
        )
        return ImportResult(
            ok = true,
            conversation = fixed,
            warnings = warnings.distinct(),
            detectedPlatform = fixed.platform,
            detectedExporter = fixed.exporter.ifBlank { "canonical" },
        )
    }
}
