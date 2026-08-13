package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.ACTIVE_REVISION_LATEST
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.database.entity.SafeMessageSourceTarget
import cn.nabr.chatwithchat.data.database.entity.effectiveTokenUsage
import cn.nabr.chatwithchat.data.database.entity.safeDedupeKey
import cn.nabr.chatwithchat.data.database.entity.safeNavigationTarget
import cn.nabr.chatwithchat.util.isAssistantErrorMessage
import cn.nabr.chatwithchat.util.stripAssistantErrorNote
import javax.inject.Inject

class ContextBuilder @Inject constructor() {
    fun build(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        policy: ProviderContextPolicy = ProviderContextPolicy.forClientType(platform.compatibleType)
    ): List<ConversationTurn> = buildContext(
        userMessages = userMessages,
        assistantMessages = assistantMessages,
        platform = platform,
        policy = policy
    ).turns

    fun buildContext(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platform: PlatformV2,
        policy: ProviderContextPolicy = ProviderContextPolicy.forClientType(platform.compatibleType)
    ): ConversationContext {
        if (userMessages.isEmpty()) return ConversationContext()

        val rawTurns = userMessages.mapIndexed { index, userMessage ->
            val assistantMessage = assistantMessages.getOrNull(index)
                ?.firstValidAssistantCandidate(platform.uid, platform.model)

            ConversationTurn(
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                isCurrentTurn = index == userMessages.lastIndex
            )
        }

        val filteredTurns = rawTurns.filter { turn ->
            when {
                turn.isCurrentTurn -> true
                turn.assistantMessage == null -> false
                turn.assistantMessage.semanticAssistantContent().isBlank() && turn.assistantMessage.attachments.isEmpty() -> false
                else -> true
            }
        }

        if (filteredTurns.isEmpty()) return ConversationContext()

        val tokenCounter = ContextTokenCounter.forPlatform(platform)
        val currentTurn = filteredTurns.lastOrNull { it.isCurrentTurn }
        val historyTurns = filteredTurns.filterNot { it.isCurrentTurn }
        val selectedHistoryTurns = currentTurn?.let { selectHistoryTurns(historyTurns, it, policy, tokenCounter) }
            ?: historyTurns.takeLast(policy.recentTurnWindow)

        val selectedTurns = buildList {
            addAll(selectedHistoryTurns)
            currentTurn?.let { add(it) }
        }
        val omittedTurns = historyTurns.dropLast(selectedHistoryTurns.size)
        val summary = buildSummaryForOmittedTurns(omittedTurns, selectedTurns, policy, tokenCounter)
        val estimatedTokens = tokenCounter.estimateTurns(selectedTurns) + tokenCounter.estimateText(summary.orEmpty())

        return ConversationContext(
            turns = applyAttachmentWindow(selectedTurns, policy),
            summary = summary,
            estimatedTokens = estimatedTokens,
            omittedTurns = omittedTurns.toConversationTurnsWithoutAttachments()
        )
    }

    /**
     * The context-management build: range and strategy in, [ContextBuildResult] out.
     *
     * Kept alongside the legacy [buildContext] rather than replacing it, because the two answer
     * different questions. [buildContext] fits turns into [ProviderContextPolicy.maxContextTokens],
     * a small local construction budget; this one fits them into the *model's* real window via
     * [ContextBudget] and knows about stored checkpoints. Both share the turn pairing, sanitization
     * and attachment-window helpers below, so a fix to how a message becomes context lands in both.
     *
     * This function is pure: no network, no database, no suspension. Everything it needs about
     * previously folded state arrives in [ContextAssemblyRequest], which is what lets the entire
     * strategy matrix be unit-tested without a provider or a Room instance.
     */
    fun buildManagedContext(request: ContextAssemblyRequest): ContextBuildResult {
        val emptyResult = ContextBuildResult(
            strategy = request.strategy,
            range = request.range,
            budget = request.budget
        )
        if (request.userMessages.isEmpty()) return emptyResult

        val pairedTurns = pairManagedTurns(
            userMessages = request.userMessages,
            assistantMessages = request.assistantMessages,
            platformUid = request.platform.uid,
            modelId = request.platform.model
        )
        if (pairedTurns.isEmpty()) return emptyResult

        val tokenCounter = ContextTokenCounter.forPlatform(request.platform)
        val rangeResolution = resolveRange(pairedTurns, request.range, request.assistantMessages)

        return if (request.usesNativeCompaction()) {
            buildNativeManagedContext(request, rangeResolution, tokenCounter)
        } else {
            buildAppSideManagedContext(request, rangeResolution, tokenCounter)
        }
    }

