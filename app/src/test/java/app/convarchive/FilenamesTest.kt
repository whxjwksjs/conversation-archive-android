package app.convarchive

import app.convarchive.export.ExportFormat
import app.convarchive.export.Filenames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FilenamesTest {

    @Test
    fun `pattern matches spec example`() {
        assertEquals(
            "AIStudio_Conversation_2026-10-12.json",
            Filenames.forConversation("aistudio", "Conversation", LocalDate.of(2026, 10, 12), "json"),
        )
    }

    @Test
    fun `platform labels map correctly`() {
        assertEquals("Gemini", Filenames.platformLabel("gemini"))
        assertEquals("AIStudio", Filenames.platformLabel("aistudio"))
        assertEquals("Jules", Filenames.platformLabel("jules"))
        assertEquals("Conversation", Filenames.platformLabel("other"))
        assertEquals("Conversation", Filenames.platformLabel("claude"))
    }

    @Test
    fun `sanitize is ascii-safe with spaces to underscores`() {
        assertEquals("Hello_World_2026", Filenames.sanitize("Hello World! 2026"))
        assertEquals("Conversation", Filenames.sanitize("!!!"))
        assertEquals("Conversation", Filenames.sanitize(""))
        // non-ASCII stripped
        assertEquals("caf", Filenames.sanitize("caf\u00e9"))
        // no path separators survive
        val s = Filenames.sanitize("../../etc/passwd")
        assertTrue(!s.contains("/") && !s.contains("\\"))
    }

    @Test
    fun `date defaults to today when null`() {
        val name = Filenames.forConversation("gemini", "T", null, "md")
        assertTrue(name.startsWith("Gemini_T_${LocalDate.now()}.md"))
    }

    @Test
    fun `all export formats produce sane filenames`() {
        for (format in ExportFormat.entries) {
            val name = Filenames.forConversation("jules", "My Task", LocalDate.of(2026, 1, 2), format.ext)
            assertEquals("Jules_My_Task_2026-01-02.${format.ext}", name)
        }
    }
}
