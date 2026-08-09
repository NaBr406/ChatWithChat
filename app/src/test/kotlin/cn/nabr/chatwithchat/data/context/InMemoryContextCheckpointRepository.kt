package cn.nabr.chatwithchat.data.context

/**
 * In-memory stand-in for [ContextCheckpointRepository], shared by the scheduler and fold-service
 * tests.
 *
 * It models the storage honestly rather than conveniently: [appendSegment] really appends,
 * [invalidateFrom] really keeps bodies, and [replaceChainFrom] really refuses to touch anything
 * before its cut point. A fake that quietly let a caller violate one of those would make the tests
 * agree with each other while disagreeing with Room.
 *
 * Write counters are exposed because most of what these tests assert is that *nothing was written* —
 * a failed fold must leave storage untouched, and that is invisible without counting.
 */
internal class InMemoryContextCheckpointRepository(
    initialSegments: List<ConversationCheckpoint> = emptyList(),
    private var nativeState: OpenAINativeCompactionState? = null,
    private var range: ContextRange? = null
) : ContextCheckpointRepository {

    val segments = initialSegments.toMutableList()
    var appendCount = 0
        private set
    var replaceCount = 0
        private set
    var clearNativeStateCount = 0
        private set
    var markNativeUnsupportedCount = 0
        private set

    override suspend fun loadChain(chatRoomId: Int, platformUid: String): CheckpointChainView {
        val ordered = segmentsFor(chatRoomId, platformUid).sortedBy { it.segmentIndex }
        // Stops at the first non-VALID segment, exactly like the real query's consumer: a VALID
        // segment sitting behind an INVALID one is unusable, because its body summarised turns that
        // the invalid segment before it no longer describes correctly.
        val valid = ordered.takeWhile { it.status == CheckpointSegmentStatus.VALID }
        return CheckpointChainView(
            validSegments = valid,
            invalidatedFromSegmentIndex = ordered.getOrNull(valid.size)?.segmentIndex
        )
    }

    override suspend fun loadAllSegments(chatRoomId: Int, platformUid: String): List<ConversationCheckpoint> =
        segmentsFor(chatRoomId, platformUid).sortedBy { it.segmentIndex }

    override suspend fun appendSegment(segment: ConversationCheckpoint) {
        appendCount++
        segments.removeAll { it.matches(segment.chatRoomId, segment.platformUid, segment.segmentIndex) }
        segments += segment
    }

    override suspend fun invalidateFrom(chatRoomId: Int, platformUid: String, userMessageId: Int): Int? {
        val ordered = segmentsFor(chatRoomId, platformUid).sortedBy { it.segmentIndex }
        val first = ordered.firstOrNull { userMessageId <= it.endUserMessageId } ?: return null
        ordered.filter { it.segmentIndex >= first.segmentIndex }.forEach { stale ->
            val index = segments.indexOfFirst {
                it.matches(chatRoomId, platformUid, stale.segmentIndex)
            }
            segments[index] = segments[index].copy(status = CheckpointSegmentStatus.INVALID)
        }
        clearNativeState(chatRoomId, platformUid)
        return first.segmentIndex
    }

    override suspend fun replaceChainFrom(
        chatRoomId: Int,
        platformUid: String,
        fromSegmentIndex: Int,
        rebuilt: List<ConversationCheckpoint>
    ) {
        replaceCount++
        require(rebuilt.none { it.segmentIndex < fromSegmentIndex }) {
            "a rebuild may not rewrite the still-valid prefix"
        }
        segments.removeAll {
            it.chatRoomId == chatRoomId && it.platformUid == platformUid && it.segmentIndex >= fromSegmentIndex
        }
        segments += rebuilt
    }

    override suspend fun clearChain(chatRoomId: Int, platformUid: String) {
        segments.removeAll { it.chatRoomId == chatRoomId && it.platformUid == platformUid }
    }

    override suspend fun loadRange(chatRoomId: Int, platformUid: String): ContextRange =
        range ?: ContextRange.fullHistory(chatRoomId, platformUid)

    override suspend fun saveRange(range: ContextRange) {
        this.range = range
    }

    override suspend fun clearRange(chatRoomId: Int, platformUid: String) {
        range = null
    }

    override suspend fun loadNativeState(chatRoomId: Int, platformUid: String): OpenAINativeCompactionState? =
        nativeState?.takeIf { it.chatRoomId == chatRoomId && it.platformUid == platformUid }

    override suspend fun saveNativeState(state: OpenAINativeCompactionState) {
        nativeState = state
    }

    override suspend fun clearNativeState(chatRoomId: Int, platformUid: String) {
        clearNativeStateCount++
        nativeState = null
    }

    override suspend fun markNativeUnsupported(chatRoomId: Int, platformUid: String) {
        markNativeUnsupportedCount++
        val existing = nativeState
        nativeState = existing?.copy(nativeUnsupported = true, isReadyForInput = false)
            ?: OpenAINativeCompactionState(
                chatRoomId = chatRoomId,
                platformUid = platformUid,
                compactionItemsJson = "",
                boundaryUserMessageId = 0,
                sourceTurnsHash = "",
                estimatedTokens = 0,
                isReadyForInput = false,
                nativeUnsupported = true
            )
    }

    private fun segmentsFor(chatRoomId: Int, platformUid: String): List<ConversationCheckpoint> =
        segments.filter { it.chatRoomId == chatRoomId && it.platformUid == platformUid }

    private fun ConversationCheckpoint.matches(chatRoomId: Int, platformUid: String, segmentIndex: Int): Boolean =
        this.chatRoomId == chatRoomId && this.platformUid == platformUid && this.segmentIndex == segmentIndex
}
