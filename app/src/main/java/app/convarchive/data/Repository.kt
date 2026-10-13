package app.convarchive.data

import app.convarchive.model.CanonicalConversation
import app.convarchive.model.CanonicalJson
import app.convarchive.model.ContentHash
import app.convarchive.util.Dates

/** Sort orders for [Repository.query]. */
enum class SortOrder { NEWEST, OLDEST, TITLE }

data class InsertOutcome(val id: Long, val wasDuplicate: Boolean, val existingId: Long? = null)

/**
 * All database access goes through here. Pure Kotlin/JVM except for the DAO
 * interface it is given, so it is unit-testable with an in-memory fake.
 *
 * Callers must invoke these on Dispatchers.IO (Room forbids main-thread use).
 */
class Repository(private val dao: ConversationDao) {

    /**
     * Insert unless an identical conversation (by content hash) already exists.
     * @return [InsertOutcome] with `wasDuplicate=true` and the existing id on match.
     */
    fun insert(conv: CanonicalConversation): InsertOutcome {
        val hash = ContentHash.of(conv)
        val existing = dao.findByHash(hash)
        if (existing != null) return InsertOutcome(existing, wasDuplicate = true, existingId = existing)
        val id = dao.insert(toEntity(conv, hash))
        return InsertOutcome(id, wasDuplicate = false)
    }

    /**
     * Insert unconditionally (the "import anyway" path).
     *
     * The unique contentHash index forbids two rows with the same hash, so a
     * forced re-import stores a "#dupN"-suffixed hash to get its own row id.
     * The original content hash stays computable from the conversation via
     * [ContentHash] for duplicate display.
     */
    fun insertAnyway(conv: CanonicalConversation): Long {
        val base = ContentHash.of(conv)
        var hash = base
        var n = 2
        while (dao.findByHash(hash) != null) {
            hash = "$base#dup$n"
            n++
        }
        return dao.insert(toEntity(conv, hash))
    }

    fun get(id: Long): ConversationEntity? = dao.getById(id)

    fun query(
        q: String,
        platform: String? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
        sort: SortOrder = SortOrder.NEWEST,
    ): List<ConversationEntity> {
        val rows = dao.search(q.ifBlank { null }, platform, fromMs, toMs)
        return when (sort) {
            SortOrder.NEWEST -> rows.sortedWith(
                compareByDescending<ConversationEntity> { it.createdAt ?: it.importedAt }
                    .thenByDescending { it.id },
            )
            SortOrder.OLDEST -> rows.sortedWith(
                compareBy<ConversationEntity> { it.createdAt ?: it.importedAt }
                    .thenBy { it.id },
            )
            SortOrder.TITLE -> rows.sortedWith(
                compareBy<ConversationEntity> { it.title.lowercase() }.thenBy { it.id },
            )
        }
    }

    fun count(): Int = dao.count()

    fun all(): List<ConversationEntity> = dao.all()

    fun delete(ids: List<Long>) = dao.deleteByIds(ids)

    fun hashExists(hash: String): Long? = dao.findByHash(hash)

    /**
     * Update title/notes/tags/favorite. The canonical JSON in `rawJson` is the
     * source of truth, so it is updated too (indexed columns stay in sync).
     */
    fun updateMeta(id: Long, title: String, notes: String, tags: List<String>, favorite: Boolean) {
        val entity = dao.getById(id) ?: return
        val conv = runCatching { CanonicalJson.decode(entity.rawJson) }.getOrNull()
        val updatedJson = if (conv != null) {
            CanonicalJson.encode(
                conv.copy(title = title, notes = notes, tags = tags, favorite = favorite),
            )
        } else entity.rawJson
        dao.update(
            entity.copy(
                title = title,
                notes = notes,
                tags = tags.joinToString(","),
                favorite = favorite,
                rawJson = updatedJson,
            ),
        )
    }

    /** Re-parse an entity's `rawJson` into a [CanonicalConversation]. */
    fun conversationOf(entity: ConversationEntity): CanonicalConversation =
        CanonicalJson.decode(entity.rawJson)

    private fun toEntity(conv: CanonicalConversation, hash: String): ConversationEntity =
        ConversationEntity(
            platform = conv.platform,
            title = conv.title,
            sourceId = conv.sourceId,
            sourceUrl = conv.sourceUrl,
            createdAt = Dates.isoToEpochMillis(conv.createdAt),
            updatedAt = Dates.isoToEpochMillis(conv.updatedAt),
            exportedAt = Dates.isoToEpochMillis(conv.exportedAt) ?: System.currentTimeMillis(),
            exporter = conv.exporter,
            tags = conv.tags.joinToString(","),
            favorite = conv.favorite,
            notes = conv.notes,
            contentHash = hash,
            messageCount = conv.messages.size,
            rawJson = CanonicalJson.encode(conv),
            importedAt = System.currentTimeMillis(),
        )
}
