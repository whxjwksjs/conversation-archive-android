package app.convarchive.export

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * One styled run of text on a line. A page is a list of lines; a document is
 * a list of pages. Line breaking/wrapping is the caller's job.
 */
data class TextRun(val text: String, val size: Float, val bold: Boolean, val mono: Boolean)

/**
 * Minimal hand-rolled PDF 1.4 writer (decision D1). Pure Kotlin/JVM, no
 * android.* imports, so it is unit-testable (`%PDF` header, xref offsets,
 * page count).
 *
 * - Page size A4 (595 x 842 pt), 48 pt margins.
 * - Base-14 fonts only: Helvetica, Helvetica-Bold, Courier, Courier-Bold
 *   (WinAnsiEncoding). Characters outside WinAnsi become '?'.
 * - Content streams use FlateDecode.
 * - Output starts with "%PDF-1.4" and ends with "%%EOF".
 */
object PdfWriter {

    const val PAGE_WIDTH = 595f
    const val PAGE_HEIGHT = 842f
    const val MARGIN = 48f

    /** Font resource names used in content streams. */
    private fun fontName(bold: Boolean, mono: Boolean): String = when {
        mono && bold -> "F4"   // Courier-Bold
        mono -> "F3"           // Courier
        bold -> "F2"           // Helvetica-Bold
        else -> "F1"           // Helvetica
    }

    /** Approximate advance width of [text] in points (Base-14 metrics). */
    fun textWidth(text: String, size: Float, mono: Boolean): Float {
        if (mono) return text.length * size * 0.6f // Courier: 600/1000 em for every glyph
        var units = 0
        for (c in text) units += helveticaWidth(c)
        return units * size / 1000f
    }

