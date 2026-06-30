package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.database.entity.ChatClassification

@Dao
interface ChatClassificationDao {

    @Query("SELECT * FROM chat_classification WHERE chat_id = :chatId")
    suspend fun getByChatId(chatId: Int): ChatClassification?

    @Upsert
    suspend fun upsert(classification: ChatClassification)
}
