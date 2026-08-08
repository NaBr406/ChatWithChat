package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.dao.ContextManagementDao
import cn.nabr.chatwithchat.data.database.entity.ContextCheckpointSegment
import cn.nabr.chatwithchat.data.database.entity.ContextNativeCompactionState
import cn.nabr.chatwithchat.data.database.entity.ContextRangeState

/**
 * Deliberately not `@Inject`-annotated: [nowSeconds] is a defaulted lambda, and Dagger ignores
 * default values, so it would try to inject a `Function0<Long>` and fail. Construction happens in
 * `ContextManagementModule`, which also keeps the clock swappable for tests.
 */
class ContextCheckpointRepositoryImpl(
    private val dao: ContextManagementDao,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) : ContextCheckpointRepository {

    override suspend fun loadChain(chatRoomId: Int, platformUid: String): CheckpointChainView {
        val segments = loadAllSegments(chatRoomId, platformUid)
        // Stop at the first non-VALID segment rather than filtering: a later VALID segment was built
        // on top of an invalidated one, so it cannot be trusted even though its own status says so.
        val valid = segments.takeWhile { it.status == CheckpointSegmentStatus.VALID }
        val invalidatedFrom = segments.getOrNull(valid.size)?.segmentIndex

        return CheckpointChainView(
            validSegments = valid,
            invalidatedFromSegmentIndex = invalidatedFrom
        )
    }

    override suspend fun loadAllSegments(
        chatRoomId: Int,
        platformUid: String
    ): List<ConversationCheckpoint> = dao.getCheckpointChain(chatRoomId, platformUid).map { it.toDomain() }

    override suspend fun appendSegment(segment: ConversationCheckpoint) {
        dao.upsertCheckpointSegment(segment.toEntity())
    }

    override suspend fun invalidateFrom(
        chatRoomId: Int,
        platformUid: String,
        userMessageId: Int
    ): Int? {
        // Native state has no partial-invalidation story, so it goes regardless of whether the edit
        // landed inside an already-folded segment.
        clearNativeState(chatRoomId, platformUid)

        val affected = dao.findSegmentIndexCovering(chatRoomId, platformUid, userMessageId)
            ?: return null

        dao.invalidateFromSegment(
            chatId = chatRoomId,
            platformUid = platformUid,
            fromSegmentIndex = affected,
            invalidStatus = CheckpointSegmentStatus.INVALID.name,
            updatedAt = nowSeconds()
        )
        return affected
    }

    override suspend fun replaceChainFrom(
        chatRoomId: Int,
        platformUid: String,
        fromSegmentIndex: Int,
        rebuilt: List<ConversationCheckpoint>
    ) {
        require(rebuilt.all { it.segmentIndex >= fromSegmentIndex }) {
            "rebuilt segments must not reach back before fromSegmentIndex($fromSegmentIndex)"
        }
        dao.replaceChainFrom(
            chatId = chatRoomId,
            platformUid = platformUid,
            fromSegmentIndex = fromSegmentIndex,
            rebuilt = rebuilt.map { it.toEntity() }
        )
    }

    override suspend fun clearChain(chatRoomId: Int, platformUid: String) {
        dao.clearCheckpointChain(chatRoomId, platformUid)
    }

    override suspend fun loadRange(chatRoomId: Int, platformUid: String): ContextRange =
        dao.getRange(chatRoomId, platformUid)
            ?.let {
                ContextRange(
                    chatRoomId = it.chatId,
                    platformUid = it.platformUid,
                    startUserMessageId = it.startUserMessageId
                )
            }
            ?: ContextRange.fullHistory(chatRoomId, platformUid)

    override suspend fun saveRange(range: ContextRange) {
        dao.upsertRange(
            ContextRangeState(
                chatId = range.chatRoomId,
                platformUid = range.platformUid,
                startUserMessageId = range.startUserMessageId,
                // Null is the current sentinel: V1's picker is always "from turn X to now".
                endUserMessageId = null,
                updatedAt = nowSeconds()
            )
        )
    }

    override suspend fun clearRange(chatRoomId: Int, platformUid: String) {
        dao.clearRange(chatRoomId, platformUid)
    }

    override suspend fun loadNativeState(
        chatRoomId: Int,
        platformUid: String
    ): OpenAINativeCompactionState? = dao.getNativeState(chatRoomId, platformUid)?.toDomain()

    override suspend fun saveNativeState(state: OpenAINativeCompactionState) {
        dao.upsertNativeState(
            ContextNativeCompactionState(
                chatId = state.chatRoomId,
                platformUid = state.platformUid,
                compactionItemsJson = state.compactionItemsJson,
                boundaryUserMessageId = state.boundaryUserMessageId,
                sourceTurnsHash = state.sourceTurnsHash,
                estimatedTokens = state.estimatedTokens,
                isReadyForInput = state.isReadyForInput,
                nativeUnsupported = state.nativeUnsupported,
                updatedAt = nowSeconds()
            )
        )
    }

    override suspend fun clearNativeState(chatRoomId: Int, platformUid: String) {
        dao.clearNativeState(chatRoomId, platformUid)
    }

    override suspend fun markNativeUnsupported(chatRoomId: Int, platformUid: String) {
        val now = nowSeconds()
        val existing = dao.getNativeState(chatRoomId, platformUid)
        if (existing == null) {
            // No window was ever stored — the rejection came from an ordinary request. A marker row
            // is still written, otherwise the gate would re-attempt context_management every turn.
            dao.upsertNativeState(
                ContextNativeCompactionState(
                    chatId = chatRoomId,
                    platformUid = platformUid,
                    compactionItemsJson = "",
                    boundaryUserMessageId = 0,
                    sourceTurnsHash = "",
                    estimatedTokens = 0,
                    isReadyForInput = false,
                    nativeUnsupported = true,
                    updatedAt = now
                )
            )
        } else {
            dao.markNativeUnsupported(chatRoomId, platformUid, now)
        }
    }

    private fun ContextCheckpointSegment.toDomain(): ConversationCheckpoint = ConversationCheckpoint(
        chatRoomId = chatId,
        platformUid = platformUid,
        segmentIndex = segmentIndex,
        startUserMessageId = startUserMessageId,
        endUserMessageId = endUserMessageId,
        body = body,
        sourceTurnsHash = sourceTurnsHash,
        estimatedTokens = estimatedTokens,
        compactionUsage = compactionUsage,
        // An unrecognised status is treated as INVALID rather than VALID: the only way it can occur
        // is a downgrade or a hand-edited row, and reusing a body of unknown provenance is worse
        // than replaying the tail raw.
        status = CheckpointSegmentStatus.entries.firstOrNull { it.name == status } ?: CheckpointSegmentStatus.INVALID,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ConversationCheckpoint.toEntity(): ContextCheckpointSegment = ContextCheckpointSegment(
        chatId = chatRoomId,
        platformUid = platformUid,
        segmentIndex = segmentIndex,
        startUserMessageId = startUserMessageId,
        endUserMessageId = endUserMessageId,
        body = body,
        sourceTurnsHash = sourceTurnsHash,
        estimatedTokens = estimatedTokens,
        compactionUsage = compactionUsage,
        status = status.name,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ContextNativeCompactionState.toDomain(): OpenAINativeCompactionState =
        OpenAINativeCompactionState(
            chatRoomId = chatId,
            platformUid = platformUid,
            compactionItemsJson = compactionItemsJson,
            boundaryUserMessageId = boundaryUserMessageId,
            sourceTurnsHash = sourceTurnsHash,
            estimatedTokens = estimatedTokens,
            isReadyForInput = isReadyForInput,
            nativeUnsupported = nativeUnsupported,
            updatedAt = updatedAt
        )
}
