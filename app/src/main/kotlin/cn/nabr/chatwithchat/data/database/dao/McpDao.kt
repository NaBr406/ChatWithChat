package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cn.nabr.chatwithchat.data.database.entity.McpServerEntity
import cn.nabr.chatwithchat.data.database.entity.McpToolEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface McpDao {

    @Query("SELECT * FROM mcp_servers ORDER BY created_at ASC")
    fun observeServers(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers ORDER BY created_at ASC")
    suspend fun loadServers(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE server_id = :serverId LIMIT 1")
    suspend fun loadServer(serverId: String): McpServerEntity?

    @Query("SELECT alias FROM mcp_servers")
    suspend fun loadAliases(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertServer(server: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE server_id = :serverId")
    suspend fun deleteServer(serverId: String)

    @Query("UPDATE mcp_servers SET enabled = :enabled, updated_at = :updatedAt WHERE server_id = :serverId")
    suspend fun setServerEnabled(serverId: String, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query(
        "UPDATE mcp_servers SET last_synced_at = :syncedAt, last_sync_error = :error, updated_at = :updatedAt " +
            "WHERE server_id = :serverId"
    )
    suspend fun recordSyncResult(
        serverId: String,
        syncedAt: Long?,
        error: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM mcp_tools ORDER BY server_id ASC, tool_name ASC")
    fun observeTools(): Flow<List<McpToolEntity>>

    @Query("SELECT * FROM mcp_tools WHERE server_id = :serverId ORDER BY tool_name ASC")
    suspend fun loadTools(serverId: String): List<McpToolEntity>

    /**
     * Every usable tool belonging to an enabled server: exactly the set the tool registry installs.
     * A disabled server's tools never reach the catalog, so no resolver-level gate is needed.
     */
    @Query(
        "SELECT t.* FROM mcp_tools AS t INNER JOIN mcp_servers AS s ON s.server_id = t.server_id " +
            "WHERE s.enabled = 1 AND t.rejection_code IS NULL ORDER BY t.server_id ASC, t.tool_name ASC"
    )
    suspend fun loadInstallableTools(): List<McpToolEntity>

    @Query("DELETE FROM mcp_tools WHERE server_id = :serverId")
    suspend fun deleteToolsForServer(serverId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTools(tools: List<McpToolEntity>)

    /** A resync is a full replacement: tools the server dropped must disappear, not linger. */
    @Transaction
    suspend fun replaceTools(serverId: String, tools: List<McpToolEntity>) {
        deleteToolsForServer(serverId)
        if (tools.isNotEmpty()) insertTools(tools)
    }
}
