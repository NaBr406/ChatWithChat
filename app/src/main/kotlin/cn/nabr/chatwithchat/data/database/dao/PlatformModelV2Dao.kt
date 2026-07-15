package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2

@Dao
interface PlatformModelV2Dao {

    @Query("SELECT * FROM platform_model_v2 ORDER BY platform_uid ASC, display_name COLLATE NOCASE ASC, model_id COLLATE NOCASE ASC")
    suspend fun getModels(): List<PlatformModelV2>

    @Query("SELECT * FROM platform_model_v2 WHERE platform_uid = :platformUid ORDER BY display_name COLLATE NOCASE ASC, model_id COLLATE NOCASE ASC")
    suspend fun getModelsForPlatform(platformUid: String): List<PlatformModelV2>

    @Query("SELECT * FROM platform_model_v2 WHERE platform_uid = :platformUid AND model_id = :modelId LIMIT 1")
    suspend fun getModel(platformUid: String, modelId: String): PlatformModelV2?

    @Upsert
    suspend fun upsertModels(models: List<PlatformModelV2>)

    @Query("UPDATE platform_model_v2 SET enabled = :enabled, updated_at = :updatedAt WHERE platform_uid = :platformUid AND model_id = :modelId")
    suspend fun updateEnabled(platformUid: String, modelId: String, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE platform_model_v2 SET is_default = CASE WHEN model_id = :modelId THEN 1 ELSE 0 END, enabled = CASE WHEN model_id = :modelId THEN 1 ELSE enabled END, updated_at = :updatedAt WHERE platform_uid = :platformUid")
    suspend fun setDefault(platformUid: String, modelId: String, updatedAt: Long)

    @Query("UPDATE platform_model_v2 SET is_default = 0, updated_at = :updatedAt WHERE platform_uid = :platformUid AND model_id = :modelId")
    suspend fun clearDefault(platformUid: String, modelId: String, updatedAt: Long)

    @Query("DELETE FROM platform_model_v2 WHERE platform_uid = :platformUid")
    suspend fun deleteByPlatformUid(platformUid: String)
}
