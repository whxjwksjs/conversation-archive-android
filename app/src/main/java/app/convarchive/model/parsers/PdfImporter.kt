package app.convarchive.model.parsers

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.ImportResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

/**
 * Minimal embedded PDF text extractor (pure JVM, no android.* imports).
 *
 * Approach: scan the raw file for `stream ... endstream` objects, inflate
 * those marked `/FlateDecode`, then run a small content-stream parser for the
 * text-showing operators `Tj`, `TJ`, `'` and `"`. Literal strings use
 * WinAnsi (PDFDoc) decoding; hex strings `<...>` are supported; escaped
 * octal/parenthesis sequences are handled.
 *
 * Limits (reported in warnings/errors, never silent):
 * - Encrypted PDFs (`/Encrypt` in the trailer) -> ok=false.
 * - Non-Flate filters, `ToUnicode` CMaps, and precise glyph positioning are
 *   NOT handled; text order is stream order, which is usually reading order
 *   but not guaranteed.
 * - Reconstructing message structure from extracted text is best-effort.
 */
object PdfImporter {

    fun parse(bytes: ByteArray, fileName: String = "import.pdf"): ImportResult {
        if (bytes.isEmpty()) {
            return ImportResult(
                ok = false,
                errors = listOf("PDF file is empty"),
                detectedExporter = EXPORTER_ID,
            )
        }
        val header = bytes.take(8).toByteArray().toString(Charsets.ISO_8859_1)
        if (!header.startsWith("%PDF-")) {
            return ImportResult(
                ok = false,
                errors = listOf("not a PDF file (missing %PDF- header)"),
                detectedExporter = EXPORTER_ID,
            )
        }
        // latin-1 keeps byte values 1:1 so offsets stay valid.
        val raw = bytes.toString(Charsets.ISO_8859_1)
        if (Regex("/Encrypt\\b").containsMatchIn(raw)) {
            return ImportResult(
                ok = false,
                errors = listOf("PDF is encrypted; text extraction is not supported"),
                detectedExporter = EXPORTER_ID,
            )
        }

        val chunks = extractTextChunks(raw)
        val text = chunks.joinToString(" ")
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" \\n"), "\n")
            .trim()
        if (text.isBlank()) {
            return ImportResult(
                ok = false,
                errors = listOf(
                    "no extractable text found (only scanned images, unsupported filters, " +
                        "or custom-encoded fonts?)",
                ),
                detectedExporter = EXPORTER_ID,
            )
        }

        val warnings = mutableListOf(
            "PDF text extraction is minimal (FlateDecode streams, WinAnsi text only)",
            "message structure reconstruction is best-effort",
        )
        val (truncated, wasTruncated) = ParseHelpers.truncate(text)
        if (wasTruncated) warnings += "extracted text truncated to 200,000 characters"

        // Try the transcript splitter; fall back to a single document message.
        val split = TranscriptSplit.split(truncated, markdownAware = false, fileName = fileName)
        val messages = if (split.foundStructure && split.messages.isNotEmpty()) {
            split.messages
        } else {
            warnings += "no message structure detected; stored as one document"
            listOf(
                CanonicalMessage(
                    role = "event",
                    text = truncated,
                    raw = JsonObject(mapOf("pdfText" to JsonPrimitive(true))),
                ),
            )
        }

        val conv = CanonicalConversation(
            platform = "other",
            title = split.titleHint?.takeIf { it.isNotBlank() } ?: fallbackTitle(fileName),
            exportedAt = ParseHelpers.nowIso(),
            exporter = EXPORTER_ID,
            notes = "Imported from PDF; text extraction and message structure are best-effort.",
            messages = messages,
            originalExport = JsonPrimitive("[PDF binary, ${bytes.size} bytes; text extracted best-effort]"),
        )
        return ImportResult(
            ok = true,
            conversation = conv,
            warnings = warnings,
            detectedPlatform = "other",
            detectedExporter = EXPORTER_ID,
        )
    }

    private const val EXPORTER_ID = "convarchive-pdf-importer"

    /** Find stream objects, inflate FlateDecode ones, collect shown text. */
    private fun extractTextChunks(raw: String): List<String> {
        val out = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val streamIdx = raw.indexOf("stream", searchFrom)
            if (streamIdx < 0) break
            val endIdx = raw.indexOf("endstream", streamIdx)
            if (endIdx < 0) break
            // Dictionary is the text just before "stream" (bounded scan).
            val dictStart = maxOf(0, streamIdx - 2000)
            val dict = raw.substring(dictStart, streamIdx)
            var dataStart = streamIdx + "stream".length
            // Skip EOL after the stream keyword.
            if (raw.startsWith("\r\n", dataStart)) dataStart += 2
            else if (raw.startsWith("\n", dataStart) || raw.startsWith("\r", dataStart)) dataStart += 1
            val data = raw.substring(dataStart, endIdx)
            val isFlate = dict.contains("/FlateDecode")
            val isTextual = !dict.contains("/Image")
            if (isTextual) {
                val content: String? = if (isFlate) {
                    runCatching { inflate(data) }.getOrNull()
                } else if (looksLikeContentStream(dict)) {
                    data
                } else null
                if (content != null) {
                    val shown = showText(content)
                    if (shown.isNotBlank()) out += shown
                }
            }
            searchFrom = endIdx + "endstream".length
        }
        return out
    }

    private fun looksLikeContentStream(dict: String): Boolean =
        dict.contains("/Length") && !dict.contains("/Font") && !dict.contains("/XObject")

    private fun inflate(latin1: String): String {
        val bytes = latin1.toByteArray(Charsets.ISO_8859_1)
        // PDF FlateDecode is zlib (RFC1950), so the default Inflater (not nowrap).
        InflaterInputStream(ByteArrayInputStream(bytes)).use { ins ->
            return ins.readBytes().toString(Charsets.ISO_8859_1)
        }
    }

    /**
     * Very small PDF content-stream text-showing parser. Handles
     * `(literal) Tj`, `[ (a) 120 (b) ] TJ`, `(literal) '`, and
     * `(a) (b) "`. Returns the shown strings joined with newlines.
     */
    private fun showText(content: String): String {
        val out = StringBuilder()
        var i = 0
        val n = content.length
        fun skipWs() { while (i < n && content[i].isWhitespace()) i++ }

        while (i < n) {
            skipWs()
            if (i >= n) break
            val c = content[i]
            when {
                c == '(' -> {
                    val (s, next) = readLiteral(content, i)
                    i = next
                    skipWs()
                    when {
                        content.startsWith("Tj", i) -> { out.append(s).append('\n'); i += 2 }
                        content.startsWith("'", i) -> { out.append(s).append('\n'); i += 1 }
                        content.startsWith("\"", i) -> {
                            // (aw) (ac) string "  -> pops two numbers then shows string
                            out.append(s).append('\n'); i += 1
                        }
                        else -> { /* string used as something else; keep scanning */ }
                    }
                }
                c == '<' && i + 1 < n && content[i + 1] == '<' -> {
                    i = skipDict(content, i)
                }
                c == '<' -> {
                    val (s, next) = readHex(content, i)
                    i = next
                    skipWs()
                    if (content.startsWith("Tj", i)) { out.append(s).append('\n'); i += 2 }
                }
                c == '[' -> {
                    // TJ array: collect strings, ignore kerning numbers.
                    i++
                    val piece = StringBuilder()
                    while (i < n && content[i] != ']') {
                        skipWs()
                        if (i < n && content[i] == '(') {
                            val (s, next) = readLiteral(content, i)
                            piece.append(s)
                            i = next
                        } else if (i < n && content[i] == '<') {
                            val (s, next) = readHex(content, i)
                            piece.append(s)
                            i = next
                        } else i++
                    }
                    if (i < n) i++ // consume ']'
                    skipWs()
                    if (content.startsWith("TJ", i)) {
                        out.append(piece).append('\n')
                        i += 2
                    } else if (content.startsWith("Tj", i)) {
                        out.append(piece).append('\n')
                        i += 2
                    }
                }
                c == '/' -> {
                    // Name object: skip.
                    i++
                    while (i < n && !content[i].isWhitespace() && content[i] !in "()<>[]{}/") i++
                }
                else -> i++
            }
        }
        return out.toString()
    }

    private fun readLiteral(s: String, start: Int): Pair<String, Int> {
        // start points at '('
        val sb = StringBuilder()
        var i = start + 1
        var depth = 1
        while (i < s.length && depth > 0) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    val e = s[i + 1]
                    when (e) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        '(', ')', '\\' -> sb.append(e)
                        in '0'..'7' -> {
                            var oct = ""
                            var j = i + 1
                            while (j < s.length && j < i + 4 && s[j] in '0'..'7') { oct += s[j]; j++ }
                            sb.append(winAnsiChar(oct.toInt(8)))
                            i = j - 1
                        }
                        else -> sb.append(e) // line continuation or unknown: literal char
                    }
                    i += 2
                }
                c == '(' -> { depth++; sb.append(c); i++ }
                c == ')' -> { depth--; if (depth > 0) sb.append(c); i++ }
                else -> { sb.append(winAnsiChar(c.code)); i++ }
            }
        }
        return sb.toString() to i
    }

    private fun readHex(s: String, start: Int): Pair<String, Int> {
        var i = start + 1
        val hex = StringBuilder()
        while (i < s.length && s[i] != '>') {
            if (!s[i].isWhitespace()) hex.append(s[i])
            i++
        }
        if (i < s.length) i++
        var h = hex.toString()
        if (h.length % 2 == 1) h += "0"
        val sb = StringBuilder()
        var j = 0
        while (j + 1 < h.length) {
            sb.append(winAnsiChar(h.substring(j, j + 2).toIntOrNull(16) ?: 0x3F))
            j += 2
        }
        return sb.toString() to i
    }

    private fun skipDict(s: String, start: Int): Int {
        var i = start + 2
        var depth = 1
        while (i < s.length && depth > 0) {
            when {
                s.startsWith("<<", i) -> { depth++; i += 2 }
                s.startsWith(">>", i) -> { depth--; i += 2 }
                else -> i++
            }
        }
        return i
    }

    /** WinAnsi (PDFDocEncoding-ish) byte -> Char. Undefined codes become '?'. */
    private fun winAnsiChar(byte: Int): Char {
        val b = byte and 0xFF
        return when (b) {
            in 0x20..0x7E -> b.toChar()
            0x0A -> '\n'
            0x0D -> '\r'
            0x09 -> '\t'
            // WinAnsi additions in 0x80..0x9F.
            0x80 -> '€'; 0x82 -> '‚'; 0x83 -> 'ƒ'; 0x84 -> '„'; 0x85 -> '…'
            0x86 -> '†'; 0x87 -> '‡'; 0x88 -> 'ˆ'; 0x89 -> '‰'; 0x8A -> 'Š'
            0x8B -> '‹'; 0x8C -> 'Œ'; 0x8E -> 'Ž'; 0x91 -> '‘'; 0x92 -> '’'
            0x93 -> '“'; 0x94 -> '”'; 0x95 -> '•'; 0x96 -> '–'; 0x97 -> '—'
            0x98 -> '˜'; 0x99 -> '™'; 0x9A -> 'š'; 0x9B -> '›'; 0x9C -> 'œ'
            0x9E -> 'ž'; 0x9F -> 'Ÿ'
            in 0xA0..0xFF -> b.toChar() // Latin-1 supplement matches WinAnsi here
            else -> '?'
        }
    }
}
