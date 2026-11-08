package cn.nabr.chatwithchat.data.network

import cn.nabr.chatwithchat.data.dto.anthropic.request.MessageRequest
import cn.nabr.chatwithchat.data.dto.google.request.GenerateContentRequest
import cn.nabr.chatwithchat.data.dto.groq.request.GroqChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ChatCompletionRequest
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesRequest
import cn.nabr.chatwithchat.data.dto.openai.response.ReasoningSummaryTextDoneEvent
import cn.nabr.chatwithchat.data.dto.openai.response.ResponseCompletedEvent
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.cio.CIO
import java.io.EOFException
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStreamDisconnectTest {
    @Test
    fun `large reasoning done event does not prevent Responses completion`() = runBlocking {
        val summary = "Long reasoning. ".repeat(20_000)
        val body = "data: {\"type\":\"response.reasoning_summary_text.done\",\"item_id\":\"rs_1\"," +
            "\"output_index\":0,\"summary_index\":0,\"text\":\"$summary\"}\n\n" +
            "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r_1\",\"status\":\"completed\"}}\n\n"
        withServer(body, 200) { endpoint, client ->
            val events = OpenAIAPIImpl(client).streamResponses(
                ResponsesRequest("test", emptyList()),
                10,
                endpoint
            ).toList()
            assertEquals(summary, (events.first() as ReasoningSummaryTextDoneEvent).text)
            assertTrue(events.last() is ResponseCompletedEvent)
            assertEquals(2, events.size)
        }
    }

    @Test
    fun `all providers reject EOF before a completion event`() = runBlocking {
        withServer("", 200) { endpoint, client ->
            providerStreams(endpoint, client).forEach { stream ->
                try {
                    stream.toList()
                    throw AssertionError("Expected premature EOF")
                } catch (error: EOFException) {
                    assertTrue(error.isChatNetworkFailure())
                }
            }
        }
    }

    @Test
    fun `authentication errors remain terminal provider events`() = runBlocking {
        withServer("{\"error\":{\"message\":\"unauthorized\"}}", 401) { endpoint, client ->
            providerStreams(endpoint, client).forEach { stream ->
                assertEquals(1, stream.toList().size)
            }
        }
    }

    @Test
    fun `openai completion and done markers permit normal EOF`() = runBlocking {
        val bodies = listOf(
            "data: [DONE]\n\n",
            "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
        )
        bodies.forEach { body ->
            withServer(body, 200) { endpoint, client ->
                OpenAIAPIImpl(client).streamChatCompletion(
                    ChatCompletionRequest("test", emptyList()),
                    10,
                    endpoint
                ).toList()
            }
        }
    }

    private fun providerStreams(endpoint: ProviderEndpoint, client: NetworkClient): List<Flow<*>> = listOf(
        OpenAIAPIImpl(client).streamChatCompletion(ChatCompletionRequest("test", emptyList()), 10, endpoint),
        OpenAIAPIImpl(client).streamResponses(ResponsesRequest("test", emptyList()), 10, endpoint),
        AnthropicAPIImpl(client).streamChatMessage(MessageRequest("test", emptyList(), 100), 10, endpoint),
        GoogleAPIImpl(client).streamGenerateContent(GenerateContentRequest(emptyList()), "test", 10, endpoint),
        GroqAPIImpl(client).streamChatCompletion(GroqChatCompletionRequest("test", emptyList()), 10, endpoint)
    )

    private suspend fun withServer(
        body: String,
        status: Int,
        block: suspend (ProviderEndpoint, NetworkClient) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(status, 0)
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val client = NetworkClient(CIO)
        try {
            block(ProviderEndpoint("test", "http://127.0.0.1:${server.address.port}"), client)
        } finally {
            client().close()
            server.stop(0)
        }
    }
}
