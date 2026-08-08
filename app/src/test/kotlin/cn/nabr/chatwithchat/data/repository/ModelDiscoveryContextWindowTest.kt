package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.network.NetworkClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 0: every provider's model-list response already carries the input context window (except
 * OpenAI and Ollama). These tests pin the field names, and — just as importantly — that a missing
 * field degrades to "unknown" instead of throwing and breaking the whole model refresh.
 */
class ModelDiscoveryContextWindowTest {

    private val json = NetworkClient.json

    @Test
    fun `groq reports context_window`() {
        val parsed = json.decodeFromString<OpenAIModelListResponse>(
            """{"data":[{"id":"llama-3.3-70b-versatile","context_window":131072}]}"""
        )
        assertEquals(131072, parsed.data.single().contextWindow)
        assertNull(parsed.data.single().contextLength)
    }

    @Test
    fun `openrouter reports context_length`() {
        val parsed = json.decodeFromString<OpenAIModelListResponse>(
            """{"data":[{"id":"anthropic/claude-sonnet-4","context_length":200000}]}"""
        )
        assertEquals(200000, parsed.data.single().contextLength)
        assertNull(parsed.data.single().contextWindow)
    }

    @Test
    fun `official openai omits both window fields without failing`() {
        val parsed = json.decodeFromString<OpenAIModelListResponse>(
            """{"data":[{"id":"gpt-5.2","object":"model","owned_by":"openai"}]}"""
        )
        assertNull(parsed.data.single().contextWindow)
        assertNull(parsed.data.single().contextLength)
    }

    @Test
    fun `google reports inputTokenLimit`() {
        val parsed = json.decodeFromString<GoogleModelListResponse>(
            """{"models":[{"name":"models/gemini-2.5-pro","inputTokenLimit":1048576,"outputTokenLimit":65536}]}"""
        )
        assertEquals(1048576, parsed.models.single().inputTokenLimit)
    }

    @Test
    fun `google model without inputTokenLimit parses to null`() {
        val parsed = json.decodeFromString<GoogleModelListResponse>(
            """{"models":[{"name":"models/gemini-legacy"}]}"""
        )
        assertNull(parsed.models.single().inputTokenLimit)
    }

    @Test
    fun `anthropic reports max_input_tokens`() {
        val parsed = json.decodeFromString<AnthropicModelListResponse>(
            """{"data":[{"id":"claude-sonnet-4-20250514","display_name":"Claude Sonnet 4","max_input_tokens":200000}]}"""
        )
        assertEquals(200000, parsed.data.single().maxInputTokens)
    }

    @Test
    fun `anthropic gateway omitting max_input_tokens parses to null`() {
        // Third-party Anthropic-compatible gateways frequently drop this field.
        val parsed = json.decodeFromString<AnthropicModelListResponse>(
            """{"data":[{"id":"claude-sonnet-4","display_name":"Claude Sonnet 4"}]}"""
        )
        assertNull(parsed.data.single().maxInputTokens)
    }

    @Test
    fun `unknown extra fields never break model discovery`() {
        val parsed = json.decodeFromString<OpenAIModelListResponse>(
            """{"data":[{"id":"m","context_window":8192,"some_future_field":{"nested":true}}],"object":"list"}"""
        )
        assertEquals(8192, parsed.data.single().contextWindow)
    }

    @Test
    fun `ollama tags response carries no window field at all`() {
        // Ollama's architectural limit is deliberately not parsed: the served num_ctx is smaller
        // and overflow is truncated silently server-side.
        val parsed = json.decodeFromString<OllamaTagsResponse>(
            """{"models":[{"name":"qwen3:8b","details":{"parameter_size":"8B"},"context_length":131072}]}"""
        )
        assertEquals("qwen3:8b", parsed.models.single().name)
        assertEquals("8B", parsed.models.single().details?.parameterSize)
    }
}
