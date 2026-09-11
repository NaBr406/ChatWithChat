package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.dao.MessageV2Dao
import cn.nabr.chatwithchat.data.database.dao.PlatformV2Dao
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobStatus
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobType
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler
import cn.nabr.chatwithchat.data.memory.MemoryTurnBatchScheduler
import cn.nabr.chatwithchat.data.visual.VisualAssetRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * How one fold attempt ended, in the vocabulary the maintenance processor understands.
 *
 * Mirrors `MemoryBatchProcessResult` rather than reusing it, because `MemoryMaintenanceOutcome` is
 * file-private to the processor and a context feature should not start depending on memory's result
 * types just to report success.
 */
data class ContextFoldProcessResult(
    val status: String,
    val chatRoomId: Int = 0,
    val platformUid: String = "",
    val segmentIndex: Int = -1,
    val foldedTurnCount: Int = 0,
    val outcomeCode: String? = null
) {
    companion object {
        const val STATUS_SUCCEEDED = "succeeded"

        /** The batch was already folded, or the chain moved past it. Not an error. */
        const val STATUS_SUPERSEDED = "superseded"

        /** Retry will not help: the platform is gone, or the payload is unreadable. */
        const val STATUS_TERMINAL = "terminal"

        /** A dependency is missing or the provider is not usable for an auxiliary call. */
        const val STATUS_BLOCKED = "blocked"

        /** Transient: the call failed or timed out. */
        const val STATUS_RETRYABLE = "retryable"
    }
}

/**
 * Executes a queued [ContextFoldJobPayload]: re-read the batch, fold it, append one checkpoint
 * segment.
 *
 * Three rules govern everything here.
 *
 * **Nothing is written unless the fold succeeded.** There is no pending row to reconcile after a
 * crash, and a failed fold leaves the chain byte-identical to what it was. The extractive fallback
 * that [ContextCompactionService] returns on failure is deliberately discarded: it is a display-time
 * degradation the request assembler produces on its own, and storing it would turn a temporary
 * network failure into a permanently worse checkpoint.
 *
 * **The batch is verified before it is folded.** Turns are re-read from Room and re-hashed; a
 * mismatch means the conversation changed while the job sat in the queue, so the fold is abandoned
 * and the next request enqueues a fresh one against the current text.
 *
 * **This runs whether or not long-term memory is enabled.** Context management is a separate feature
 * that happens to share a queue — see [MemoryMaintenanceJobType.FOLD_CONTEXT_CHECKPOINT].
 */