    fun writeDocument(pages: List<List<TextRun>>): ByteArray {
        val safePages = if (pages.isEmpty()) listOf(emptyList()) else pages
        val n = safePages.size

        // Object numbering: 1 catalog, 2 pages, 3..3+n-1 page objs,
        // 3+n..3+2n-1 content streams, then 4 font objects.
        val pageObj = (0 until n).map { 3 + it }
        val contentObj = (0 until n).map { 3 + n + it }
        val fontObj = mapOf("F1" to 3 + 2 * n, "F2" to 4 + 2 * n, "F3" to 5 + 2 * n, "F4" to 6 + 2 * n)
        val objectCount = 2 + 2 * n + 4

        val out = ByteArrayOutputStream()
        val offsets = LongArray(objectCount + 1) // 1-based
        fun writeObj(num: Int, body: String) {
            offsets[num] = out.size().toLong()
            out.write("$num 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        fun writeObjBytes(num: Int, dict: String, data: ByteArray) {
            offsets[num] = out.size().toLong()
            out.write("$num 0 obj\n$dict\nstream\n".toByteArray(Charsets.ISO_8859_1))
            out.write(data)
            out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }

        out.write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n".toByteArray(Charsets.ISO_8859_1))

        writeObj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        writeObj(
            2,
            "<< /Type /Pages /Kids [${pageObj.joinToString(" ") { "$it 0 R" }}] /Count $n >>",
        )
        for (i in 0 until n) {
            writeObj(
                pageObj[i],
                "<< /Type /Page /Parent 2 0 R " +
                    "/MediaBox [0 0 $PAGE_WIDTH $PAGE_HEIGHT] " +
                    "/Resources << /Font << /F1 ${fontObj["F1"]} 0 R /F2 ${fontObj["F2"]} 0 R " +
                    "/F3 ${fontObj["F3"]} 0 R /F4 ${fontObj["F4"]} 0 R >> >> " +
                    "/Contents ${contentObj[i]} 0 R >>",
            )
        }
        for (i in 0 until n) {
            val stream = renderPage(safePages[i])
            val compressed = flate(stream)
            writeObjBytes(
                contentObj[i],
                "<< /Length ${compressed.size} /Filter /FlateDecode >>",
                compressed,
            )
        }
        writeObj(fontObj["F1"]!!, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        writeObj(fontObj["F2"]!!, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        writeObj(fontObj["F3"]!!, "<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>")
        writeObj(fontObj["F4"]!!, "<< /Type /Font /Subtype /Type1 /BaseFont /Courier-Bold /Encoding /WinAnsiEncoding >>")

        val xrefOffset = out.size().toLong()
        out.write("xref\n0 ${objectCount + 1}\n".toByteArray(Charsets.ISO_8859_1))
        out.write("0000000000 65535 f \n".toByteArray(Charsets.ISO_8859_1))
        for (num in 1..objectCount) {
            out.write("%010d 00000 n \n".format(offsets[num]).toByteArray(Charsets.ISO_8859_1))
        }
        out.write(
            "trailer\n<< /Size ${objectCount + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF"
                .toByteArray(Charsets.ISO_8859_1),
        )
        return out.toByteArray()
    }

    /**
     * Render one page. Per the [writeDocument] contract a page is a list of
     * [TextRun]s and each run is one line of text.
     */
    private fun renderPage(lines: List<TextRun>): ByteArray {
        val sb = StringBuilder()
        var y = PAGE_HEIGHT - MARGIN
        sb.append("BT\n")
        var curFont = ""
        var curSize = -1f
        for (line in lines) {
            val lineHeight = line.size * 1.25f
            y -= lineHeight
            if (y < MARGIN) break // safety; caller is responsible for pagination
            val font = fontName(line.bold, line.mono)
            if (font != curFont || line.size != curSize) {
                sb.append('/').append(font).append(' ')
                    .append(trimNum(line.size)).append(" Tf\n")
                curFont = font
                curSize = line.size
            }
            // Absolute text position via Tm (1 0 0 1 x y).
            sb.append("1 0 0 1 ").append(trimNum(MARGIN)).append(' ')
                .append(trimNum(y)).append(" Tm\n")
            sb.append('(').append(escape(line.text)).append(") Tj\n")
        }
        sb.append("ET\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun trimNum(f: Float): String =
        if (f == f.toLong().toFloat()) f.toLong().toString() else "%.2f".format(f)

    private fun flate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val buf = ByteArray(data.size + 64)
        val n = deflater.deflate(buf)
        deflater.end()
        return buf.copyOf(n)
    }

    /**
     * Escape a string for a PDF literal: `\`, `(`, `)` are backslash-escaped,
     * control chars become spaces, and anything outside WinAnsi becomes '?'.
     */
    internal fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '(' -> append("\\(")
                ')' -> append("\\)")
                '\n', '\r', '\t' -> append(' ')
                else -> {
                    val b = winAnsiByte(c)
                    append(if (b != null) b.toChar() else '?')
                }
            }
        }
    }

    /** WinAnsi byte value for [c], or null when not representable. */
    private fun winAnsiByte(c: Char): Int? {
        val code = c.code
        if (code in 0x20..0x7E) return code
        if (code in 0xA0..0xFF && code !in UNDEFINED_WINANSI) return code
        return WINANSI_SPECIAL[c]
    }

    private val UNDEFINED_WINANSI = setOf(0x81, 0x8D, 0x8F, 0x90, 0x9D)
    private val WINANSI_SPECIAL = mapOf(
        '€' to 0x80, '‚' to 0x82, 'ƒ' to 0x83, '„' to 0x84, '…' to 0x85,
        '†' to 0x86, '‡' to 0x87, 'ˆ' to 0x88, '‰' to 0x89, 'Š' to 0x8A,
        '‹' to 0x8B, 'Œ' to 0x8C, 'Ž' to 0x8E, '‘' to 0x91, '’' to 0x92,
        '“' to 0x93, '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97,
        '˜' to 0x98, '™' to 0x99, 'š' to 0x9A, '›' to 0x9B, 'œ' to 0x9C,
        'ž' to 0x9E, 'Ÿ' to 0x9F,
    )

    // Helvetica advance widths per 1000 em (AFM), ASCII printable range.
    private val HELVETICA_WIDTHS: Map<Char, Int> by lazy {
        buildMap {
            put(' ', 278); put('!', 278); put('"', 355); put('#', 556); put('$', 556)
            put('%', 889); put('&', 667); put('\'', 191); put('(', 333); put(')', 333)
            put('*', 389); put('+', 584); put(',', 278); put('-', 333); put('.', 278)
            put('/', 278)
            for (d in '0'..'9') put(d, 556)
            put(':', 278); put(';', 278); put('<', 584); put('=', 584); put('>', 584)
            put('?', 556); put('@', 1015)
            put('A', 667); put('B', 667); put('C', 722); put('D', 722); put('E', 667)
            put('F', 611); put('G', 778); put('H', 722); put('I', 278); put('J', 500)
            put('K', 667); put('L', 556); put('M', 833); put('N', 722); put('O', 778)
            put('P', 667); put('Q', 778); put('R', 722); put('S', 667); put('T', 611)
            put('U', 722); put('V', 667); put('W', 944); put('X', 667); put('Y', 667)
            put('Z', 611)
            put('[', 278); put('\\', 278); put(']', 278); put('^', 469); put('_', 556)
            put('`', 233)
            put('a', 556); put('b', 556); put('c', 500); put('d', 556); put('e', 556)
            put('f', 278); put('g', 556); put('h', 556); put('i', 222); put('j', 222)
            put('k', 500); put('l', 222); put('m', 833); put('n', 556); put('o', 556)
            put('p', 556); put('q', 556); put('r', 333); put('s', 500); put('t', 278)
            put('u', 556); put('v', 500); put('w', 722); put('x', 500); put('y', 500)
            put('z', 500)
            put('{', 334); put('|', 260); put('}', 334); put('~', 584)
        }
    }

    private fun helveticaWidth(c: Char): Int = HELVETICA_WIDTHS[c] ?: 500
}
