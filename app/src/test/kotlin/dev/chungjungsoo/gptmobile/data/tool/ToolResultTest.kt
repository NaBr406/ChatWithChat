package dev.chungjungsoo.gptmobile.data.tool

import dev.chungjungsoo.gptmobile.data.database.entity.AppSourceNavigationTarget
import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultTest {
    @Test
    fun `legacy constructor remains compatible`() {
        val result = ToolResult("call_1", "legacy", "content", false, mapOf("key" to "value"))

        assertEquals("content", result.content)
        assertEquals(mapOf("key" to "value"), result.metadata)
        assertNull(result.structuredContent)
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun `structured content and typed sources serialize round trip`() {
        val result = ToolResult(
            callId = "call_2",
            name = "local_search",
            content = "Two results",
            structuredContent = buildJsonObject {
                put("count", 2)
                put("query", "project notes")
            },
            sources = listOf(
                ToolSource.PublicUrl(
                    title = "Public result",
                    url = "https://example.com/result",
                    snippet = "A public result"
                ),
                ToolSource.LocalApp(
                    title = "Local chat",
                    localEntityId = "chat:42",
                    navigationTarget = AppSourceNavigationTarget.CHAT_ROOM,
                    snippet = "A local result"
                )
            )
        )

        val decoded = toolProtocolJson.decodeFromString<ToolResult>(toolProtocolJson.encodeToString(result))

        assertEquals(result, decoded)
    }

    @Test
    fun `bounding clips text and drops oversized structured content without truncating json`() {
        val result = ToolResult(
            callId = "call_3",
            name = "large_result",
            content = "abcdefgh",
            structuredContent = buildJsonObject { put("payload", "x".repeat(100)) },
            sources = listOf(
                ToolSource.PublicUrl("Safe", "https://example.com", "snippet"),
                ToolSource.PublicUrl("Unsafe", "javascript:alert(1)"),
                ToolSource.LocalApp("Raw path", "C:\\Users\\name\\secret.txt", AppSourceNavigationTarget.MEMORY)
            )
        )

        val bounded = result.boundPayload(
            ToolResultBounds(
                maxContentChars = 4,
                maxStructuredContentChars = 20,
                maxSourcePayloadChars = 256,
                maxSourceTextChars = 20,
                maxMetadataChars = 100,
                maxTotalPayloadChars = 512
            )
        )

        assertEquals("abcd", bounded.result.content)
        assertNull(bounded.result.structuredContent)
        assertEquals(listOf(ToolSource.PublicUrl("Safe", "https://example.com", "snippet")), bounded.result.sources)
        assertTrue(ToolResultPayloadPart.CONTENT in bounded.droppedPayloadParts)
        assertTrue(ToolResultPayloadPart.STRUCTURED_CONTENT in bounded.droppedPayloadParts)
        assertTrue(ToolResultPayloadPart.SOURCES in bounded.droppedPayloadParts)
        assertFalse(bounded.isRejected)
        toolProtocolJson.parseToJsonElement(toolProtocolJson.encodeToString(bounded.result))
    }

    @Test
    fun `strict bounding rejects an oversized optional payload`() {
        val bounded = ToolResult(
            callId = "call_4",
            name = "large_result",
            content = "fallback",
            structuredContent = buildJsonObject { put("payload", "x".repeat(100)) }
        ).boundPayload(
            bounds = ToolResultBounds(
                maxContentChars = 100,
                maxStructuredContentChars = 10,
                maxTotalPayloadChars = 100
            ),
            oversizedPayloadPolicy = OversizedToolPayloadPolicy.REJECT_RESULT
        )

        assertTrue(bounded.isRejected)
        assertTrue(bounded.result.isError)
        assertEquals("tool_result_too_large:large_result", bounded.result.content)
        assertNull(bounded.result.structuredContent)
        assertTrue(bounded.result.sources.isEmpty())
    }

    @Test
    fun `source mapping keeps only safe typed targets`() {
        val sources = listOf(
            ToolSource.PublicUrl("Web", "https://example.com"),
            ToolSource.PublicUrl("File", "file:///private/file"),
            ToolSource.LocalApp("Chat", "chat_42", AppSourceNavigationTarget.CHAT_ROOM),
            ToolSource.LocalApp("Path", "/private/chat.db", AppSourceNavigationTarget.CHAT_ROOM)
        ).toMessageSourceMetadata("local_search")

        assertEquals(2, sources.size)
        assertEquals(MessageSourceType.PUBLIC_URL, sources[0].sourceType)
        assertEquals(MessageSourceType.LOCAL_APP, sources[1].sourceType)
        assertEquals("chat_42", sources[1].localEntityId)
        assertEquals(AppSourceNavigationTarget.CHAT_ROOM, sources[1].appNavigationTarget)
        assertTrue(sources[1].url.isBlank())
    }

    @Test
    fun `metadata bounding is deterministic across input map order`() {
        val first = ToolResult(
            callId = "call_5",
            name = "metadata",
            content = "ok",
            metadata = linkedMapOf("z" to "last", "a" to "first")
        )
        val second = first.copy(metadata = linkedMapOf("a" to "first", "z" to "last"))
        val bounds = ToolResultBounds(maxContentChars = 100, maxMetadataChars = 100, maxTotalPayloadChars = 200)

        val firstMetadata = first.boundPayload(bounds).result.metadata
        val secondMetadata = second.boundPayload(bounds).result.metadata

        assertEquals(firstMetadata, secondMetadata)
        assertEquals(
            toolProtocolJson.encodeToString(firstMetadata),
            toolProtocolJson.encodeToString(secondMetadata)
        )
    }

    @Test
    fun `bounding prioritizes error code content and structured content over sources and metadata`() {
        val structuredContent = buildJsonObject { put("answer", "structured") }
        val errorMetadata = mapOf("error_code" to "stable_error")
        val content = "core fallback"
        val totalBudget = toolProtocolJson.encodeToString(errorMetadata).length +
            content.length +
            toolProtocolJson.encodeToString(structuredContent).length
        val bounded = ToolResult(
            callId = "call_6",
            name = "prioritized_result",
            content = content,
            isError = true,
            metadata = errorMetadata + ("debug" to "optional metadata"),
            structuredContent = structuredContent,
            sources = listOf(ToolSource.PublicUrl("Optional source", "https://example.com/source"))
        ).boundPayload(
            ToolResultBounds(
                maxContentChars = 100,
                maxStructuredContentChars = 100,
                maxSourcePayloadChars = 100,
                maxMetadataChars = 100,
                maxTotalPayloadChars = totalBudget
            )
        )

        assertEquals(content, bounded.result.content)
        assertEquals(structuredContent, bounded.result.structuredContent)
        assertEquals(errorMetadata, bounded.result.metadata)
        assertTrue(bounded.result.sources.isEmpty())
        assertTrue(ToolResultPayloadPart.SOURCES in bounded.droppedPayloadParts)
        assertTrue(ToolResultPayloadPart.METADATA in bounded.droppedPayloadParts)
    }

    @Test
    fun `bounding retains error code and content before structured content`() {
        val errorMetadata = mapOf("error_code" to "stable_error")
        val content = "core fallback"
        val bounded = ToolResult(
            callId = "call_7",
            name = "prioritized_error",
            content = content,
            isError = true,
            metadata = errorMetadata,
            structuredContent = buildJsonObject { put("optional", "structured") }
        ).boundPayload(
            ToolResultBounds(
                maxContentChars = 100,
                maxStructuredContentChars = 100,
                maxMetadataChars = 100,
                maxTotalPayloadChars = toolProtocolJson.encodeToString(errorMetadata).length + content.length
            )
        )

        assertEquals(errorMetadata, bounded.result.metadata)
        assertEquals(content, bounded.result.content)
        assertNull(bounded.result.structuredContent)
        assertTrue(ToolResultPayloadPart.STRUCTURED_CONTENT in bounded.droppedPayloadParts)
    }
}
