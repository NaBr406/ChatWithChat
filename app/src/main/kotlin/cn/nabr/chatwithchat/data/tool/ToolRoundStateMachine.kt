package cn.nabr.chatwithchat.data.tool

internal enum class ToolRoundReasoningPolicy {
    USER,
    LOW
}

internal data class ToolRoundState(
    val definitions: List<ToolDefinition>,
    val reasoningPolicy: ToolRoundReasoningPolicy,
    val isFinalOnly: Boolean,
    /**
     * Whether the rounds before this one can be dropped from what the next request carries.
     *
     * Only ever true while the turn's tool work is nothing but stickers. Compacting was previously keyed on
     * the phase alone, which meant a turn that had already searched the web and then reacted with a sticker
     * threw the web results away — the model then re-derived the request from scratch, because its own tool
     * history was the only record that it had done the work.
     */
    internal val shouldCompactScratchpad: Boolean,
    /**
     * Whether the sticker exchange itself can be dropped now that the sticker is settled.
     *
     * Separate from [shouldCompactScratchpad] because the native loops only ever drop history once the send
     * has completed: dropping it a round earlier would take the candidate list with it, leaving the send
     * round nothing to choose a `sticker_id` from.
     */
    internal val canDropStickerHistory: Boolean,
    /**
     * Whether an answer written in this round can be taken as the answer.
     *
     * The JSON loop normally re-asks when a model answers during a tool round, on the grounds that a draft
     * written while it was still deciding whether to call something is provisional. A round that follows a
     * settled sticker is not that: the sticker phase used to end the loop outright, so this round's answer was
     * already accepted directly, and reopening the toolset here must not quietly cost every sticker turn an
     * extra request to get the same text back.
     */
    internal val acceptsDirectFinalAnswer: Boolean
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
    private var hasNonStickerResults = false

    val current: ToolRoundState
        get() {
            val definitions = when (phase) {
                Phase.Open -> availableDefinitions
                Phase.StickerSearchOnly -> availableDefinitions.only(ToolDefinition.SearchStickers.name)
                Phase.StickerSendOnly -> availableDefinitions.only(ToolDefinition.SendSticker.name)
                Phase.StickerDone -> availableDefinitions
                    .without(STICKER_TOOL_NAMES)
                    // `discover_tools` on its own is not pending work — it is the control that reaches work.
                    // Counting it would hand every sticker turn an extra round whose only offer is a tool the
                    // model has nothing to discover for.
                    .takeIf { remaining ->
                        remaining.any { definition -> definition.name != ToolDefinition.DiscoverTools.name }
                    }
                    .orEmpty()
                Phase.FinalOnly -> emptyList()
            }
            return ToolRoundState(
                definitions = definitions,
                reasoningPolicy = when (phase) {
                    Phase.StickerSearchOnly,
                    Phase.StickerSendOnly -> ToolRoundReasoningPolicy.LOW
                    Phase.Open,
                    Phase.StickerDone,
                    Phase.FinalOnly -> ToolRoundReasoningPolicy.USER
                },
                isFinalOnly = phase == Phase.FinalOnly,
                shouldCompactScratchpad = phase != Phase.Open && !hasNonStickerResults,
                canDropStickerHistory = phase == Phase.StickerDone && !hasNonStickerResults,
                acceptsDirectFinalAnswer = phase == Phase.StickerDone
            )
        }

    fun onToolResults(results: List<ToolResult>) {
        hasNonStickerResults = hasNonStickerResults ||
            results.any { result -> result.name !in STICKER_TOOL_NAMES }
        val latestStickerResult = results.lastOrNull { result ->
            result.name == ToolDefinition.SearchStickers.name ||
                result.name == ToolDefinition.SendSticker.name
        } ?: return

        phase = when (latestStickerResult.name) {
            ToolDefinition.SearchStickers.name -> nextSearchPhase(latestStickerResult)
            // Sending is over either way — a failed send is not retried — but the turn may not be. A sticker
            // is a reaction to the user's message, so the model reaches for one early, often before it has
            // done the work the message actually asked for. Ending the loop here left that work with no tool
            // channel to use, and a model that still needed one wrote its call out as text instead.
            ToolDefinition.SendSticker.name -> Phase.StickerDone
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

    private fun List<ToolDefinition>.without(names: Set<String>): List<ToolDefinition> =
        filterNot { definition -> definition.name in names }

    private enum class Phase {
        Open,
        StickerSearchOnly,
        StickerSendOnly,

        /**
         * The sticker is settled; everything else the turn still needs is not.
         *
         * Distinct from [Open] only in that the sticker tools are withheld, so the model cannot search or
         * send again, and distinct from [FinalOnly] in that the rest of the toolset stays reachable. A turn
         * whose only tool work was the sticker lands here with an empty definition list, which the loops
         * already treat as "go and write the final answer" — so the pure-chat path behaves exactly as it did
         * before this phase existed.
         */
        StickerDone,
        FinalOnly
    }

    private companion object {
        private val STICKER_TOOL_NAMES = setOf(
            ToolDefinition.SearchStickers.name,
            ToolDefinition.SendSticker.name
        )
    }
}

internal fun ToolResult.hasStickerCandidates(): Boolean {
    metadata["candidate_count"]?.toIntOrNull()?.let { count -> return count > 0 }
    return content.lineSequence().any { line -> line.trimStart().startsWith("sticker_id=") }
}
