package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.visual.VisualAssetCatalogItem
import cn.nabr.chatwithchat.data.visual.VisualAssetProfile
import cn.nabr.chatwithchat.data.visual.VisualAssetRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ListVisualAssetsToolProvider(
    private val repository: VisualAssetRepository
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.ListVisualAssets

    override val settingsMetadata: ToolSettingsMetadata = ToolSettingsMetadata(
        category = ToolCategory.Utility,
        defaultEnabled = true,
        isSensitive = false,
        allowDefaultReadOnlyPrivate = true,
        presentationKey = "list_visual_assets",
        iconKey = "image"
    )

    override val discoveryMetadata: ToolDiscoveryMetadata = ToolDiscoveryMetadata(
        exposure = ToolExposure.Resident,
        intentTags = setOf(
            "image",
            "picture",
            "visual",
            "attachment",
            "图片",
            "附件",
            "旧图",
            "历史图片"
        ),
        priority = 70
    )

    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPrivate

    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = MAX_CALLS_PER_REQUEST,
        maxCallsPerChat = MAX_CALLS_PER_CHAT,
        timeoutSeconds = 5,
        maxResultChars = MAX_RESULT_CHARS
    )

    override fun progressLabel(call: ToolCall): String = "正在整理图片索引"

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
        val query = arguments["query"]?.jsonPrimitive?.contentOrNull?.trim()
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) {
            return call.errorResult("tool_arguments_invalid:limit_out_of_range", "tool_arguments_invalid")
        }
        val includeRaw = arguments["include_raw"]?.jsonPrimitive?.booleanOrNull ?: true
        val includeFolded = arguments["include_folded"]?.jsonPrimitive?.booleanOrNull ?: true
        var items = repository.listAssets(
            chatId = executionContext.chatId,
            query = query,
            limit = limit,
            includeRaw = includeRaw,
            includeFolded = includeFolded
        )
        if (items.isEmpty() && repository.listAssets(chatId = executionContext.chatId, limit = 1).isEmpty()) {
            repository.ensureChatIndexed(executionContext.chatId)
            items = repository.listAssets(
                chatId = executionContext.chatId,
                query = query,
                limit = limit,
                includeRaw = includeRaw,
                includeFolded = includeFolded
            )
        }
        return ToolResult(
            callId = call.id,
            name = call.name,
            content = items.toCatalogText(),
            structuredContent = buildJsonObject {
                put(
                    "assets",
                    JsonArray(
                        items.map { item ->
                            buildJsonObject {
                                put("visual_ref", JsonPrimitive(item.visualRef))
                                put("asset_hash", JsonPrimitive(item.assetHash))
                                put("turn", JsonPrimitive(item.turnOrdinal))
                                put("message_id", JsonPrimitive(item.messageId))
                                put("attachment_index", JsonPrimitive(item.attachmentIndex))
                                put("role", JsonPrimitive(item.role))
                                put("name", JsonPrimitive(item.displayName))
                                put("mime_type", JsonPrimitive(item.mimeType))
                                put("width", item.width?.let(::JsonPrimitive) ?: JsonNull)
                                put("height", item.height?.let(::JsonPrimitive) ?: JsonNull)
                                put("folded", JsonPrimitive(item.isFolded))
                                put(
                                    "profile",
                                    JsonPrimitive(
                                        item.compactedProfile
                                            ?: VisualAssetProfile.ORIGINAL.storageValue
                                    )
                                )
                            }
                        }
                    )
                )
            },
            metadata = mapOf("visual_asset_count" to items.size.toString())
        )
    }

    private companion object {
        const val VISUAL_ASSET_FORBIDDEN = "visual_asset_forbidden"
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
        const val MAX_CALLS_PER_REQUEST = 3
        const val MAX_CALLS_PER_CHAT = 8
        const val MAX_RESULT_CHARS = 12_000
    }
}

private fun List<VisualAssetCatalogItem>.toCatalogText(): String =
    if (isEmpty()) {
        "当前聊天没有可查看的图片。"
    } else {
        joinToString(separator = "\n") { item ->
            buildString {
                append(item.visualRef)
                append(" | turn=")
                append(item.turnOrdinal)
                append(" | name=")
                append(item.displayName)
                append(" | ")
                append(item.width ?: "?")
                append("x")
                append(item.height ?: "?")
                append(" | folded=")
                append(item.isFolded)
                append(" | profile=")
                append(item.compactedProfile ?: VisualAssetProfile.ORIGINAL.storageValue)
            }
        }
    }
