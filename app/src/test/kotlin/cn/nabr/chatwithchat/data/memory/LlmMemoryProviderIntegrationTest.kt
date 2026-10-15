package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.openai.response.ChatCompletionChunk
import cn.nabr.chatwithchat.data.dto.openai.response.Choice
import cn.nabr.chatwithchat.data.dto.openai.response.Delta
import cn.nabr.chatwithchat.data.dto.openai.response.OutputTextDeltaEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponsesStreamEvent
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.network.AnthropicAPIImpl
import cn.nabr.chatwithchat.data.network.GoogleAPIImpl
import cn.nabr.chatwithchat.data.network.NetworkClient
import cn.nabr.chatwithchat.data.network.OpenAIAPIImpl
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmMemoryProviderIntegrationTest {
    @Test
    fun correctedCustomEndpoint_sendsMemoryPayloadAndHeadersAndParsesProposal() = runBlocking {
        withServer { intelligence, baseUrl, received ->
            listOf(false, true).forEach { isFullApiUrl ->
                val platform = platform(baseUrl).copy(
                    apiUrl = if (isFullApiUrl) "$baseUrl/chat/completions" else baseUrl,
                    isFullApiUrl = isFullApiUrl
                )
                val proposal = intelligence.consolidateMemoryBatch(batchRequest(), platform)
                assertEquals("Prefers concise answers.", proposal?.operations?.single()?.text)
            }

            val requests = received.toList()
            assertEquals(2, requests.size)
            requests.forEach { request ->
                assertEquals("/zen/go/v1/chat/completions", request.path)
                assertEquals("deepseek-v4.1-flash", request.body.getValue("model").jsonPrimitive.content)
                assertEquals(
                    listOf("system", "user"),
                    request.body.getValue("messages").jsonArray.map {
                        it.jsonObject.getValue("role").jsonPrimitive.content
                    }
                )
                assertTrue(request.body.toString().contains("Remember that I prefer concise answers."))
                assertFalse(request.body.containsKey("input"))
                assertEquals("Bearer local-test-key", request.authorization)
                assertTrue(request.userAgent.orEmpty().startsWith("ChatWithChat/"))
                assertTrue(!request.session.isNullOrBlank() && request.session != "{session_id}")
            }
            assertNotEquals(requests[0].session, requests[1].session)
        }
    }

    @Test
    fun customResponsesUrl_reproducesMissingInputWithoutProducingMemory() = runBlocking {
        withServer { intelligence, baseUrl, received ->
            val platform = platform(baseUrl).copy(apiUrl = "$baseUrl/responses", isFullApiUrl = true)

            assertNull(intelligence.consolidateMemoryBatch(batchRequest(), platform))
            val request = received.single()
            assertEquals("/zen/go/v1/responses", request.path)
            assertTrue(request.body.containsKey("messages"))
            assertFalse(request.body.containsKey("input"))
        }
    }

    @Test
    fun openaiResponses_sendsNonemptyInputAndInstructionsAndParsesMemory() = runBlocking {
        withServer { intelligence, baseUrl, received ->
            val proposal = intelligence.consolidateMemoryBatch(
                batchRequest(),
                platform(baseUrl).copy(compatibleType = ClientType.OPENAI)
            )

            assertEquals("Prefers concise answers.", proposal?.operations?.single()?.text)
            val request = received.single()
            assertEquals("/zen/go/v1/responses", request.path)
            assertTrue(request.body.getValue("input").jsonArray.isNotEmpty())
            assertTrue(request.body.getValue("instructions").jsonPrimitive.content.isNotBlank())
            assertFalse(request.body.containsKey("messages"))
        }
    }

    private suspend fun withServer(
        block: suspend (LlmMemoryIntelligence, String, LinkedBlockingQueue<ReceivedRequest>) -> Unit
    ) {
        val received = LinkedBlockingQueue<ReceivedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = NetworkClient.json.parseToJsonElement(exchange.requestBody.use { it.readBytes().decodeToString() }).jsonObject
            val isResponses = exchange.requestURI.path.endsWith("/responses")
            received += ReceivedRequest(
                path = exchange.requestURI.path,
                body = body,
                session = exchange.requestHeaders.getFirst("x-opencode-session"),
                userAgent = exchange.requestHeaders.getFirst("User-Agent"),
                authorization = exchange.requestHeaders.getFirst("Authorization")
            )
            val invalidProtocol = isResponses && !body.containsKey("input") && !body.containsKey("instructions")
            val response = if (invalidProtocol) {
                """{"error":{"type":"invalid_request_error","message":"Either input or instructions must be provided"}}"""
            } else {
                val event = if (isResponses) {
                    NetworkClient.openAIJson.encodeToString<ResponsesStreamEvent>(OutputTextDeltaEvent("item", 0, 0, PROPOSAL))
                } else {
                    NetworkClient.openAIJson.encodeToString(
                        ChatCompletionChunk(
                            choices = listOf(Choice(index = 0, delta = Delta(content = PROPOSAL), finishReason = "stop"))
                        )
                    )
                }
                "data: $event\n\ndata: [DONE]\n\n"
            }
            exchange.responseHeaders.add("Content-Type", if (invalidProtocol) "application/json" else "text/event-stream")
            exchange.sendResponseHeaders(if (invalidProtocol) 400 else 200, 0)
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        val client = NetworkClient(CIO)
        try {
            block(
                LlmMemoryIntelligence(OpenAIAPIImpl(client), AnthropicAPIImpl(client), GoogleAPIImpl(client)),
                "http://127.0.0.1:${server.address.port}/zen/go/v1",
                received
            )
        } finally {
            client().close()
            server.stop(0)
        }
    }

    private fun platform(baseUrl: String): PlatformV2 = PlatformV2(
        uid = "memory-provider",
        name = "Existing custom provider",
        compatibleType = ClientType.CUSTOM,
        enabled = true,
        apiUrl = baseUrl,
        token = "local-test-key",
        model = "deepseek-v4.1-flash",
        customHeaders = "x-opencode-session: {session_id}"
    )

    private fun batchRequest(): MemoryBatchConsolidationRequest = MemoryBatchConsolidationRequest(
        batchId = "batch-1",
        chatId = 73,
        chatTitle = "Test",
        triggerReason = MemoryTurnBatchTriggerReason.THRESHOLD,
        turns = listOf(
            MemoryCompletedTurnSnapshot(
                turnKey = "turn-1",
                chatId = 73,
                chatTitle = "Test",
                userMessageId = 1,
                userContent = "Remember that I prefer concise answers.",
                userAttachments = emptyList(),
                assistantPlatformUid = "memory-provider",
                assistantContent = "Understood.",
                completedAt = 100L
            )
        ),
        existingMemories = emptyList()
    )

    private data class ReceivedRequest(
        val path: String,
        val body: JsonObject,
        val session: String?,
        val userAgent: String?,
        val authorization: String?
    )

    private companion object {
        const val PROPOSAL = """{"operations":[{"destination":"daily","action":"create","text":"Prefers concise answers.","evidenceTurnKeys":["turn-1"]}]}"""
    }
}
