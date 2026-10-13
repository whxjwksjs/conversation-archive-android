package app.convarchive.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for one archived conversation (ARCHITECTURE.md §3).
 *
 * `rawJson` is the single source of truth (full canonical JSON v1); the scalar
 * columns are indexed query helpers only. Rendering and export always re-parse
 * `rawJson`.
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["contentHash"], unique = true),
        Index(value = ["platform"]),
        Index(value = ["favorite"]),
        Index(value = ["createdAt"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String = "other",
    val title: String = "",
    val sourceId: String? = null,
    val sourceUrl: String? = null,
    /** Epoch millis, nullable. */
    val createdAt: Long? = null,
    /** Epoch millis, nullable. */
    val updatedAt: Long? = null,
    /** Epoch millis. */
    val exportedAt: Long = 0,
    val exporter: String = "",
    /** Tags joined with "," (commas inside a tag are kept as-is). */
    val tags: String = "",
    val favorite: Boolean = false,
    val notes: String = "",
    /** SHA-256 duplicate-detection hash (see ContentHash). */
    val contentHash: String = "",
    val messageCount: Int = 0,
    @ColumnInfo(name = "rawJson") val rawJson: String = "",
    /** Epoch millis when the row was imported. */
    val importedAt: Long = 0,
)
