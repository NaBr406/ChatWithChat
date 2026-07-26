package cn.nabr.chatwithchat.data.database.entity

import androidx.room.TypeConverter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class MessageStickerRefListConverter {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @TypeConverter
    fun fromString(value: String): List<MessageStickerRef> = if (value.isBlank()) {
        emptyList()
    } else {
        try {
            json.decodeFromString(value)
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromList(value: List<MessageStickerRef>): String = json.encodeToString(value)
}
