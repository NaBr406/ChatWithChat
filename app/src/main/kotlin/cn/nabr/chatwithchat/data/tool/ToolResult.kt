package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.visual.VisualAssetProfile
import cn.nabr.chatwithchat.data.visual.VisualDetail
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

@Serializable
data class ToolResult(
    val callId: String,
    val name: String,
    val content: String,
    val isError: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
    val structuredContent: JsonElement? = null,
    val sources: List<ToolSource> = emptyList(),
    @Transient
    val presentationArtifacts: List<ToolPresentationArtifact> = emptyList(),
    val visualAttachments: List<ToolResultVisualAttachment> = emptyList()
)

@Serializable
data class ToolResultVisualAttachment(
    val assetId: String,
    val visualRef: String? = null,
    val profile: VisualAssetProfile,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long,
    val detail: VisualDetail = VisualDetail.AUTO,
    @Transient
    val base64Data: String? = null,
    @Transient
    val localPath: String? = null,
    @Transient
    val compactionFallback: Boolean = false
) {
    fun estimatedTokens(): Int {
        val pixelCount = (width?.coerceAtLeast(1) ?: DEFAULT_ESTIMATED_EDGE) *
            (height?.coerceAtLeast(1) ?: DEFAULT_ESTIMATED_EDGE)
        return (pixelCount / PIXELS_PER_TOKEN).coerceAtLeast(MIN_ESTIMATED_TOKENS)
    }

    private companion object {
        const val DEFAULT_ESTIMATED_EDGE = 1024
        const val PIXELS_PER_TOKEN = 750
        const val MIN_ESTIMATED_TOKENS = 85
    }
}
