package cn.nabr.chatwithchat.data.tool.provider

import cn.nabr.chatwithchat.data.dto.openai.common.ImageContent
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseFunctionCallOutputPartsItem
import cn.nabr.chatwithchat.data.dto.anthropic.common.MessageContent
import cn.nabr.chatwithchat.data.tool.ToolCall
import cn.nabr.chatwithchat.data.tool.ToolDefinition
import cn.nabr.chatwithchat.data.tool.ToolResult
import cn.nabr.chatwithchat.data.tool.ToolResultVisualAttachment
import cn.nabr.chatwithchat.data.visual.VisualAssetProfile
import cn.nabr.chatwithchat.data.visual.VisualDetail
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualToolResultProviderContractTest {

    private val result = ToolResult(
        callId = "call-1",
        name = ToolDefinition.ViewImage.name,
        content = "loaded image",
        visualAttachments = listOf(
            ToolResultVisualAttachment(
                assetId = "asset-1",
                visualRef = "v17-1",
                profile = VisualAssetProfile.COMPACTED_V1,
                mimeType = "image/webp",
                width = 800,
                height = 600,
                sizeBytes = 1_024,
                detail = VisualDetail.AUTO,
                base64Data = "AAAA",
                localPath = "/tmp/v17-1.webp"
            )
        )
    )
    private val call = ToolCall(id = "call-1", name = ToolDefinition.ViewImage.name, arguments = "{}")
    private val json = Json { encodeDefaults = true }

    @Test
    fun `openai responses emits multimodal function output`() {
        val output = OpenAIResponsesToolAdapter().toolResultToResponseInputItem(result)
            as ResponseFunctionCallOutputPartsItem

        assertEquals("call-1", output.callId)
        assertTrue(output.output.any { part -> part.type == "input_text" })
        assertEquals("input_image", output.output.last().type)
        assertTrue(output.output.last().imageUrl.orEmpty().startsWith("data:image/webp;base64,"))
    }

    @Test
    fun `anthropic emits image block in tool result`() {
        val content = AnthropicNativeToolAdapter().toolResultToMessageContent(result)
        val payload = json.encodeToString<MessageContent>(content)

        assertTrue(payload.contains(""""type":"tool_result""""))
        assertTrue(payload.contains(""""type":"image""""))
        assertTrue(payload.contains(""""data":"AAAA""""))
    }

    @Test
    fun `chat completions injects synthetic user image`() {
        val messages = OpenAIChatCompletionsToolAdapter().continuationMessages(listOf(call), listOf(result))

        val visualMessage = messages.last()
        assertEquals("user", visualMessage.role.name.lowercase())
        assertTrue(visualMessage.content.any { part -> part::class.simpleName == "ImageContent" })
    }

    @Test
    fun `chat completions injects every visual attachment in one round`() {
        val results = (1..4).map { index ->
            result.copy(
                callId = "call-$index",
                visualAttachments = listOf(
                    result.visualAttachments.single().copy(
                        assetId = "asset-$index",
                        visualRef = "v17-$index"
                    )
                )
            )
        }
        val calls = results.map { result -> call.copy(id = result.callId) }

        val messages = OpenAIChatCompletionsToolAdapter().continuationMessages(calls, results)
        val images = messages.last().content.filterIsInstance<ImageContent>()

        assertEquals(4, images.size)
    }

    @Test
    fun `google injects synthetic user image`() {
        val contents = GoogleNativeToolAdapter().continuationContents(listOf(call), listOf(result))

        val visualParts = contents.last().parts.filter { part -> part.inlineData != null }
        assertEquals(1, visualParts.size)
        assertEquals("image/webp", visualParts.single().inlineData?.mimeType)
    }
}