    /**
     * The turns of a conversation, paired and sanitized exactly as [buildManagedContext] would pair
     * them, with no range, budget or checkpoint applied.
     *
     * Exists for the background fold, which re-reads messages from Room and must reproduce the very
     * same [ConversationTurn] values that [ContextBuildResult.pendingFoldTurns] was hashed from.
     * Assistant text is sanitized before it is hashed, so a caller that paired messages by hand would
     * compute a different digest and every fold would abort as "batch changed".
     */
    fun buildFoldableTurns(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platformUid: String
    ): List<ConversationTurn> =
        pairManagedTurns(userMessages, assistantMessages, platformUid, modelId = null).map { it.turn }

    /**
     * A model that folds server-side takes the native path — unless the provider has already answered
     * `unsupported_parameter` for it, in which case the gate guessed wrong and the app-side path is
     * authoritative from then on.
     */
    private fun ContextAssemblyRequest.usesNativeCompaction(): Boolean =
        NativeCompactionModelGate.supportsServerSideCompaction(platform.compatibleType, platform.model) &&
            nativeState?.nativeUnsupported != true

    private fun buildAppSideManagedContext(
        request: ContextAssemblyRequest,
        rangeResolution: RangeResolution,
        tokenCounter: ContextTokenCounter
    ): ContextBuildResult {
        val budget = request.budget
        val checkpointBody = request.chain.checkpointBody
        val foldedThroughUserMessageId = request.chain.foldedThroughUserMessageId

        // Turns the checkpoint already covers must not also be replayed verbatim, or the model sees
        // the same exchange twice — once summarised, once in full — and treats the repetition as
        // emphasis.
        val unfoldedTurns = rangeResolution.turns.dropFoldedPrefix(foldedThroughUserMessageId)

        val checkpointTokens = checkpointBody?.let { body ->
            tokenCounter.estimateText(ContextBuildResult.HISTORICAL_CONTEXT_HEADER) + tokenCounter.estimateText(body)
        } ?: 0
        val rawTail = selectRawTail(
            turns = unfoldedTurns,
            maxTokens = rawTailBudgetFor(request.strategy, budget, checkpointTokens),
            tokenCounter = tokenCounter
        )

        // Overflow is never dropped. It has not reached a checkpoint yet — that is the background
        // fold's job and it has not landed — so the deterministic summarizer stands in until it does.
        val deterministicSummary = ConversationContextSummarizer.build(
            turns = rawTail.overflow.map { it.turn },
            maxTokens = budget.summaryTarget,
            maxTurns = request.policy.maxSummaryTurns,
            tokenCounter = tokenCounter
        )

        // What a background fold should consume. Under the AUTO_COMPACT family the raw tail is already
        // sized to recentRawTail so the overflow is exactly that batch; under RAW_PREFERRED the tail is
        // much larger, so the same boundary has to be computed separately — folding must not wait until
        // the conversation is over budget.
        val pendingFoldBatch = if (request.strategy.sendsRawWhileCapacityAllows) {
            selectRawTail(unfoldedTurns, budget.recentRawTail, tokenCounter).overflow
        } else {
            rawTail.overflow
        }
        val pendingFoldTurns = pendingFoldBatch.map { it.turn }

        val selectedTurns = rawTail.selected.map { it.turn }
        val estimatedTokens = checkpointTokens +
            tokenCounter.estimateTurns(selectedTurns) +
            tokenCounter.estimateText(deterministicSummary.orEmpty())

        return ContextBuildResult(
            strategy = request.strategy,
            range = request.range,
            budget = budget,
            turns = applyAttachmentWindow(selectedTurns, request.policy),
            checkpointBody = checkpointBody,
            checkpointBoundaryUserMessageId = foldedThroughUserMessageId,
            deterministicSummary = deterministicSummary,
            pendingFoldTurns = pendingFoldTurns,
            // Unlike estimatedTokens this is not clamped by the budget, which is exactly why the fold
            // decision reads it: it keeps growing until a fold actually removes the turns.
            pendingFoldTokens = tokenCounter.estimateTurns(pendingFoldTurns),
            estimatedTokens = estimatedTokens,
            compactionState = when {
                rawTail.overflow.isNotEmpty() -> ContextCompactionState.DETERMINISTIC_FALLBACK
                !request.chain.isEmpty -> ContextCompactionState.REUSED_CHECKPOINT
                else -> ContextCompactionState.NONE
            },
            crossProviderDegradedTurnCount = rawTail.selected.count { it.isCrossProviderDegraded },
            rangeCorrection = rangeResolution.correction
        )
    }

