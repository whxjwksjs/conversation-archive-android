package app.convarchive.model.parsers

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.CodeBlock
import app.convarchive.model.ImportResult
import app.convarchive.util.Dates
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Shared helpers for the import parsers. Not part of the public contract. */
internal object ParseHelpers {
    /** Lenient JSON for third-party exports: unknown keys ignored, relaxed syntax. */
    @OptIn(ExperimentalSerializationApi::class)
    val lenientJson: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun obj(el: JsonElement?): JsonObject? = when (el) {
        is JsonObject -> el
        else -> null
    }

    fun str(obj: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            val v = obj[k] ?: continue
            if (v is JsonNull) continue
            if (v is JsonPrimitive) return v.contentOrNull
        }
        return null
    }

    fun arr(obj: JsonObject, key: String): JsonArray? = obj[key] as? JsonArray

    fun int(obj: JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.intOrNull

    fun long(obj: JsonObject, key: String): Long? =
        (obj[key] as? JsonPrimitive)?.longOrNull

    fun bool(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.booleanOrNull

    fun strList(obj: JsonObject, key: String): List<String> = when (val el = obj[key]) {
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(el.contentOrNull)
        else -> emptyList()
    }

    /** Normalize a role string to the canonical role set; unknown -> "event". */
    fun normRole(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "user", "human" -> "user"
        "assistant", "model", "ai", "bot", "gemini" -> "assistant"
        "system" -> "system"
        "tool", "function" -> "tool"
        "event", "error", "status" -> "event"
        else -> "event"
    }

    fun nowIso(): String = Dates.nowIso()

    private val FENCE = Regex("```([A-Za-z0-9+#_.-]*)[ \t]*\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    /**
     * Extract fenced code blocks from markdown text. The text is returned
     * unchanged (fences stay inline) and the blocks are also listed
     * structurally so exporters can render them as code.
     */
    fun extractCodeBlocks(text: String): List<CodeBlock> =
        FENCE.findAll(text).map { m ->
            CodeBlock(
                language = m.groupValues[1].takeIf { it.isNotBlank() }?.lowercase(),
                code = m.groupValues[2].trimEnd('\n'),
            )
        }.toList()

    fun truncate(s: String, max: Int = 200_000): Pair<String, Boolean> =
        if (s.length <= max) s to false else s.substring(0, max) to true

    fun fail(errors: List<String>, fileName: String, detectedExporter: String): ImportResult =
        ImportResult(
            ok = false,
            errors = errors,
            detectedExporter = detectedExporter,
            warnings = listOf("source file: $fileName"),
        )
}

/** Base title used when an exporter provides none. */
internal fun fallbackTitle(fileName: String): String =
    fileName.substringAfterLast('/').substringAfterLast('\\')
        .substringBeforeLast('.').takeIf { it.isNotBlank() } ?: "Imported conversation"
