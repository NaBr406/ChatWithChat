package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobFamily
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobType
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceWorkEnqueuer
import java.time.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Turns "this conversation is getting long" into a queued background fold.
 *
 * Enqueuing is all this does. It never compacts, never calls a provider and never writes a
 * checkpoint, so it stays cheap enough to run alongside a send without delaying it — which is the
 * point of pre-folding at [ContextBudget.preFoldTrigger] rather than waiting for
 * [ContextBudget.compactTrigger] and having to fold while the user waits.
 *
 * It reuses the `memory_maintenance_job` queue wholesale: job-type registry, three-layer idempotency,
 * lease claim and watchdog, per-family WorkManager wake-up. See
 * [MemoryMaintenanceJobType.FOLD_CONTEXT_CHECKPOINT] for why a context job lives in a memory queue.
 */
class ContextFoldScheduler(
    private val maintenanceScheduler: MemoryMaintenanceScheduler,
    private val checkpointRepository: ContextCheckpointRepository,
    private val workEnqueuer: MemoryMaintenanceWorkEnqueuer,
    /**
     * Consulted so a pair whose folds have exhausted their retries stops queuing a fresh job every
     * turn. Null-safe: without it the scheduler behaves exactly as it did before, which is correct but
     * wasteful against a provider that is down.
     */
    private val degradationRegistry: ContextFoldDegradationRegistry? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val json: Json = Json { encodeDefaults = true }
) {

    /**
     * Queues a fold for the batch [result] identified as foldable, if there is one.
     *
     * Callers must not await this on the synchronous send path — it performs database work — and must
     * not treat a null return as an error. Null is the ordinary outcome: most turns are nowhere near
     * the pre-fold line.
     *
     * @return the queued job, or null when nothing needed folding or the request is ineligible.
     */
    suspend fun enqueueFoldIfNeeded(
        chatRoomId: Int,
        platform: PlatformV2,
        result: ContextBuildResult
    ): MemoryMaintenanceJob? {
        if (chatRoomId <= 0) return null
        if (!result.shouldEnqueueBackgroundFold) return null

        // The native path folds server-side inside the ordinary request. Queuing an app-side fold for
        // it would produce a second, competing summary of the same turns.
        if (NativeCompactionModelGate.supportsServerSideCompaction(platform.compatibleType, platform.model)) {
            return null
        }

        val turns = result.pendingFoldTurns
        val startUserMessageId = turns.firstOrNull()?.userMessage?.id ?: return null
        val endUserMessageId = turns.lastOrNull()?.userMessage?.id ?: return null
        if (startUserMessageId <= 0 || endUserMessageId <= 0) return null

        // Claimed last among the cheap checks, because claiming refreshes the cooldown: a request
        // that was going to bail out for some other reason must not consume the one probe the
        // cooldown window allows. The request still assembles correctly without a fold — the
        // deterministic extractive summary covers the same turns — so declining here degrades cost,
        // not correctness.
        if (degradationRegistry?.claimBackgroundFoldSlot(chatRoomId, platform.uid) == false) return null

        // Reloaded rather than taken from `result`, because the result may have been assembled a
        // moment ago and a fold queued since then would already have advanced the chain.
        val chain = checkpointRepository.loadChain(chatRoomId, platform.uid)
        val previousSegmentHash = chain.validSegments.lastOrNull()?.sourceTurnsHash
        val sourceTurnsHash = ContextTurnHasher.hashSegment(previousSegmentHash, turns)

        val payload = ContextFoldJobPayload(
            chatRoomId = chatRoomId,
            platformUid = platform.uid,
            segmentIndex = chain.nextSegmentIndex,
            startUserMessageId = startUserMessageId,
            endUserMessageId = endUserMessageId,
            previousSegmentHash = previousSegmentHash,
            sourceTurnsHash = sourceTurnsHash,
            createdAt = clock.instant().epochSecond
        )

        // The hash is part of the key, so re-sending the same turn twice dedupes onto one job while a
        // genuinely different batch still gets its own.
        val job = maintenanceScheduler.enqueue(
            type = MemoryMaintenanceJobType.FOLD_CONTEXT_CHECKPOINT,
            idempotencyKey = idempotencyKey(payload),
            payloadJson = json.encodeToString(payload)
        )
        workEnqueuer.enqueueWork(MemoryMaintenanceJobFamily.SEMANTIC)
        return job
    }

    private fun idempotencyKey(payload: ContextFoldJobPayload): String = buildString {
        append("context_fold:")
        append(payload.chatRoomId)
        append(':')
        append(payload.platformUid)
        append(':')
        append(payload.segmentIndex)
        append(':')
        append(payload.sourceTurnsHash)
    }
}
