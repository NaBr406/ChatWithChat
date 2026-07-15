package cn.nabr.chatwithchat.data.database.entity

import androidx.room.TypeConverter
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class TokenUsageRecordConverter {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @TypeConverter
    fun fromString(value: String): TokenUsageRecord? = if (value.isBlank()) {
        null
    } else {
        try {
            json.decodeFromString(value)
        } catch (e: SerializationException) {
            null
        }
    }

    @TypeConverter
    fun fromRecord(value: TokenUsageRecord?): String = value?.let { json.encodeToString(it) }.orEmpty()
}