    /**
     * The OpenAI native path: replay the stored opaque window, then every complete turn after it.
     *
     * There is no app-side checkpoint here and no background pre-fold. Folding happens server-side
     * inside the request the user was already making, so the only local responsibility is deciding
     * what still has to be sent alongside the window.
     */
    private fun buildNativeManagedContext(
        request: ContextAssemblyRequest,
        rangeResolution: RangeResolution,
        tokenCounter: ContextTokenCounter
    ): ContextBuildResult {
        val budget = request.budget
        val nativeState = request.nativeState?.takeIf { it.isUsable }
        val replayTurns = rangeResolution.turns.dropFoldedPrefix(nativeState?.boundaryUserMessageId)

        val windowTokens = nativeState?.estimatedTokens ?: 0
        val rawTail = selectRawTail(
            turns = replayTurns,
            maxTokens = (budget.effectiveInputBudget - windowTokens).coerceAtLeast(MIN_RAW_TAIL_TOKENS),
            tokenCounter = tokenCounter
        )

        // Overflow here means the range no longer fits one request, which is the one situation the
        // standalone /v1/responses/compact endpoint exists for. An extractive summary is still
        // produced as the floor: if that initialization call fails, the turns must reach the model in
        // some form rather than vanish.
        val deterministicSummary = ConversationContextSummarizer.build(
            turns = rawTail.overflow.map { it.turn },
            maxTokens = budget.summaryTarget,
            maxTurns = request.policy.maxSummaryTurns,
            tokenCounter = tokenCounter
        )

        val selectedTurns = rawTail.selected.map { it.turn }
        return ContextBuildResult(
            strategy = request.strategy,
            range = request.range,
            budget = budget,
            turns = applyAttachmentWindow(selectedTurns, request.policy),
            nativeCompactionItemsJson = nativeState?.compactionItemsJson,
            deterministicSummary = deterministicSummary,
            nativeInitializationRequired = rawTail.overflow.isNotEmpty(),
            estimatedTokens = windowTokens +
                tokenCounter.estimateTurns(selectedTurns) +
                tokenCounter.estimateText(deterministicSummary.orEmpty()),
            compactionState = if (nativeState != null) {
                ContextCompactionState.REUSED_NATIVE_STATE
            } else {
                ContextCompactionState.NATIVE_SERVER_SIDE
            },
            crossProviderDegradedTurnCount = rawTail.selected.count { it.isCrossProviderDegraded },
            rangeCorrection = rangeResolution.correction
        )
    }

    /**
     * How much of the budget may go out verbatim.
     *
     * [ContextStrategy.RAW_PREFERRED] means "raw while capacity allows", so it gets everything left
     * after the checkpoint. The `AUTO_COMPACT_*` family deliberately takes far less than it could: a
     * bounded tail keeps request size stable and cheap instead of letting it grow until it has no
     * choice but to fold. Which bound applies is the user's tier choice, already baked into
     * [ContextBudget.recentRawTail] by [ContextBudgetTier].
     */
    private fun rawTailBudgetFor(
        strategy: ContextStrategy,
        budget: ContextBudget,
        checkpointTokens: Int
    ): Int {
        val allowance = if (strategy.sendsRawWhileCapacityAllows) {
            budget.effectiveInputBudget - checkpointTokens
        } else {
            budget.recentRawTail
        }
        return allowance.coerceAtLeast(MIN_RAW_TAIL_TOKENS)
    }

