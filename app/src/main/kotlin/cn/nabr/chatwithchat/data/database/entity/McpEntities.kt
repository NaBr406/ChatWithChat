package cn.nabr.chatwithchat.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "mcp_servers",
    indices = [Index(value = ["alias"], unique = true)]
)
data class McpServerEntity(
    @PrimaryKey @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "alias") val alias: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "transport") val transport: String,
    @ColumnInfo(name = "auth_token") val authToken: String? = null,
    @ColumnInfo(name = "extra_headers_json") val extraHeadersJson: String = "{}",
    @ColumnInfo(name = "enabled") val enabled: Boolean = false,
    @ColumnInfo(name = "allow_private_network") val allowPrivateNetwork: Boolean = false,
    @ColumnInfo(name = "last_synced_at") val lastSyncedAt: Long? = null,
    @ColumnInfo(name = "last_sync_error") val lastSyncError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Cached `tools/list` output for one server.
 *
 * Cached rather than fetched on demand so the tool catalog is populated at app start without waiting on
 * the network, and so MCP settings can list a server's tools while it is unreachable.
 *
 * `namespaced_name` is deliberately *not* unique: a collision after truncation is recorded as a
 * per-tool rejection so it stays a visible verdict instead of failing the whole catalog install.
 */
@Entity(
    tableName = "mcp_tools",
    primaryKeys = ["server_id", "tool_name"],
    foreignKeys = [
        ForeignKey(
            entity = McpServerEntity::class,
            parentColumns = ["server_id"],
            childColumns = ["server_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["server_id"]), Index(value = ["namespaced_name"])]
)
data class McpToolEntity(
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "tool_name") val toolName: String,
    @ColumnInfo(name = "namespaced_name") val namespacedName: String,
    @ColumnInfo(name = "description") val description: String,
    /** Verbatim server schema, kept so a later app version can re-translate without reconnecting. */
    @ColumnInfo(name = "input_schema_json") val inputSchemaJson: String,
    @ColumnInfo(name = "annotation_title") val annotationTitle: String? = null,
    @ColumnInfo(name = "read_only_hint") val readOnlyHint: Boolean? = null,
    @ColumnInfo(name = "destructive_hint") val destructiveHint: Boolean? = null,
    @ColumnInfo(name = "idempotent_hint") val idempotentHint: Boolean? = null,
    @ColumnInfo(name = "open_world_hint") val openWorldHint: Boolean? = null,
    @ColumnInfo(name = "rejection_code") val rejectionCode: String? = null,
    @ColumnInfo(name = "rejection_path") val rejectionPath: String? = null,
    @ColumnInfo(name = "synced_at") val syncedAt: Long = System.currentTimeMillis()
)
