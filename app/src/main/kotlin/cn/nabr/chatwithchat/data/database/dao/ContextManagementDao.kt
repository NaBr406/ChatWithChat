package cn.nabr.chatwithchat.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import cn.nabr.chatwithchat.data.database.entity.ContextCheckpointSegment
import cn.nabr.chatwithchat.data.database.entity.ContextNativeCompactionState
import cn.nabr.chatwithchat.data.database.entity.ContextRangeState

/**
 * Storage for the three context-management tables.
 *
 * Every query is scoped by `chat_id + platform_uid`, never by `chat_id` alone: two providers used in
 * the same chat must not see each other's checkpoint chain, range, or native state.
 */
@Dao
interface ContextManagementDao {

    // ---------------------------------------------------------------- checkpoint chain

    @Query(
        """
        SELECT * FROM context_checkpoint_segment
        WHERE chat_id = :chatId AND platform_uid = :platformUid
        ORDER BY segment_index ASC
        """
    )
    suspend fun getCheckpointChain(chatId: Int, platformUid: String): List<ContextCheckpointSegment>

    @Query(
        """
        SELECT * FROM context_checkpoint_segment
        WHERE chat_id = :chatId AND platform_uid = :platformUid AND segment_index = :segmentIndex
        LIMIT 1
        """
    )
    suspend fun getCheckpointSegment(
        chatId: Int,
        platformUid: String,
        segmentIndex: Int
    ): ContextCheckpointSegment?

    /**
     * A segment is only ever written after the compaction that produced it succeeded, so REPLACE is
     * safe: it either appends a new link or overwrites one being rebuilt.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpointSegment(segment: ContextCheckpointSegment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCheckpointSegments(segments: List<ContextCheckpointSegment>)

    /**
     * Lazy invalidation. Marks segment [fromSegmentIndex] and every later segment INVALID without
     * deleting a single body: an invalid body is still a correct summary of what it originally
     * folded, so keeping it lets a later rebuild proceed incrementally and guarantees nothing is lost
     * if that rebuild fails.
     */
    @Query(
        """
        UPDATE context_checkpoint_segment
        SET status = :invalidStatus, updated_at = :updatedAt
        WHERE chat_id = :chatId AND platform_uid = :platformUid AND segment_index >= :fromSegmentIndex
        """
    )
    suspend fun invalidateFromSegment(
        chatId: Int,
        platformUid: String,
        fromSegmentIndex: Int,
        invalidStatus: String,
        updatedAt: Long
    )

    /**
     * Lowest segment index whose folded range covers [userMessageId] — the segment `K` an edit at
     * that message invalidates.
     */
    @Query(
        """
        SELECT MIN(segment_index) FROM context_checkpoint_segment
        WHERE chat_id = :chatId AND platform_uid = :platformUid AND end_user_message_id >= :userMessageId
        """
    )
    suspend fun findSegmentIndexCovering(
        chatId: Int,
        platformUid: String,
        userMessageId: Int
    ): Int?

    @Query(
        """
        DELETE FROM context_checkpoint_segment
        WHERE chat_id = :chatId AND platform_uid = :platformUid AND segment_index >= :fromSegmentIndex
        """
    )
    suspend fun deleteSegmentsFrom(chatId: Int, platformUid: String, fromSegmentIndex: Int)

    /**
     * Commits a completed rebuild: drops the stale tail and writes the new one in a single
     * transaction, so a crash midway can never leave the chain with a hole in it.
     *
     * [rebuilt] must start at [fromSegmentIndex]; segments before it are untouched, which is what
     * keeps a failed rebuild from destroying the still-valid prefix.
     */
    @Transaction
    suspend fun replaceChainFrom(
        chatId: Int,
        platformUid: String,
        fromSegmentIndex: Int,
        rebuilt: List<ContextCheckpointSegment>
    ) {
        deleteSegmentsFrom(chatId, platformUid, fromSegmentIndex)
        if (rebuilt.isNotEmpty()) {
            upsertCheckpointSegments(rebuilt)
        }
    }

    @Query("DELETE FROM context_checkpoint_segment WHERE chat_id = :chatId AND platform_uid = :platformUid")
    suspend fun clearCheckpointChain(chatId: Int, platformUid: String)

    // ---------------------------------------------------------------- range

    @Query(
        """
        SELECT * FROM context_range_state
        WHERE chat_id = :chatId AND platform_uid = :platformUid
        LIMIT 1
        """
    )
    suspend fun getRange(chatId: Int, platformUid: String): ContextRangeState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRange(range: ContextRangeState)

    @Query("DELETE FROM context_range_state WHERE chat_id = :chatId AND platform_uid = :platformUid")
    suspend fun clearRange(chatId: Int, platformUid: String)

    // ---------------------------------------------------------------- native state

    @Query(
        """
        SELECT * FROM context_native_compaction_state
        WHERE chat_id = :chatId AND platform_uid = :platformUid
        LIMIT 1
        """
    )
    suspend fun getNativeState(chatId: Int, platformUid: String): ContextNativeCompactionState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNativeState(state: ContextNativeCompactionState)

    /**
     * Native state is *cleared*, never marked invalid: unlike an app-side body it carries no reusable
     * meaning once stale, and the next ordinary `/v1/responses` call rebuilds it server-side. This is
     * why invalidation here never needs a synchronous `/v1/responses/compact` call.
     */
    @Query(
        """
        DELETE FROM context_native_compaction_state
        WHERE chat_id = :chatId AND platform_uid = :platformUid
        """
    )
    suspend fun clearNativeState(chatId: Int, platformUid: String)

    /**
     * Records that the provider rejected `context_management` for this model. Kept as an update on a
     * cleared row rather than a delete so the app stops retrying every turn.
     */
    @Query(
        """
        UPDATE context_native_compaction_state
        SET native_unsupported = 1, is_ready_for_input = 0, updated_at = :updatedAt
        WHERE chat_id = :chatId AND platform_uid = :platformUid
        """
    )
    suspend fun markNativeUnsupported(chatId: Int, platformUid: String, updatedAt: Long)
}
