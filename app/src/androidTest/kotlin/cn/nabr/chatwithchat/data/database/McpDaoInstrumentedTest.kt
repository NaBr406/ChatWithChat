package cn.nabr.chatwithchat.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.dao.McpDao
import cn.nabr.chatwithchat.data.database.entity.McpServerEntity
import cn.nabr.chatwithchat.data.database.entity.McpToolEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `loadInstallableTools` is the query that decides what reaches the tool registry, so the "a disabled
 * server is unreachable" guarantee is enforced here in SQL rather than by a caller remembering to filter.
 */
@RunWith(AndroidJUnit4::class)
class McpDaoInstrumentedTest {

    private lateinit var database: ChatDatabaseV2
    private lateinit var dao: McpDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ChatDatabaseV2::class.java
        ).build()
        dao = database.mcpDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun loadInstallableTools_excludesToolsOfADisabledServer() = runBlocking {
        dao.upsertServer(server("enabled-server", "alpha", enabled = true))
        dao.upsertServer(server("disabled-server", "beta", enabled = false))
        dao.insertTools(
            listOf(
                tool("enabled-server", "from_enabled"),
                tool("disabled-server", "from_disabled")
            )
        )

        val installable = dao.loadInstallableTools()

        assertEquals(listOf("from_enabled"), installable.map(McpToolEntity::toolName))
    }

    @Test
    fun loadInstallableTools_excludesRejectedTools() = runBlocking {
        dao.upsertServer(server("s1", "alpha", enabled = true))
        dao.insertTools(
            listOf(
                tool("s1", "usable"),
                tool("s1", "refused", rejectionCode = "schema_unsupported_composition")
            )
        )

        assertEquals(listOf("usable"), dao.loadInstallableTools().map(McpToolEntity::toolName))
    }

    @Test
    fun loadInstallableTools_isOrderedDeterministically() = runBlocking {
        dao.upsertServer(server("s1", "alpha", enabled = true))
        dao.insertTools(listOf(tool("s1", "zebra"), tool("s1", "apple"), tool("s1", "mango")))

        assertEquals(
            listOf("apple", "mango", "zebra"),
            dao.loadInstallableTools().map(McpToolEntity::toolName)
        )
    }

    @Test
    fun replaceTools_dropsToolsTheServerNoLongerAdvertises() = runBlocking {
        dao.upsertServer(server("s1", "alpha", enabled = true))
        dao.replaceTools("s1", listOf(tool("s1", "old_one"), tool("s1", "kept")))

        dao.replaceTools("s1", listOf(tool("s1", "kept"), tool("s1", "new_one")))

        assertEquals(
            listOf("kept", "new_one"),
            dao.loadTools("s1").map(McpToolEntity::toolName)
        )
    }

    @Test
    fun replaceTools_withEmptyListClearsTheServerCatalogOnly() = runBlocking {
        dao.upsertServer(server("s1", "alpha", enabled = true))
        dao.upsertServer(server("s2", "beta", enabled = true))
        dao.insertTools(listOf(tool("s1", "a"), tool("s2", "b")))

        dao.replaceTools("s1", emptyList())

        assertTrue(dao.loadTools("s1").isEmpty())
        assertEquals(listOf("b"), dao.loadTools("s2").map(McpToolEntity::toolName))
    }

    @Test
    fun deletingAServer_cascadesToItsCachedTools() = runBlocking {
        dao.upsertServer(server("s1", "alpha", enabled = true))
        dao.insertTools(listOf(tool("s1", "a"), tool("s1", "b")))

        dao.deleteServer("s1")

        assertTrue(dao.loadTools("s1").isEmpty())
        assertNull(dao.loadServer("s1"))
    }

    @Test
    fun recordSyncResult_keepsTheCatalogWhileStoringTheError() = runBlocking {
        dao.upsertServer(server("s1", "alpha", enabled = true))
        dao.insertTools(listOf(tool("s1", "a")))

        dao.recordSyncResult(serverId = "s1", syncedAt = 500L, error = "mcp_connect_failed")

        val stored = dao.loadServer("s1")!!
        assertEquals(500L, stored.lastSyncedAt)
        assertEquals("mcp_connect_failed", stored.lastSyncError)
        // A failed refresh must not strip tools the user already enabled.
        assertEquals(1, dao.loadTools("s1").size)
    }

    @Test
    fun setServerEnabled_flipsWhatIsInstallableWithoutTouchingTheCatalog() = runBlocking {
        dao.upsertServer(server("s1", "alpha", enabled = false))
        dao.insertTools(listOf(tool("s1", "a")))
        assertTrue(dao.loadInstallableTools().isEmpty())

        dao.setServerEnabled("s1", true)

        assertEquals(listOf("a"), dao.loadInstallableTools().map(McpToolEntity::toolName))
        assertEquals(1, dao.loadTools("s1").size)
    }

    @Test
    fun loadAliases_returnsEveryTakenAliasSoNewServersCanAvoidCollisions() = runBlocking {
        dao.upsertServer(server("s1", "alpha", enabled = true))
        dao.upsertServer(server("s2", "beta", enabled = false))

        assertEquals(setOf("alpha", "beta"), dao.loadAliases().toSet())
    }

    private fun server(
        serverId: String,
        alias: String,
        enabled: Boolean
    ) = McpServerEntity(
        serverId = serverId,
        alias = alias,
        displayName = "Server $alias",
        url = "https://$alias.example.invalid/mcp",
        transport = "STREAMABLE_HTTP",
        enabled = enabled
    )

    private fun tool(
        serverId: String,
        toolName: String,
        rejectionCode: String? = null
    ) = McpToolEntity(
        serverId = serverId,
        toolName = toolName,
        namespacedName = "mcp_${serverId}_$toolName",
        description = "Tool $toolName.",
        inputSchemaJson = """{"type":"object","properties":{}}""",
        rejectionCode = rejectionCode
    )
}
