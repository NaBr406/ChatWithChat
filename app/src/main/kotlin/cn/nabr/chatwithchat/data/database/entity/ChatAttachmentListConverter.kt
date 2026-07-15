package cn.nabr.chatwithchat.data.database.entity

import androidx.room.TypeConverter
import cn.nabr.chatwithchat.data.model.ChatAttachment
import kotlinx.serialization.json.Json

class ChatAttachmentListConverter {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @TypeConverter
    fun fromString(value: String): List<ChatAttachment> = if (value.isBlank()) {
        emptyList()
    } else {
        json.decodeFromString(value)
    }

    @TypeConverter
    fun fromList(value: List<ChatAttachment>): String = json.encodeToString(value)
}
