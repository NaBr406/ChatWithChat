package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.visual.VisualAssetProfile
import cn.nabr.chatwithchat.data.visual.VisualDetail
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualAssetToolExecutionTest {

    @Test
    fun `unsupported provider returns structured visual error`() = runBlocking {
        val executor = ToolExecutor(ToolRegistry(listOf(visualProvider())))

        val result = executor.execute(
            call = ToolCall(id = "call-1", name = "view_image", arguments = "{}"),
            activeToolNames = setOf("view_image"),
            sessionState = ToolExecutionSessionState(chatId = 1, supportsVisualInput = false)
        )

        assertTrue(result.isError)
        assertEquals("visual_asset_unsupported", result.metadata["error_code"])
        assertTrue(result.visualAttachments.isEmpty())
    }

    @Test
    fun `supported provider retains visual attachment`() = runBlocking {
        val executor = ToolExecutor(ToolRegistry(listOf(visualProvider())))

        val result = executor.execute(
            call = ToolCall(id = "call-1", name = "view_image", arguments = "{}"),
            activeToolNames = setOf("view_image"),
            sessionState = ToolExecutionSessionState(chatId = 1, supportsVisualInput = true)
        )

        assertFalse(result.isError)
        assertEquals(1, result.visualAttachments.size)
    }

    private fun visualProvider() = object : ToolProvider {
        override val definition = ToolDefinition(
            name = "view_image",
            description = "test",
            parameters = ToolDefinition.Parameters()
        )
        override val securityPolicy = ToolSecurityPolicy.ReadOnlyPrivate

        override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult = ToolResult(
            callId = call.id,
            name = call.name,
            content = "loaded",
            visualAttachments = listOf(
                ToolResultVisualAttachment(
                    assetId = "asset-1",
                    visualRef = "v1-1",
                    profile = VisualAssetProfile.COMPACTED_V1,
                    mimeType = "image/webp",
                    width = 100,
                    height = 100,
                    sizeBytes = 100,
                    detail = VisualDetail.AUTO
                )
            )
        )
    }
}
