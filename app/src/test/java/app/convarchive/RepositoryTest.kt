package app.convarchive

import app.convarchive.data.ConversationDao
import app.convarchive.data.ConversationEntity
import app.convarchive.data.InsertOutcome
import app.convarchive.data.Repository
import app.convarchive.data.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory fake of [ConversationDao]; mirrors the SQL contract
 * (LIKE %q% over title+rawJson, platform/date filters, hash lookup).
 */
class FakeConversationDao : ConversationDao {
    private val rows = mutableListOf<ConversationEntity>()
    private var nextId = 1L

    override fun insert(entity: ConversationEntity): Long {
        if (entity.contentHash.isNotEmpty() && rows.any { it.contentHash == entity.contentHash }) {
            throw IllegalStateException("UNIQUE constraint failed: contentHash")
        }
        val id = nextId++
        rows += entity.copy(id = id)
        return id
    }

    override fun update(entity: ConversationEntity) {
        val i = rows.indexOfFirst { it.id == entity.id }
        if (i >= 0) rows[i] = entity
    }

    override fun getById(id: Long): ConversationEntity? = rows.firstOrNull { it.id == id }

    override fun search(q: String?, platform: String?, fromMs: Long?, toMs: Long?): List<ConversationEntity> =
        rows.filter { e ->
            (q.isNullOrEmpty() || e.title.contains(q, ignoreCase = true) || e.rawJson.contains(q, ignoreCase = true)) &&
                (platform == null || e.platform == platform) &&
                (fromMs == null || (e.createdAt ?: Long.MIN_VALUE) >= fromMs) &&
                (toMs == null || (e.createdAt ?: Long.MAX_VALUE) <= toMs)
        }

    override fun deleteByIds(ids: List<Long>) {
        rows.removeAll { it.id in ids }
    }

    override fun count(): Int = rows.size

    override fun findByHash(hash: String): Long? = rows.firstOrNull { it.contentHash == hash }?.id

    override fun all(): List<ConversationEntity> = rows.toList()
}

class DuplicateDetectionTest {

    @Test
    fun `insert twice reports duplicate with existing id`() {
        val repo = Repository(FakeConversationDao())
        val conv = TestHelpers.sampleConversation(title = "Dup")
        val first: InsertOutcome = repo.insert(conv)
        assertFalse(first.wasDuplicate)
        assertNull(first.existingId)

        val second = repo.insert(conv)
        assertTrue(second.wasDuplicate)
        assertEquals(first.id, second.existingId)
        assertEquals(1, repo.count())
    }

    @Test
    fun `insertAnyway stores a second row`() {
        val repo = Repository(FakeConversationDao())
        val conv = TestHelpers.sampleConversation(title = "Dup")
        val first = repo.insert(conv)
        val forced = repo.insertAnyway(conv)
        assertTrue(forced != first.id)
        assertEquals(2, repo.count())
    }

    @Test
    fun `different content gives different rows`() {
        val repo = Repository(FakeConversationDao())
        repo.insert(TestHelpers.sampleConversation(title = "A"))
        val second = repo.insert(TestHelpers.sampleConversation(title = "B"))
        assertFalse(second.wasDuplicate)
        assertEquals(2, repo.count())
    }
}

class RepositoryTest {

    private fun repoWith(vararg titles: String): Repository {
        val repo = Repository(FakeConversationDao())
        titles.forEachIndexed { i, t ->
            val conv = TestHelpers.sampleConversation(title = t).copy(
                platform = if (i % 2 == 0) "gemini" else "aistudio",
                createdAt = "2026-10-0${i + 1}T10:00:00Z",
            )
            repo.insert(conv)
        }
        return repo
    }

    @Test
    fun `search matches title and rawJson`() {
        val repo = repoWith("Trip to Japan", "Recursion")
        assertEquals(1, repo.query("japan").size)
        assertEquals(2, repo.query("Message 2 body").size) // inside rawJson of both rows
        assertEquals(2, repo.query("").size)
    }

    @Test
    fun `platform filter works`() {
        val repo = repoWith("A", "B", "C")
        assertEquals(2, repo.query("", platform = "gemini").size)
        assertEquals(1, repo.query("", platform = "aistudio").size)
    }

    @Test
    fun `date range filter works`() {
        val repo = repoWith("A", "B", "C")
        // createdAt: Oct 1, 2, 3
        val from = app.convarchive.util.Dates.isoToEpochMillis("2026-10-02T00:00:00Z")!!
        val to = app.convarchive.util.Dates.isoToEpochMillis("2026-10-02T23:59:59Z")!!
        assertEquals(1, repo.query("", fromMs = from, toMs = to).size)
    }

    @Test
    fun `sort orders are respected`() {
        val repo = repoWith("Charlie", "Alpha", "Bravo")
        assertEquals(
            listOf("Bravo", "Alpha", "Charlie"),
            repo.query("", sort = SortOrder.NEWEST).map { it.title },
        )
        assertEquals(
            listOf("Charlie", "Alpha", "Bravo"),
            repo.query("", sort = SortOrder.OLDEST).map { it.title },
        )
        assertEquals(
            listOf("Alpha", "Bravo", "Charlie"),
            repo.query("", sort = SortOrder.TITLE).map { it.title },
        )
    }

    @Test
    fun `delete removes rows`() {
        val repo = repoWith("A", "B")
        val ids = repo.all().map { it.id }
        repo.delete(ids.take(1))
        assertEquals(1, repo.count())
        assertNull(repo.get(ids[0]))
    }

    @Test
    fun `updateMeta updates entity and rawJson`() {
        val repo = repoWith("Old title")
        val id = repo.all().first().id
        repo.updateMeta(id, "New title", "my notes", listOf("t1", "t2"), true)
        val entity = repo.get(id)!!
        assertEquals("New title", entity.title)
        assertEquals("my notes", entity.notes)
        assertEquals("t1,t2", entity.tags)
        assertTrue(entity.favorite)
        val conv = repo.conversationOf(entity)
        assertEquals("New title", conv.title)
        assertEquals(listOf("t1", "t2"), conv.tags)
        assertTrue(conv.favorite)
    }

    @Test
    fun `conversationOf re-parses rawJson`() {
        val repo = repoWith("RoundTrip")
        val entity = repo.all().first()
        val conv = repo.conversationOf(entity)
        assertEquals("RoundTrip", conv.title)
        assertEquals(entity.messageCount, conv.messages.size)
    }

    @Test
    fun `hashExists finds inserted conversation`() {
        val repo = Repository(FakeConversationDao())
        val conv = TestHelpers.sampleConversation(title = "HashMe")
        val outcome = repo.insert(conv)
        assertNotNull(repo.hashExists(app.convarchive.model.ContentHash.of(conv)))
        assertEquals(outcome.id, repo.hashExists(app.convarchive.model.ContentHash.of(conv)))
    }
}
