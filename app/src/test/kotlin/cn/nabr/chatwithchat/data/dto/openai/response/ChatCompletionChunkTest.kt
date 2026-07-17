package cn.nabr.chatwithchat.data.dto.openai.response

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatCompletionChunkTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deepseek reasoning content is decoded from streaming delta`() {
        val chunk = json.decodeFromString<ChatCompletionChunk>(
            """{"choices":[{"index":0,"delta":{"reasoning_content":"Plan","content":"Answer"}}]}"""
        )

        val delta = chunk.choices?.single()?.delta
        assertEquals("Plan", delta?.reasoningContent)
        assertEquals("Answer", delta?.content)
    }

    @Test
    fun `compatible reasoning alias is decoded from streaming delta`() {
        val reasoning = json.decodeFromString<ChatCompletionChunk>(
            """{"choices":[{"index":0,"delta":{"reasoning":"Plan"}}]}"""
        )

        assertEquals("Plan", reasoning.choices?.single()?.delta?.reasoning)
    }

    @Test
    fun `non text reasoning fields do not discard visible content`() {
        val chunk = json.decodeFromString<ChatCompletionChunk>(
            """{"choices":[{"index":0,"delta":{"reasoning_content":{"type":"summary"},"reasoning":["Plan"],"content":"Answer"}}]}"""
        )

        val delta = chunk.choices?.single()?.delta
        assertNull(delta?.reasoningContent)
        assertNull(delta?.reasoning)
        assertEquals("Answer", delta?.content)
    }
}
