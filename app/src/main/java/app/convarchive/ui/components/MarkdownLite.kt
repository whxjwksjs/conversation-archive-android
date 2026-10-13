package app.convarchive.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small built-in markdown-lite renderer (D5: no markdown library).
 * Handles headings (#), **bold**, `inline code`, and ```fenced code blocks```.
 * Everything else renders as plain text. Code blocks use a monospace font on
 * a subtle background.
 */
sealed interface MdBlock {
    data class Prose(val text: AnnotatedString) : MdBlock
    data class Code(val language: String?, val code: String) : MdBlock
}

private val inlinePattern = Regex("""(\*\*.+?\*\*)|(`.+?`)""")
private val headingPattern = Regex("""^(#{1,6})\s+(.*)$""")

fun parseMarkdownLite(input: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val prose = StringBuilder()
    val code = StringBuilder()
    var inCode = false
    var codeLang: String? = null

    fun flushProse() {
        if (prose.isNotEmpty()) {
            blocks.add(MdBlock.Prose(buildProse(prose.toString()))
            )
            prose.clear()
        }
    }

    for (rawLine in input.lines()) {
        val line = rawLine
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks.add(MdBlock.Code(codeLang, code.toString().trimEnd('\n')))
                code.clear()
                codeLang = null
                inCode = false
            } else {
                flushProse()
                codeLang = line.trimStart().removePrefix("```").trim().takeIf { it.isNotEmpty() }
                inCode = true
            }
        } else if (inCode) {
            code.appendLine(line)
        } else {
            prose.appendLine(line)
        }
    }
    if (inCode) {
        // Unclosed fence: treat the remainder as a code block anyway.
        blocks.add(MdBlock.Code(codeLang, code.toString().trimEnd('\n')))
    } else {
        flushProse()
    }
    return blocks
}

private fun buildProse(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.trimEnd('\n').lines()
    lines.forEachIndexed { index, line ->
        val heading = headingPattern.matchEntire(line.trimEnd())
        if (heading != null) {
            val level = heading.groupValues[1].length
            val content = heading.groupValues[2]
            val start = length
            appendInline(content)
            val size = when (level) {
                1 -> 20.sp
                2 -> 18.sp
                else -> 16.sp
            }
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = size), start, length)
        } else {
            appendInline(line)
        }
        if (index != lines.lastIndex) append("\n")
    }
}

private fun AnnotatedString.Builder.appendInline(s: String) {
    var pos = 0
    for (m in inlinePattern.findAll(s)) {
        if (m.range.first > pos) append(s.substring(pos, m.range.first))
        val token = m.value
        val start = length
        when {
            token.startsWith("**") -> {
                append(token.removePrefix("**").removeSuffix("**"))
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
            }
            token.startsWith("`") -> {
                append(token.removePrefix("`").removeSuffix("`"))
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = androidx.compose.ui.graphics.Color(0x22000000),
                    ),
                    start, length,
                )
            }
        }
        pos = m.range.last + 1
    }
    if (pos < s.length) append(s.substring(pos))
}

/** Renders parsed markdown-lite blocks vertically. */
@Composable
fun MarkdownLiteText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseMarkdownLite(markdown) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Prose -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                )
                is MdBlock.Code -> CodeBlockView(
                    language = block.language,
                    code = block.code,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
fun CodeBlockView(
    language: String?,
    code: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (!language.isNullOrBlank()) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}
