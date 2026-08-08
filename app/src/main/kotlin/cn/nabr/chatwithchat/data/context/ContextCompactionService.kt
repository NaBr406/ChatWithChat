package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.token.TokenUsageEstimator
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Why a compaction attempt did not produce a checkpoint body. */
enum class ContextCompactionFailure(val code: String) {
    /** Nothing to fold. Not an error; the caller simply has no work to do. */
    NOTHING_TO_COMPACT("context_compaction_nothing_to_compact"),

    /**
     * The provider is not usable for an auxiliary call — disabled, missing model, URL or token. The
     * conversation itself still works; only folding is unavailable.
     */
    PROVIDER_NOT_ELIGIBLE("context_compaction_provider_not_eligible"),

    /**
     * The caller routed an official OpenAI model that passed [NativeCompactionModelGate] into the
     * app-side path. That model folds server-side and must never receive the app-side prompt, so
     * this is a wiring bug rather than a runtime condition.
     */
    NATIVE_PATH_REQUIRED("context_compaction_native_path_required"),

    CALL_FAILED("context_compaction_call_failed"),

    TIMED_OUT("context_compaction_timed_out"),

    /** The model answered, but with nothing that could be stored as a checkpoint. */
    INVALID_OUTPUT("context_compaction_invalid_output")
}

/**
 * Result of one compaction attempt.
 *
 * [Compacted] is the only outcome that may be written to the checkpoint chain. [Failed] always
 * carries [Failed.fallbackSummary], the deterministic extractive summary, so the caller can always
 * assemble a request — it just must not persist it: an extractive summary is a display-time
 * degradation, not a checkpoint.
 */
sealed interface ContextCompactionOutcome {

    data class Compacted(
        val body: String,
        val estimatedTokens: Int,
        val usage: TokenUsageRecord?,
        val requestPrompt: String,
        val followedFormat: Boolean
    ) : ContextCompactionOutcome

    data class Failed(
        val reason: ContextCompactionFailure,
        val fallbackSummary: String?,
        val detail: String? = null,
        val requestPrompt: String? = null
    ) : ContextCompactionOutcome
}

/**
 * App-side semantic compaction: one isolated LLM call that folds a checkpoint plus a batch of
 * complete turns into new checkpoint text.
 *
 * What this service deliberately does *not* do, each of which would be a correctness bug:
 *
 *  - It never enters the tool loop. There is no tool plumbing on [ContextCompactionModelClient].
 *  - It never triggers long-term memory learning. Memory is triggered by the *fold event* at the
 *    call site, never by this call's own traffic.
 *  - It never recurses. The payload is assembled and capped by [ContextCompactionPrompt]; a
 *    compaction request is never itself context-managed.
 *  - It never reuses the `SearchDecisionService` or `LlmMemoryIntelligence` call paths.
 *  - It never writes. Persistence belongs to the caller, and only after [Compacted].
 *
 * Failure, timeout and unusable output all degrade to the extractive summarizer rather than
 * propagating, because a conversation must always be sendable. Genuine cancellation is the one
 * exception and is rethrown: if the surrounding scope is gone there is nobody left to degrade for,
 * and swallowing it would break structured concurrency.
 */
