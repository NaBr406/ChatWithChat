package cn.nabr.chatwithchat.data.database

import cn.nabr.chatwithchat.data.database.dao.ContextManagementDao
import cn.nabr.chatwithchat.data.database.entity.ContextCheckpointSegment
import cn.nabr.chatwithchat.data.database.entity.ContextNativeCompactionState
import cn.nabr.chatwithchat.data.database.entity.ContextRangeState

/**
 * In-memory stand-in for [ContextManagementDao].
 *
 * It models the three context tables the way SQLite does rather than the way that would be
 * convenient, because the repository under test leans on those exact semantics:
 *
 * - `MIN(segment_index)` over an empty result set is null, not zero, which is how "the edit landed in
 *   the still-raw tail" is distinguished from "the edit hit segment 0".
 * - REPLACE collapses rows on the composite primary key `chat_id + platform_uid + segment_index`, so
 *   re-appending a rebuilt link overwrites instead of duplicating.
 * - `invalidateFromSegment` is an UPDATE, so it keeps every body it invalidates.
 * - `markNativeUnsupported` is an UPDATE over three columns that silently does nothing when no row
 *   matches — which is exactly why the repository has to write a marker row itself.
 *
 * [replaceChainFrom] is deliberately *not* overridden: it has a body on the interface, so inheriting
 * it means the tests exercise the real delete-then-upsert transaction rather than a re-implementation.
 *
 * Write counters are exposed because several tests assert that nothing was written, which is
 * invisible without counting.
 */
internal class InMemoryContextManagementDao : ContextManagementDao {

    private val segmentRows = mutableListOf<ContextCheckpointSegment>()
    private val rangeRows = mutableListOf<ContextRangeState>()
    private val nativeRows = mutableListOf<ContextNativeCompactionState>()

    var deleteSegmentsFromCount = 0
        private set
    var markNativeUnsupportedCount = 0
        private set

    // ---------------------------------------------------------------- checkpoint chain

    override suspend fun getCheckpointChain(chatId: Int, platformUid: String): List<ContextCheckpointSegment> =
        segmentRows.filter { it.chatId == chatId && it.platformUid == platformUid }.sortedBy { it.segmentIndex }

    override suspend fun getCheckpointSegment(
        chatId: Int,
        platformUid: String,
        segmentIndex: Int
    ): ContextCheckpointSegment? = segmentRows.firstOrNull {
        it.chatId == chatId && it.platformUid == platformUid && it.segmentIndex == segmentIndex
    }

    override suspend fun upsertCheckpointSegment(segment: ContextCheckpointSegment) {
        segmentRows.removeAll {
            it.chatId == segment.chatId &&
                it.platformUid == segment.platformUid &&
                it.segmentIndex == segment.segmentIndex
        }
        segmentRows += segment
    }

    override suspend fun upsertCheckpointSegments(segments: List<ContextCheckpointSegment>) {
        segments.forEach { upsertCheckpointSegment(it) }
    }

    override suspend fun invalidateFromSegment(
        chatId: Int,
        platformUid: String,
        fromSegmentIndex: Int,
        invalidStatus: String,
        updatedAt: Long
    ) {
        for (index in segmentRows.indices) {
            val row = segmentRows[index]
            val matches = row.chatId == chatId &&
                row.platformUid == platformUid &&
                row.segmentIndex >= fromSegmentIndex
            if (matches) {
                segmentRows[index] = row.copy(status = invalidStatus, updatedAt = updatedAt)
            }
        }
    }

    override suspend fun findSegmentIndexCovering(chatId: Int, platformUid: String, userMessageId: Int): Int? =
        segmentRows
            .filter { it.chatId == chatId && it.platformUid == platformUid && it.endUserMessageId >= userMessageId }
            .minOfOrNull { it.segmentIndex }

    override suspend fun deleteSegmentsFrom(chatId: Int, platformUid: String, fromSegmentIndex: Int) {
        deleteSegmentsFromCount++
        segmentRows.removeAll {
            it.chatId == chatId && it.platformUid == platformUid && it.segmentIndex >= fromSegmentIndex
        }
    }

    override suspend fun clearCheckpointChain(chatId: Int, platformUid: String) {
        segmentRows.removeAll { it.chatId == chatId && it.platformUid == platformUid }
    }

    // ---------------------------------------------------------------- range

    override suspend fun getRange(chatId: Int, platformUid: String): ContextRangeState? =
        rangeRows.firstOrNull { it.chatId == chatId && it.platformUid == platformUid }

    override suspend fun upsertRange(range: ContextRangeState) {
        rangeRows.removeAll { it.chatId == range.chatId && it.platformUid == range.platformUid }
        rangeRows += range
    }

    override suspend fun clearRange(chatId: Int, platformUid: String) {
        rangeRows.removeAll { it.chatId == chatId && it.platformUid == platformUid }
    }

    // ---------------------------------------------------------------- native state

    override suspend fun getNativeState(chatId: Int, platformUid: String): ContextNativeCompactionState? =
        nativeRows.firstOrNull { it.chatId == chatId && it.platformUid == platformUid }

    override suspend fun upsertNativeState(state: ContextNativeCompactionState) {
        nativeRows.removeAll { it.chatId == state.chatId && it.platformUid == state.platformUid }
        nativeRows += state
    }

    override suspend fun clearNativeState(chatId: Int, platformUid: String) {
        nativeRows.removeAll { it.chatId == chatId && it.platformUid == platformUid }
    }

    override suspend fun markNativeUnsupported(chatId: Int, platformUid: String, updatedAt: Long) {
        markNativeUnsupportedCount++
        for (index in nativeRows.indices) {
            val row = nativeRows[index]
            if (row.chatId == chatId && row.platformUid == platformUid) {
                nativeRows[index] = row.copy(
                    nativeUnsupported = true,
                    isReadyForInput = false,
                    updatedAt = updatedAt
                )
            }
        }
    }
}
