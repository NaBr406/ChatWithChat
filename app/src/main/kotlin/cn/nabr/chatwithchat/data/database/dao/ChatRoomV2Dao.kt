package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2

@Dao
interface ChatRoomV2Dao {

    @Query("SELECT * FROM chats_v2 ORDER BY updated_at DESC")
    suspend fun getChatRooms(): List<ChatRoomV2>

    @Query("SELECT * FROM chats_v2 WHERE title LIKE '%' || :query || '%' ORDER BY updated_at DESC")
    suspend fun searchChatRoomsByTitle(query: String): List<ChatRoomV2>

    @Insert
    suspend fun addChatRoom(chatRoom: ChatRoomV2): Long

    @Update
    suspend fun editChatRoom(chatRoom: ChatRoomV2)

    /**
     * Renames without touching any other column.
     *
     * A rename must not reorder the chat list, and writing the whole row back through
     * [editChatRoom] would also push the caller's in-memory `updated_at` — which is typically the
     * value read when the screen opened — over the newer one already stored.
     */
    @Query("UPDATE chats_v2 SET title = :title WHERE chat_id = :chatId")
    suspend fun updateChatRoomTitle(chatId: Int, title: String)

    @Delete
    suspend fun deleteChatRooms(vararg chatRooms: ChatRoomV2)
}
