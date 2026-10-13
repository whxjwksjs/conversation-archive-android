package app.convarchive.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ConversationDao {

    /** @return the new row id. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(entity: ConversationEntity): Long

    @Update
    fun update(entity: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getById(id: Long): ConversationEntity?

    /**
     * Search with optional filters. Pass null/blank `q` for no text filter.
     * Text search is `LIKE %q%` over title and rawJson (documented trade-off:
     * no FTS dependency; fast enough for a personal archive).
     */
    @Query(
        """SELECT * FROM conversations
           WHERE (:q IS NULL OR :q = '' OR title LIKE '%' || :q || '%' OR rawJson LIKE '%' || :q || '%')
             AND (:platform IS NULL OR platform = :platform)
             AND (:fromMs IS NULL OR createdAt >= :fromMs)
             AND (:toMs IS NULL OR createdAt <= :toMs)""",
    )
    fun search(q: String?, platform: String?, fromMs: Long?, toMs: Long?): List<ConversationEntity>

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM conversations")
    fun count(): Int

    /** @return the existing row id, or null when the hash is unknown. */
    @Query("SELECT id FROM conversations WHERE contentHash = :hash LIMIT 1")
    fun findByHash(hash: String): Long?

    @Query("SELECT * FROM conversations ORDER BY importedAt DESC")
    fun all(): List<ConversationEntity>
}
