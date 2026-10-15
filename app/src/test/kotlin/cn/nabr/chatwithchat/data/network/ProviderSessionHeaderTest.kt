package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.context.ProviderContextCompactionModelClient
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.anthropic.request.MessageRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.model.ClientType
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.cio.CIO
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSessionHeaderTest {
    @Test
    fun sessionHeaders_reachAllOpenCodeProtocolsAndAuxiliaryCalls() = runBlocking {
        val received = Collections.synchronizedList(mutableListOf<Pair<String?, String?>>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            received += exchange.requestHeaders.getFirst("x-opencode-session") to
                exchange.requestHeaders.getFirst("User-Agent")
            val body = if (exchange.requestURI.path.endsWith("/messages")) {
                "data: {\"type\":\"message_stop\"}\n\n"
            } else {
                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val client = NetworkClient(CIO)
        try {
            val openAI = OpenAIAPIImpl(client)
            val anthropic = AnthropicAPIImpl(client)
            val platform = PlatformV2(
                uid = "saved-platform",
                name = "Existing compatible provider",
                compatibleType = ClientType.CUSTOM,
                apiUrl = "http://127.0.0.1:${server.address.port}/v1",
                model = "test-model",
                customHeaders = "x-opencode-session: {session_id}"
            )
            val endpoint = platform.providerEndpoint(73)
            openAI.streamChatCompletion(ChatCompletionRequest("test", emptyList()), 10, endpoint).toList()
            openAI.streamResponses(ResponsesRequest("test", emptyList()), 10, endpoint).toList()
            anthropic.streamChatMessage(MessageRequest("test", emptyList(), 100), 10, endpoint).toList()

            assertEquals(3, received.size)
            assertTrue(received.all { (session, agent) -> session == endpoint.sessionId && agent.orEmpty().startsWith("ChatWithChat/") })

            // Titles and context folds share this one-shot client. Neither has a chat ID parameter.
            val auxiliary = ProviderContextCompactionModelClient(openAI, GroqAPIImpl(client), anthropic, GoogleAPIImpl(client))
            repeat(2) {
                assertTrue(auxiliary.requestCompaction(platform, "Summarize", "hello", 100, 10).isSuccess)
            }
            val auxiliarySessions = received.drop(3).map { it.first }
            assertEquals(2, auxiliarySessions.toSet().size)
            assertTrue(auxiliarySessions.all { !it.isNullOrBlank() && it != endpoint.sessionId && it != "{session_id}" })

            // Overlapping chats share the HTTP client but never a mutable provider session.
            coroutineScope {
                listOf(73, 74).map { chatId ->
                    async {
                        openAI.streamChatCompletion(
                            ChatCompletionRequest("test", emptyList()),
                            10,
                            platform.providerEndpoint(chatId)
                        ).toList()
                    }
                }.awaitAll()
            }
            assertEquals(
                setOf(platform.providerEndpoint(73).sessionId, platform.providerEndpoint(74).sessionId),
                received.takeLast(2).map { it.first }.toSet()
            )
        } finally {
            client().close()
            server.stop(0)
        }
    }
}
