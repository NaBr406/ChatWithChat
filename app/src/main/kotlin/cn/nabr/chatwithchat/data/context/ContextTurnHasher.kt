package cn.nabr.chatwithchat.data.context

import java.security.MessageDigest

/**
 * Version identifier for the turns a checkpoint segment folded.
 *
 * Computed **per segment**, never once for the whole chain: that is what lets invalidation locate
 * the first affected segment `K` and keep `0..K-1`, instead of throwing the chain away on every
 * edit.
 *
 * The digest covers everything a user edit can change — message ids, the active revision index, and
 * the content of both halves of a turn — so an in-place edit cannot leave a stale segment looking
 * valid. It deliberately ignores timestamps, which change for reasons that do not affect what the
 * summary should say.
 */
object ContextTurnHasher {

    /** ASCII unit separator. It cannot occur in message text, so fields cannot be made to collide. */
    private const val SEPARATOR = '\u001F'

    fun hashTurns(turns: List<ConversationTurn>): String = digestOf(null, turns)

    /**
     * Segment N's compaction input included segment N-1's body, so its identity depends on that
     * segment too. Chaining the previous hash in means a rebuilt upstream segment can never produce
     * a downstream segment that still looks like it matches.
     */
    fun hashSegment(previousSegmentHash: String?, turns: List<ConversationTurn>): String =
        digestOf(previousSegmentHash, turns)

    private fun digestOf(previousSegmentHash: String?, turns: List<ConversationTurn>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((previousSegmentHash.orEmpty() + SEPARATOR).toByteArray(Charsets.UTF_8))
        turns.forEach { turn ->
            digest.update(turnFingerprint(turn).toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun turnFingerprint(turn: ConversationTurn): String = buildString {
        append(turn.userMessage.id)
        append(SEPARATOR)
        append(turn.userMessage.content)
        append(SEPARATOR)
        append(turn.assistantMessage?.id ?: -1)
        append(SEPARATOR)
        append(turn.assistantMessage?.activeRevisionIndex ?: -1)
        append(SEPARATOR)
        append(turn.assistantMessage?.content.orEmpty())
        append(SEPARATOR)
        turn.visualAssets
            .sortedWith(compareBy({ it.messageId }, { it.attachmentIndex }, { it.visualRef }))
            .forEach { visualAsset ->
                append(visualAsset.visualRef)
                append(SEPARATOR)
                append(visualAsset.assetHash)
                append(SEPARATOR)
                append(visualAsset.attachmentIndex)
                append(SEPARATOR)
                append(visualAsset.compactedProfile.orEmpty())
                append(SEPARATOR)
            }
    }
}
