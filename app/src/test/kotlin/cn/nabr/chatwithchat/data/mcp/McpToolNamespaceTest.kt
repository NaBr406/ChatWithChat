package cn.nabr.chatwithchat.data.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolNamespaceTest {

    @Test
    fun namespacedName_prefixesWithServerAlias() {
        assertEquals(
            "mcp_acme_search_docs",
            McpToolNamespace.namespacedName(serverAlias = "acme", serverId = "s1", toolName = "search_docs")
        )
    }

    @Test
    fun namespacedName_foldsCharactersOutsideTheAcceptedSet() {
        assertEquals(
            "mcp_acme_corp_files_read",
            McpToolNamespace.namespacedName("Acme Corp", "s1", "files.read")
        )
        assertEquals(
            "mcp_a_b_repo_git_log",
            McpToolNamespace.namespacedName("a/b", "s1", "repo::git  log")
        )
    }

    @Test
    fun namespacedName_collapsesRunsAndTrimsSeparators() {
        assertEquals(
            "mcp_acme_tool",
            McpToolNamespace.namespacedName("__Acme__", "s1", "--tool--")
        )
    }

    @Test
    fun namespacedName_preservesALoneHyphenInsideTheName() {
        // Hyphens are legal in tool names and common in the wild; only runs and edges are normalized.
        assertEquals(
            "mcp_acme_get-weather",
            McpToolNamespace.namespacedName("acme", "s1", "get-weather")
        )
    }

    @Test
    fun namespacedName_fallsBackWhenAliasOrToolSanitizesToNothing() {
        assertEquals(
            "mcp_srv_tool",
            McpToolNamespace.namespacedName("服务器", "s1", "工具")
        )
    }

    @Test
    fun namespacedName_alwaysFitsTheRegistryNamePattern() {
        val names = listOf(
            McpToolNamespace.namespacedName("a".repeat(80), "s1", "b".repeat(200)),
            McpToolNamespace.namespacedName("Acme Corp", "s1", "search.docs"),
            McpToolNamespace.namespacedName("服务器", "s1", "工具"),
            McpToolNamespace.namespacedName("", "s1", "")
        )
        names.forEach { name ->
            assertTrue("$name is not a legal tool name", TOOL_NAME_PATTERN.matches(name))
            assertTrue("$name exceeds 64 chars", name.length <= 64)
        }
    }

    @Test
    fun namespacedName_isStableForTheSameServerAndTool() {
        val first = McpToolNamespace.namespacedName("acme", "s1", "x".repeat(200))
        val second = McpToolNamespace.namespacedName("acme", "s1", "x".repeat(200))
        assertEquals(first, second)
    }

    @Test
    fun namespacedName_keepsTruncatedLongNamesDistinct() {
        val shared = "very_long_shared_prefix_that_will_certainly_be_truncated_somewhere"
        val first = McpToolNamespace.namespacedName("acme", "s1", "${shared}_alpha")
        val second = McpToolNamespace.namespacedName("acme", "s1", "${shared}_beta")
        assertNotEquals(first, second)
        assertTrue(first.length <= 64)
        assertTrue(second.length <= 64)
    }

    @Test
    fun namespacedName_distinguishesSameToolOnDifferentServers() {
        assertNotEquals(
            McpToolNamespace.namespacedName("alpha", "s1", "read"),
            McpToolNamespace.namespacedName("beta", "s2", "read")
        )
    }

    @Test
    fun namespacedName_sameToolNameTruncatedOnDifferentServersStaysDistinct() {
        // Aliases are unique per server, but the digest must also carry the server identity so that two
        // servers sharing an alias prefix after truncation still resolve to different tools.
        val toolName = "t".repeat(200)
        assertNotEquals(
            McpToolNamespace.namespacedName("sameali", "server-one", toolName),
            McpToolNamespace.namespacedName("sameali", "server-two", toolName)
        )
    }

    private companion object {
        /** Copy of `ToolDefinitionValidator.TOOL_NAME_PATTERN`, which is private to the tool package. */
        private val TOOL_NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}")
    }
}
