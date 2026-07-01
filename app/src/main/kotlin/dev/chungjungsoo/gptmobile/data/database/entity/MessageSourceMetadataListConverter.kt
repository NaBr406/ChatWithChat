package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.TypeConverter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class MessageSourceMetadataListConverter {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @TypeConverter
    fun fromString(value: String): List<MessageSourceMetadata> = if (value.isBlank()) {
        emptyList()
    } else {
        try {
            json.decodeFromString(value)
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromList(value: List<MessageSourceMetadata>): String = json.encodeToString(value)
}
