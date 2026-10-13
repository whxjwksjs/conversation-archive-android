package app.convarchive.ui.browser

import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Parsing of the raw string delivered by WebView.evaluateJavascript's
 * ValueCallback.
 *
 * evaluateJavascript JSON-encodes the script's return value: a JS string
 * return arrives as a quoted JSON string (e.g. "\"{\\\"a\\\":1}\""),
 * `null` arrives as the literal `null`. The extractor script
 * (assets/extractor.js) returns one of:
 * - the canonical JSON v1 payload string  -> [Valid]
 * - `{"__error": "..."}`                   -> [JsError]
 * - `{"__empty": "..."}`                   -> [Empty]
 *
 * Pure Kotlin/JVM — unit-tested in JsBridgeResultTest.
 */
sealed interface BridgeOutcome {
    /** Inner JSON string of the canonical payload; feed to CanonicalParser. */
    data class Valid(val json: String) : BridgeOutcome
    /** Extractor found nothing, or the page returned nothing usable. */
    data class Empty(val reason: String) : BridgeOutcome
    /** The extractor script threw. */
    data class JsError(val message: String) : BridgeOutcome
    /** The bridge value itself could not be decoded. */
    data class Malformed(val details: String) : BridgeOutcome
}

object JsBridgeResult {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String?): BridgeOutcome {
        if (raw == null) return BridgeOutcome.Empty("no response from the page")
        val trimmed = raw.trim()
        if (trimmed == "null" || trimmed.isEmpty()) {
            return BridgeOutcome.Empty(
                "the page returned nothing (it may still be loading — " +
                    "wait for the conversation to appear, then try again)",
            )
        }
        val inner: String = try {
            json.decodeFromString(serializer<String>(), trimmed)
        } catch (e: Exception) {
            return BridgeOutcome.Malformed(
                "could not decode the page result: ${e.message?.lineSequence()?.firstOrNull() ?: e}",
            )
        }
        val element = try {
            json.parseToJsonElement(inner)
        } catch (e: Exception) {
            return BridgeOutcome.Malformed(
                "the page result was not JSON: ${e.message?.lineSequence()?.firstOrNull() ?: e}",
            )
        }
        val obj = element as? JsonObject
            ?: return BridgeOutcome.Malformed("the page result was not a JSON object")
        obj["__error"]?.let {
            val msg = (it as? JsonPrimitive)?.contentOrNull
                ?: it.toString()
            return BridgeOutcome.JsError(msg)
        }
        obj["__empty"]?.let {
            val reason = (it as? JsonPrimitive)?.contentOrNull
                ?: it.toString()
            return BridgeOutcome.Empty(reason)
        }
        return BridgeOutcome.Valid(inner)
    }

    /** Builds a fake evaluateJavascript callback value for tests. */
    fun encodeForBridge(innerJson: String): String =
        json.encodeToString(serializer<String>(), innerJson)
}
