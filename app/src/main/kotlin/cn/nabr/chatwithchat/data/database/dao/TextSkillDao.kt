package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.nabr.chatwithchat.data.database.entity.MessageTextSkillInvocation
import cn.nabr.chatwithchat.data.database.entity.SessionTextSkillBinding

@Dao
interface TextSkillDao {
    @Query("SELECT * FROM session_text_skill_bindings WHERE chat_id = :chatId ORDER BY order_index ASC, created_at ASC")
    suspend fun loadSessionBindings(chatId: Int): List<SessionTextSkillBinding>

    @Query("SELECT * FROM session_text_skill_bindings WHERE package_id = :packageId ORDER BY chat_id ASC, order_index ASC, created_at ASC")
    suspend fun loadBindingsForPackage(packageId: String): List<SessionTextSkillBinding>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBinding(binding: SessionTextSkillBinding)

    @Query(
        "UPDATE session_text_skill_bindings SET is_enabled = :enabled, updated_at = :updatedAt " +
            "WHERE chat_id = :chatId AND package_id = :packageId AND skill_id = :skillId AND variant_key = :variantKey"
    )
    suspend fun setBindingEnabled(
        chatId: Int,
        packageId: String,
        skillId: String,
        variantKey: String,
        enabled: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        "DELETE FROM session_text_skill_bindings WHERE chat_id = :chatId AND package_id = :packageId " +
            "AND skill_id = :skillId AND variant_key = :variantKey"
    )
    suspend fun removeBinding(chatId: Int, packageId: String, skillId: String, variantKey: String)

    @Query("UPDATE session_text_skill_bindings SET is_enabled = 0, updated_at = :updatedAt WHERE package_id = :packageId")
    suspend fun disableBindingsForPackage(packageId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM session_text_skill_bindings WHERE package_id = :packageId")
    suspend fun countBindingsForPackage(packageId: String): Int

    @Query("SELECT * FROM message_text_skill_invocations WHERE user_message_id = :userMessageId LIMIT 1")
    suspend fun loadInvocation(userMessageId: Int): MessageTextSkillInvocation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvocation(invocation: MessageTextSkillInvocation)
}
