package app.convarchive

import app.convarchive.export.HtmlExporter
import app.convarchive.export.JsonExporter
import app.convarchive.export.MarkdownExporter
import app.convarchive.export.PdfExporter
import app.convarchive.export.TextExporter
import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalJson
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.parsers.ParserRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emoji, CJK, RTL text and tricky characters must survive the text-based
 * export/import round trips. PDF intentionally replaces non-WinAnsi chars
 * with '?' (decision D1); it must still produce a valid file.
 */
class UnicodeRoundTripTest {

    private val tricky = "Emoji \uD83D\uDE00\uD83C\uDF89 CJK \u4E2D\u6587\u65E5\u672C\u8A9E " +
        "RTL \u0645\u0631\u062D\u0628\u0627 \u05E9\u05DC\u05D5\u05DD " +
        "accents caf\u00E9 na\u00EFve \u00DF " +
        "quotes \u201C\u201D dashes \u2014 \u2013 math \u2211 \u221E"

    private fun conv() = CanonicalConversation(
        platform = "gemini",
        title = "Unicode \uD83D\uDE00 \u4E2D\u6587",
        exportedAt = "2026-10-12T19:00:00Z",
        tags = listOf("\u65E5\u672C\u8A9E", "emoji-\uD83D\uDE00"),
        notes = tricky,
        messages = listOf(
            CanonicalMessage(role = "user", text = tricky, markdown = tricky),
            CanonicalMessage(role = "assistant", text = "R\u00E9ponse: \u4E2D\u6587 \uD83D\uDC4D"),
        ),
    )

    @Test
    fun `json round trip preserves unicode exactly`() {
        val c = conv()
        val decoded = CanonicalJson.decode(JsonExporter.export(c).bytes.toString(Charsets.UTF_8))
        assertEquals(c.title, decoded.title)
        assertEquals(c.notes, decoded.notes)
        assertEquals(c.messages.map { it.text }, decoded.messages.map { it.text })
        assertEquals(c.tags, decoded.tags)
    }

    @Test
    fun `markdown export preserves unicode and re-imports`() {
        val c = conv()
        val md = MarkdownExporter.export(c).bytes.toString(Charsets.UTF_8)
        assertTrue(md.contains("\u4E2D\u6587"))
        assertTrue(md.contains("\uD83D\uDE00"))
        val back = ParserRegistry.parseFile("u.md", md.toByteArray())
        assertTrue(back.ok)
        assertTrue(back.conversation!!.messages.any { it.text.contains("\u4E2D\u6587") })
    }

    @Test
    fun `html export preserves unicode`() {
        val html = HtmlExporter.export(conv()).bytes.toString(Charsets.UTF_8)
        assertTrue(html.contains("\u4E2D\u6587"))
        assertTrue(html.contains("\uD83D\uDE00"))
        assertTrue(html.contains("\u0645\u0631\u062D\u0628\u0627"))
    }

    @Test
    fun `text export preserves unicode`() {
        val txt = TextExporter.export(conv()).bytes.toString(Charsets.UTF_8)
        assertTrue(txt.contains(tricky))
    }

    @Test
    fun `pdf export stays valid with unicode input`() {
        val bytes = PdfExporter.export(conv()).bytes
        val head = bytes.toString(Charsets.ISO_8859_1)
        assertTrue(head.startsWith("%PDF-"))
        assertTrue(head.trimEnd().endsWith("%%EOF"))
    }
}