    /**
     * Walks backwards from the newest turn, keeping whatever fits.
     *
     * The newest turn is admitted unconditionally. It carries the question being asked, and a request
     * assembled without it is not the request the user made — better to exceed the budget and let the
     * provider complain than to silently ask something else.
     */
    private fun selectRawTail(
        turns: List<PairedTurn>,
        maxTokens: Int,
        tokenCounter: ContextTokenCounter
    ): RawTailSelection {
        if (turns.isEmpty()) return RawTailSelection()

        var selectedCount = 0
        var usedTokens = 0
        for (index in turns.indices.reversed()) {
            val cost = tokenCounter.estimateTurn(turns[index].turn)
            if (index != turns.lastIndex && usedTokens + cost > maxTokens) break

            selectedCount += 1
            usedTokens += cost
        }

        return RawTailSelection(
            selected = turns.takeLast(selectedCount),
            overflow = turns.dropLast(selectedCount)
        )
    }

    /**
     * Drops every turn up to and including [foldedThroughUserMessageId].
     *
     * Compares ids rather than looking the boundary up, so a boundary turn that has since been
     * deleted still cleanly separates folded history from the raw tail instead of falling back to
     * replaying everything.
     *
     * The current turn stops the walk unconditionally. A background fold only ever consumes turns
     * older than the raw tail, so a boundary at or past the current turn should be impossible — but
     * "impossible" here would mean sending a request without the message the user just typed, and a
     * stale checkpoint racing a retry is exactly the kind of thing that produces it.
     */
    private fun List<PairedTurn>.dropFoldedPrefix(foldedThroughUserMessageId: Int?): List<PairedTurn> {
        val boundary = foldedThroughUserMessageId ?: return this
        return dropWhile { !it.turn.isCurrentTurn && it.turn.userMessage.id <= boundary }
    }

    private fun pairManagedTurns(
        userMessages: List<MessageV2>,
        assistantMessages: List<List<MessageV2>>,
        platformUid: String,
        modelId: String?
    ): List<PairedTurn> = userMessages
        .mapIndexed { index, userMessage ->
            val candidates = assistantMessages.getOrNull(index).orEmpty()
            val ownReply = candidates.firstValidAssistantCandidate(platformUid, modelId)
            val foreignReply = if (ownReply == null) candidates.firstForeignAssistantCandidate(platformUid) else null

            PairedTurn(
                turn = ConversationTurn(
                    userMessage = userMessage,
                    assistantMessage = ownReply ?: foreignReply,
                    isCurrentTurn = index == userMessages.lastIndex
                ),
                isCrossProviderDegraded = foreignReply != null
            )
        }
        .filter { paired ->
            val turn = paired.turn
            when {
                turn.isCurrentTurn -> true
                turn.assistantMessage == null -> false
                turn.assistantMessage.semanticAssistantContent().isBlank() &&
                    turn.assistantMessage.attachments.isEmpty() -> false
                else -> true
            }
        }

    /**
     * A reply produced by a *different* provider, reduced to plain text.
     *
     * The legacy path drops these outright, which also deletes the user's half of the turn, because a
     * turn with no assistant message is filtered out entirely — so switching provider mid-chat
     * silently erases the conversation that led up to the switch. Provider-specific structure genuinely
     * cannot be replayed (a file uploaded to one provider is meaningless to another), so attachments
     * are stripped and only the semantic text survives. `platformType` is deliberately left alone:
     * rewriting it would make a degraded turn indistinguishable from a native one everywhere else.
     */
    private fun List<MessageV2>.firstForeignAssistantCandidate(platformUid: String): MessageV2? =
        firstNotNullOfOrNull { message ->
            if (message.platformType == null || message.platformType == platformUid) {
                return@firstNotNullOfOrNull null
            }

            val sanitizedMessage = sanitizeAssistantMessageForContext(message)
            when {
                sanitizedMessage.semanticAssistantContent().isBlank() -> null
                isAssistantErrorMessage(sanitizedMessage.content) -> null
                else -> sanitizedMessage.copy(attachments = emptyList())
            }
        }

