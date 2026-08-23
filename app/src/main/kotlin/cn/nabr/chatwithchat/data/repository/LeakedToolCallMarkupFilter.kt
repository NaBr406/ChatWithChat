package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.dto.ApiState
import java.util.regex.Pattern

/**
 * Keeps a model's own tool-call markup out of the visible message when it writes the call as prose.
 *
 * Every native tool loop streams content deltas straight to the UI, and the only cleanup between the
 * provider stream and the bubble is `stripInternalStickerMarkers`, which knows about stickers and nothing
 * else. So when a model emits its native tool-call envelope as ordinary text, the raw envelope lands in the
 * chat verbatim.
 *
 * This is containment, not repair. Parsing the envelope back into a real call was tried and removed: it turns
 * text that merely *looks* like a call — a model explaining a format, quoting documentation — into an actual
 * tool execution, which is a far worse failure than showing the markup. Prevention belongs upstream, in
 * keeping a tool channel available on every round and telling the model never to write calls as text.
 *
 * Suppressed markup is re-emitted as [ApiState.Thinking] rather than dropped: the thinking block is already
 * collapsed by default, so the bubble stays clean while the text survives for diagnosis. That also has to
 * bypass [ReasoningStreamParser], whose tagged-thought path discards everything once a provider has sent
 * structured `reasoning_content` — which the providers that leak this way do.
 *
 * Mounted only inside the native tool-round collectors. A plain chat turn that mentions `<invoke name=` is
 * far more likely to be a user asking about tool formats than a leak, and there is no round to protect.
 */
internal class LeakedToolCallMarkupFilter {
    private val pending = StringBuilder()
    private var isSuppressing = false

    /**
     * Whether this round wrote a tool-call envelope into its text.
     *
     * The filter is the only component that knows: it recognises the envelope in order to suppress it, and
     * for a long time it kept that to itself, so a round whose call had leaked was indistinguishable from a
     * round that had simply finished answering — and the native loops end the turn on "no tool calls". The
     * flag latches, because a leak anywhere in the round is what matters, not where.
     */
    val hasSuppressedToolCallMarkup: Boolean
        get() = isSuppressing

    fun append(textChunk: String): List<ApiState> {
        if (textChunk.isEmpty()) return emptyList()
        if (isSuppressing) return suppress(textChunk)

        pending.append(textChunk)
        return drain()
    }

    fun flush(): List<ApiState> {
        if (pending.isEmpty()) return emptyList()

        val remaining = pending.toString()
        pending.clear()
        return if (isSuppressing) suppress(remaining) else listOf(ApiState.Success(remaining))
    }

    private fun drain(): List<ApiState> {
        val matcher = SENTINEL.matcher(pending)
        if (matcher.find()) {
            val visiblePrefix = pending.substring(0, matcher.start())
            val markup = pending.substring(matcher.start())
            pending.clear()
            isSuppressing = true
            return buildList {
                if (visiblePrefix.isNotEmpty()) add(ApiState.Success(visiblePrefix))
                addAll(suppress(markup))
            }
        }

        val holdBackFrom = holdBackIndex()
        if (holdBackFrom == 0) return emptyList()

        val visible = pending.substring(0, holdBackFrom)
        pending.delete(0, holdBackFrom)
        return listOf(ApiState.Success(visible))
    }

    /**
     * Where to stop emitting so a sentinel straddling a chunk boundary is still caught.
     *
     * A position-only rule would hold back every `<` near the tail, which stalls ordinary markup — a chunk
     * of `<div>` would never stream. Instead each candidate `<` in the tail window is asked whether the
     * pattern could still match there given more input: [java.util.regex.Matcher.hitEnd] answers exactly
     * that, so `<div>` is ruled out immediately while a half-arrived `<|DSM` is not.
     */
    private fun holdBackIndex(): Int {
        val earliestCandidate = (pending.length - MAX_SENTINEL_CHARS).coerceAtLeast(0)
        for (index in earliestCandidate until pending.length) {
            if (pending[index] != '<') continue

            val matcher = SENTINEL.matcher(pending)
            matcher.region(index, pending.length)
            matcher.useTransparentBounds(true)
            matcher.useAnchoringBounds(false)
            // lookingAt() anchors at the region start; a full match here was already handled by find().
            if (matcher.lookingAt() || matcher.hitEnd()) return index
        }
        return pending.length
    }

    private fun suppress(text: String): List<ApiState> = listOf(ApiState.Thinking(text))

    private companion object {
        /**
         * Openings of a tool-call envelope, narrow on purpose.
         *
         * The delimiter run is deliberately not spelled out. The first version of this matched a single
         * pipe, built from `<|DSML|tool_calls>`; what the provider actually sends is `<||DSML||tool_calls>`,
         * so nothing matched and the markup went straight to the bubble. Closing tags (`</||DSML||parameter>`)
         * were missed for the same reason — the pattern had no room for the slash. Rather than guess the exact
         * delimiter again, alternation one keys on the one structural fact that holds across dialects: a `<`
         * followed immediately by a pipe, then a short run of anything up to a tool-call keyword. Ordinary
         * markup never puts a pipe straight after `<`.
         *
         * Deliberately not "any unknown tag": `<think>`/`<thinking>` belong to [ReasoningStreamParser] and
         * every other tag has to render as written. `invoke` and `parameter` are excluded from the bare-tag
         * alternation because `<parameter>` and `<invoke>` are plausible in hand-written XML; they only count
         * when carrying a `name=` attribute or a pipe delimiter.
         */
        private val SENTINEL: Pattern = Pattern.compile(
            buildString {
                val keyword = "(?:tool[_▁]calls?|invoke|parameter|function[_▁]calls?)"
                append("<\\s{0,4}/?\\s{0,4}(?:")
                // Pipe-delimited envelope, any delimiter run: <|DSML|tool_calls>, <||DSML||invoke name=...>,
                // </||DSML||parameter>, <｜tool▁calls▁begin｜>.
                append("[|｜][^<>\\r\\n]{0,24}").append(keyword)
                append("|")
                // Bare protocol tags carrying an argument name.
                append("(?:invoke|tool[_▁]call|function[_▁]call)\\s{1,4}name\\s{0,4}=")
                append("|")
                // Bare envelope tags whose name is not plausible prose.
                append("(?:tool[_▁]calls?|function[_▁]call)\\s{0,4}>")
                append(")")
            },
            Pattern.CASE_INSENSITIVE
        )

        /** Longest string [SENTINEL] can match (49), rounded up for headroom. */
        private const val MAX_SENTINEL_CHARS = 80
    }
}
