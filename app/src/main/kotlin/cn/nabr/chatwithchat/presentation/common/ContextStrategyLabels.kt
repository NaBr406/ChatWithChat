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
    ContextStrategy.AUTO_COMPACT -> R.string.context_strategy_auto_compact
    ContextStrategy.RAW_PREFERRED -> R.string.context_strategy_raw_preferred
}

@StringRes
fun ContextStrategy.descriptionRes(): Int = when (this) {
    ContextStrategy.AUTO_COMPACT -> R.string.context_strategy_auto_compact_description
    ContextStrategy.RAW_PREFERRED -> R.string.context_strategy_raw_preferred_description
}
