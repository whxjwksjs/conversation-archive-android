package app.convarchive

import app.convarchive.model.parsers.ParserRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests. Third-party fixtures live in fixtures/ at the repo root with
 * license/verification-level headers; copies (comments stripped, since JSON
 * has no comments) are used here:
 * - voyager_export_sample.json: authored from voyager-crew/voyager docs
 *   (ConversationExportService.toJSON shape); exact item fields approximate.
 * - rmtd418_sample_INFERRED.json: authored from README claims only
 *   ("turns"/"entries" keys are README-verified; inner fields inferred).
 * - aistudio-dump-script_v5.0_sample.json: schema verified from the script's
 *   published v5.0 source (system_instruction + messages of role/content).
 */
class ParserTests {

    @Test
    fun `canonical sample parses with originalExport preserved`() {
        val r = ParserRegistry.parseFile(
            "canonical_sample.json",
            TestHelpers.resourceBytes("canonical_sample.json"),
        )
        assertTrue(r.ok)
        val c = r.conversation!!
        assertEquals("aistudio", c.platform)
        assertEquals(2, c.messages.size)
        assertEquals("aistudio", r.detectedPlatform)
        assertNotNull(c.originalExport)
        // untouched input kept verbatim
        assertEquals(
            "must be preserved in metadata",
            c.originalExport!!.jsonObject["topLevelCustom"]?.jsonPrimitive?.content,
        )
        assertTrue(c.messages[0].codeBlocks.isEmpty())
        assertEquals(1, c.messages[1].codeBlocks.size)
        assertEquals("python", c.messages[1].codeBlocks[0].language)
    }

    @Test
    fun `voyager fixture parses`() {
        val r = ParserRegistry.parseFile(
            "voyager_export.json",
            TestHelpers.resourceBytes("voyager_export_sample.json"),
        )
        assertTrue("errors: ${r.errors}", r.ok)
        val c = r.conversation!!
        assertEquals("gemini", c.platform)
        assertEquals(4, c.messages.size)
        assertEquals("user", c.messages[0].role)
        assertEquals("assistant", c.messages[1].role)
        assertEquals("voyager", r.detectedExporter)
        assertNotNull(c.originalExport)
    }

    @Test
    fun `rmtd418 entries parse in order with thoughts kept`() {
        val r = ParserRegistry.parseFile(
            "export.json",
            TestHelpers.resourceBytes("rmtd418_sample_INFERRED.json"),
        )
        assertTrue("errors: ${r.errors}", r.ok)
        val c = r.conversation!!
        assertEquals("aistudio", c.platform)
        // 2 user + 1 thought(as assistant) + 3 responses = 6
        assertEquals(6, c.messages.size)
        assertEquals("user", c.messages[0].role)
        assertEquals("assistant", c.messages[1].role)
        assertEquals("thought", c.messages[1].raw["originalRole"]?.jsonPrimitive?.content)
        assertEquals("rmtd418-ai-studio-conversation-exporter", r.detectedExporter)
        assertNotNull(c.originalExport)
    }

    @Test
    fun `aistudio dump script parses with system instruction in metadata`() {
        val r = ParserRegistry.parseFile(
            "dump.json",
            TestHelpers.resourceBytes("aistudio-dump-script_v5.0_sample.json"),
        )
        assertTrue("errors: ${r.errors}", r.ok)
        val c = r.conversation!!
        assertEquals("aistudio", c.platform)
        assertEquals(4, c.messages.size)
        assertEquals(
            "You are a concise assistant. Answer in British English.",
            c.metadata["systemInstruction"]?.jsonPrimitive?.content,
        )
        assertTrue(r.warnings.any { it.contains("no timestamps", ignoreCase = true) })
        assertNotNull(c.originalExport)
    }

    @Test
    fun `caffeinegmt multi-conversation file imports first with warning`() {
        val r = ParserRegistry.parseFile(
            "gemini-export.json",
            TestHelpers.resourceBytes("gemini-export_caffeinegmt_sample.json"),
        )
        assertTrue("errors: ${r.errors}", r.ok)
        val c = r.conversation!!
        assertEquals("gemini", c.platform)
        assertEquals("Help me plan a trip to Japan", c.title)
        assertEquals(2, c.messages.size)
        assertTrue(r.warnings.any { it.contains("2 conversations") })
    }

