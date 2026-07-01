package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "platform_model_v2",
    primaryKeys = ["platform_uid", "model_id"],
    foreignKeys = [
        ForeignKey(
            entity = PlatformV2::class,
            parentColumns = ["uid"],
            childColumns = ["platform_uid"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("platform_uid")]
)
data class PlatformModelV2(
    @ColumnInfo("platform_uid")
    val platformUid: String,

    @ColumnInfo("model_id")
    val modelId: String,

    @ColumnInfo("display_name")
    val displayName: String,

    @ColumnInfo("description")
    val description: String = "",

    @ColumnInfo("enabled")
    val enabled: Boolean = true,

    @ColumnInfo("is_default")
    val isDefault: Boolean = false,

    @ColumnInfo("updated_at")
    val updatedAt: Long = System.currentTimeMillis() / 1000
)
