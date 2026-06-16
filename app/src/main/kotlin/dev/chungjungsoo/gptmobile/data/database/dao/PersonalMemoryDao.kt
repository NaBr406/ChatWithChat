package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory

@Dao
interface PersonalMemoryDao {

    @Query("SELECT * FROM personal_memory ORDER BY status ASC, type ASC, updated_at DESC")
    suspend fun getAll(): List<PersonalMemory>

    @Query("SELECT * FROM personal_memory WHERE status IN (:statuses)")
    suspend fun getByStatuses(statuses: List<String>): List<PersonalMemory>

    @Query("SELECT * FROM personal_memory WHERE type=:type AND content=:content LIMIT 1")
    suspend fun findByTypeAndContent(type: String, content: String): PersonalMemory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: PersonalMemory): Long

    @Update
    suspend fun update(memory: PersonalMemory)

    @Delete
    suspend fun delete(memory: PersonalMemory)

    @Query("DELETE FROM personal_memory WHERE memory_id=:memoryId")
    suspend fun deleteById(memoryId: Int)

    @Query("UPDATE personal_memory SET content=:content, updated_at=:updatedAt WHERE memory_id=:memoryId")
    suspend fun updateContent(memoryId: Int, content: String, updatedAt: Long)

    @Query("UPDATE personal_memory SET status=:status, updated_at=:updatedAt WHERE memory_id=:memoryId")
    suspend fun updateStatus(memoryId: Int, status: String, updatedAt: Long)

    @Query("UPDATE personal_memory SET last_accessed_at=:lastAccessedAt WHERE memory_id IN (:memoryIds)")
    suspend fun updateLastAccessed(memoryIds: List<Int>, lastAccessedAt: Long)
}
