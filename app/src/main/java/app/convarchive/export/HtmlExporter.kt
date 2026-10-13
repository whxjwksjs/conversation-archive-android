package app.convarchive.export

import app.convarchive.model.CanonicalConversation
import app.convarchive.util.Dates

/**
 * Standalone HTML export: single self-contained file, inline CSS, all text
 * HTML-escaped, code blocks in `<pre><code class="language-x">`.
 */
object HtmlExporter {
    fun export(conv: CanonicalConversation): ExportFile {
        val sb = StringBuilder()
        val title = conv.title.ifBlank { "Untitled conversation" }
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(esc(title)).append("</title>\n")
        sb.append("<style>\n").append(CSS).append("\n</style>\n</head>\n<body>\n")
        sb.append("<article>\n")
        sb.append("<h1>").append(esc(title)).append("</h1>\n")
        sb.append("<dl class=\"meta\">\n")
        sb.append("<dt>Platform</dt><dd>").append(esc(conv.platform)).append("</dd>\n")
        if (!conv.exportedAt.isBlank())
            sb.append("<dt>Exported</dt><dd>").append(esc(conv.exportedAt)).append("</dd>\n")
        if (!conv.createdAt.isNullOrBlank())
            sb.append("<dt>Created</dt><dd>").append(esc(conv.createdAt)).append("</dd>\n")
        if (!conv.sourceUrl.isNullOrBlank())
            sb.append("<dt>Source</dt><dd><a href=\"").append(escAttr(conv.sourceUrl))
                .append("\">").append(esc(conv.sourceUrl)).append("</a></dd>\n")
        if (conv.tags.isNotEmpty())
            sb.append("<dt>Tags</dt><dd>").append(esc(conv.tags.joinToString(", "))).append("</dd>\n")
        if (conv.favorite) sb.append("<dt>Favorite</dt><dd>yes</dd>\n")
        if (conv.notes.isNotBlank())
            sb.append("<dt>Notes</dt><dd>").append(esc(conv.notes.trim())).append("</dd>\n")
        sb.append("<dt>Messages</dt><dd>").append(conv.messages.size).append("</dd>\n")
        sb.append("</dl>\n")

        for (msg in conv.messages) {
            val roleLabel = msg.role.replaceFirstChar { it.uppercase() }
            sb.append("<section class=\"message role-").append(escAttr(msg.role)).append("\">\n")
            sb.append("<h2>").append(esc(roleLabel))
            val ts = Dates.formatForDisplay(msg.createdAt)
            if (ts.isNotBlank()) sb.append(" <time>").append(esc(ts)).append("</time>")
            if (!msg.model.isNullOrBlank())
                sb.append(" <span class=\"model\">").append(esc(msg.model)).append("</span>")
            sb.append("</h2>\n")
            sb.append("<div class=\"body\">")
            // Preserve line breaks; full markdown rendering is out of scope (D5).
            for ((pi, para) in (msg.markdown ?: msg.text).split("\n\n").withIndex()) {
                if (pi > 0) sb.append("\n")
                sb.append("<p>").append(esc(para).replace("\n", "<br>")).append("</p>")
            }
            sb.append("</div>\n")
            for (block in msg.codeBlocks) {
                sb.append("<pre><code")
                if (!block.language.isNullOrBlank())
                    sb.append(" class=\"language-").append(escAttr(block.language)).append("\"")
                sb.append(">").append(esc(block.code.trimEnd())).append("</code></pre>\n")
            }
            sb.append("</section>\n")
        }
        sb.append("</article>\n</body>\n</html>\n")

        return ExportFile(
            fileName = Filenames.forConversation(
                conv.platform, conv.title,
                Filenames.dateOf(conv.exportedAt), ExportFormat.HTML.ext,
            ),
            bytes = sb.toString().toByteArray(Charsets.UTF_8),
            mimeType = ExportFormat.HTML.mimeType,
        )
    }

    private fun esc(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private fun escAttr(s: String): String = esc(s).replace("'", "&#39;")

    private const val CSS = """
body { font-family: system-ui, -apple-system, sans-serif; line-height: 1.55;
       max-width: 72ch; margin: 0 auto; padding: 1.5rem; color: #1a1a1a;
       background: #fff; }
@media (prefers-color-scheme: dark) {
  body { color: #e8e8e8; background: #121212; }
  pre { background: #1e1e1e; } a { color: #8ab4f8; }
}
h1 { font-size: 1.6rem; border-bottom: 2px solid #888; padding-bottom: .4rem; }
h2 { font-size: 1.15rem; margin-top: 2rem; }
h2 time { font-weight: normal; font-size: .85rem; opacity: .7; }
.model { font-weight: normal; font-size: .8rem; opacity: .6; }
dl.meta { display: grid; grid-template-columns: auto 1fr; gap: .15rem .8rem;
          font-size: .9rem; opacity: .9; }
dl.meta dt { font-weight: 600; }
.message { border-top: 1px solid #ccc; }
pre { background: #f4f4f4; padding: .8rem; overflow-x: auto; border-radius: 6px; }
code { font-family: ui-monospace, monospace; font-size: .9em; }
"""
}
