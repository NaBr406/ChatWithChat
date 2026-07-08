package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryChunk
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryDocument

@Dao
interface MemoryIndexDao {

    @Query("SELECT * FROM memory_document ORDER BY scope ASC, source_path COLLATE NOCASE ASC")
    suspend fun getDocuments(): List<MemoryDocument>

    @Query("SELECT * FROM memory_document WHERE source_path = :sourcePath LIMIT 1")
    suspend fun getDocument(sourcePath: String): MemoryDocument?

    @Query("SELECT * FROM memory_chunk WHERE source_path = :sourcePath ORDER BY chunk_index ASC")
    suspend fun getChunksForSource(sourcePath: String): List<MemoryChunk>

    @Query(
        """
        SELECT * FROM memory_chunk
        WHERE (:sourcePath IS NULL OR source_path = :sourcePath)
            AND (:includePrivate = 1 OR sensitivity IS NULL OR sensitivity NOT IN ('private', 'sensitive'))
        ORDER BY
            CASE WHEN source_path = 'MEMORY.md' THEN 0 ELSE 1 END ASC,
            updated_at DESC,
            indexed_at DESC,
            chunk_index ASC
        LIMIT :limit
        """
    )
    suspend fun getSearchCandidates(
        sourcePath: String? = null,
        includePrivate: Boolean = true,
        limit: Int = 200
    ): List<MemoryChunk>

    @Upsert
    suspend fun upsertDocument(document: MemoryDocument)

    @Upsert
    suspend fun upsertDocuments(documents: List<MemoryDocument>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<MemoryChunk>)

    @Query("DELETE FROM memory_chunk WHERE source_path = :sourcePath")
    suspend fun deleteChunksForSource(sourcePath: String)

    @Query("DELETE FROM memory_document WHERE source_path = :sourcePath")
    suspend fun deleteDocument(sourcePath: String)

    @Query("DELETE FROM memory_chunk")
    suspend fun clearChunks()

    @Query("DELETE FROM memory_document")
    suspend fun clearDocuments()

    @Transaction
    suspend fun replaceDocument(document: MemoryDocument, chunks: List<MemoryChunk>) {
        deleteChunksForSource(document.sourcePath)
        upsertDocument(document)
        insertChunks(chunks)
    }

    @Transaction
    suspend fun removeDocument(sourcePath: String) {
        deleteChunksForSource(sourcePath)
        deleteDocument(sourcePath)
    }

    @Transaction
    suspend fun replaceAll(documents: List<MemoryDocument>, chunks: List<MemoryChunk>) {
        clearChunks()
        clearDocuments()
        upsertDocuments(documents)
        insertChunks(chunks)
    }
}
