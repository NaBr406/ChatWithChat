package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.database.entity.PersonalMemory

@Dao
interface PersonalMemoryDao {

    @Query("SELECT * FROM personal_memory ORDER BY status ASC, importance DESC, updated_at DESC")
    suspend fun getAll(): List<PersonalMemory>

    @Query("SELECT * FROM personal_memory WHERE status NOT IN ('archived', 'superseded') ORDER BY importance DESC, updated_at DESC")
    suspend fun getRecallCandidates(): List<PersonalMemory>

    @Query("SELECT * FROM personal_memory WHERE memory_id IN (:ids)")
    suspend fun getByIds(ids: List<Int>): List<PersonalMemory>

    @Insert
    suspend fun insert(memory: PersonalMemory): Long

    @Upsert
    suspend fun upsert(memory: PersonalMemory)

    @Update
    suspend fun update(memory: PersonalMemory)

    @Delete
    suspend fun delete(memory: PersonalMemory)
}
