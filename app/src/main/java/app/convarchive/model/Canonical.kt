package app.convarchive.model

import app.convarchive.util.Hashing
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
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
import java.text.Normalizer

// Canonical JSON schema v1 for Conversation Archive (see ARCHITECTURE.md §1).
// Unknown fields are never dropped: custom serializers merge unrecognized
// top-level keys into `metadata` (conversation) and unrecognized message keys
// into `raw` (message). Data moves location on a decode/encode round trip but
// is preserved verbatim.

@Serializable(with = CanonicalConversationSerializer::class)
data class CanonicalConversation(
    val schemaVersion: Int = 1,
    val platform: String = "other",
    val title: String = "",
    val sourceId: String? = null,
    val sourceUrl: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val exportedAt: String = "",
    val exporter: String = "convarchive-app/1.0.0",
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val notes: String = "",
    val messages: List<CanonicalMessage> = emptyList(),
    val metadata: JsonObject = JsonObject(emptyMap()),
    val originalExport: JsonElement? = null,
)

@Serializable(with = CanonicalMessageSerializer::class)
data class CanonicalMessage(
    val role: String = "event",
    val text: String = "",
    val markdown: String? = null,
    val createdAt: String? = null,
    val model: String? = null,
    val codeBlocks: List<CodeBlock> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CodeBlock(
    val language: String? = null,
    val code: String = "",
)

@Serializable
data class Attachment(
    val name: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val note: String? = null,
)

private fun JsonObject.optStr(key: String): String? =
    when (val e = this[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> e.contentOrNull
        else -> null
    }

private fun JsonObject.optInt(key: String): Int? =
    when (val e = this[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> e.intOrNull
        else -> null
    }

private fun JsonObject.optBool(key: String): Boolean? =
    when (val e = this[key]) {
        null, is JsonNull -> null
        is JsonPrimitive -> e.booleanOrNull
        else -> null
    }

private fun JsonObject.optObj(key: String): JsonObject? =
    when (val e = this[key]) {
        null, is JsonNull -> null
        is JsonObject -> e
        else -> null
    }

/** Known top-level keys of [CanonicalConversation]; everything else lands in metadata. */
private val CONV_KNOWN_KEYS = setOf(
    "schemaVersion", "platform", "title", "sourceId", "sourceUrl",
    "createdAt", "updatedAt", "exportedAt", "exporter", "tags",
    "favorite", "notes", "messages", "metadata", "originalExport",
)

/** Known keys of [CanonicalMessage]; everything else lands in raw. */
private val MSG_KNOWN_KEYS = setOf(
    "role", "text", "markdown", "createdAt", "model",
    "codeBlocks", "attachments", "raw",
)

internal object CanonicalConversationSerializer : KSerializer<CanonicalConversation> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("CanonicalConversation")

    override fun deserialize(decoder: Decoder): CanonicalConversation {
        require(decoder is JsonDecoder) { "CanonicalConversation requires a JSON decoder" }
        val obj = decoder.decodeJsonElement().jsonObject

        val unknown = obj.filterKeys { it !in CONV_KNOWN_KEYS }
        val baseMeta = obj.optObj("metadata") ?: JsonObject(emptyMap())
        val metadata = JsonObject(baseMeta + unknown)

        val messagesEl = obj["messages"]
        val messages = when {
            messagesEl == null || messagesEl is JsonNull -> emptyList()
            messagesEl is JsonArray -> messagesEl.mapNotNull { el ->
                runCatching { CanonicalJson.json.decodeFromJsonElement(CanonicalMessage.serializer(), el) }.getOrNull()
            }
            else -> emptyList() // lenient: non-array "messages" treated as empty
        }

        val tagsEl = obj["tags"]
        val tags = when {
            tagsEl is JsonArray -> tagsEl.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            tagsEl is JsonPrimitive -> listOfNotNull(tagsEl.contentOrNull)
            else -> emptyList()
        }

        val platformRaw = obj.optStr("platform")?.trim()?.lowercase().orEmpty()
        val platform = when (platformRaw) {
            "gemini", "aistudio", "ai-studio", "aistudio.google", "jules", "other" -> platformRaw
            "ai_studio", "ai studio" -> "aistudio"
            else -> "other"
        }

        return CanonicalConversation(
            schemaVersion = obj.optInt("schemaVersion") ?: 1,
            platform = platform,
            title = obj.optStr("title") ?: "",
            sourceId = obj.optStr("sourceId"),
            sourceUrl = obj.optStr("sourceUrl"),
            createdAt = obj.optStr("createdAt"),
            updatedAt = obj.optStr("updatedAt"),
            exportedAt = obj.optStr("exportedAt") ?: "",
            exporter = obj.optStr("exporter") ?: "convarchive-app/1.0.0",
            tags = tags,
            favorite = obj.optBool("favorite") ?: false,
            notes = obj.optStr("notes") ?: "",
            messages = messages,
            metadata = metadata,
            originalExport = obj["originalExport"]?.takeUnless { it is JsonNull },
        )
    }

    override fun serialize(encoder: Encoder, value: CanonicalConversation) {
        require(encoder is JsonEncoder) { "CanonicalConversation requires a JSON encoder" }
        val map = linkedMapOf<String, JsonElement>()
        fun put(key: String, el: JsonElement?) {
            if (el != null && el !is JsonNull) map[key] = el
        }
        map["schemaVersion"] = JsonPrimitive(value.schemaVersion)
        map["platform"] = JsonPrimitive(value.platform)
        map["title"] = JsonPrimitive(value.title)
        put("sourceId", value.sourceId?.let { JsonPrimitive(it) })
        put("sourceUrl", value.sourceUrl?.let { JsonPrimitive(it) })
        put("createdAt", value.createdAt?.let { JsonPrimitive(it) })
        put("updatedAt", value.updatedAt?.let { JsonPrimitive(it) })
        map["exportedAt"] = JsonPrimitive(value.exportedAt)
        map["exporter"] = JsonPrimitive(value.exporter)
        map["tags"] = JsonArray(value.tags.map { JsonPrimitive(it) })
        map["favorite"] = JsonPrimitive(value.favorite)
        map["notes"] = JsonPrimitive(value.notes)
        map["messages"] = JsonArray(
            value.messages.map {
                CanonicalJson.json.encodeToJsonElement(CanonicalMessage.serializer(), it)
            },
        )
        map["metadata"] = value.metadata
        put("originalExport", value.originalExport)
        encoder.encodeJsonElement(JsonObject(map))
    }
}

internal object CanonicalMessageSerializer : KSerializer<CanonicalMessage> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("CanonicalMessage")

    override fun deserialize(decoder: Decoder): CanonicalMessage {
        require(decoder is JsonDecoder) { "CanonicalMessage requires a JSON decoder" }
        val obj = decoder.decodeJsonElement().jsonObject

        val unknown = obj.filterKeys { it !in MSG_KNOWN_KEYS }
        val baseRaw = obj.optObj("raw") ?: JsonObject(emptyMap())
        val raw = JsonObject(baseRaw + unknown)

        val text = obj.optStr("text") ?: ""
        return CanonicalMessage(
            role = obj.optStr("role")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "event",
            text = text,
            markdown = obj.optStr("markdown") ?: text,
            createdAt = obj.optStr("createdAt"),
            model = obj.optStr("model"),
            codeBlocks = when (val el = obj["codeBlocks"]) {
                is JsonArray -> el.mapNotNull {
                    runCatching { CanonicalJson.json.decodeFromJsonElement(CodeBlock.serializer(), it) }.getOrNull()
                }
                else -> emptyList()
            },
            attachments = when (val el = obj["attachments"]) {
                is JsonArray -> el.mapNotNull {
                    runCatching { CanonicalJson.json.decodeFromJsonElement(Attachment.serializer(), it) }.getOrNull()
                }
                else -> emptyList()
            },
            raw = raw,
        )
    }

    override fun serialize(encoder: Encoder, value: CanonicalMessage) {
        require(encoder is JsonEncoder) { "CanonicalMessage requires a JSON encoder" }
        val map = linkedMapOf<String, JsonElement>()
        fun put(key: String, el: JsonElement?) {
            if (el != null && el !is JsonNull) map[key] = el
        }
        map["role"] = JsonPrimitive(value.role)
        map["text"] = JsonPrimitive(value.text)
        put("markdown", value.markdown?.let { JsonPrimitive(it) })
        put("createdAt", value.createdAt?.let { JsonPrimitive(it) })
        put("model", value.model?.let { JsonPrimitive(it) })
        map["codeBlocks"] = JsonArray(
            value.codeBlocks.map { CanonicalJson.json.encodeToJsonElement(CodeBlock.serializer(), it) },
        )
        map["attachments"] = JsonArray(
            value.attachments.map { CanonicalJson.json.encodeToJsonElement(Attachment.serializer(), it) },
        )
        map["raw"] = value.raw
        encoder.encodeJsonElement(JsonObject(map))
    }
}

/** Shared kotlinx-serialization-json instance for the canonical schema. */
@OptIn(ExperimentalSerializationApi::class)
object CanonicalJson {
    val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(c: CanonicalConversation): String =
        json.encodeToString(CanonicalConversation.serializer(), c)

    /**
     * Lenient decode: missing `messages` becomes an empty list, an unknown
     * `platform` becomes "other", unknown fields are preserved in
     * `metadata`/`raw` catch-alls. Throws [IllegalArgumentException] if the
     * input is not a JSON object at all.
     */
    fun decode(s: String): CanonicalConversation {
        val el = json.parseToJsonElement(s)
        require(el is JsonObject) { "Canonical JSON must be a JSON object, found ${el::class.simpleName}" }
        return json.decodeFromJsonElement(CanonicalConversation.serializer(), el)
    }
}

/** Human-readable validation warnings for a canonical conversation. */
object CanonicalValidation {
    fun validate(c: CanonicalConversation): List<String> {
        val warnings = mutableListOf<String>()
        if (c.title.isBlank()) warnings += "title is blank"
        if (c.messages.isEmpty()) warnings += "conversation has no messages"
        if (c.schemaVersion != 1) warnings += "schemaVersion is ${c.schemaVersion}, expected 1"
        if (c.platform.isBlank()) warnings += "platform is blank"
        if (c.exportedAt.isBlank()) warnings += "exportedAt is blank"
        return warnings
    }
}

/**
 * Duplicate-detection hash: SHA-256 hex of
 * `platform + "\n" + title.trim() + for each message: "\n" + role + "\n" + text.trim()`,
 * NFC-normalized, UTF-8 encoded.
 */
object ContentHash {
    fun of(c: CanonicalConversation): String {
        val sb = StringBuilder()
        sb.append(c.platform).append('\n')
        sb.append(c.title.trim())
        for (m in c.messages) {
            sb.append('\n').append(m.role).append('\n').append(m.text.trim())
        }
        val normalized = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
        return Hashing.sha256Hex(normalized.toByteArray(Charsets.UTF_8))
    }
}