class ContextFoldService(
    private val messageDao: MessageV2Dao,
    private val platformDao: PlatformV2Dao,
    private val contextBuilder: ContextBuilder,
    private val checkpointRepository: ContextCheckpointRepository,
    private val compactionService: ContextCompactionService,
    private val contextWindowResolver: ContextWindowResolver,
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    /**
     * Long-term memory's trigger, not a dependency of folding. Before this plan, context compaction
     * was what told memory a batch of turns had aged out; folding inherits that role so the signal
     * does not silently disappear. Null-safe because folding must keep working without it.
     */
    private val memoryTurnBatchScheduler: MemoryTurnBatchScheduler? = null,
    /**
     * Where "this pair ran out of retries" is recorded, so the degradation is observable instead of
     * being felt only as compaction quietly never happening again.
     */
    private val degradationRegistry: ContextFoldDegradationRegistry? = null,
    private val visualAssetRepository: VisualAssetRepository? = null,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    suspend fun process(job: MemoryMaintenanceJob): ContextFoldProcessResult {
        val payload = runCatching { json.decodeFromString<ContextFoldJobPayload>(job.payloadJson) }
            .getOrNull()
            ?: return dismiss(job, "context_fold_payload_unreadable")

        val platform = runCatching { platformDao.getPlatforms() }
            .getOrNull()
            ?.firstOrNull { it.uid == payload.platformUid }
            ?: return dismiss(job, "context_fold_platform_missing", payload)

        // A model that folds server-side must never receive the app-side prompt. Reaching this with
        // one queued means the gate changed under us — a stale job, not a condition to retry.
        if (NativeCompactionModelGate.supportsServerSideCompaction(platform.compatibleType, platform.model)) {
            return dismiss(job, "context_fold_native_path_required", payload)
        }

        val chain = checkpointRepository.loadChain(payload.chatRoomId, payload.platformUid)
        if (chain.nextSegmentIndex != payload.segmentIndex ||
            chain.validSegments.lastOrNull()?.sourceTurnsHash != payload.previousSegmentHash
        ) {
            // Another fold landed first, or an edit invalidated the prefix this segment was chained
            // to. Appending here would either duplicate a segment or chain onto a body that is no
            // longer there.
            return dismiss(job, "context_fold_chain_moved", payload, ContextFoldProcessResult.STATUS_SUPERSEDED)
        }

        val turns = loadBatchTurns(payload, platform.uid)
        if (turns.isEmpty()) {
            return dismiss(job, "context_fold_batch_empty", payload, ContextFoldProcessResult.STATUS_SUPERSEDED)
        }
        if (ContextTurnHasher.hashSegment(payload.previousSegmentHash, turns) != payload.sourceTurnsHash) {
            return dismiss(job, "context_fold_batch_changed", payload, ContextFoldProcessResult.STATUS_SUPERSEDED)
        }

        val budget = ContextBudget.of(
            modelContextWindow = contextWindowResolver.resolve(platform),
            policy = ProviderContextPolicy.forClientType(platform.compatibleType)
        )
        val outcome = try {
            compactionService.compact(
                platform = platform,
                previousCheckpointBody = chain.checkpointBody,
                turns = turns,
                budget = budget
            )
        } catch (cancellation: CancellationException) {
            // The worker is being torn down. Leave the job claimed and let the lease watchdog release
            // it: marking it failed here would burn a retry attempt on something that never ran.
            throw cancellation
        }

        return when (outcome) {
            is ContextCompactionOutcome.Compacted -> appendSegment(job, payload, turns, outcome)
            is ContextCompactionOutcome.Failed -> failed(job, payload, outcome)
        }
    }

    private suspend fun appendSegment(
        job: MemoryMaintenanceJob,
        payload: ContextFoldJobPayload,
        turns: List<ConversationTurn>,
        outcome: ContextCompactionOutcome.Compacted
    ): ContextFoldProcessResult {
        checkpointRepository.appendSegment(
            ConversationCheckpoint(
                chatRoomId = payload.chatRoomId,
                platformUid = payload.platformUid,
                segmentIndex = payload.segmentIndex,
                startUserMessageId = payload.startUserMessageId,
                endUserMessageId = payload.endUserMessageId,
                body = outcome.body,
                sourceTurnsHash = payload.sourceTurnsHash,
                estimatedTokens = outcome.estimatedTokens,
                compactionUsage = outcome.usage
            )
        )
        runCatching {
            visualAssetRepository?.markMessagesFolded(
                chatId = payload.chatRoomId,
                messageIds = turns.flatMap { turn ->
                    listOfNotNull(turn.userMessage.id, turn.assistantMessage?.id)
                }
            )
        }
        maintenanceScheduler.markSucceeded(job)
        // A pair that folds again is no longer degraded, whatever it did before. Cleared before the
        // memory nudge so a scheduler that throws cannot leave a healthy chat marked as failing.
        degradationRegistry?.clear(payload.chatRoomId, payload.platformUid)
        notifyMemoryOfFoldedBatch(payload)
        return ContextFoldProcessResult(
            status = ContextFoldProcessResult.STATUS_SUCCEEDED,
            chatRoomId = payload.chatRoomId,
            platformUid = payload.platformUid,
            segmentIndex = payload.segmentIndex,
            foldedTurnCount = turns.size
        )
    }

    /**
     * Tells long-term memory that a complete batch of turns has aged out of the raw context.
     *
     * Fires here and nowhere else, which is what makes it exactly-once per batch: a superseded or
     * failed fold never reaches this point, and a rebuild of the same context does not re-enter it
     * because the job is already gone. `markCompactionUrgent` gates on the memory switch itself, so
     * folding stays correct for users who have long-term memory turned off.
     *
     * Failures are swallowed on purpose — the checkpoint is already committed, and losing one memory
     * nudge is far better than reporting a successful fold as failed and folding the batch twice.
     */
    private suspend fun notifyMemoryOfFoldedBatch(payload: ContextFoldJobPayload) {
        val scheduler = memoryTurnBatchScheduler ?: return
        if (payload.chatRoomId <= 0 || payload.endUserMessageId <= 0) return
        try {
            scheduler.markCompactionUrgent(payload.chatRoomId, payload.endUserMessageId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // See the KDoc: the fold is already committed and must not be reported as failed.
        }
    }

    /**
     * Maps a compaction failure onto a job disposition. [ContextCompactionOutcome.Failed.fallbackSummary]
     * is intentionally ignored — see the class comment.
     */
    private suspend fun failed(
        job: MemoryMaintenanceJob,
        payload: ContextFoldJobPayload,
        outcome: ContextCompactionOutcome.Failed
    ): ContextFoldProcessResult {
        val reason = outcome.reason
        val detail = listOfNotNull(reason.code, outcome.detail).joinToString(separator = ": ")
        return when (reason) {
            ContextCompactionFailure.NOTHING_TO_COMPACT,
            ContextCompactionFailure.NATIVE_PATH_REQUIRED ->
                dismiss(job, reason.code, payload, ContextFoldProcessResult.STATUS_TERMINAL)

            ContextCompactionFailure.PROVIDER_NOT_ELIGIBLE -> {
                maintenanceScheduler.markBlockedDependency(job, reason.code)
                result(ContextFoldProcessResult.STATUS_BLOCKED, payload, reason.code)
            }

            ContextCompactionFailure.CALL_FAILED,
            ContextCompactionFailure.TIMED_OUT,
            ContextCompactionFailure.INVALID_OUTPUT -> {
                val failedJob = maintenanceScheduler.markFailedRetryable(job, detail)
                val exhausted = failedJob.status == MemoryMaintenanceJobStatus.FAILED_TERMINAL
                if (exhausted) {
                    // Retries are over for this job, and the next turn would otherwise queue an
                    // identical one against the same broken provider. Recording it here is what turns
                    // "compaction silently stopped" into a state something can look at, and what lets
                    // ContextFoldScheduler hold the next attempt back until the cooldown elapses.
                    degradationRegistry?.recordBackgroundFoldExhausted(
                        chatRoomId = payload.chatRoomId,
                        platformUid = payload.platformUid,
                        reason = reason.code
                    )
                }
                val status = if (exhausted) {
                    ContextFoldProcessResult.STATUS_TERMINAL
                } else {
                    ContextFoldProcessResult.STATUS_RETRYABLE
                }
                result(status, payload, reason.code)
            }
        }
    }

    /**
     * Rebuilds the batch from live rows.
     *
     * Grouping mirrors the chat screen exactly — ordered by `createdAt`, a null `platformType` opens a
     * turn, everything after it until the next user message is a candidate reply — because the hash
     * that guards this fold was computed over turns paired the same way. Pairing is delegated to
     * [ContextBuilder] for the same reason: assistant text is sanitized before it is hashed, so
     * reproducing that here by hand would make every hash comparison fail.
     */
    private suspend fun loadBatchTurns(
        payload: ContextFoldJobPayload,
        platformUid: String
    ): List<ConversationTurn> {
        val messages = runCatching { messageDao.loadMessages(payload.chatRoomId) }
            .getOrNull()
            ?.sortedBy { it.createdAt }
            ?: return emptyList()

        val userMessages = mutableListOf<MessageV2>()
        val assistantMessages = mutableListOf<MutableList<MessageV2>>()
        messages.forEach { message ->
            if (message.platformType == null) {
                userMessages.add(message)
                assistantMessages.add(mutableListOf())
            } else {
                assistantMessages.lastOrNull()?.add(message)
            }
        }

        runCatching { visualAssetRepository?.indexMessages(messages) }
        val visualAssets = runCatching {
            visualAssetRepository?.listAssets(chatId = payload.chatRoomId, limit = VISUAL_ASSET_CATALOG_LIMIT)
        }.getOrNull().orEmpty()
        return contextBuilder
            .buildFoldableTurns(userMessages, assistantMessages, platformUid, visualAssets)
            .filter { turn ->
                turn.userMessage.id >= payload.startUserMessageId &&
                    turn.userMessage.id <= payload.endUserMessageId
            }
    }

    private suspend fun dismiss(
        job: MemoryMaintenanceJob,
        reason: String,
        payload: ContextFoldJobPayload? = null,
        status: String = ContextFoldProcessResult.STATUS_TERMINAL
    ): ContextFoldProcessResult {
        maintenanceScheduler.markDismissed(job, reason)
        return result(status, payload, reason)
    }

    private fun result(
        status: String,
        payload: ContextFoldJobPayload?,
        outcomeCode: String?
    ): ContextFoldProcessResult = ContextFoldProcessResult(
        status = status,
        chatRoomId = payload?.chatRoomId ?: 0,
        platformUid = payload?.platformUid.orEmpty(),
        segmentIndex = payload?.segmentIndex ?: -1,
        outcomeCode = outcomeCode
    )

    private companion object {
        const val VISUAL_ASSET_CATALOG_LIMIT = 200
    }
}
