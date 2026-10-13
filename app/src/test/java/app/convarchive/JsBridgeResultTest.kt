package app.convarchive

import app.convarchive.model.parsers.CanonicalParser
import app.convarchive.ui.browser.BridgeOutcome
import app.convarchive.ui.browser.DownloadHelper
import app.convarchive.ui.browser.JsBridgeResult
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the v1.1 WebView capture path:
 * - parsing of evaluateJavascript callback values ([JsBridgeResult]),
 * - validation of extractor-shaped canonical JSON ([CanonicalParser]),
 * - unknown-field preservation from extractor output,
 * - download URL allow-listing ([DownloadHelper]).
 */
class JsBridgeResultTest {

    private fun bridgeOf(innerJson: String): String = JsBridgeResult.encodeForBridge(innerJson)

    @Test
    fun `valid canonical payload round-trips through the bridge encoding`() {
        val inner = TestHelpers.resourceText("extractor_sample_gemini.json")
        val outcome = JsBridgeResult.parse(bridgeOf(inner))
        assertTrue(outcome is BridgeOutcome.Valid)
        assertEquals(inner, (outcome as BridgeOutcome.Valid).json)
    }

    @Test
    fun `null bridge value becomes Empty`() {
        val outcome = JsBridgeResult.parse("null")
        assertTrue(outcome is BridgeOutcome.Empty)
    }

    @Test
    fun `null input becomes Empty`() {
        val outcome = JsBridgeResult.parse(null)
        assertTrue(outcome is BridgeOutcome.Empty)
    }

    @Test
    fun `non-JSON bridge value becomes Malformed`() {
        val outcome = JsBridgeResult.parse("{this is not json")
        assertTrue(outcome is BridgeOutcome.Malformed)
    }

    @Test
    fun `inner non-object becomes Malformed`() {
        val outcome = JsBridgeResult.parse(bridgeOf("[1, 2, 3]"))
        assertTrue(outcome is BridgeOutcome.Malformed)
    }

    @Test
    fun `extractor __error becomes JsError with message`() {
        val outcome = JsBridgeResult.parse(bridgeOf("""{"__error": "boom (the site may have changed its layout)"}"""))
        assertTrue(outcome is BridgeOutcome.JsError)
        assertTrue((outcome as BridgeOutcome.JsError).message.contains("boom"))
    }

    @Test
    fun `extractor __empty becomes Empty with reason`() {
        val outcome = JsBridgeResult.parse(bridgeOf("""{"__empty": "no Gemini messages found in the page DOM"}"""))
        assertTrue(outcome is BridgeOutcome.Empty)
        assertTrue((outcome as BridgeOutcome.Empty).reason.contains("no Gemini messages"))
    }

    @Test
    fun `extractor payload parses as canonical with warnings only`() {
        val inner = TestHelpers.resourceText("extractor_sample_gemini.json")
        val outcome = JsBridgeResult.parse(bridgeOf(inner))
        val valid = outcome as BridgeOutcome.Valid
        val result = CanonicalParser.parse(valid.json, "webview-capture.json")
        assertTrue("expected ok, errors=${result.errors}", result.ok)
        val conv = result.conversation!!
        assertEquals("gemini", conv.platform)
        assertEquals("Explain quantum tunneling", conv.title)
        assertEquals(2, conv.messages.size)
        assertEquals("python", conv.messages[1].codeBlocks.single().language)
        assertEquals("diagram.png", conv.messages[1].attachments.single().name)
        assertEquals("https://gemini.google.com/app/abc123", conv.sourceUrl)
    }

    @Test
    fun `unknown top-level fields from extractor output are preserved in metadata`() {
        val inner = TestHelpers.resourceText("extractor_sample_gemini.json")
        val valid = JsBridgeResult.parse(bridgeOf(inner)) as BridgeOutcome.Valid
        val conv = CanonicalParser.parse(valid.json, "webview-capture.json").conversation!!
        val meta = conv.metadata
        assertEquals("must be preserved", (meta["extraTopLevelField"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
        assertEquals("2", (meta["turnCount"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
    }

    @Test
    fun `unknown message fields from extractor output are preserved in raw`() {
        val inner = """{"schemaVersion":1,"platform":"jules","title":"T",
            "exportedAt":"2026-10-13T16:00:00Z","exporter":"x",
            "messages":[{"role":"event","text":"step done","customBlock":"activity-log"}]}"""
        val valid = JsBridgeResult.parse(bridgeOf(inner)) as BridgeOutcome.Valid
        val conv = CanonicalParser.parse(valid.json, "webview-capture.json").conversation!!
        val raw = conv.messages.single().raw
        assertEquals(
            "activity-log",
            (raw["customBlock"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
    }

    @Test
    fun `empty message list from extractor is still ok with a warning`() {
        val inner = """{"schemaVersion":1,"platform":"aistudio","title":"T",
            "exportedAt":"2026-10-13T16:00:00Z","exporter":"x","messages":[]}"""
        val valid = JsBridgeResult.parse(bridgeOf(inner)) as BridgeOutcome.Valid
        val result = CanonicalParser.parse(valid.json, "webview-capture.json")
        assertTrue(result.ok)
        assertTrue(result.warnings.any { it.contains("no messages") })
    }

    // --- DownloadHelper allow-list ---

    @Test
    fun `download allow-list accepts google hosts over https`() {
        assertTrue(DownloadHelper.isAllowedUrl("https://gemini.google.com/export/x.json"))
        assertTrue(DownloadHelper.isAllowedUrl("https://aistudio.google.com/prompts/abc"))
        assertTrue(DownloadHelper.isAllowedUrl("https://accounts.google.com/signin"))
    }

    @Test
    fun `download allow-list rejects non-google hosts and http`() {
        assertTrue(!DownloadHelper.isAllowedUrl("https://evil.com/gemini.google.com/x"))
        assertTrue(!DownloadHelper.isAllowedUrl("http://gemini.google.com/x"))
        assertTrue(!DownloadHelper.isAllowedUrl("not a url"))
    }

    @Test
    fun `download file name is sanitized`() {
        val name = DownloadHelper.fileNameFor(
            "https://gemini.google.com/export",
            "attachment; filename=\"chat: 2026/10/13.json\"",
            "application/json",
        )
        assertTrue(!name.contains(":") && !name.contains("/"))
        assertTrue(name.isNotBlank())
    }
}