class ContextCompactionService(
    private val modelClient: ContextCompactionModelClient
) {

    suspend fun compact(
        platform: PlatformV2,
        previousCheckpointBody: String?,
        turns: List<ConversationTurn>,
        budget: ContextBudget
    ): ContextCompactionOutcome {
        val tokenCounter = ContextTokenCounter.forPlatform(platform)
        val fallback = {
            ConversationContextSummarizer.build(
                turns = turns,
                maxTokens = budget.summaryTarget,
                maxTurns = FALLBACK_SUMMARY_MAX_TURNS,
                tokenCounter = tokenCounter
            )
        }

        if (turns.isEmpty()) {
            return ContextCompactionOutcome.Failed(ContextCompactionFailure.NOTHING_TO_COMPACT, null)
        }
        if (NativeCompactionModelGate.supportsServerSideCompaction(platform.compatibleType, platform.model)) {
            return ContextCompactionOutcome.Failed(
                reason = ContextCompactionFailure.NATIVE_PATH_REQUIRED,
                fallbackSummary = fallback()
            )
        }
        if (!platform.isEligibleForCompaction()) {
            return ContextCompactionOutcome.Failed(
                reason = ContextCompactionFailure.PROVIDER_NOT_ELIGIBLE,
                fallbackSummary = fallback()
            )
        }

        val systemPrompt = ContextCompactionPrompt.systemPrompt()
        val maxOutputTokens = maxOutputTokensFor(budget)
        val payloadBudget = payloadBudgetFor(budget, maxOutputTokens, systemPrompt, tokenCounter)
        val userPayload = ContextCompactionPrompt.buildUserPayload(
            previousCheckpointBody = previousCheckpointBody,
            turns = turns,
            maxTokens = payloadBudget,
            tokenCounter = tokenCounter
        )

        val timeoutSeconds = timeoutSecondsFor(platform)
        val response = try {
            withTimeoutOrNoLimit(timeoutSeconds) {
                modelClient.requestCompaction(
                    platform = platform,
                    systemPrompt = systemPrompt,
                    userPayload = userPayload,
                    maxOutputTokens = maxOutputTokens,
                    timeoutSeconds = timeoutSeconds
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            // Caught specifically, and never CancellationException at large: a timeout is this
            // service's own decision to stop waiting and is recoverable, whereas an outer
            // cancellation means the caller is gone.
            return ContextCompactionOutcome.Failed(
                reason = ContextCompactionFailure.TIMED_OUT,
                fallbackSummary = fallback(),
                detail = timeout.message,
                requestPrompt = systemPrompt
            )
        }

        val successful = response.getOrElse { error ->
            return ContextCompactionOutcome.Failed(
                reason = ContextCompactionFailure.CALL_FAILED,
                fallbackSummary = fallback(),
                detail = error.message?.trim()?.take(MAX_DETAIL_LENGTH),
                requestPrompt = systemPrompt
            )
        }

        val body = ContextCompactionPrompt.parse(
            rawOutput = successful.content,
            maxTokens = budget.summaryTarget,
            tokenCounter = tokenCounter
        ) ?: return ContextCompactionOutcome.Failed(
            reason = ContextCompactionFailure.INVALID_OUTPUT,
            fallbackSummary = fallback(),
            requestPrompt = systemPrompt
        )

        // Real provider usage when the provider reported it, estimated otherwise. Either way it is
        // recorded: compaction is real spend that belongs to no message row, and leaving it out
        // would make the token totals quietly understate what the feature costs.
        val usage = successful.usage?.toTokenUsageRecord(platform, COMPACTION_USAGE_LABEL)
            ?: TokenUsageEstimator.estimateRequestUsage(
                requestText = systemPrompt + "\n" + userPayload,
                outputText = successful.content,
                platform = platform,
                label = COMPACTION_USAGE_LABEL
            )

        return ContextCompactionOutcome.Compacted(
            body = body,
            estimatedTokens = tokenCounter.estimateText(body),
            usage = usage,
            requestPrompt = systemPrompt,
            followedFormat = ContextCompactionPrompt.hasExpectedSections(body)
        )
    }

    /**
     * `timeout == 0` means "no limit" throughout this codebase, and [withTimeout] cannot express
     * that, so the wrapper is skipped entirely in that case.
     */
    private suspend fun <T> withTimeoutOrNoLimit(timeoutSeconds: Int, block: suspend () -> T): T =
        if (timeoutSeconds <= 0) block() else withTimeout(timeoutSeconds * 1_000L) { block() }

    private fun maxOutputTokensFor(budget: ContextBudget): Int =
        (budget.summaryTarget * OUTPUT_HEADROOM_RATIO).toInt()
            .coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKENS)

    /**
     * The compaction call is an ordinary request against the same model, so its payload has to fit
     * the same window. Everything the request will carry besides the turns — the system prompt and
     * the room reserved for the answer — is subtracted first.
     */
    private fun payloadBudgetFor(
        budget: ContextBudget,
        maxOutputTokens: Int,
        systemPrompt: String,
        tokenCounter: ContextTokenCounter
    ): Int = (budget.effectiveInputBudget - maxOutputTokens - tokenCounter.estimateText(systemPrompt))
        .coerceAtLeast(MIN_PAYLOAD_TOKENS)

    /**
     * A user-facing chat timeout is tuned for first-token latency. Compaction reads a long payload
     * before it writes anything, so it gets a floor — while still honouring a deliberately longer
     * per-provider setting, and the codebase-wide `0 == no limit` convention.
     */
    private fun timeoutSecondsFor(platform: PlatformV2): Int =
        if (platform.timeout <= 0) 0 else maxOf(platform.timeout, MIN_TIMEOUT_SECONDS)

    private fun PlatformV2.isEligibleForCompaction(): Boolean =
        uid.isNotBlank() &&
            model.isNotBlank() &&
            apiUrl.isNotBlank() &&
            (compatibleType == ClientType.OLLAMA || !token.isNullOrBlank())

    private companion object {
        const val COMPACTION_USAGE_LABEL = "上下文压缩"
        const val FALLBACK_SUMMARY_MAX_TURNS = 8
        const val OUTPUT_HEADROOM_RATIO = 1.5
        const val MIN_OUTPUT_TOKENS = 1_024
        const val MAX_OUTPUT_TOKENS = 4_096
        const val MIN_PAYLOAD_TOKENS = 512
        const val MIN_TIMEOUT_SECONDS = 90
        const val MAX_DETAIL_LENGTH = 500
    }
}
