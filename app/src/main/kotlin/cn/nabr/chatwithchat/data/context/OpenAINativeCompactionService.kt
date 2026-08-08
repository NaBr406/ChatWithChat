package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseContextManagement
import cn.nabr.chatwithchat.data.dto.openai.request.ResponseInputItem
import cn.nabr.chatwithchat.data.dto.openai.request.ResponsesCompactRequest
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.network.OpenAIAPI
import cn.nabr.chatwithchat.data.network.OpenAICompactionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why native compaction could not be used for a request. */
enum class NativeCompactionFailure(val code: String) {
    /** Not an official OpenAI model, or the family is not known to support `context_management`. */
    NOT_SUPPORTED("native_compaction_not_supported"),

    /**
     * The provider answered `unsupported_parameter`. Distinct from [NOT_SUPPORTED] because it is
     * authoritative: the gate guessed wrong, and the answer should be remembered so the app stops
     * asking every turn.
     */
    REJECTED_BY_PROVIDER("native_compaction_rejected_by_provider"),

    CALL_FAILED("native_compaction_call_failed")
}

sealed interface NativeCompactionInitOutcome {

    /**
     * [outputWindowJson] is the provider's opaque canonical window, ready to be spliced verbatim
     * into the next request's input array. It is never parsed, trimmed or displayed.
     */
    data class Initialized(
        val outputWindowJson: String,
        val itemCount: Int
    ) : NativeCompactionInitOutcome

    data class Failed(
        val reason: NativeCompactionFailure,
        val detail: String? = null
    ) : NativeCompactionInitOutcome
}

/**
 * The OpenAI-native half of context management.
 *
 * Folding here is the provider's job, not the app's. In the ordinary case there is no extra network
 * call at all: [contextManagementFor] attaches `context_management` to the request the user was
 * already making, the server compacts mid-stream when the threshold is crossed, and the app's only
 * responsibility is to persist the opaque `compaction` item and replay it next time.
 *
 * The standalone `/v1/responses/compact` call in [initializeWindow] exists for exactly one
 * situation: a selected range that is already too large to submit as a single request, so there is
 * no request to attach compaction to. It is not a general summarizer and is never a fallback for a
 * failed server-side fold.
 *
 * This service must never see [ContextCompactionPrompt]. A model that folds server-side has its own
 * compaction semantics, and injecting an app-side summarization instruction on top would produce a
 * summary of a summary.
 */
class OpenAINativeCompactionService(
    private val openAIAPI: OpenAIAPI,
    private val clientMutex: Mutex = Mutex()
) {

    fun supports(platform: PlatformV2): Boolean =
        NativeCompactionModelGate.supportsServerSideCompaction(platform.compatibleType, platform.model)

    /**
     * The `context_management` array for an ordinary `/v1/responses` request, or null when this
     * provider or model must not receive one.
     *
     * Returns null for every client type other than [ClientType.OPENAI], including OpenAI-compatible
     * gateways reached through `CUSTOM`: sending an unknown parameter to a third-party gateway is a
     * hard failure, not a graceful degradation.
     */
    fun contextManagementFor(
        platform: PlatformV2,
        budget: ContextBudget,
        nativeUnsupported: Boolean
    ): List<ResponseContextManagement>? {
        if (nativeUnsupported || !supports(platform)) return null

        return listOf(ResponseContextManagement.compaction(compactThresholdFor(budget)))
    }

    /**
     * The threshold at which the server should fold, expressed in tokens of streamed context.
     *
     * Deliberately the app's own [ContextBudget.compactTrigger] rather than a provider default, so
     * both paths fold at the same point and switching a chat between an app-side and a native
     * provider does not visibly change when history stops being verbatim.
     */
    fun compactThresholdFor(budget: ContextBudget): Int = budget.compactTrigger

    /**
     * A compaction-enabled request must either omit `max_output_tokens` or leave at least
     * [ResponseContextManagement.MIN_MAX_OUTPUT_TOKENS_WITH_COMPACTION] of room; anything smaller is
     * rejected outright. Callers pass whatever cap they would otherwise have used and get back a
     * value that is safe to send — usually null, because omitting the cap is both simpler and what
     * the provider's own examples do.
     */
    fun maxOutputTokensWithCompaction(requestedMaxOutputTokens: Int?): Int? {
        val requested = requestedMaxOutputTokens ?: return null
        if (requested >= ResponseContextManagement.MIN_MAX_OUTPUT_TOKENS_WITH_COMPACTION) return requested

        // Raising a small cap to the minimum would silently let the model write far more than the
        // caller asked for, so the cap is dropped instead and the provider default applies.
        return null
    }

    /**
     * Initialises native state for a range too large to submit in one request.
     *
     * Nothing is persisted here: the caller writes the returned window only after this returns
     * [NativeCompactionInitOutcome.Initialized], which is what keeps a failed initialization from
     * leaving a half-built window that a later request would submit as if it were complete.
     */
    suspend fun initializeWindow(
        platform: PlatformV2,
        input: List<ResponseInputItem>
    ): NativeCompactionInitOutcome {
        if (!supports(platform)) {
            return NativeCompactionInitOutcome.Failed(NativeCompactionFailure.NOT_SUPPORTED)
        }
        if (input.isEmpty()) {
            return NativeCompactionInitOutcome.Failed(
                reason = NativeCompactionFailure.CALL_FAILED,
                detail = "empty_input"
            )
        }

        return try {
            val result = clientMutex.withLock {
                openAIAPI.setToken(platform.token)
                openAIAPI.setAPIUrl(platform.apiUrl)
                openAIAPI.compactResponses(
                    request = ResponsesCompactRequest(
                        model = platform.model,
                        input = input,
                        // Explicitly false: the compaction chain is replayed from local storage, so
                        // it must never depend on server-retained state.
                        store = false
                    ),
                    timeoutSeconds = platform.timeout
                )
            }
            NativeCompactionInitOutcome.Initialized(
                outputWindowJson = result.outputWindowJson,
                itemCount = result.outputItemCount
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (compaction: OpenAICompactionException) {
            NativeCompactionInitOutcome.Failed(
                reason = if (compaction.isUnsupportedParameter) {
                    NativeCompactionFailure.REJECTED_BY_PROVIDER
                } else {
                    NativeCompactionFailure.CALL_FAILED
                },
                detail = compaction.message?.take(MAX_DETAIL_LENGTH)
            )
        } catch (error: Throwable) {
            NativeCompactionInitOutcome.Failed(
                reason = NativeCompactionFailure.CALL_FAILED,
                detail = error.message?.take(MAX_DETAIL_LENGTH)
            )
        }
    }

    private companion object {
        const val MAX_DETAIL_LENGTH = 500
    }
}