    private fun resolveRange(
        turns: List<PairedTurn>,
        range: ContextRange,
        assistantMessages: List<List<MessageV2>>
    ): RangeResolution {
        val anchor = range.startUserMessageId ?: return RangeResolution(turns, ContextRangeCorrection.NONE)

        val anchorIndex = turns.indexOfFirst { it.turn.userMessage.id == anchor }
        if (anchorIndex >= 0) return RangeResolution(turns.drop(anchorIndex), ContextRangeCorrection.NONE)

        // Correction always widens back toward full history and never narrows: a stale anchor must
        // not be able to hide turns the user can still see in the transcript.
        val anchorIsAssistantMessage = assistantMessages.any { candidates ->
            candidates.any { candidate -> candidate.id == anchor }
        }
        return RangeResolution(
            turns = turns,
            correction = if (anchorIsAssistantMessage) {
                ContextRangeCorrection.ANCHOR_NOT_A_TURN_BOUNDARY
            } else {
                ContextRangeCorrection.ANCHOR_MISSING
            }
        )
    }

    private data class PairedTurn(
        val turn: ConversationTurn,
        val isCrossProviderDegraded: Boolean
    )

    private data class RawTailSelection(
        val selected: List<PairedTurn> = emptyList(),
        val overflow: List<PairedTurn> = emptyList()
    )

    private data class RangeResolution(
        val turns: List<PairedTurn>,
        val correction: ContextRangeCorrection
    )

    private fun selectHistoryTurns(
        historyTurns: List<ConversationTurn>,
        currentTurn: ConversationTurn,
        policy: ProviderContextPolicy,
        tokenCounter: ContextTokenCounter
    ): List<ConversationTurn> {
        if (historyTurns.isEmpty()) return emptyList()

        val candidates = historyTurns.takeLast(policy.recentTurnWindow)
        val selectedTurns = mutableListOf<ConversationTurn>()

        for (candidate in candidates.asReversed()) {
            val trialTurns = buildList {
                add(candidate)
                addAll(selectedTurns)
                add(currentTurn)
            }
            val omittedCount = historyTurns.size - (selectedTurns.size + 1)
            val reservedSummaryTokens = if (omittedCount > 0) {
                minOf(policy.summaryTokenBudget, policy.maxContextTokens / 4)
            } else {
                0
            }
            val allowedTokenBudget = (policy.maxContextTokens - reservedSummaryTokens).coerceAtLeast(0)

            if (tokenCounter.estimateTurns(trialTurns) <= allowedTokenBudget) {
                selectedTurns.add(0, candidate)
            } else {
                break
            }
        }

        return selectedTurns
    }

    private fun buildSummaryForOmittedTurns(
        omittedTurns: List<ConversationTurn>,
        selectedTurns: List<ConversationTurn>,
        policy: ProviderContextPolicy,
        tokenCounter: ContextTokenCounter
    ): String? {
        if (omittedTurns.isEmpty()) return null

        val selectedTokenCount = tokenCounter.estimateTurns(selectedTurns)
        val summaryBudget = minOf(policy.summaryTokenBudget, policy.maxContextTokens - selectedTokenCount)
        if (summaryBudget < MIN_SUMMARY_TOKEN_BUDGET) return null

        return ConversationContextSummarizer.build(
            turns = omittedTurns,
            maxTokens = summaryBudget,
            maxTurns = policy.maxSummaryTurns,
            tokenCounter = tokenCounter
        )
    }

