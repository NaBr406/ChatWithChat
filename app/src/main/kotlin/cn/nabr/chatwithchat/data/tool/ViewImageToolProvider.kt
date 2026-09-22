package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.visual.VisualAssetFailure
import cn.nabr.chatwithchat.data.visual.VisualAssetReadResult
import cn.nabr.chatwithchat.data.visual.VisualAssetRepository
import cn.nabr.chatwithchat.data.visual.VisualDetail
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class ViewImageToolProvider(
    private val repository: VisualAssetRepository
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.ViewImage

    override val settingsMetadata: ToolSettingsMetadata = ToolSettingsMetadata(
        category = ToolCategory.Utility,
        defaultEnabled = true,
        isSensitive = false,
        allowDefaultReadOnlyPrivate = true,
        presentationKey = "view_image",
        iconKey = "image"
    )

    override val discoveryMetadata: ToolDiscoveryMetadata = ToolDiscoveryMetadata(
        exposure = ToolExposure.Resident,
        intentTags = setOf(
            "image",
            "picture",
            "screenshot",
            "photo",
            "看图",
            "图片",
            "截图",
            "照片"
        ),
        requiredCompanionToolNames = setOf(ToolDefinition.ListVisualAssets.name),
        priority = 80
    )

    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPrivate

    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = MAX_CALLS_PER_REQUEST,
        maxCallsPerChat = MAX_CALLS_PER_CHAT,
        timeoutSeconds = 10,
        maxResultChars = MAX_RESULT_CHARS
    )

    override fun progressLabel(call: ToolCall): String = "正在查看图片"

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
        call.errorResult(VISUAL_ASSET_FORBIDDEN, VISUAL_ASSET_FORBIDDEN)

    override suspend fun execute(
        call: ToolCall,
        config: ToolLoopConfig,
        executionContext: ToolExecutionContext
    ): ToolResult {
        val arguments = call.argumentsObject().getOrElse { throwable ->
            return call.errorResult("tool_arguments_invalid:${throwable.message}", "tool_arguments_invalid")
        }
        val visualRef = arguments["visual_ref"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            .orEmpty()
        if (visualRef.isBlank()) {
            return call.errorResult("tool_arguments_invalid:visual_ref_required", "tool_arguments_invalid")
        }
        val detail = VisualDetail.fromWire(
            arguments["detail"]
                ?.jsonPrimitive
                ?.contentOrNull
        )
        var resolved = repository.resolve(executionContext.chatId, visualRef, detail, allowInlineFallback = true)
        if (resolved is VisualAssetReadResult.Failure && resolved.failure == VisualAssetFailure.NotFound) {
            repository.ensureChatIndexed(executionContext.chatId)
            resolved = repository.resolve(executionContext.chatId, visualRef, detail, allowInlineFallback = true)
        }
        return when (resolved) {
            is VisualAssetReadResult.Failure -> call.errorResult(
                resolved.failure.code,
                resolved.failure.code
            )
            is VisualAssetReadResult.Success -> {
                val asset = resolved.asset
                val compactedForOriginal = detail == VisualDetail.ORIGINAL &&
                    asset.profile == cn.nabr.chatwithchat.data.visual.VisualAssetProfile.COMPACTED_V1
                val originalFallback = detail == VisualDetail.AUTO &&
                    asset.profile == cn.nabr.chatwithchat.data.visual.VisualAssetProfile.ORIGINAL
                val fallback = compactedForOriginal || originalFallback
                ToolResult(
                    callId = call.id,
                    name = call.name,
                    content = buildString {
                        append("已查看 ")
                        append(asset.catalogItem.visualRef)
                        append("；requested_detail=")
                        append(detail.wireValue)
                        append("；delivered_profile=")
                        append(asset.profile.storageValue)
                        append("；")
                        append(asset.mimeType)
                        asset.width?.let { width ->
                            append("；")
                            append(width)
                            append("x")
                            append(asset.height ?: 0)
                        }
                        if (compactedForOriginal) {
                            append("；inline_safety_fallback=compacted")
                        } else if (originalFallback) {
                            append("；compaction_fallback=original")
                        }
                    },
                    visualAttachments = listOf(
                        ToolResultVisualAttachment(
                            assetId = asset.asset.assetHash,
                            visualRef = asset.catalogItem.visualRef,
                            profile = asset.profile,
                            mimeType = asset.mimeType,
                            width = asset.width,
                            height = asset.height,
                            sizeBytes = asset.sizeBytes,
                            detail = detail,
                            base64Data = asset.base64Data,
                            localPath = asset.filePath,
                            compactionFallback = fallback
                        )
                    ),
                    metadata = buildMap {
                        put("visual_ref", asset.catalogItem.visualRef)
                        put("asset_hash", asset.asset.assetHash)
                        put("profile", asset.profile.storageValue)
                        if (compactedForOriginal) put("inline_safety_fallback", "compacted")
                        if (originalFallback) put("compaction_fallback", "true")
                    }
                )
            }
        }
    }

    private companion object {
        const val VISUAL_ASSET_FORBIDDEN = "visual_asset_forbidden"
        const val MAX_CALLS_PER_REQUEST = 4
        const val MAX_CALLS_PER_CHAT = 12
        const val MAX_RESULT_CHARS = 1_000
    }
}
