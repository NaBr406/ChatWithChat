package cn.nabr.chatwithchat.data.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import cn.nabr.chatwithchat.data.database.dao.ChatRoomDao
import cn.nabr.chatwithchat.data.database.dao.MessageDao
import cn.nabr.chatwithchat.data.database.entity.APITypeConverter
import cn.nabr.chatwithchat.data.database.entity.ChatRoom
import cn.nabr.chatwithchat.data.database.entity.Message

@Database(
    entities = [ChatRoom::class, Message::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
@TypeConverters(APITypeConverter::class)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatRoomDao(): ChatRoomDao
    abstract fun messageDao(): MessageDao
}
