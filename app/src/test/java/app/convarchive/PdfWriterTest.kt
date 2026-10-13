package app.convarchive

import app.convarchive.export.PdfWriter
import app.convarchive.export.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfWriterTest {

    private fun doc(pages: Int = 1): ByteArray =
        PdfWriter.writeDocument(
            (1..pages).map { p ->
                listOf(
                    TextRun("Page $p title", 18f, bold = true, mono = false),
                    TextRun("Hello (world) \\ test", 12f, bold = false, mono = false),
                    TextRun("mono line", 10f, bold = false, mono = true),
                )
            },
        )

    @Test
    fun `output starts with pdf-1_4 header`() {
        val bytes = doc()
        assertTrue(bytes.toString(Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
    }

    @Test
    fun `output ends with eof marker`() {
        val bytes = doc()
        assertTrue(bytes.toString(Charsets.ISO_8859_1).trimEnd().endsWith("%%EOF"))
    }

    @Test
    fun `three page doc has page count 3`() {
        val text = doc(pages = 3).toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("/Count 3"))
    }

    @Test
    fun `xref offsets point at object headers`() {
        val bytes = doc(pages = 2)
        val text = bytes.toString(Charsets.ISO_8859_1)
        // lastIndexOf: compressed content streams could theoretically contain
        // the byte sequence "xref", the real table is at the end.
        val xrefStart = text.lastIndexOf("\nxref\n")
        assertTrue(xrefStart > 0)
        val lines = text.substring(xrefStart).lines()
        // lines[0]="" lines[1]="xref" lines[2]="0 N" lines[3]=free entry, rest are objects
        val header = lines[2].trim().split(" ")
        val count = header[1].toInt()
        val entries = lines.drop(4).take(count - 1)
        assertEquals(count - 1, entries.size)
        for ((index, entry) in entries.withIndex()) {
            val offset = entry.substring(0, 10).toInt()
            val objNum = index + 1
            val at = bytes.drop(offset).take(32).toByteArray().toString(Charsets.ISO_8859_1)
            assertTrue(
                "xref entry $objNum (offset $offset) does not point at \"$objNum 0 obj\": $at",
                at.startsWith("$objNum 0 obj"),
            )
        }
    }

    @Test
    fun `literal string escaping handles parens and backslash`() {
        val escaped = PdfWriter.escape("a(b)\\c")
        assertEquals("a\\(b\\)\\\\c", escaped)
    }

    @Test
    fun `non-winansi chars become question marks`() {
        // Emoji is a surrogate pair: each half is outside WinAnsi -> '?' each.
        assertEquals("??hello?", PdfWriter.escape("😀hello中"))
        // WinAnsi-defined chars map to their WinAnsi byte values
        // (0xE9 for e-acute, 0x97 for em dash); check at byte level since the
        // PDF stream is written as latin-1.
        val bytes = PdfWriter.escape("café —").toByteArray(Charsets.ISO_8859_1)
        assertEquals(0x63, bytes[0].toInt() and 0xFF)
        assertEquals(0xE9, bytes[3].toInt() and 0xFF)
        assertEquals(0x97, bytes[5].toInt() and 0xFF)
    }

    @Test
    fun `content stream is flate-encoded`() {
        val text = doc().toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("/Filter /FlateDecode"))
    }

    @Test
    fun `empty page list still yields one page`() {
        val text = PdfWriter.writeDocument(emptyList()).toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("/Count 1"))
    }
}
