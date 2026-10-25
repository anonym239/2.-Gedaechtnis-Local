package de.gedaechtnis.local.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SnippetDao {

    @Insert
    suspend fun insert(snippet: ContextSnippet): Long

    @Query("SELECT COUNT(*) FROM snippets")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM snippets WHERE embedding IS NOT NULL")
    fun observeEmbeddedCount(): Flow<Int>

    @Query("SELECT MAX(timestamp) FROM snippets")
    fun observeLastTimestamp(): Flow<Long?>

    @Query("SELECT * FROM snippets ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ContextSnippet>>

    @Query(
        """SELECT appLabel, packageName, COUNT(*) AS count FROM snippets
           GROUP BY packageName ORDER BY count DESC LIMIT :limit""",
    )
    fun observeTopApps(limit: Int): Flow<List<AppCount>>

    /** Gibt es denselben Inhalt derselben App bereits seit [since]? */
    @Query("SELECT EXISTS(SELECT 1 FROM snippets WHERE contentHash = :hash AND packageName = :pkg AND timestamp >= :since)")
    suspend fun existsSince(hash: Long, pkg: String, since: Long): Boolean

    /** Volltextsuche (FTS4 MATCH) innerhalb eines Zeitfensters. */
    @Query(
        """SELECT s.* FROM snippets s
           JOIN snippets_fts f ON s.id = f.rowid
           WHERE snippets_fts MATCH :match AND s.timestamp BETWEEN :from AND :to
           ORDER BY s.timestamp DESC LIMIT :limit""",
    )
    suspend fun searchFts(match: String, from: Long, to: Long, limit: Int): List<ContextSnippet>

    @Query(
        """SELECT id, embedding FROM snippets
           WHERE embedding IS NOT NULL AND timestamp BETWEEN :from AND :to
           ORDER BY timestamp DESC LIMIT :limit""",
    )
    suspend fun embeddingsInRange(from: Long, to: Long, limit: Int): List<EmbeddingRow>

    @Query("SELECT * FROM snippets WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp DESC LIMIT :limit")
    suspend fun inRange(from: Long, to: Long, limit: Int): List<ContextSnippet>

    @Query("SELECT * FROM snippets WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ContextSnippet>

    @Query("SELECT * FROM snippets WHERE embedding IS NULL ORDER BY timestamp DESC LIMIT :limit")
    suspend fun pendingEmbedding(limit: Int): List<ContextSnippet>

    @Query("UPDATE snippets SET embedding = :embedding WHERE id = :id")
    suspend fun setEmbedding(id: Long, embedding: ByteArray)

    @Query("DELETE FROM snippets WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM snippets WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String): Int

    @Query("DELETE FROM snippets")
    suspend fun deleteAll()
}
