package app.convarchive

import app.convarchive.backup.BackupManager
import app.convarchive.data.Repository
import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalJson
import app.convarchive.util.Io
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreTest {

    private fun convs() = listOf(
        TestHelpers.sampleConversation(title = "First", platform = "gemini").copy(
            tags = listOf("keep", "me"),
            favorite = true,
            notes = "important notes",
        ),
        TestHelpers.sampleConversation(title = "Second", platform = "jules"),
    )

    @Test
    fun `backup contains manifest plus one json per conversation`() {
        val bytes = BackupManager.createBackup(convs())
        val names = TestHelpers.zipEntryNames(bytes)
        assertTrue(names.contains("manifest.json"))
        assertEquals(2, names.count { it.startsWith("conversations/") })
    }

    @Test
    fun `manifest carries app id and count`() {
        val bytes = BackupManager.createBackup(convs())
        val entries = Io.readZipEntries(bytes).toMap()
        val manifest = CanonicalJson.json.parseToJsonElement(
            entries["manifest.json"]!!.toString(Charsets.UTF_8),
        ).jsonObject
        assertEquals("convarchive-app", manifest["app"]!!.jsonPrimitive.content)
        assertEquals(2, manifest["count"]!!.jsonPrimitive.int)
    }

    @Test
    fun `backup restore round trip preserves tags favorite notes`() {
        val bytes = BackupManager.createBackup(convs())
        val results = BackupManager.parseBackup(bytes)
        assertEquals(2, results.size)
        assertTrue(results.all { it.ok })

        val repo = Repository(FakeConversationDao())
        for (r in results) {
            val outcome = repo.insert(r.conversation!!)
            assertFalse(outcome.wasDuplicate)
        }
        assertEquals(2, repo.count())

        val first = repo.query("First").first()
        val restored = repo.conversationOf(first)
        assertEquals(listOf("keep", "me"), restored.tags)
        assertTrue(restored.favorite)
        assertEquals("important notes", restored.notes)
        assertEquals("gemini", restored.platform)
    }

    @Test
    fun `restoring twice flags duplicates`() {
        val bytes = BackupManager.createBackup(convs())
        val repo = Repository(FakeConversationDao())
        val first = BackupManager.parseBackup(bytes).map { repo.insert(it.conversation!!) }
        assertTrue(first.all { !it.wasDuplicate })
        val second = BackupManager.parseBackup(bytes).map { repo.insert(it.conversation!!) }
        assertTrue(second.all { it.wasDuplicate })
        assertEquals(2, repo.count())
    }

    @Test
    fun `malformed entry becomes ok=false with exact error`() {
        val good = CanonicalJson.encode(convs()[0]).toByteArray(Charsets.UTF_8)
        val bytes = Io.writeZip(
            listOf(
                "manifest.json" to """{"app":"convarchive-app","version":1,"count":2}""".toByteArray(),
                "conversations/good.json" to good,
                "conversations/bad.json" to "{ not json".toByteArray(),
            ),
        )
        val results = BackupManager.parseBackup(bytes)
        val bad = results.first { !it.ok }
        assertTrue(bad.errors.any { it.contains("bad.json") })
        assertTrue(bad.errors.any { it.contains("JSON parse error", ignoreCase = true) })
        assertEquals(1, results.count { it.ok })
    }

    @Test
    fun `non-zip bytes produce a clear error`() {
        val results = BackupManager.parseBackup("hello".toByteArray())
        assertEquals(1, results.size)
        assertFalse(results[0].ok)
        assertTrue(results[0].errors[0].contains("not a zip archive", ignoreCase = true))
    }

    @Test
    fun `missing manifest is reported`() {
        val bytes = Io.writeZip(
            listOf("conversations/a.json" to CanonicalJson.encode(convs()[0]).toByteArray()),
        )
        val results = BackupManager.parseBackup(bytes)
        assertTrue(results.any { !it.ok && it.errors.any { e -> e.contains("manifest") } })
    }
}
