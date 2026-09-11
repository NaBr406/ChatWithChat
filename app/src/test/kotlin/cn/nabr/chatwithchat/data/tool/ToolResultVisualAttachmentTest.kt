package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.visual.VisualAssetProfile
import cn.nabr.chatwithchat.data.visual.VisualDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultVisualAttachmentTest {

    @Test
    fun `visual attachments are not clipped by the text content budget`() {
        val result = ToolResult(
            callId = "call-1",
            name = ToolDefinition.ViewImage.name,
            content = "x".repeat(2_000),
            visualAttachments = listOf(visualAttachment(assetId = "asset-1", sizeBytes = 4_096))
        )

        val bounded = result.boundPayload(ToolResultBounds(maxContentChars = 64)).result

        assertEquals(64, bounded.content.length)
        assertEquals(1, bounded.visualAttachments.size)
        assertEquals("v17-1", bounded.visualAttachments.single().visualRef)
    }

    @Test
    fun `visual attachment count budget drops excess without rejecting text`() {
        val result = ToolResult(
            callId = "call-1",
            name = ToolDefinition.ViewImage.name,
            content = "loaded",
            visualAttachments = listOf(
                visualAttachment(assetId = "asset-1", sizeBytes = 1_024),
                visualAttachment(assetId = "asset-2", sizeBytes = 1_024)
            )
        )

        val bounded = result.boundPayload(
            ToolResultBounds(
                maxContentChars = 1_000,
                maxVisualAttachmentCount = 1
            )
        )

        assertEquals(1, bounded.result.visualAttachments.size)
        assertTrue(ToolResultPayloadPart.VISUAL_ATTACHMENTS in bounded.droppedPayloadParts)
    }

    private fun visualAttachment(
        assetId: String,
        sizeBytes: Long
    ) = ToolResultVisualAttachment(
        assetId = assetId,
        visualRef = "v17-1",
        profile = VisualAssetProfile.COMPACTED_V1,
        mimeType = "image/webp",
        width = 1024,
        height = 768,
        sizeBytes = sizeBytes,
        detail = VisualDetail.AUTO
    )
}
