package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.sticker.STICKER_MEDIA_KIND_STATIC_RASTER
import cn.nabr.chatwithchat.data.sticker.StickerPresentationArtifact
import cn.nabr.chatwithchat.data.sticker.StickerRepository
import cn.nabr.chatwithchat.data.sticker.StickerResolution
import kotlinx.coroutines.CancellationException

class SendStickerToolProvider(
    private val stickerRepository: StickerRepository
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.SendSticker

    override val settingsMetadata: ToolSettingsMetadata = ToolSettingsMetadata(
        userVisible = false,
        category = ToolCategory.Other,
        defaultEnabled = true,
        isSensitive = false,
        presentationKey = "automatic_sticker_replies",
        iconKey = "stickers",
        enablementGroup = ToolEnablementGroup.AutomaticStickerReplies
    )

    override val securityPolicy: ToolSecurityPolicy = ToolSecurityPolicy.ReadOnlyPrivate
    override val policy: ToolPolicy = ToolPolicy(
        maxCallsPerRequest = 1,
        maxCallsPerChat = 1,
        timeoutSeconds = 2,
        maxResultChars = MAX_SEND_RESULT_CHARS,
        maxCallsPerRequestErrorKey = "max_sticker_sends_per_request",
        maxCallsPerChatErrorKey = "max_sticker_sends_per_request"
    )

    override fun progressLabel(call: ToolCall): String = STICKER_PROGRESS_LABEL

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult {
        val stickerId = call.stringArgument("sticker_id").getOrElse { throwable ->
            return call.errorResult("tool_arguments_invalid:${throwable.message}")
        }
        if (stickerId.length > MAX_STICKER_ID_CHARS || stickerId.any(Char::isISOControl)) {
            return call.errorResult("tool_arguments_invalid:sticker_id_invalid")
        }
        val resolution = runCatching {
            stickerRepository.ensureInitialized()
            stickerRepository.resolveEnabledStatic(stickerId, call.id)
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            return call.errorResult(STICKER_UNAVAILABLE, STICKER_UNAVAILABLE)
        }

        return when (resolution) {
            is StickerResolution.Success -> resolution.artifact.toToolResult(call)
            is StickerResolution.Unavailable -> call.errorResult(
                resolution.code.boundedStickerErrorCode(),
                resolution.code.boundedStickerErrorCode()
            )
        }
    }

    override fun validateSessionCall(call: ToolCall, sessionState: ToolExecutionSessionState): ToolResult? {
        val stickerId = call.stringArgument("sticker_id").getOrNull()?.trim().orEmpty()
        return if (sessionState.containsValue(STICKER_CANDIDATE_IDS_SESSION_KEY, stickerId)) {
            null
        } else {
            call.errorResult("sticker_not_found", "sticker_not_found")
        }
    }

    override fun presentationArtifacts(result: ToolResult): List<ToolPresentationArtifact> =
        if (result.isError) {
            emptyList()
        } else {
            result.presentationArtifacts
                .filterIsInstance<StickerPresentationArtifact>()
                .filter { artifact -> artifact.isSafeForPresentation(result.callId) }
                .take(1)
        }
}

private fun StickerPresentationArtifact.toToolResult(call: ToolCall): ToolResult =
    if (!isSafeForPresentation(call.id)) {
        call.errorResult(STICKER_UNAVAILABLE, STICKER_UNAVAILABLE)
    } else {
        ToolResult(
            callId = call.id,
            name = call.name,
            content = "Sticker selected: $stickerId",
            presentationArtifacts = listOf(this)
        )
    }

private fun StickerPresentationArtifact.isSafeForPresentation(callId: String): Boolean =
    instanceId == callId &&
        stickerId.isNotBlank() &&
        stickerId.length <= MAX_STICKER_ID_CHARS &&
        assetKey.isNotBlank() &&
        altText.isNotBlank() &&
        mediaKind == STICKER_MEDIA_KIND_STATIC_RASTER

private fun String.boundedStickerErrorCode(): String = when (this) {
    "sticker_not_found" -> "sticker_not_found"
    STICKER_UNAVAILABLE -> STICKER_UNAVAILABLE
    else -> STICKER_UNAVAILABLE
}

private const val MAX_STICKER_ID_CHARS = 160
private const val MAX_SEND_RESULT_CHARS = 400
