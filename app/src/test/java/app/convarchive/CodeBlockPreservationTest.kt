package app.convarchive

import app.convarchive.export.JsonExporter
import app.convarchive.export.MarkdownExporter
import app.convarchive.model.CanonicalJson
import app.convarchive.model.parsers.ParserRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeBlockPreservationTest {

    @Test
    fun `code blocks survive json round trip`() {
        val conv = TestHelpers.codeBlockConversation()
        val decoded = CanonicalJson.decode(JsonExporter.export(conv).bytes.toString(Charsets.UTF_8))
        val blocks = decoded.messages[1].codeBlocks
        assertEquals(1, blocks.size)
        assertEquals("python", blocks[0].language)
        assertEquals("print(17 * 23)", blocks[0].code)
    }

    @Test
    fun `code blocks survive markdown export and re-import`() {
        val conv = TestHelpers.codeBlockConversation()
        val md = MarkdownExporter.export(conv).bytes.toString(Charsets.UTF_8)
        assertTrue(md.contains("```python"))
        val back = ParserRegistry.parseFile("code.md", md.toByteArray())
        assertTrue(back.ok)
        val blocks = back.conversation!!.messages.flatMap { it.codeBlocks }
        assertTrue(blocks.any { it.language == "python" && it.code.contains("print(17 * 23)") })
    }

    @Test
    fun `multiple languages preserved`() {
        val conv = TestHelpers.codeBlockConversation().copy(
            messages = TestHelpers.codeBlockConversation().messages + listOf(
                app.convarchive.model.CanonicalMessage(
                    role = "assistant",
                    text = "And in Kotlin:\n```kotlin\nprintln(42)\n```",
                    markdown = "And in Kotlin:\n```kotlin\nprintln(42)\n```",
                    codeBlocks = listOf(
                        app.convarchive.model.CodeBlock(language = "kotlin", code = "println(42)"),
                    ),
                ),
            ),
        )
        val decoded = CanonicalJson.decode(JsonExporter.export(conv).bytes.toString(Charsets.UTF_8))
        val langs = decoded.messages.flatMap { it.codeBlocks }.map { it.language }
        assertEquals(listOf("python", "kotlin"), langs)
    }
}
