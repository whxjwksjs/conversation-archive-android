package app.convarchive

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalMessage
import app.convarchive.model.CodeBlock
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Shared helpers for unit tests (JUnit4, pure JVM). */
object TestHelpers {
    fun resourceText(name: String): String =
        TestHelpers::class.java.getResourceAsStream("/$name")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: error("test resource not found: $name")

    fun resourceBytes(name: String): ByteArray =
        TestHelpers::class.java.getResourceAsStream("/$name")?.readBytes()
            ?: error("test resource not found: $name")

    fun sampleConversation(
        title: String = "Sample",
        platform: String = "gemini",
        messageCount: Int = 3,
    ): CanonicalConversation = CanonicalConversation(
        platform = platform,
        title = title,
        exportedAt = "2026-10-12T19:00:00Z",
        exporter = "test",
        messages = (1..messageCount).map { i ->
            CanonicalMessage(
                role = if (i % 2 == 1) "user" else "assistant",
                text = "Message $i body with some text.",
                markdown = "Message $i body with some text.",
                createdAt = "2026-10-12T19:0${i % 10}:00Z",
                model = "gemini-2.5-pro",
            )
        },
    )

    fun codeBlockConversation(): CanonicalConversation = CanonicalConversation(
        platform = "aistudio",
        title = "Code blocks",
        exportedAt = "2026-10-12T19:00:00Z",
        messages = listOf(
            CanonicalMessage(
                role = "user",
                text = "Show it in Python.",
                markdown = "Show it in Python.",
            ),
            CanonicalMessage(
                role = "assistant",
                text = "Here:\n```python\nprint(17 * 23)\n```",
                markdown = "Here:\n```python\nprint(17 * 23)\n```",
                codeBlocks = listOf(CodeBlock(language = "python", code = "print(17 * 23)")),
            ),
        ),
    )

    fun zipEntryNames(zip: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory) names += e.name
                e = zin.nextEntry
            }
        }
        return names
    }
}
