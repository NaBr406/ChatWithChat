package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.context.ContextRangeCorrection
import cn.nabr.chatwithchat.presentation.common.RadioItem
import cn.nabr.chatwithchat.presentation.common.descriptionRes
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.common.titleRes

/**
 * Chat-level context range picker.
 *
 * Deliberately a sheet rather than a dialog: reaching the threshold is reported here as a status
 * line, and the plan requires that automatic compaction never interrupt the user with a
 * confirmation. Selecting a start turn applies immediately, so there is no confirm button either.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextRangeSheet(
    state: ContextRangeSheetState,
    onSelectStart: (Int?) -> Unit,
    onDismissRequest: () -> Unit
) {
    val materialColors = settingsMaterialColors()
    ChatGlassSelectionSheet(onDismissRequest = onDismissRequest) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(bottom = 24.dp)
        ) {
            item(key = "context-range-header") {
                ContextRangeSheetHeader(state = state)
            }

            when {
                state.isLoading -> item(key = "context-range-loading") {
                    ContextRangeSheetNote(text = stringResource(R.string.context_range_loading))
                }

                !state.isRangeSelectable -> item(key = "context-range-auto") {
                    ContextRangeSheetNote(text = stringResource(R.string.context_range_auto_compact_note))
                }

                state.turns.isEmpty() -> item(key = "context-range-empty") {
                    ContextRangeSheetNote(text = stringResource(R.string.context_range_empty))
                }

                else -> {
                    item(key = "context-range-full-history") {
                        RadioItem(
                            value = "",
                            selected = state.isFullHistory,
                            title = stringResource(R.string.context_range_full_history),
                            description = stringResource(R.string.context_range_full_history_description),
                            onSelected = { onSelectStart(null) }
                        )
                    }
                    items(
                        items = state.turns,
                        key = { turn -> turn.userMessageId }
                    ) { turn ->
                        RadioItem(
                            value = turn.userMessageId.toString(),
                            selected = state.selectedStartUserMessageId == turn.userMessageId,
                            title = stringResource(R.string.context_range_turn, turn.turnNumber),
                            description = turn.preview.ifBlank {
                                stringResource(R.string.context_range_no_preview)
                            },
                            onSelected = { onSelectStart(turn.userMessageId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextRangeSheetHeader(state: ContextRangeSheetState) {
    val materialColors = settingsMaterialColors()
    Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)) {
        Text(
            text = stringResource(R.string.context_range),
            style = MaterialTheme.typography.titleLarge,
            color = materialColors.primaryLabel
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.context_range_provider, state.platformName),
            style = MaterialTheme.typography.bodyMedium,
            color = materialColors.secondaryLabel
        )
        Text(
            text = stringResource(
                R.string.context_range_strategy,
                stringResource(state.strategy.titleRes())
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = materialColors.secondaryLabel
        )
        Text(
            text = stringResource(state.strategy.descriptionRes()),
            style = MaterialTheme.typography.bodySmall,
            color = materialColors.secondaryLabel
        )
        if (state.isRangeSelectable && !state.isLoading && state.turns.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.context_range_summary,
                    state.includedTurnCount,
                    state.turns.size
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = materialColors.primaryLabel
            )
        }
        if (state.isCompacted) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.context_range_compacted_notice),
                style = MaterialTheme.typography.bodySmall,
                color = materialColors.secondaryLabel
            )
        }
        contextRangeCorrectionRes(state.correction)?.let { correctionRes ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(correctionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(thickness = 0.5.dp, color = materialColors.separator)
    }
}

@Composable
private fun ContextRangeSheetNote(text: String) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = settingsMaterialColors().secondaryLabel
    )
}

/**
 * Exhaustive on purpose: a new correction reason must decide what the user is told, and failing
 * compilation here is the cheapest place to be reminded of that.
 */
private fun contextRangeCorrectionRes(correction: ContextRangeCorrection): Int? = when (correction) {
    ContextRangeCorrection.NONE -> null
    ContextRangeCorrection.ANCHOR_MISSING -> R.string.context_range_correction_anchor_missing
    ContextRangeCorrection.ANCHOR_NOT_A_TURN_BOUNDARY -> R.string.context_range_correction_not_a_turn_boundary
}