    @Test
    fun `ndjson merges lines into one conversation`() {
        val r = ParserRegistry.parseFile(
            "chats.ndjson",
            TestHelpers.resourceBytes("gemini-chat-exporter_davidmalko87_sample.ndjson"),
        )
        assertTrue("errors: ${r.errors} warnings: ${r.warnings}", r.ok)
        assertEquals(4, r.conversation!!.messages.size)
    }

    @Test
    fun `takeout parses as gemini activity events`() {
        val r = ParserRegistry.parseFile(
            "takeout.json",
            TestHelpers.resourceBytes("takeout_sample.json"),
        )
        assertTrue("errors: ${r.errors}", r.ok)
        val c = r.conversation!!
        assertEquals("gemini", c.platform)
        assertEquals(2, c.messages.size)
        assertTrue(c.messages.all { it.role == "event" })
        assertTrue(c.messages[0].text.contains("Asked about 17 times 23"))
        assertEquals("google-takeout", r.detectedExporter)
        assertTrue(r.warnings.any { it.contains("activity log", ignoreCase = true) })
    }

    @Test
    fun `markdown splits on markers and extracts code blocks`() {
        val r = ParserRegistry.parseFile(
            "notes.md",
            TestHelpers.resourceBytes("sample.md"),
        )
        assertTrue("errors: ${r.errors}", r.ok)
        val c = r.conversation!!
        assertEquals(listOf("user", "assistant", "user", "assistant"), c.messages.map { it.role })
        assertEquals("Trip planning", c.title)
        val withCode = c.messages.first { it.codeBlocks.isNotEmpty() }
        assertEquals("python", withCode.codeBlocks[0].language)
        assertTrue(withCode.codeBlocks[0].code.contains("day 1: Tokyo"))
    }

    @Test
    fun `markdown without structure becomes one event message`() {
        val r = ParserRegistry.parseFile(
            "plain.md",
            "Just some prose.\nMore prose, no markers at all.\n".toByteArray(),
        )
        assertTrue(r.ok)
        val c = r.conversation!!
        assertEquals(1, c.messages.size)
        assertEquals("event", c.messages[0].role)
        assertTrue(r.warnings.any { it.contains("no message structure detected; stored as one document") })
    }

    @Test
    fun `text splits on role prefixes`() {
        val r = ParserRegistry.parseFile(
            "notes.txt",
            TestHelpers.resourceBytes("sample.txt"),
        )
        assertTrue("errors: ${r.errors}", r.ok)
        assertEquals(listOf("user", "assistant", "user", "assistant"), r.conversation!!.messages.map { it.role })
    }

    @Test
    fun `html strips scripts and keeps pre blocks`() {
        val r = ParserRegistry.parseFile(
            "page.html",
            TestHelpers.resourceBytes("sample.html"),
        )
        assertTrue("errors: ${r.errors}", r.ok)
        val c = r.conversation!!
        assertEquals("Chat export", c.title)
        assertTrue(c.messages.none { it.text.contains("alert(") })
        assertTrue(c.messages.any { it.text.contains("Japan & Korea") })
        val withCode = c.messages.first { it.codeBlocks.isNotEmpty() }
        assertEquals("python", withCode.codeBlocks[0].language)
        assertTrue(r.warnings.any { it.contains("best-effort", ignoreCase = true) })
    }

    @Test
    fun `invalid json reports exact parse error`() {
        val r = ParserRegistry.parseFile(
            "bad.json",
            TestHelpers.resourceBytes("invalid.json"),
        )
        assertFalse(r.ok)
        assertTrue(r.errors.isNotEmpty())
        assertTrue(r.errors[0].contains("JSON parse error", ignoreCase = true))
    }

    @Test
    fun `unknown json shape falls back to one event message with verbatim original`() {
        val raw = """{"someWeirdExport": {"nested": [1, 2, 3]}}"""
        val r = ParserRegistry.parseFile("weird.json", raw.toByteArray())
        assertTrue(r.ok)
        val c = r.conversation!!
        assertEquals(1, c.messages.size)
        assertEquals("event", c.messages[0].role)
        // originalExport keeps the full input verbatim (structural comparison)
        assertEquals(
            raw.replace(" ", ""),
            c.originalExport.toString().replace(" ", ""),
        )
        assertTrue(r.warnings.any { it.contains("unrecognized JSON shape", ignoreCase = true) })
    }

    @Test
    fun `empty markdown is an error not a silent drop`() {
        val r = ParserRegistry.parseFile("empty.md", ByteArray(0))
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("empty", ignoreCase = true) })
    }
}
