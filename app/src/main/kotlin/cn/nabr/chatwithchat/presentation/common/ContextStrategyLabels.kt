package cn.nabr.chatwithchat.presentation.common

import androidx.annotation.StringRes
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.context.ContextStrategy

/**
 * Display labels for [ContextStrategy], shared by the provider settings screen and the chat-level
 * range sheet so the two never drift into describing the same strategy differently.
 *
 * The `when` is deliberately exhaustive without an `else`: adding a strategy should fail to compile
 * here rather than silently render as the wrong option.
 */
@StringRes
fun ContextStrategy.titleRes(): Int = when (this) {
    ContextStrategy.AUTO_COMPACT_ECONOMY -> R.string.context_strategy_economy
    ContextStrategy.AUTO_COMPACT_BALANCED -> R.string.context_strategy_balanced
    ContextStrategy.AUTO_COMPACT_GENEROUS -> R.string.context_strategy_generous
    ContextStrategy.AUTO_COMPACT_FOLLOW_WINDOW -> R.string.context_strategy_follow_window
    ContextStrategy.RAW_PREFERRED -> R.string.context_strategy_raw_preferred
}

@StringRes
fun ContextStrategy.descriptionRes(): Int = when (this) {
    ContextStrategy.AUTO_COMPACT_ECONOMY -> R.string.context_strategy_economy_description
    ContextStrategy.AUTO_COMPACT_BALANCED -> R.string.context_strategy_balanced_description
    ContextStrategy.AUTO_COMPACT_GENEROUS -> R.string.context_strategy_generous_description
    ContextStrategy.AUTO_COMPACT_FOLLOW_WINDOW -> R.string.context_strategy_follow_window_description
    ContextStrategy.RAW_PREFERRED -> R.string.context_strategy_raw_preferred_description
}
