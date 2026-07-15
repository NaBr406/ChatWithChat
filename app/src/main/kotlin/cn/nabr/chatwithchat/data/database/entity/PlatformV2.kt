package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import cn.nabr.chatwithchat.data.model.ClientType
import java.util.*

@Entity(
    tableName = "platform_v2",
    indices = [Index(value = ["uid"], unique = true)]
)
data class PlatformV2(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("platform_id")
    val id: Int = 0,

    @ColumnInfo("uid")
    val uid: String = UUID.randomUUID().toString(),

    @ColumnInfo("name")
    val name: String,

    @ColumnInfo("compatible_type")
    val compatibleType: ClientType,

    @ColumnInfo(name = "enabled")
    val enabled: Boolean = false,

    @ColumnInfo(name = "api_url")
    val apiUrl: String,

    @ColumnInfo(name = "token")
    val token: String? = null,

    @ColumnInfo(name = "model")
    val model: String,

    @ColumnInfo(name = "temperature")
    val temperature: Float? = null,

    @ColumnInfo(name = "top_p")
    val topP: Float? = null,

    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String? = null,

    @ColumnInfo(name = "stream")
    val stream: Boolean = true,

    @ColumnInfo(name = "reasoning")
    val reasoning: Boolean = false,

    @ColumnInfo(name = "timeout")
    val timeout: Int = 30,

    @ColumnInfo(name = "model_refresh_status")
    val modelRefreshStatus: String = PlatformModelRefreshStatus.NOT_LOADED,

    @ColumnInfo(name = "model_refresh_error")
    val modelRefreshError: String? = null,

    @ColumnInfo(name = "model_refreshed_at")
    val modelRefreshedAt: Long? = null
)

object PlatformModelRefreshStatus {
    const val NOT_LOADED = "not_loaded"
    const val SUCCESS = "success"
    const val FAILED = "failed"
}