    private fun List<MessageV2>.firstValidAssistantCandidate(
        platformUid: String,
        modelId: String?
    ): MessageV2? {
        val providerCandidates = filter { message -> message.platformType == platformUid }
        val exactModel = modelId?.trim().orEmpty().takeIf { it.isNotBlank() }
        val orderedCandidates = if (exactModel == null) {
            providerCandidates
        } else {
            providerCandidates.sortedByDescending { message ->
                message.effectiveTokenUsage()?.model?.trim() == exactModel
            }
        }

        return orderedCandidates.firstNotNullOfOrNull { message ->
            val sanitizedMessage = sanitizeAssistantMessageForContext(message)
            when {
                sanitizedMessage.semanticAssistantContent().isBlank() && sanitizedMessage.attachments.isEmpty() -> null
                isAssistantErrorMessage(sanitizedMessage.content) -> null
                else -> sanitizedMessage
            }
        }
    }

    private fun applyAttachmentWindow(
        turns: List<ConversationTurn>,
        policy: ProviderContextPolicy
    ): List<ConversationTurn> {
        if (turns.isEmpty()) return emptyList()

        val lastIndex = turns.lastIndex
        return turns.mapIndexed { index, turn ->
            val shouldKeepAttachments = (lastIndex - index) <= policy.historicalImageTurnWindow
            val userMessage = if (shouldKeepAttachments) {
                turn.userMessage
            } else {
                turn.userMessage.copy(attachments = emptyList())
            }

            val assistantMessage = turn.assistantMessage?.let { message ->
                if (shouldKeepAttachments) {
                    message
                } else {
                    message.copy(attachments = emptyList())
                }
            }

            ConversationTurn(
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                isCurrentTurn = turn.isCurrentTurn
            )
        }
    }

    private fun List<ConversationTurn>.toConversationTurnsWithoutAttachments(): List<ConversationTurn> =
        map { turn ->
            ConversationTurn(
                userMessage = turn.userMessage.copy(attachments = emptyList()),
                assistantMessage = turn.assistantMessage?.copy(attachments = emptyList()),
                isCurrentTurn = false
            )
        }

    private fun sanitizeAssistantMessageForContext(message: MessageV2): MessageV2 {
        val sanitizedContent = stripAssistantErrorNote(message.semanticAssistantContent()).trimEnd()
            .withSourceContext(message.sourceMetadata)
        return if (sanitizedContent == message.content && message.activeRevisionIndex == ACTIVE_REVISION_LATEST) {
            message
        } else {
            message.copy(
                content = sanitizedContent,
                activeRevisionIndex = ACTIVE_REVISION_LATEST
            )
        }
    }

    private fun String.withSourceContext(sources: List<MessageSourceMetadata>): String {
        val sourceLines = sources
            .mapNotNull { source -> source.safeDedupeKey()?.let { key -> key to source } }
            .distinctBy { (key, _) -> key }
            .map { (_, source) -> source }
            .take(MAX_CONTEXT_SOURCES)
            .mapIndexed { index, source ->
                buildString {
                    val target = source.safeNavigationTarget() ?: return@buildString
                    append("${index + 1}. ")
                    append(source.title.trim().ifBlank { source.sourceToolName })
                    append(" - ")
                    append(
                        when (target) {
                            is SafeMessageSourceTarget.PublicUrl -> target.url
                            is SafeMessageSourceTarget.LocalApp ->
                                "app:${target.navigationTarget.name.lowercase()}:${target.entityId}"
                        }
                    )
                    source.snippet.trim().takeIf { it.isNotBlank() }?.let { snippet ->
                        append(" | ")
                        append(snippet.replace(Regex("\\s+"), " ").take(MAX_SOURCE_SNIPPET_CHARS))
                    }
                }
            }
        if (sourceLines.isEmpty()) return this

        val sourceBlock = buildString {
            appendLine("此回答引用的来源：")
            sourceLines.forEach { line -> appendLine(line) }
        }.trim()

        return listOf(this.trimEnd(), sourceBlock)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n")
    }

    private companion object {
        const val MIN_SUMMARY_TOKEN_BUDGET = 48
        const val MAX_CONTEXT_SOURCES = 5
        const val MAX_SOURCE_SNIPPET_CHARS = 240

        /**
         * Floor for the verbatim tail, so a checkpoint that has grown larger than the whole budget
         * cannot drive the raw allowance to zero and leave the model with a summary and nothing else.
         */
        const val MIN_RAW_TAIL_TOKENS = 512
    }
}
