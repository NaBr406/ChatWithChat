package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.context.ContextRangeCorrection
import cn.nabr.chatwithchat.data.context.ContextStrategy
import cn.nabr.chatwithchat.data.context.stripInternalStickerMarkers
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent

/**
 * One selectable turn boundary in the chat-level range picker.
 *
 * Only user messages appear here: at the persistence layer a turn is exactly one user message plus
 * its assistant reply, so a user message id is the only thing that can name a complete boundary.
 */
data class ContextRangeTurn(
    val userMessageId: Int,
    /** 1-based position in the *whole* conversation, not in [ContextRangeSheetState.turns]. */
    val turnNumber: Int,
    val preview: String
)

/**
 * Everything the range sheet shows for one chat and one provider.
 *
 * Held per `chatRoomId + platformUid` exactly like the stored range, so switching provider while the
 * sheet is open reloads rather than reinterpreting the previous provider's selection.
 */
data class ContextRangeSheetState(
    val platformUid: String,
    val platformName: String,
    val strategy: ContextStrategy,
    val turns: List<ContextRangeTurn> = emptyList(),
    /** `null` means the whole conversation participates — the default for an unadjusted chat. */
    val selectedStartUserMessageId: Int? = null,
    val correction: ContextRangeCorrection = ContextRangeCorrection.NONE,
    val foldedTurnCount: Int = 0,
    val isLoading: Boolean = true
) {
    val isFullHistory: Boolean get() = selectedStartUserMessageId == null

    /** True once anything has been folded, which is what the lightweight status line reports. */
    val isCompacted: Boolean get() = foldedTurnCount > 0

    /**
     * Turns from the selected start through the newest one.
     *
     * An unknown anchor counts as full history rather than zero: the range only ever widens back
     * toward the whole conversation, so the optimistic answer is also the correct one.
     */
    val includedTurnCount: Int
        get() {
            val start = selectedStartUserMessageId ?: return turns.size
            val index = turns.indexOfFirst { it.userMessageId == start }
            return if (index < 0) turns.size else turns.size - index
        }

    /** Range selection is a `RAW_PREFERRED` affordance; the auto-compact tiers pick the window. */
    val isRangeSelectable: Boolean get() = strategy.sendsRawWhileCapacityAllows
}

data class ContextRangeAnchorResolution(
    val startUserMessageId: Int?,
    val correction: ContextRangeCorrection
)

/**
 * Projects the chat's user messages onto selectable boundaries.
 *
 * Messages that have not been persisted yet carry id `0` and are skipped: an anchor has to survive
 * process death to mean anything, and the in-flight turn is appended to every request regardless.
 * [ContextRangeTurn.turnNumber] still counts from the full list so the numbering the user sees
 * matches the conversation rather than the filtered list.
 */
fun buildContextRangeTurns(
    userMessages: List<MessageV2>,
    previewLength: Int = CONTEXT_RANGE_PREVIEW_LENGTH
): List<ContextRangeTurn> = userMessages.mapIndexedNotNull { index, message ->
    if (message.id <= 0) {
        null
    } else {
        ContextRangeTurn(
            userMessageId = message.id,
            turnNumber = index + 1,
            preview = message.contextRangePreview(previewLength)
        )
    }
}

/**
 * Decides what a stored anchor still means, mirroring the correction `ContextBuilder` performs at
 * assembly time so the sheet can explain the change instead of appearing to have silently discarded
 * the user's choice.
 *
 * Correction always widens to full history. It never narrows, and it never removes messages.
 */
fun resolveContextRangeAnchor(
    storedStartUserMessageId: Int?,
    turns: List<ContextRangeTurn>,
    assistantMessageIds: Set<Int>
): ContextRangeAnchorResolution {
    val anchor = storedStartUserMessageId
        ?: return ContextRangeAnchorResolution(null, ContextRangeCorrection.NONE)
    if (turns.any { it.userMessageId == anchor }) {
        return ContextRangeAnchorResolution(anchor, ContextRangeCorrection.NONE)
    }
    val correction = if (anchor in assistantMessageIds) {
        ContextRangeCorrection.ANCHOR_NOT_A_TURN_BOUNDARY
    } else {
        ContextRangeCorrection.ANCHOR_MISSING
    }
    return ContextRangeAnchorResolution(null, correction)
}

/**
 * How many turns the stored checkpoint chain — or the OpenAI native window — has already folded.
 *
 * Comparison is by id because `messages_v2` ids are monotonic per insert, so within one chat "id at
 * or below the boundary" is the same set as "turn at or before the boundary".
 */
fun countFoldedTurns(turns: List<ContextRangeTurn>, foldedThroughUserMessageId: Int?): Int {
    val boundary = foldedThroughUserMessageId ?: return 0
    return turns.count { it.userMessageId <= boundary }
}

private const val CONTEXT_RANGE_PREVIEW_LENGTH = 48

private val CONTEXT_RANGE_WHITESPACE = Regex("\\s+")

private fun MessageV2.contextRangePreview(previewLength: Int): String {
    val text = effectiveContent()
        .stripInternalStickerMarkers()
        .replace(CONTEXT_RANGE_WHITESPACE, " ")
        .trim()
    return when {
        text.length <= previewLength -> text
        else -> text.take(previewLength).trimEnd() + "…"
    }
}
