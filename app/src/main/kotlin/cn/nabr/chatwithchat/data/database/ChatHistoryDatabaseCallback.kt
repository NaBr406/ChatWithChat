package cn.nabr.chatwithchat.data.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

class ChatHistoryDatabaseCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        ChatDatabaseV2Migrations.createChatHistoryDerivedTables(db)
        ChatDatabaseV2Migrations.createChatHistoryFtsTriggers(db)
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        ChatDatabaseV2Migrations.createChatHistoryDerivedTables(db)
        ChatDatabaseV2Migrations.createChatHistoryFtsTriggers(db)
    }
}
