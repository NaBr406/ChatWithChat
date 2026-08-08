package cn.nabr.chatwithchat.data.context

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * One chat + provider pair whose background fold has exhausted its automatic retries.
 *
 * [occurrenceCount] survives the cooldown deliberately: a pair that keeps burning through the retry
 * ceiling is a different problem from one that failed once during a flight, and collapsing the two
 * into a boolean would hide it.
 */
data class ContextFoldDegradation(
    val chatRoomId: Int,
    val platformUid: String,
    val reason: String,
    val recordedAtEpochSecond: Long,
    val occurrenceCount: Int
)

/**
 * Remembers which chat + provider pairs are currently running without a working background fold.
 *
 * A fold job that fails is retried by [cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler]
 * until its family's ceiling, and then stops. Without anything watching that, the chat would keep
 * enqueuing a fresh job on every single turn — each new batch hashes differently, so the queue never
 * dedupes them — and every one of those jobs would walk the same three attempts into the same
 * provider failure. The user would never see an error, because a failed fold is invisible by design,
 * and the app would quietly spend request budget forever.
 *
 * So exhaustion is recorded here, and [claimBackgroundFoldSlot] holds new folds back for
 * [cooldownSeconds]. The chat is not broken while it waits: an unfolded range still assembles
 * correctly through [ContextBuilder]'s deterministic extractive summary, which is exactly the
 * synchronous fallback the plan degrades to. Compaction simply stops being semantic until a probe
 * succeeds.
 *
 * The cooldown expires rather than latching, because the usual cause is transient — the provider was
 * down, the key had expired, the device was on a captive portal — and a permanent stop would mean one
 * bad afternoon disabled semantic compaction for that chat until the app was reinstalled.
 *
 * Deliberately in-memory. This is telemetry plus a rate limit, not state a request depends on; losing
 * it on process death costs at most one extra round of retries, whereas a table would need a
 * migration and a cleanup story for chats that no longer exist.
 */
class ContextFoldDegradationRegistry(
    private val clock: Clock = Clock.systemUTC(),
    private val cooldownSeconds: Long = DEFAULT_COOLDOWN_SECONDS
) {

    private val entries = ConcurrentHashMap<String, ContextFoldDegradation>()

    /**
     * Records that the background fold for this pair ran out of automatic attempts.
     *
     * Called only after the scheduler has already moved the job to a terminal status — this does not
     * decide when retries stop, it observes that they did.
     */
    fun recordBackgroundFoldExhausted(
        chatRoomId: Int,
        platformUid: String,
        reason: String
    ): ContextFoldDegradation {
        val now = clock.instant().epochSecond
        val updated = entries.compute(keyOf(chatRoomId, platformUid)) { _, existing ->
            ContextFoldDegradation(
                chatRoomId = chatRoomId,
                platformUid = platformUid,
                reason = reason,
                recordedAtEpochSecond = now,
                occurrenceCount = (existing?.occurrenceCount ?: 0) + 1
            )
        }
        return checkNotNull(updated)
    }

    /**
     * Clears the degradation after a fold succeeds, so the next failure starts from a clean count.
     */
    fun clear(chatRoomId: Int, platformUid: String) {
        entries.remove(keyOf(chatRoomId, platformUid))
    }

    /**
     * Whether a fold may be queued for this pair right now, refreshing the cooldown when it may.
     *
     * Read and refresh are one operation on purpose: two sends racing during the same cooldown must
     * not both be told yes, or the probe that the cooldown exists to rate-limit would fire twice.
     *
     * @return true when nothing is degraded, or when the cooldown has elapsed and this caller is the
     *   one probe let through.
     */
    fun claimBackgroundFoldSlot(chatRoomId: Int, platformUid: String): Boolean {
        var allowed = true
        entries.computeIfPresent(keyOf(chatRoomId, platformUid)) { _, existing ->
            val now = clock.instant().epochSecond
            if (now - existing.recordedAtEpochSecond < cooldownSeconds) {
                allowed = false
                existing
            } else {
                existing.copy(recordedAtEpochSecond = now)
            }
        }
        return allowed
    }

    fun current(chatRoomId: Int, platformUid: String): ContextFoldDegradation? =
        entries[keyOf(chatRoomId, platformUid)]

    /** Every pair currently degraded. Exists so the state is inspectable rather than merely felt. */
    fun snapshot(): List<ContextFoldDegradation> = entries.values.sortedWith(
        compareBy({ it.chatRoomId }, { it.platformUid })
    )

    private fun keyOf(chatRoomId: Int, platformUid: String): String = "$chatRoomId:$platformUid"

    companion object {
        /**
         * Long enough that a provider outage is not probed once a minute, short enough that a user who
         * fixes an expired key gets semantic compaction back within one sitting.
         */
        const val DEFAULT_COOLDOWN_SECONDS: Long = 30 * 60L
    }
}
