package app.convarchive

import app.convarchive.export.JsonExporter
import app.convarchive.export.MarkdownExporter
import app.convarchive.export.PdfExporter
import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalJson
import app.convarchive.model.CanonicalMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LargeConversationTest {

    private fun big(n: Int = 2000): CanonicalConversation = CanonicalConversation(
        platform = "gemini",
        title = "Large conversation",
        exportedAt = "2026-10-12T19:00:00Z",
        messages = (1..n).map { i ->
            CanonicalMessage(
                role = if (i % 2 == 1) "user" else "assistant",
                text = "Message number $i. " + "Lorem ipsum dolor sit amet. ".repeat(10),
                markdown = "Message number $i.",
            )
        },
    )

    @Test
    fun `2000-message json export re-parses with all messages`() {
        val conv = big()
        val file = JsonExporter.export(conv)
        val decoded = CanonicalJson.decode(file.bytes.toString(Charsets.UTF_8))
        assertEquals(2000, decoded.messages.size)
        assertEquals("Message number 2000.", decoded.messages.last().markdown)
        assertEquals("Message number 1.", decoded.messages.first().markdown)
    }

    @Test
    fun `2000-message markdown export keeps every message`() {
        val conv = big()
        val md = MarkdownExporter.export(conv).bytes.toString(Charsets.UTF_8)
        val headings = Regex("^## (User|Assistant)", RegexOption.MULTILINE).findAll(md).count()
        assertEquals(2000, headings)
    }

    @Test
    fun `2000-message pdf lays out without truncation`() {
        val conv = big()
        val pages = PdfExporter.layout(conv)
        val lineCount = pages.sumOf { it.size }
        assertTrue("expected at least one line per message, got $lineCount", lineCount >= 2000)
        assertTrue("expected multiple pages, got ${pages.size}", pages.size > 1)
        val bytes = PdfExporter.export(conv).bytes
        assertTrue(bytes.toString(Charsets.ISO_8859_1).startsWith("%PDF-"))
    }
}
