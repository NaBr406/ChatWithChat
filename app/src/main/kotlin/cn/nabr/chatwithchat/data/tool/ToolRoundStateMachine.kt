package cn.nabr.chatwithchat.data.tool

internal enum class ToolRoundReasoningPolicy {
    USER,
    LOW
}

internal data class ToolRoundState(
    val definitions: List<ToolDefinition>,
    val reasoningPolicy: ToolRoundReasoningPolicy,
    val isFinalOnly: Boolean,
    internal val shouldCompactScratchpad: Boolean
) {
    val allowedToolNames: Set<String>
        get() = definitions.mapTo(linkedSetOf(), ToolDefinition::name)
}

/** Keeps model-visible schemas and the execution allowlist aligned across one tool loop. */
internal class ToolRoundStateMachine(
    initialDefinitions: List<ToolDefinition>
) {
    private var availableDefinitions = initialDefinitions.distinctBy(ToolDefinition::name)
    private var phase: Phase = Phase.Open
    private var stickerSearchCount = 0

    val current: ToolRoundState
        get() {
            val definitions = when (phase) {
                Phase.Open -> availableDefinitions
                Phase.StickerSearchOnly -> availableDefinitions.only(ToolDefinition.SearchStickers.name)
                Phase.StickerSendOnly -> availableDefinitions.only(ToolDefinition.SendSticker.name)
                Phase.FinalOnly -> emptyList()
            }
            return ToolRoundState(
                definitions = definitions,
                reasoningPolicy = when (phase) {
                    Phase.StickerSearchOnly,
                    Phase.StickerSendOnly -> ToolRoundReasoningPolicy.LOW
                    Phase.Open,
                    Phase.FinalOnly -> ToolRoundReasoningPolicy.USER
                },
                isFinalOnly = phase == Phase.FinalOnly,
                shouldCompactScratchpad = phase != Phase.Open
            )
        }

    fun onToolResults(results: List<ToolResult>) {
        val latestStickerResult = results.lastOrNull { result ->
            result.name == ToolDefinition.SearchStickers.name ||
                result.name == ToolDefinition.SendSticker.name
        } ?: return

        phase = when (latestStickerResult.name) {
            ToolDefinition.SearchStickers.name -> nextSearchPhase(latestStickerResult)
            ToolDefinition.SendSticker.name -> Phase.FinalOnly
            else -> phase
        }
    }

    internal fun updateAvailableDefinitions(definitions: List<ToolDefinition>) {
        availableDefinitions = definitions.distinctBy(ToolDefinition::name)
    }

    private fun nextSearchPhase(result: ToolResult): Phase {
        stickerSearchCount += 1
        if (result.isError) return Phase.FinalOnly
        if (result.hasStickerCandidates()) {
            return if (availableDefinitions.any { definition ->
                    definition.name == ToolDefinition.SendSticker.name
                }
            ) {
                Phase.StickerSendOnly
            } else {
                Phase.FinalOnly
            }
        }
        return if (
            stickerSearchCount < MAX_STICKER_SEARCH_CALLS_PER_REQUEST &&
            availableDefinitions.any { definition -> definition.name == ToolDefinition.SearchStickers.name }
        ) {
            Phase.StickerSearchOnly
        } else {
            Phase.FinalOnly
        }
    }

    private fun List<ToolDefinition>.only(name: String): List<ToolDefinition> =
        filter { definition -> definition.name == name }

    private enum class Phase {
        Open,
        StickerSearchOnly,
        StickerSendOnly,
        FinalOnly
    }
}

internal fun ToolResult.hasStickerCandidates(): Boolean {
    metadata["candidate_count"]?.toIntOrNull()?.let { count -> return count > 0 }
    return content.lineSequence().any { line -> line.trimStart().startsWith("sticker_id=") }
}
