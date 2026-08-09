package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.InMemoryContextManagementDao
import cn.nabr.chatwithchat.data.database.entity.ContextCheckpointSegment
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [ContextCheckpointRepositoryImpl].
 *
 * The DAO is faked rather than run against Room, because these are JVM unit tests. The fake mirrors
 * SQLite semantics deliberately — `MIN(segment_index)` over no rows yields null, REPLACE collapses on
 * the composite primary key, and the `markNativeUnsupported` UPDATE touches only its three columns.
 * A fake that "helpfully" diverged from those would let the tests agree with each other while
 * disagreeing with the database.
 *
 * The clock is a mutable seam so the tests can prove which writes are stamped by the repository and
 * which pass a caller-supplied timestamp straight through.
 */
class ContextCheckpointRepositoryImplTest {

    // ------------------------------------------------------------------ chain reads

    @Test
    fun `an untouched chat reports an empty chain rather than failing`() = runBlocking {
        val fixture = Fixture()

        val view = fixture.repository.loadChain(CHAT, PLATFORM_A)

        assertTrue(view.isEmpty)
        assertNull(view.invalidatedFromSegmentIndex)
        assertNull(view.checkpointBody)
        assertNull(view.foldedThroughUserMessageId)
        assertEquals(0, view.nextSegmentIndex)
    }

