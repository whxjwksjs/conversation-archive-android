package app.convarchive

import app.convarchive.model.parsers.PdfImporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PdfImporterTest {

    private fun pdfWithStream(dictExtra: String, content: ByteArray): ByteArray {
        val head = """
            %PDF-1.4
            1 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            2 0 obj
            << /Type /Pages /Kids [3 0 R] /Count 1 >>
            endobj
            3 0 obj
            << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>
            endobj
            4 0 obj
            << /Length ${content.size} $dictExtra >>
            stream
            
        """.trimIndent().toByteArray(Charsets.ISO_8859_1)
        val tail = "\nendstream\nendobj\ntrailer\n<< /Root 1 0 R >>\n".toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream()
        out.write(head)
        out.write(content)
        out.write(tail)
        return out.toByteArray()
    }

    @Test
    fun `uncompressed stream with Tj and TJ operators extracts text`() {
        val content = "BT /F1 12 Tf 48 700 Td (Hello world) Tj ET\n".toByteArray(Charsets.ISO_8859_1) +
            "BT 48 680 Td [(Foo) 120 (Bar)] TJ ET\n".toByteArray(Charsets.ISO_8859_1)
        val r = PdfImporter.parse(pdfWithStream("", content), "doc.pdf")
        assertTrue("errors: ${r.errors}", r.ok)
        val text = r.conversation!!.messages.joinToString(" ") { it.text }
        assertTrue("text was: $text", text.contains("Hello world"))
        assertTrue("text was: $text", text.contains("FooBar"))
    }

    @Test
    fun `flatedecode stream inflates`() {
        val raw = "BT (Compressed hello) Tj ET".toByteArray(Charsets.ISO_8859_1)
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val buf = ByteArray(256)
        val n = deflater.deflate(buf)
        deflater.end()
        val r = PdfImporter.parse(
            pdfWithStream("/Filter /FlateDecode", buf.copyOf(n)),
            "doc.pdf",
        )
        assertTrue("errors: ${r.errors}", r.ok)
        assertTrue(r.conversation!!.messages.joinToString(" ") { it.text }.contains("Compressed hello"))
    }

    @Test
    fun `escaped parens and hex strings decode`() {
        val content = "BT (a\\(b\\)) Tj ET\nBT <48656C6C6F> Tj ET\n".toByteArray(Charsets.ISO_8859_1)
        val r = PdfImporter.parse(pdfWithStream("", content), "doc.pdf")
        assertTrue("errors: ${r.errors}", r.ok)
        val text = r.conversation!!.messages.joinToString(" ") { it.text }
        assertTrue("text was: $text", text.contains("a(b)"))
        assertTrue("text was: $text", text.contains("Hello"))
    }

    @Test
    fun `encrypted pdf is a clear error`() {
        val bytes = "%PDF-1.4\n1 0 obj\n<< /Encrypt 5 0 R >>\nendobj\n".toByteArray(Charsets.ISO_8859_1)
        val r = PdfImporter.parse(bytes, "locked.pdf")
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("encrypted", ignoreCase = true) })
    }

    @Test
    fun `non-pdf bytes are a clear error`() {
        val r = PdfImporter.parse("hello world".toByteArray(), "x.pdf")
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("not a PDF", ignoreCase = true) })
    }

    @Test
    fun `pdf with no text is a clear error`() {
        val r = PdfImporter.parse(pdfWithStream("", "BT ET".toByteArray()), "empty.pdf")
        assertFalse(r.ok)
        assertTrue(r.errors.any { it.contains("no extractable text", ignoreCase = true) })
    }

    @Test
    fun `winansi bytes decode`() {
        // 0xE9 is e-acute in WinAnsi.
        val content = "BT (caf\u00e9) Tj ET".toByteArray(Charsets.ISO_8859_1)
        val r = PdfImporter.parse(pdfWithStream("", content), "doc.pdf")
        assertTrue("errors: ${r.errors}", r.ok)
        assertTrue(r.conversation!!.messages.joinToString(" ") { it.text }.contains("caf\u00e9"))
    }
}
