package cn.nabr.chatwithchat.data.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

class ChatHistoryDatabaseCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        ChatDatabaseV2Migrations.ensureChatHistoryRuntimeObjects(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        ChatDatabaseV2Migrations.ensureChatHistoryRuntimeObjects(db)
    }
}
