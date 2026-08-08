package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.entity.MessageV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hash is what makes a queued fold safe to run later.
 *
 * A fold job carries the hash of the batch it was queued for; the worker re-reads the rows and
 * re-hashes them before folding. So the hash must move when the *text that would be summarized*
 * moves — content, which message, which revision — and must stay still when something irrelevant to
 * the summary moves, or every fold would be thrown away for no reason.
 *
 * The chaining half matters just as much: a segment is a summary of a batch *given the summary
 * before it*, so segment N's hash has to depend on segment N-1's. That is what makes an edit inside
 * an early segment invalidate everything after it instead of leaving a chain whose later bodies
 * silently reference text that no longer exists.
 */
class ContextTurnHasherTest {

    @Test
    fun `hash is stable across calls for the same turns`() {
        val turns = listOf(turn(1, "first question", 1, "first answer"))

        assertEquals(ContextTurnHasher.hashTurns(turns), ContextTurnHasher.hashTurns(turns))
    }

    @Test
    fun `hash is a lowercase hex sha256 digest`() {
        val hash = ContextTurnHasher.hashTurns(listOf(turn(1, "question", 1, "answer")))

        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `empty batch still hashes to something deterministic`() {
        assertEquals(ContextTurnHasher.hashTurns(emptyList()), ContextTurnHasher.hashTurns(emptyList()))
        assertNotEquals(
            ContextTurnHasher.hashTurns(emptyList()),
            ContextTurnHasher.hashTurns(listOf(turn(1, "question", 1, "answer")))
        )
    }

    @Test
    fun `editing user content changes the hash`() {
        val before = listOf(turn(1, "first question", 1, "first answer"))
        val after = listOf(turn(1, "first question, edited", 1, "first answer"))

        assertNotEquals(ContextTurnHasher.hashTurns(before), ContextTurnHasher.hashTurns(after))
    }

    @Test
    fun `editing assistant content changes the hash`() {
        val before = listOf(turn(1, "first question", 1, "first answer"))
        val after = listOf(turn(1, "first question", 1, "first answer, regenerated"))

        assertNotEquals(ContextTurnHasher.hashTurns(before), ContextTurnHasher.hashTurns(after))
    }

    @Test
    fun `switching the active revision changes the hash even when text is unchanged`() {
        val original = turn(1, "question", 1, "answer")
        val switched = original.copy(
            assistantMessage = original.assistantMessage!!.copy(activeRevisionIndex = 0)
        )

        assertNotEquals(
            ContextTurnHasher.hashTurns(listOf(original)),
            ContextTurnHasher.hashTurns(listOf(switched))
        )
    }

    @Test
    fun `a deleted assistant reply changes the hash`() {
        val answered = listOf(turn(1, "question", 1, "answer"))
        val unanswered = listOf(answered.first().copy(assistantMessage = null))

        assertNotEquals(ContextTurnHasher.hashTurns(answered), ContextTurnHasher.hashTurns(unanswered))
    }

    @Test
    fun `message identity is part of the hash so a delete and re-add is detected`() {
        val before = listOf(turn(1, "question", 1, "answer"))
        val after = listOf(turn(2, "question", 2, "answer"))

        assertNotEquals(ContextTurnHasher.hashTurns(before), ContextTurnHasher.hashTurns(after))
    }

    @Test
    fun `reordering turns changes the hash`() {
        val first = turn(1, "first question", 1, "first answer")
        val second = turn(2, "second question", 2, "second answer")

        assertNotEquals(
            ContextTurnHasher.hashTurns(listOf(first, second)),
            ContextTurnHasher.hashTurns(listOf(second, first))
        )
    }

    @Test
    fun `timestamps are not part of the hash`() {
        val original = turn(1, "question", 1, "answer")
        val retimed = original.copy(
            userMessage = original.userMessage.copy(createdAt = 999_999),
            assistantMessage = original.assistantMessage!!.copy(createdAt = 1_000_000)
        )

        assertEquals(
            ContextTurnHasher.hashTurns(listOf(original)),
            ContextTurnHasher.hashTurns(listOf(retimed))
        )
    }

    @Test
    fun `the current turn flag is not part of the hash`() {
        val turn = turn(1, "question", 1, "answer")

        assertEquals(
            ContextTurnHasher.hashTurns(listOf(turn.copy(isCurrentTurn = false))),
            ContextTurnHasher.hashTurns(listOf(turn.copy(isCurrentTurn = true)))
        )
    }

    @Test
    fun `segment hash chains onto the previous segment`() {
        val turns = listOf(turn(3, "third question", 3, "third answer"))

        val chainedToA = ContextTurnHasher.hashSegment("aaaa", turns)
        val chainedToB = ContextTurnHasher.hashSegment("bbbb", turns)

        assertNotEquals(chainedToA, chainedToB)
    }

    @Test
    fun `first segment hash equals the unchained hash of the same turns`() {
        val turns = listOf(turn(1, "question", 1, "answer"))

        assertEquals(ContextTurnHasher.hashTurns(turns), ContextTurnHasher.hashSegment(null, turns))
    }

    @Test
    fun `segment hash is stable when both the previous hash and the turns are unchanged`() {
        val turns = listOf(turn(4, "question", 4, "answer"))

        assertEquals(
            ContextTurnHasher.hashSegment("previous", turns),
            ContextTurnHasher.hashSegment("previous", turns)
        )
    }

    private fun turn(
        userId: Int,
        userContent: String,
        assistantId: Int,
        assistantContent: String
    ) = ConversationTurn(
        userMessage = MessageV2(id = userId, content = userContent, platformType = null),
        assistantMessage = MessageV2(
            id = 100 + assistantId,
            content = assistantContent,
            platformType = PLATFORM_UID
        ),
        isCurrentTurn = false
    )

    private companion object {
        const val PLATFORM_UID = "platform"
    }
}
