package dev.chungjungsoo.gptmobile.data.tool

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.chungjungsoo.gptmobile.data.websearch.WebPageExtractor
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchRepository
import dev.chungjungsoo.gptmobile.data.websearch.WebSearchResult
import java.net.InetSocketAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutorTest {

    @Test
    fun `web search tool returns formatted search results`() = runBlocking {
        val searchRepository = FakeWebSearchRepository(
            listOf(
                result("One", "https://one.example", "first"),
                result("Duplicate", "https://one.example", "duplicate"),
                result("Two", "https://two.example", "second")
            )
        )
        val executor = builtInExecutor(searchRepository)

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_1",
                name = "web_search",
                arguments = """{"query":"latest Android SDK"}"""
            ),
            ToolLoopConfig(maxSearchResults = 2, maxToolResultChars = 1_000)
        )

        assertFalse(toolResult.isError)
        assertEquals("web_search", toolResult.name)
        assertEquals("2", toolResult.metadata["result_count"])
        assertTrue(toolResult.content.contains("Web search results for: latest Android SDK"))
        assertTrue(toolResult.content.contains("1. One"))
        assertTrue(toolResult.content.contains("2. Two"))
        assertFalse(toolResult.content.contains("Duplicate"))
        assertEquals("latest Android SDK", searchRepository.queries.single())
        assertEquals(2, searchRepository.limits.single())

        val sources = executor.sourceMetadata(toolResult)
        assertEquals(2, sources.size)
        assertEquals("One", sources[0].title)
        assertEquals("https://one.example", sources[0].url)
        assertEquals("first", sources[0].snippet)
        assertEquals("web_search", sources[0].sourceToolName)
        assertEquals("Two", sources[1].title)
        assertEquals("https://two.example", sources[1].url)
    }

    @Test
    fun `fetch url tool returns title and page excerpt`() = runBlocking {
        withServer(
            body = """
            <html>
              <head>
                <title>Example Page</title>
                <meta property="og:site_name" content="Example Site">
              </head>
              <body><main><p>Readable text for the page.</p></main></body>
            </html>
            """.trimIndent()
        ) { baseUrl ->
            val executor = builtInExecutor(FakeWebSearchRepository(emptyList()))

            val toolResult = executor.executeWithRegisteredTools(
                ToolCall(
                    id = "call_2",
                    name = "fetch_url",
                    arguments = """{"url":"$baseUrl/post"}"""
                ),
                ToolLoopConfig(allowPrivateNetworkFetch = true)
            )

            assertFalse(toolResult.isError)
            assertEquals("fetch_url", toolResult.name)
            assertEquals("$baseUrl/post", toolResult.metadata["url"])
            assertEquals("Example Page", toolResult.metadata["title"])
            assertTrue(toolResult.content.contains("Title: Example Page"))
            assertTrue(toolResult.content.contains("Site: Example Site"))
            assertTrue(toolResult.content.contains("Readable text for the page."))
        }
    }

    @Test
    fun `fetch url preserves its source when page content reaches the result limit`() = runBlocking {
        val longPageText = "Long page content. ".repeat(1_000)
        withServer(
            body = """
            <html>
              <head><title>Long Page</title></head>
              <body><main><p>$longPageText</p></main></body>
            </html>
            """.trimIndent()
        ) { baseUrl ->
            val executor = builtInExecutor(FakeWebSearchRepository(emptyList()))
            val toolResult = executor.executeWithRegisteredTools(
                ToolCall(
                    id = "call_long_page",
                    name = "fetch_url",
                    arguments = """{"url":"$baseUrl/long"}"""
                ),
                ToolLoopConfig(
                    maxFetchedPageChars = 10_000,
                    maxToolResultChars = 3_000,
                    allowPrivateNetworkFetch = true
                )
            )

            assertEquals(3_000, toolResult.content.length)
            val source = executor.sourceMetadata(toolResult).single()
            assertEquals("Long Page", source.title)
            assertEquals("$baseUrl/long", source.url)
        }
    }

    @Test
    fun `unknown tool returns error result`() = runBlocking {
        val executor = ToolExecutor(
            ToolRegistry(
                definitions = emptyList(),
                handlers = emptyMap()
            )
        )

        val toolResult = executor.execute(
            call = ToolCall(
                id = "call_3",
                name = "missing_tool",
                arguments = "{}"
            ),
            activeToolNames = setOf("missing_tool")
        )

        assertTrue(toolResult.isError)
        assertEquals("missing_tool", toolResult.name)
        assertTrue(toolResult.content.contains("unknown_tool:missing_tool"))
    }

    @Test
    fun `tool timeout returns error result`() = runBlocking {
        val executor = ToolExecutor(
            ToolRegistry(
                definitions = listOf(
                    ToolDefinition(
                        name = "slow_tool",
                        description = "Sleeps longer than the timeout.",
                        parameters = ToolDefinition.Parameters()
                    )
                ),
                handlers = mapOf(
                    "slow_tool" to ToolHandler { call, _ ->
                        delay(1_000)
                        ToolResult(call.id, call.name, "done")
                    }
                ),
                securityPolicies = mapOf("slow_tool" to ToolSecurityPolicy.ReadOnlyPublic)
            )
        )

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_4",
                name = "slow_tool",
                arguments = "{}"
            ),
            ToolLoopConfig(toolTimeoutSeconds = 0)
        )

        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("tool_timeout:slow_tool"))
    }

    @Test
    fun `provider policy timeout overrides global timeout`() = runBlocking {
        val executor = ToolExecutor(
            ToolRegistry(
                listOf(
                    object : ToolProvider {
                        override val definition: ToolDefinition = ToolDefinition(
                            name = "slow_tool",
                            description = "Sleeps longer than the timeout.",
                            parameters = ToolDefinition.Parameters()
                        )
                        override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic
                        override val policy: ToolPolicy = ToolPolicy(timeoutSeconds = 0)

                        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
                            delay(1_000)
                            return ToolResult(call.id, call.name, "done")
                        }
                    }
                )
            )
        )

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_policy_timeout",
                name = "slow_tool",
                arguments = "{}"
            ),
            ToolLoopConfig(toolTimeoutSeconds = 20)
        )

        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("tool_timeout:slow_tool"))
    }

    @Test
    fun `provider policy result limit overrides global result limit`() = runBlocking {
        val executor = ToolExecutor(
            ToolRegistry(
                listOf(
                    object : ToolProvider {
                        override val definition: ToolDefinition = ToolDefinition(
                            name = "bounded_tool",
                            description = "Returns a bounded result.",
                            parameters = ToolDefinition.Parameters()
                        )
                        override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic
                        override val policy: ToolPolicy = ToolPolicy(maxResultChars = 4)

                        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
                            ToolResult(call.id, call.name, "abcdef")
                    }
                )
            )
        )

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_policy_chars",
                name = "bounded_tool",
                arguments = "{}"
            ),
            ToolLoopConfig(maxToolResultChars = 100)
        )

        assertFalse(toolResult.isError)
        assertEquals("abcd", toolResult.content)
    }

    @Test
    fun `missing provider permission returns structured permission error without executing tool`() = runBlocking {
        var didExecute = false
        val requirement = ToolPermissionRequirement(
            permissions = listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"
            ),
            label = "Location",
            deniedMessage = "Location permission is required.",
            grantMode = ToolPermissionGrantMode.ANY_OF
        )
        val executor = ToolExecutor(
            toolRegistry = ToolRegistry(
                listOf(
                    object : ToolProvider {
                        override val definition: ToolDefinition = ToolDefinition(
                            name = "permission_tool",
                            description = "Needs permission.",
                            parameters = ToolDefinition.Parameters()
                        )
                        override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPrivate
                        override val permissionRequirements: List<ToolPermissionRequirement> = listOf(requirement)

                        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
                            didExecute = true
                            return ToolResult(call.id, call.name, "done")
                        }
                    }
                )
            ),
            permissionChecker = ToolPermissionChecker { requirements -> requirements }
        )

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_permission",
                name = "permission_tool",
                arguments = "{}"
            )
        )

        assertTrue(toolResult.isError)
        assertFalse(didExecute)
        assertTrue(toolResult.content.contains("tool_permission_denied"))
        assertTrue(toolResult.content.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertEquals("tool_permission_denied", toolResult.metadata["error_code"])
        assertEquals(
            "android.permission.ACCESS_FINE_LOCATION,android.permission.ACCESS_COARSE_LOCATION",
            toolResult.metadata["missing_permissions"]
        )
    }

    @Test
    fun `provider thrown permission exception returns structured permission error`() = runBlocking {
        val requirement = ToolPermissionRequirement(
            permissions = listOf("android.permission.ACCESS_FINE_LOCATION"),
            label = "Location",
            deniedMessage = "Location permission is required."
        )
        val executor = ToolExecutor(
            ToolRegistry(
                listOf(
                    object : ToolProvider {
                        override val definition: ToolDefinition = ToolDefinition(
                            name = "throwing_permission_tool",
                            description = "Throws permission errors.",
                            parameters = ToolDefinition.Parameters()
                        )
                        override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPrivate

                        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
                            throw ToolPermissionDeniedException(call.name, listOf(requirement))
                    }
                )
            )
        )

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_thrown_permission",
                name = "throwing_permission_tool",
                arguments = "{}"
            )
        )

        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("tool_permission_denied"))
        assertEquals("tool_permission_denied", toolResult.metadata["error_code"])
    }

    @Test
    fun `invalid fetch url returns error result`() = runBlocking {
        val executor = builtInExecutor(FakeWebSearchRepository(emptyList()))

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_5",
                name = "fetch_url",
                arguments = """{"url":"file:///etc/passwd"}"""
            )
        )

        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("invalid_url_scheme"))
    }

    @Test
    fun `private fetch url is rejected by default`() = runBlocking {
        val executor = builtInExecutor(FakeWebSearchRepository(emptyList()))

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_6",
                name = "fetch_url",
                arguments = """{"url":"http://127.0.0.1/private"}"""
            )
        )

        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("private_url_rejected"))
    }

    @Test
    fun `blocked fetch url domain is rejected`() = runBlocking {
        val executor = builtInExecutor(FakeWebSearchRepository(emptyList()))

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_7",
                name = "fetch_url",
                arguments = """{"url":"https://news.example.com/article"}"""
            ),
            ToolLoopConfig(fetchUrlBlockedDomains = setOf("example.com"))
        )

        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("blocked_domain:example.com"))
    }

    @Test
    fun `web search backend not configured returns clear tool error`() = runBlocking {
        val executor = builtInExecutor(
            FakeWebSearchRepository(
                results = emptyList(),
                failure = IllegalStateException("web_search_backend_not_configured")
            )
        )

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_8",
                name = "web_search",
                arguments = """{"query":"news"}"""
            )
        )

        assertTrue(toolResult.isError)
        assertEquals("web_search_backend_not_configured", toolResult.content)
    }

    @Test
    fun `web search unavailable engines return readable tool error`() = runBlocking {
        val executor = builtInExecutor(
            FakeWebSearchRepository(
                results = emptyList(),
                failure = IllegalStateException("web_search_no_results_unresponsive_engines:mojeek: access denied")
            )
        )

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_9",
                name = "web_search",
                arguments = """{"query":"news"}"""
            )
        )

        assertTrue(toolResult.isError)
        assertEquals("web_search_failed:search backend unavailable: mojeek: access denied", toolResult.content)
    }

    @Test
    fun `built in providers keep web search and fetch url progress labels`() {
        val executor = builtInExecutor(FakeWebSearchRepository(emptyList()))

        assertEquals(
            "latest Android SDK",
            executor.progressLabel(
                ToolCall(
                    id = "call_search",
                    name = "web_search",
                    arguments = """{"query":"latest Android SDK"}"""
                )
            )
        )
        assertEquals(
            "https://example.com/page",
            executor.progressLabel(
                ToolCall(
                    id = "call_fetch",
                    name = "fetch_url",
                    arguments = """{"url":"https://example.com/page"}"""
                )
            )
        )
    }

    @Test
    fun `built in current datetime tool executes without network search`() = runBlocking {
        val searchRepository = FakeWebSearchRepository(emptyList())
        val executor = builtInExecutor(searchRepository)

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_datetime",
                name = "current_datetime",
                arguments = "{}"
            )
        )

        assertFalse(toolResult.isError)
        assertTrue(toolResult.content.contains("Current local date/time:"))
        assertTrue(toolResult.metadata["timezone"].orEmpty().isNotBlank())
        assertTrue(searchRepository.queries.isEmpty())
    }

    @Test
    fun `built in device location tool returns structured permission error without Android reader`() = runBlocking {
        val searchRepository = FakeWebSearchRepository(emptyList())
        val executor = builtInExecutor(searchRepository)

        val toolResult = executor.executeWithRegisteredTools(
            ToolCall(
                id = "call_location",
                name = "device_location",
                arguments = "{}"
            )
        )

        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.contains("tool_permission_denied"))
        assertTrue(toolResult.content.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertEquals("tool_permission_denied", toolResult.metadata["error_code"])
        assertTrue(searchRepository.queries.isEmpty())
    }

    @Test
    fun `built in providers expose default tool policies`() {
        val executor = builtInExecutor(FakeWebSearchRepository(emptyList()))

        val webSearchPolicy = executor.policyFor(ToolDefinition.WebSearch.name)
        assertEquals(3, webSearchPolicy.maxCallsPerRequest)
        assertEquals(20, webSearchPolicy.maxCallsPerChat)
        assertEquals("max_search_queries_per_request", webSearchPolicy.maxCallsPerRequestErrorKey)
        assertEquals("max_search_queries_per_chat", webSearchPolicy.maxCallsPerChatErrorKey)

        val fetchUrlPolicy = executor.policyFor(ToolDefinition.FetchUrl.name)
        assertEquals(2, fetchUrlPolicy.maxCallsPerRequest)
        assertEquals(4, fetchUrlPolicy.maxCallsPerChat)
        assertEquals("max_fetched_urls_per_request", fetchUrlPolicy.maxCallsPerRequestErrorKey)
        assertEquals("max_fetched_urls_per_chat", fetchUrlPolicy.maxCallsPerChatErrorKey)

        val currentDateTimePolicy = executor.policyFor("current_datetime")
        assertEquals(1, currentDateTimePolicy.maxCallsPerRequest)
        assertEquals(2, currentDateTimePolicy.maxCallsPerChat)
        assertEquals(2L, currentDateTimePolicy.timeoutSeconds)

        val deviceLocationPolicy = executor.policyFor("device_location")
        assertEquals(1, deviceLocationPolicy.maxCallsPerRequest)
        assertEquals(2, deviceLocationPolicy.maxCallsPerChat)
        assertEquals(12L, deviceLocationPolicy.timeoutSeconds)
    }

    @Test
    fun `built in registry exposes location runtime permissions`() {
        val registry = BuiltInTools(
            webSearchRepository = FakeWebSearchRepository(emptyList()),
            webPageExtractor = WebPageExtractor()
        ).registry()
        val permissions = registry.requestedRuntimePermissions()

        assertEquals(
            listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"
            ),
            permissions
        )
        assertTrue(
            registry.requestedRuntimePermissions { definition ->
                definition.name != ToolDefinition.DeviceLocation.name
            }.isEmpty()
        )
        val enabledByDefault = ToolEnablementResolver().enabledToolNames(
            registry.catalog,
            ToolEnablementOverrides()
        )
        assertTrue(ToolDefinition.WebSearch.name in enabledByDefault)
        assertTrue(ToolDefinition.FetchUrl.name in enabledByDefault)
        assertTrue(ToolDefinition.CurrentDateTime.name in enabledByDefault)
        assertFalse(ToolDefinition.DeviceLocation.name in enabledByDefault)
    }

    @Test
    fun `invalid arguments fail before permission checks and provider execution`() = runBlocking {
        var didCheckPermission = false
        var didExecute = false
        val auditEvents = mutableListOf<ToolAuditEvent>()
        val provider = object : ToolProvider {
            override val definition = ToolDefinition(
                name = "validated_tool",
                description = "test",
                parameters = ToolDefinition.Parameters(
                    properties = mapOf(
                        "query" to ToolDefinition.Parameter(type = "string")
                    ),
                    required = listOf("query")
                )
            )
            override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPublic

            override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
                didExecute = true
                return ToolResult(call.id, call.name, "executed")
            }
        }
        val executor = ToolExecutor(
            toolRegistry = ToolRegistry(listOf(provider)),
            permissionChecker = ToolPermissionChecker {
                didCheckPermission = true
                emptyList()
            },
            auditSink = ToolAuditSink { event -> auditEvents += event }
        )

        val results = listOf(
            ToolCall("call-invalid-type", "validated_tool", """{"query":42}"""),
            ToolCall("call-invalid-json", "validated_tool", "{"),
            ToolCall("call-invalid-root", "validated_tool", "[]")
        ).map { call -> executor.executeWithRegisteredTools(call) }

        assertTrue(results.all { result -> result.isError })
        assertTrue(results.all { result -> result.metadata["error_code"] == "tool_arguments_invalid" })
        assertTrue(results.first().content.contains("type_mismatch"))
        assertFalse(didCheckPermission)
        assertFalse(didExecute)
        assertEquals(
            listOf(
                ToolAuditStatus.ATTEMPTED,
                ToolAuditStatus.FAILED,
                ToolAuditStatus.ATTEMPTED,
                ToolAuditStatus.FAILED,
                ToolAuditStatus.ATTEMPTED,
                ToolAuditStatus.FAILED
            ),
            auditEvents.map { event -> event.status }
        )
    }

    @Test
    fun `permission error content and metadata stay within executor limits`() = runBlocking {
        val requirement = ToolPermissionRequirement(
            permissions = listOf("android.permission.TEST"),
            label = "Permission ".repeat(500),
            deniedMessage = "Permission is required. ".repeat(500)
        )
        val provider = object : ToolProvider {
            override val definition = ToolDefinition("bounded_permission", "test", ToolDefinition.Parameters())
            override val permissionRequirements = listOf(requirement)
            override val securityPolicy = ToolSecurityPolicy.ReadOnlyPrivate

            override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
                ToolResult(call.id, call.name, "executed")
        }
        val executor = ToolExecutor(
            toolRegistry = ToolRegistry(listOf(provider)),
            permissionChecker = ToolPermissionChecker { listOf(requirement) }
        )

        val result = executor.executeWithRegisteredTools(
            ToolCall("call_bounded_permission", provider.definition.name, "{}"),
            ToolLoopConfig(maxToolResultChars = 128)
        )

        assertTrue(result.isError)
        assertTrue(result.content.length <= 128)
        assertTrue(result.payloadCharCount() <= 256)
        assertEquals("tool_permission_denied", result.metadata["error_code"])
    }

    private fun builtInExecutor(searchRepository: WebSearchRepository): ToolExecutor = ToolExecutor(
        BuiltInTools(
            webSearchRepository = searchRepository,
            webPageExtractor = WebPageExtractor()
        ).registry()
    )

    private fun result(
        title: String,
        url: String,
        snippet: String
    ) = WebSearchResult(
        title = title,
        url = url,
        snippet = snippet,
        source = "test"
    )

    private class FakeWebSearchRepository(
        private val results: List<WebSearchResult>,
        private val failure: Throwable? = null
    ) : WebSearchRepository {
        val queries = mutableListOf<String>()
        val limits = mutableListOf<Int>()

        override suspend fun search(
            query: String,
            limit: Int
        ): Result<List<WebSearchResult>> {
            queries += query
            limits += limit
            failure?.let { return Result.failure(it) }
            return Result.success(results)
        }
    }

    private suspend fun withServer(
        body: String,
        block: suspend (baseUrl: String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            exchange.respond(body)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(body: String) {
        responseHeaders.add("Content-Type", "text/html")
        val bytes = body.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
