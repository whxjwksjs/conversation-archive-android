package app.convarchive

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalJson
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.CanonicalValidation
import app.convarchive.model.ContentHash
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSchemaTest {

    @Test
    fun `round trip preserves all fields`() {
        val conv = CanonicalConversation(
            platform = "jules",
            title = "Task",
            sourceId = "task-1",
            tags = listOf("a", "b"),
            favorite = true,
            notes = "note",
            messages = listOf(CanonicalMessage(role = "user", text = "hi", markdown = "hi")),
            metadata = JsonObject(mapOf("k" to kotlinx.serialization.json.JsonPrimitive("v"))),
        )
        val decoded = CanonicalJson.decode(CanonicalJson.encode(conv))
        assertEquals(conv.copy(originalExport = null), decoded.copy(originalExport = null))
    }

    @Test
    fun `missing messages becomes empty list`() {
        val decoded = CanonicalJson.decode("""{"schemaVersion":1,"platform":"gemini","title":"T","exportedAt":"2026-10-12T00:00:00Z"}""")
        assertTrue(decoded.messages.isEmpty())
    }

    @Test
    fun `non-array messages becomes empty list`() {
        val decoded = CanonicalJson.decode("""{"platform":"gemini","messages":"nope"}""")
        assertTrue(decoded.messages.isEmpty())
    }

    @Test
    fun `unknown platform maps to other`() {
        val decoded = CanonicalJson.decode("""{"platform":"Claude","messages":[]}""")
        assertEquals("other", decoded.platform)
    }

    @Test
    fun `unknown top-level fields land in metadata`() {
        val decoded = CanonicalJson.decode(TestHelpers.resourceText("canonical_sample.json"))
        assertEquals(
            "must be preserved in metadata",
            decoded.metadata["topLevelCustom"]?.jsonPrimitive?.content,
        )
        assertEquals(0.7, decoded.metadata["temperature"]?.jsonPrimitive?.content?.toDouble() ?: -1.0, 0.0)
    }

    @Test
    fun `unknown message fields land in raw`() {
        val decoded = CanonicalJson.decode(TestHelpers.resourceText("canonical_sample.json"))
        assertEquals(
            "must be preserved in raw",
            decoded.messages[0].raw["customField"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `markdown defaults to text when absent`() {
        val decoded = CanonicalJson.decode("""{"messages":[{"role":"user","text":"hello"}]}""")
        assertEquals("hello", decoded.messages[0].markdown)
    }

    @Test
    fun `validation warnings cover blank title, no messages, bad schemaVersion`() {
        val warnings = CanonicalValidation.validate(
            CanonicalConversation(schemaVersion = 2, title = "  "),
        )
        assertTrue(warnings.any { it.contains("title") })
        assertTrue(warnings.any { it.contains("no messages") })
        assertTrue(warnings.any { it.contains("schemaVersion") })
    }

    @Test
    fun `valid conversation has no warnings`() {
        val conv = CanonicalConversation(
            platform = "gemini",
            title = "T",
            exportedAt = "2026-10-12T00:00:00Z",
            messages = listOf(CanonicalMessage(role = "user", text = "hi")),
        )
        assertTrue(CanonicalValidation.validate(conv).isEmpty())
    }

    @Test
    fun `content hash is stable and content-sensitive`() {
        val a = TestHelpers.sampleConversation(title = "Same")
        val b = TestHelpers.sampleConversation(title = "Same")
        val c = TestHelpers.sampleConversation(title = "Different")
        assertEquals(ContentHash.of(a), ContentHash.of(b))
        assertNotEquals(ContentHash.of(a), ContentHash.of(c))
        assertEquals(64, ContentHash.of(a).length) // SHA-256 hex
    }

    @Test
    fun `content hash ignores surrounding whitespace and NFC form`() {
        val a = TestHelpers.sampleConversation(title = "caf\u00e9") // precomposed e-acute
        val b = TestHelpers.sampleConversation(title = "  caf\u00e9  ")
        val c = TestHelpers.sampleConversation(title = "cafe\u0301") // e + combining acute
        assertEquals(ContentHash.of(a), ContentHash.of(b))
        assertEquals(ContentHash.of(a), ContentHash.of(c))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects non-object JSON`() {
        CanonicalJson.decode("[1,2,3]")
    }
}
