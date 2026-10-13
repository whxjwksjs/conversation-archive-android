package app.convarchive

import app.convarchive.export.ConversationExporter
import app.convarchive.export.ExportFormat
import app.convarchive.export.HtmlExporter
import app.convarchive.export.JsonExporter
import app.convarchive.export.MarkdownExporter
import app.convarchive.export.PdfExporter
import app.convarchive.export.TextExporter
import app.convarchive.model.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExporterTests {

    private val conv = TestHelpers.codeBlockConversation()

    @Test
    fun `json export re-parses with same message count`() {
        val file = JsonExporter.export(conv)
        assertTrue(file.fileName.endsWith(".json"))
        assertEquals("application/json", file.mimeType)
        val decoded = CanonicalJson.decode(file.bytes.toString(Charsets.UTF_8))
        assertEquals(conv.messages.size, decoded.messages.size)
        assertEquals(conv.title, decoded.title)
    }

    @Test
    fun `markdown export has title heading and fenced code`() {
        val file = MarkdownExporter.export(conv)
        val text = file.bytes.toString(Charsets.UTF_8)
        assertTrue(file.fileName.endsWith(".md"))
        assertTrue(text.startsWith("# Code blocks"))
        assertTrue(text.contains("```python"))
        assertTrue(text.contains("print(17 * 23)"))
        assertTrue(text.contains("## User"))
        assertTrue(text.contains("## Assistant"))
    }

    @Test
    fun `html export is standalone with escaped code`() {
        val file = HtmlExporter.export(conv)
        val text = file.bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("<html"))
        assertTrue(text.contains("<style>"))
        assertTrue(text.contains("<pre><code class=\"language-python\">"))
        // escaping check via a hostile title
        val hostile = TestHelpers.sampleConversation(title = "<script>alert(1)</script>")
        val hostileHtml = HtmlExporter.export(hostile).bytes.toString(Charsets.UTF_8)
        assertTrue(hostileHtml.contains("&lt;script&gt;"))
        assertTrue(!hostileHtml.contains("<script>alert(1)</script>"))
    }

    @Test
    fun `text export is non-empty with separators`() {
        val file = TextExporter.export(conv)
        val text = file.bytes.toString(Charsets.UTF_8)
        assertTrue(text.isNotBlank())
        assertTrue(text.contains("===="))
        assertTrue(text.contains("---- User"))
    }

    @Test
    fun `pdf export starts with pdf header`() {
        val file = PdfExporter.export(conv)
        assertTrue(file.bytes.toString(Charsets.ISO_8859_1).startsWith("%PDF-"))
        assertTrue(file.fileName.endsWith(".pdf"))
        assertEquals("application/pdf", file.mimeType)
    }

    @Test
    fun `exportSingle dispatches every format`() {
        for (format in ExportFormat.entries) {
            val file = ConversationExporter.exportSingle(conv, format)
            assertTrue("empty export for $format", file.bytes.isNotEmpty())
            assertTrue(
                "wrong extension for $format: ${file.fileName}",
                file.fileName.endsWith(".${format.ext}"),
            )
            assertEquals(format.mimeType, file.mimeType)
        }
    }

    @Test
    fun `exportMany with one conversation returns a single file`() {
        val file = ConversationExporter.exportMany(listOf(conv), ExportFormat.JSON)
        assertTrue(file.fileName.endsWith(".json"))
    }

    @Test
    fun `exportMany zips N conversations with N entries`() {
        val convs = listOf(
            TestHelpers.sampleConversation(title = "One"),
            TestHelpers.sampleConversation(title = "Two"),
            TestHelpers.sampleConversation(title = "Three"),
        )
        val file = ConversationExporter.exportMany(convs, ExportFormat.MARKDOWN)
        assertTrue(file.fileName.endsWith(".zip"))
        val names = TestHelpers.zipEntryNames(file.bytes)
        assertEquals(3, names.size)
        assertTrue(names.all { it.endsWith(".md") })
    }

    @Test
    fun `exportMany dedupes identical filenames`() {
        val convs = listOf(
            TestHelpers.sampleConversation(title = "Same"),
            TestHelpers.sampleConversation(title = "Same"),
        )
        val file = ConversationExporter.exportMany(convs, ExportFormat.TEXT)
        val names = TestHelpers.zipEntryNames(file.bytes)
        assertEquals(2, names.size)
        assertEquals(2, names.toSet().size)
    }
}