    @Test
    fun `a fully valid chain is usable end to end`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, body = "first"))
        fixture.repository.appendSegment(checkpoint(segmentIndex = 1, start = 13, end = 15, body = "second"))

        val view = fixture.repository.loadChain(CHAT, PLATFORM_A)

        assertEquals(listOf(0, 1), view.validSegments.map { it.segmentIndex })
        assertNull(view.invalidatedFromSegmentIndex)
        assertEquals("first\n\nsecond", view.checkpointBody)
        assertEquals(15, view.foldedThroughUserMessageId)
        assertEquals(2, view.nextSegmentIndex)
    }

    /**
     * The load stops at the first non-VALID link instead of filtering. Segment 2 was folded on top of
     * segment 1's body, so once segment 1 is invalid segment 2 is untrustworthy no matter what its own
     * status column says.
     */
    @Test
    fun `a valid segment sitting behind an invalid one is still excluded`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, body = "first"))
        fixture.repository.appendSegment(
            checkpoint(
                segmentIndex = 1,
                start = 13,
                end = 15,
                body = "second",
                status = CheckpointSegmentStatus.INVALID
            )
        )
        fixture.repository.appendSegment(checkpoint(segmentIndex = 2, start = 16, end = 18, body = "third"))

        val view = fixture.repository.loadChain(CHAT, PLATFORM_A)

        assertEquals(listOf(0), view.validSegments.map { it.segmentIndex })
        assertEquals(1, view.invalidatedFromSegmentIndex)
        assertEquals("first", view.checkpointBody)
        assertEquals(12, view.foldedThroughUserMessageId)
    }

    @Test
    fun `a chain invalidated from its head is entirely unusable`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(
            checkpoint(segmentIndex = 0, start = 10, end = 12, status = CheckpointSegmentStatus.INVALID)
        )
        fixture.repository.appendSegment(
            checkpoint(segmentIndex = 1, start = 13, end = 15, status = CheckpointSegmentStatus.INVALID)
        )

        val view = fixture.repository.loadChain(CHAT, PLATFORM_A)

        assertTrue(view.isEmpty)
        assertEquals(0, view.invalidatedFromSegmentIndex)
        assertEquals(0, view.nextSegmentIndex)
    }

    /** A fold in flight is not usable yet, so PENDING closes the usable prefix exactly like INVALID. */
    @Test
    fun `a pending segment closes the usable prefix without being an error`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, body = "first"))
        fixture.repository.appendSegment(
            checkpoint(segmentIndex = 1, start = 13, end = 15, status = CheckpointSegmentStatus.PENDING)
        )

        val view = fixture.repository.loadChain(CHAT, PLATFORM_A)

        assertEquals(listOf(0), view.validSegments.map { it.segmentIndex })
        assertEquals(1, view.invalidatedFromSegmentIndex)
    }

    @Test
    fun `segments are ordered by index no matter what order they were written in`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 2, start = 16, end = 18, body = "third"))
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, body = "first"))
        fixture.repository.appendSegment(checkpoint(segmentIndex = 1, start = 13, end = 15, body = "second"))

        val view = fixture.repository.loadChain(CHAT, PLATFORM_A)

        assertEquals(listOf(0, 1, 2), view.validSegments.map { it.segmentIndex })
        assertEquals("first\n\nsecond\n\nthird", view.checkpointBody)
    }

    /** [ContextCheckpointRepository.loadAllSegments] is the rebuild path's view, so it hides nothing. */
    @Test
    fun `loading all segments returns the invalidated tail as well`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12))
        fixture.repository.appendSegment(
            checkpoint(segmentIndex = 1, start = 13, end = 15, status = CheckpointSegmentStatus.INVALID)
        )

        val all = fixture.repository.loadAllSegments(CHAT, PLATFORM_A)

        assertEquals(2, all.size)
        assertEquals(CheckpointSegmentStatus.VALID, all[0].status)
        assertEquals(CheckpointSegmentStatus.INVALID, all[1].status)
    }

    /**
     * A status string the enum does not know can only come from a downgrade or a hand-edited row.
     * Reusing a body of unknown provenance is worse than replaying the tail raw, so it reads as
     * INVALID rather than defaulting to usable.
     */
    @Test
    fun `an unrecognised status is read as invalid rather than valid`() = runBlocking {
        val fixture = Fixture()
        fixture.dao.upsertCheckpointSegment(
            entity(segmentIndex = 0, start = 10, end = 12).copy(status = "ARCHIVED_BY_A_FUTURE_VERSION")
        )

        val all = fixture.repository.loadAllSegments(CHAT, PLATFORM_A)
        val view = fixture.repository.loadChain(CHAT, PLATFORM_A)

        assertEquals(CheckpointSegmentStatus.INVALID, all.single().status)
        assertTrue(view.isEmpty)
        assertEquals(0, view.invalidatedFromSegmentIndex)
    }

    // ------------------------------------------------------------------ chain writes

    @Test
    fun `an appended segment round trips every field including its compaction cost`() = runBlocking {
        val fixture = Fixture()
        val usage = TokenUsageRecord(inputTokens = 1_200, outputTokens = 300, isEstimated = false, model = "gpt-5")
        val segment = checkpoint(segmentIndex = 0, start = 10, end = 14, body = "folded").copy(
            sourceTurnsHash = "hash-abc",
            estimatedTokens = 420,
            compactionUsage = usage,
            createdAt = 1_000L,
            updatedAt = 1_000L
        )

        fixture.repository.appendSegment(segment)

        assertEquals(segment, fixture.repository.loadAllSegments(CHAT, PLATFORM_A).single())
    }

    /** REPLACE on `chat_id + platform_uid + segment_index`: a rebuilt link overwrites, never duplicates. */
    @Test
    fun `re-appending the same index overwrites the link instead of duplicating it`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, body = "stale"))

        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, body = "rebuilt"))

        val stored = fixture.repository.loadAllSegments(CHAT, PLATFORM_A).single()
        assertEquals("rebuilt", stored.body)
    }

    @Test
    fun `clearing a chain leaves the other provider's chain in the same chat alone`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12))
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, platformUid = PLATFORM_B))

        fixture.repository.clearChain(CHAT, PLATFORM_A)

        assertTrue(fixture.repository.loadAllSegments(CHAT, PLATFORM_A).isEmpty())
        assertEquals(1, fixture.repository.loadAllSegments(CHAT, PLATFORM_B).size)
    }

    // ------------------------------------------------------------------ invalidation

    @Test
    fun `an edit inside a folded segment invalidates it and everything after it`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, body = "first"))
        fixture.repository.appendSegment(checkpoint(segmentIndex = 1, start = 13, end = 15, body = "second"))
        fixture.repository.appendSegment(checkpoint(segmentIndex = 2, start = 16, end = 18, body = "third"))

        val affected = fixture.repository.invalidateFrom(CHAT, PLATFORM_A, userMessageId = 14)

        assertEquals(1, affected)
        val statuses = fixture.repository.loadAllSegments(CHAT, PLATFORM_A).map { it.status }
        assertEquals(
            listOf(
                CheckpointSegmentStatus.VALID,
                CheckpointSegmentStatus.INVALID,
                CheckpointSegmentStatus.INVALID
            ),
            statuses
        )
    }

    /**
     * Invalidation keeps every body. An invalid body is still a correct summary of what it originally
     * folded, so keeping it lets a later rebuild proceed incrementally and guarantees nothing is lost
     * if that rebuild fails.
     */
    @Test
    fun `invalidation keeps the bodies it invalidated`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, body = "first"))
        fixture.repository.appendSegment(checkpoint(segmentIndex = 1, start = 13, end = 15, body = "second"))

        fixture.repository.invalidateFrom(CHAT, PLATFORM_A, userMessageId = 13)

        assertEquals(
            listOf("first", "second"),
            fixture.repository.loadAllSegments(CHAT, PLATFORM_A).map { it.body }
        )
    }

    /** The boundary turn belongs to the segment that folded it, so editing it invalidates that segment. */
    @Test
    fun `editing a segment's own boundary turn invalidates that segment`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12))
        fixture.repository.appendSegment(checkpoint(segmentIndex = 1, start = 13, end = 15))

        assertEquals(0, fixture.repository.invalidateFrom(CHAT, PLATFORM_A, userMessageId = 12))
    }

    @Test
    fun `an edit older than the whole chain invalidates it from the head`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12))
        fixture.repository.appendSegment(checkpoint(segmentIndex = 1, start = 13, end = 15))

        val affected = fixture.repository.invalidateFrom(CHAT, PLATFORM_A, userMessageId = 1)

        assertEquals(0, affected)
        assertTrue(fixture.repository.loadChain(CHAT, PLATFORM_A).isEmpty)
    }

    /** The common case, and the one that must cost nothing: the edit landed in the still-raw tail. */
    @Test
    fun `an edit in the raw tail leaves the chain untouched`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12))

        val affected = fixture.repository.invalidateFrom(CHAT, PLATFORM_A, userMessageId = 99)

        assertNull(affected)
        val stored = fixture.repository.loadAllSegments(CHAT, PLATFORM_A).single()
        assertEquals(CheckpointSegmentStatus.VALID, stored.status)
    }

    /**
     * Native state has no partial-invalidation story — it is one opaque server-side window — so it is
     * dropped whether or not the edit reached an already-folded turn. Skipping this when the chain was
     * unaffected would leave a stale window in place for exactly the common case.
     */
    @Test
    fun `native state is dropped even when no segment covered the edit`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.saveNativeState(nativeState())

        val affected = fixture.repository.invalidateFrom(CHAT, PLATFORM_A, userMessageId = 99)

        assertNull(affected)
        assertNull(fixture.repository.loadNativeState(CHAT, PLATFORM_A))
    }

    @Test
    fun `invalidation stamps the invalidated rows with the repository clock`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12).copy(updatedAt = 1_000L))
        fixture.now = 7_777L

        fixture.repository.invalidateFrom(CHAT, PLATFORM_A, userMessageId = 11)

        assertEquals(7_777L, fixture.repository.loadAllSegments(CHAT, PLATFORM_A).single().updatedAt)
    }

    @Test
    fun `invalidating one provider's chain leaves the other provider's chain valid`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12))
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, platformUid = PLATFORM_B))

        fixture.repository.invalidateFrom(CHAT, PLATFORM_A, userMessageId = 11)

        assertTrue(fixture.repository.loadChain(CHAT, PLATFORM_A).isEmpty)
        assertEquals(1, fixture.repository.loadChain(CHAT, PLATFORM_B).validSegments.size)
    }

    // ------------------------------------------------------------------ rebuild

    @Test
    fun `a rebuild replaces the stale tail and leaves the valid prefix alone`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, body = "kept"))
        fixture.repository.appendSegment(
            checkpoint(segmentIndex = 1, start = 13, end = 15, body = "stale", status = CheckpointSegmentStatus.INVALID)
        )
        fixture.repository.appendSegment(
            checkpoint(segmentIndex = 2, start = 16, end = 18, body = "stale", status = CheckpointSegmentStatus.INVALID)
        )

        fixture.repository.replaceChainFrom(
            chatRoomId = CHAT,
            platformUid = PLATFORM_A,
            fromSegmentIndex = 1,
            rebuilt = listOf(checkpoint(segmentIndex = 1, start = 13, end = 18, body = "rebuilt"))
        )

        val view = fixture.repository.loadChain(CHAT, PLATFORM_A)
        assertEquals(listOf("kept", "rebuilt"), view.validSegments.map { it.body })
        assertEquals(18, view.foldedThroughUserMessageId)
    }

    /** Rebuilding into fewer segments must shrink the chain, not leave orphaned links behind it. */
    @Test
    fun `a rebuild that produces nothing truncates the chain at the cut point`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, body = "kept"))
        fixture.repository.appendSegment(
            checkpoint(segmentIndex = 1, start = 13, end = 15, status = CheckpointSegmentStatus.INVALID)
        )

        fixture.repository.replaceChainFrom(CHAT, PLATFORM_A, fromSegmentIndex = 1, rebuilt = emptyList())

        assertEquals(listOf(0), fixture.repository.loadAllSegments(CHAT, PLATFORM_A).map { it.segmentIndex })
    }

    /**
     * The guard is what keeps a rebuild from destroying the still-valid prefix — the one part of the
     * chain a failed rebuild must never be able to touch.
     */
    @Test
    fun `a rebuild reaching back before its cut point is refused before anything is deleted`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, body = "kept"))
        fixture.repository.appendSegment(
            checkpoint(segmentIndex = 1, start = 13, end = 15, status = CheckpointSegmentStatus.INVALID)
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                fixture.repository.replaceChainFrom(
                    chatRoomId = CHAT,
                    platformUid = PLATFORM_A,
                    fromSegmentIndex = 1,
                    rebuilt = listOf(
                        checkpoint(segmentIndex = 0, start = 10, end = 15, body = "overreaching"),
                        checkpoint(segmentIndex = 1, start = 16, end = 18)
                    )
                )
            }
        }

        assertEquals(0, fixture.dao.deleteSegmentsFromCount)
        val bodies = fixture.repository.loadAllSegments(CHAT, PLATFORM_A).map { it.body }
        assertEquals(listOf("kept", CHECKPOINT_BODY), bodies)
    }

    @Test
    fun `a rebuild never reaches into the other provider's chain`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.appendSegment(checkpoint(segmentIndex = 0, start = 10, end = 12, platformUid = PLATFORM_B))

        fixture.repository.replaceChainFrom(CHAT, PLATFORM_A, fromSegmentIndex = 0, rebuilt = emptyList())

        assertEquals(1, fixture.repository.loadAllSegments(CHAT, PLATFORM_B).size)
    }

    // ------------------------------------------------------------------ range

    /** An absent row means "everything", never null: a chat the user never adjusted is full history. */
    @Test
    fun `a chat with no stored range reports full history`() = runBlocking {
        val fixture = Fixture()

        val range = fixture.repository.loadRange(CHAT, PLATFORM_A)

        assertTrue(range.isFullHistory)
        assertNull(range.startUserMessageId)
        assertEquals(CHAT, range.chatRoomId)
        assertEquals(PLATFORM_A, range.platformUid)
    }

    @Test
    fun `a saved range round trips its anchor turn`() = runBlocking {
        val fixture = Fixture()

        fixture.repository.saveRange(ContextRange(CHAT, PLATFORM_A, startUserMessageId = 42))

        val loaded = fixture.repository.loadRange(CHAT, PLATFORM_A)
        assertEquals(42, loaded.startUserMessageId)
        assertFalse(loaded.isFullHistory)
    }

    /** V1's picker is always "from turn X to now", so the end column stays null as the sentinel. */
    @Test
    fun `a saved range leaves its end open as the current sentinel`() = runBlocking {
        val fixture = Fixture()
        fixture.now = 5_555L

        fixture.repository.saveRange(ContextRange(CHAT, PLATFORM_A, startUserMessageId = 42))

        val stored = checkNotNull(fixture.dao.getRange(CHAT, PLATFORM_A))
        assertNull(stored.endUserMessageId)
        assertEquals(5_555L, stored.updatedAt)
    }

    @Test
    fun `resetting a range back to full history is stored rather than dropped`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.saveRange(ContextRange(CHAT, PLATFORM_A, startUserMessageId = 42))

        fixture.repository.saveRange(ContextRange.fullHistory(CHAT, PLATFORM_A))

        assertNotNull(fixture.dao.getRange(CHAT, PLATFORM_A))
        assertTrue(fixture.repository.loadRange(CHAT, PLATFORM_A).isFullHistory)
    }

    @Test
    fun `clearing a range leaves the other provider's range in the same chat alone`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.saveRange(ContextRange(CHAT, PLATFORM_A, startUserMessageId = 42))
        fixture.repository.saveRange(ContextRange(CHAT, PLATFORM_B, startUserMessageId = 77))

        fixture.repository.clearRange(CHAT, PLATFORM_A)

        assertTrue(fixture.repository.loadRange(CHAT, PLATFORM_A).isFullHistory)
        assertEquals(77, fixture.repository.loadRange(CHAT, PLATFORM_B).startUserMessageId)
    }

    // ------------------------------------------------------------------ native state

    @Test
    fun `native state round trips and is stamped with the repository clock`() = runBlocking {
        val fixture = Fixture()
        fixture.now = 9_100L

        fixture.repository.saveNativeState(
            nativeState(items = "[{\"type\":\"summary\"}]", boundary = 31, updatedAt = 1L)
        )

        val loaded = checkNotNull(fixture.repository.loadNativeState(CHAT, PLATFORM_A))
        assertEquals("[{\"type\":\"summary\"}]", loaded.compactionItemsJson)
        assertEquals(31, loaded.boundaryUserMessageId)
        assertEquals(9_100L, loaded.updatedAt)
        assertTrue(loaded.isUsable)
    }

    @Test
    fun `clearing native state leaves the other provider's window alone`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.saveNativeState(nativeState())
        fixture.repository.saveNativeState(nativeState(platformUid = PLATFORM_B))

        fixture.repository.clearNativeState(CHAT, PLATFORM_A)

        assertNull(fixture.repository.loadNativeState(CHAT, PLATFORM_A))
        assertNotNull(fixture.repository.loadNativeState(CHAT, PLATFORM_B))
    }

    /**
     * The rejection usually arrives on an ordinary request, before any window was ever stored. Without
     * a marker row there would be nothing to remember it by and the gate would re-send
     * `context_management` on every single turn.
     */
    @Test
    fun `a rejection with no stored window still leaves a marker behind`() = runBlocking {
        val fixture = Fixture()
        fixture.now = 8_800L

        fixture.repository.markNativeUnsupported(CHAT, PLATFORM_A)

        val marker = checkNotNull(fixture.repository.loadNativeState(CHAT, PLATFORM_A))
        assertTrue(marker.nativeUnsupported)
        assertFalse(marker.isReadyForInput)
        assertFalse(marker.isUsable)
        assertEquals("", marker.compactionItemsJson)
        assertEquals(0, marker.boundaryUserMessageId)
        assertEquals(8_800L, marker.updatedAt)
    }

    @Test
    fun `a rejection with a stored window flips the flags in place`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.saveNativeState(nativeState(items = "[{\"type\":\"summary\"}]", boundary = 31))
        fixture.now = 8_900L

        fixture.repository.markNativeUnsupported(CHAT, PLATFORM_A)

        val marker = checkNotNull(fixture.repository.loadNativeState(CHAT, PLATFORM_A))
        assertTrue(marker.nativeUnsupported)
        assertFalse(marker.isReadyForInput)
        assertFalse(marker.isUsable)
        assertEquals("[{\"type\":\"summary\"}]", marker.compactionItemsJson)
        assertEquals(31, marker.boundaryUserMessageId)
        assertEquals(8_900L, marker.updatedAt)
        assertEquals(1, fixture.dao.markNativeUnsupportedCount)
    }

    @Test
    fun `a rejection for one provider does not disable the other`() = runBlocking {
        val fixture = Fixture()
        fixture.repository.saveNativeState(nativeState(platformUid = PLATFORM_B))

        fixture.repository.markNativeUnsupported(CHAT, PLATFORM_A)

        assertTrue(checkNotNull(fixture.repository.loadNativeState(CHAT, PLATFORM_A)).nativeUnsupported)
        assertTrue(checkNotNull(fixture.repository.loadNativeState(CHAT, PLATFORM_B)).isUsable)
    }

    // ------------------------------------------------------------------ fixture

    private class Fixture {
        var now: Long = DEFAULT_NOW
        val dao = InMemoryContextManagementDao()
        val repository = ContextCheckpointRepositoryImpl(dao = dao, nowSeconds = { now })
    }

    private companion object {
        const val CHAT = 7
        const val PLATFORM_A = "platform-a"
        const val PLATFORM_B = "platform-b"
        const val CHECKPOINT_BODY = "checkpoint body"
        const val DEFAULT_NOW = 1_700_000_000L

        fun checkpoint(
            segmentIndex: Int,
            start: Int,
            end: Int,
            body: String = CHECKPOINT_BODY,
            status: CheckpointSegmentStatus = CheckpointSegmentStatus.VALID,
            platformUid: String = PLATFORM_A
        ): ConversationCheckpoint = ConversationCheckpoint(
            chatRoomId = CHAT,
            platformUid = platformUid,
            segmentIndex = segmentIndex,
            startUserMessageId = start,
            endUserMessageId = end,
            body = body,
            sourceTurnsHash = "hash-$segmentIndex",
            estimatedTokens = 100 + segmentIndex,
            status = status,
            createdAt = DEFAULT_NOW,
            updatedAt = DEFAULT_NOW
        )

        fun entity(
            segmentIndex: Int,
            start: Int,
            end: Int,
            platformUid: String = PLATFORM_A
        ): ContextCheckpointSegment = ContextCheckpointSegment(
            chatId = CHAT,
            platformUid = platformUid,
            segmentIndex = segmentIndex,
            startUserMessageId = start,
            endUserMessageId = end,
            body = CHECKPOINT_BODY,
            sourceTurnsHash = "hash-$segmentIndex",
            estimatedTokens = 100,
            status = CheckpointSegmentStatus.VALID.name,
            createdAt = DEFAULT_NOW,
            updatedAt = DEFAULT_NOW
        )

        fun nativeState(
            platformUid: String = PLATFORM_A,
            items: String = "[{\"type\":\"summary\",\"text\":\"so far\"}]",
            boundary: Int = 20,
            updatedAt: Long = DEFAULT_NOW
        ): OpenAINativeCompactionState = OpenAINativeCompactionState(
            chatRoomId = CHAT,
            platformUid = platformUid,
            compactionItemsJson = items,
            boundaryUserMessageId = boundary,
            sourceTurnsHash = "native-hash",
            estimatedTokens = 500,
            isReadyForInput = true,
            nativeUnsupported = false,
            updatedAt = updatedAt
        )
    }
}
