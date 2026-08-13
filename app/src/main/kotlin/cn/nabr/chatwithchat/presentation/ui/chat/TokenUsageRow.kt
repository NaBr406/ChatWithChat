package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.data.token.TokenCacheStatus
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import cn.nabr.chatwithchat.data.token.TokenCacheSummary
import cn.nabr.chatwithchat.data.token.aggregateCacheUsage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TokenUsageRow(
    usage: TokenUsageRecord?,
    sessionUsages: List<TokenUsageRecord> = emptyList(),
    modifier: Modifier = Modifier
) {
    if (usage == null) return

    var isExpanded by remember(usage, sessionUsages) { mutableStateOf(false) }
    val rotation = if (isExpanded) 180f else 0f
    val rowUsage = usage
    val sessionCacheUsage = remember(sessionUsages) { sessionUsages.aggregateCacheUsage() }
    val toolDetails = remember(rowUsage) {
        rowUsage.details
            .filter { detail -> detail.isToolRelated }
            .dropLastIfFinalAnswer(rowUsage)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        FlowRow(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            UsageMetric(Icons.Rounded.ArrowUpward, "输入 token", formatTokenCount(rowUsage.roundInputTokens()))
            UsageMetric(Icons.Rounded.ArrowDownward, "输出 token", formatTokenCount(rowUsage.roundOutputTokens()))
            UsageMetric(Icons.Rounded.Functions, "总 token", formatTokenCount(rowUsage.roundTotalTokens()))
            CacheSummary(rowUsage, compact = true)
            sessionCacheUsage?.let { summary ->
                Text(
                    text = stringResource(
                        R.string.token_cache_session_rate,
                        summary.rateLabel()
                    ) + if (summary.status == TokenCacheStatus.PARTIAL) {
                        " · ${stringResource(R.string.token_cache_partial)}"
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (rowUsage.isEstimated) {
                Text(
                    text = "估算",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                )
            }
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (isExpanded) "收起 token 用量" else "展开 token 用量",
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "本次回答",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TokenDetailLine("输入 token", rowUsage.inputTokens, rowUsage.isEstimated)
                TokenDetailLine("输出 token", rowUsage.outputTokens, rowUsage.isEstimated)
                TokenDetailLine("合计 token", rowUsage.totalTokens, rowUsage.isEstimated)
                CacheSummary(rowUsage)
                if (toolDetails.isNotEmpty()) {
                    Text(
                        text = "工具请求",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    toolDetails.forEach { detail ->
                        TokenDetailLine(
                            label = detail.label,
                            totalTokens = detail.totalTokens,
                            isEstimated = detail.isEstimated
                        )
                    }
                }
                if (toolDetails.isNotEmpty()) {
                    Text(
                        text = "本轮总计",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    TokenDetailLine("输入 token", rowUsage.roundInputTokens(), rowUsage.isEstimated)
                    TokenDetailLine("输出 token", rowUsage.roundOutputTokens(), rowUsage.isEstimated)
                    TokenDetailLine("合计 token", rowUsage.roundTotalTokens(), rowUsage.isEstimated)
                    CacheSummary(rowUsage)
                }
                if (sessionCacheUsage != null) {
                    Text(
                        text = stringResource(
                            R.string.token_cache_session_rate,
                            sessionCacheUsage.rateLabel()
                        ) + if (sessionCacheUsage.status == TokenCacheStatus.PARTIAL) {
                            " · ${stringResource(R.string.token_cache_partial)}"
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun List<cn.nabr.chatwithchat.data.token.TokenUsageDetail>.dropLastIfFinalAnswer(
    usage: TokenUsageRecord
): List<cn.nabr.chatwithchat.data.token.TokenUsageDetail> = if (
    usage.totalTokens > 0 && size > 1
) {
    dropLast(1)
} else {
    this
}

@Composable
private fun CacheSummary(
    usage: TokenUsageRecord,
    compact: Boolean = false
) {
    if (usage.cacheStatus == TokenCacheStatus.UNAVAILABLE) return
    val hit = usage.cacheHitTokens ?: return
    val miss = usage.cacheMissTokens ?: return
    val label = if (compact) {
        listOf(
            stringResource(R.string.token_cache_hit, formatTokenCount(hit)),
            stringResource(R.string.token_cache_miss, formatTokenCount(miss)),
            stringResource(R.string.token_cache_rate, usage.rateLabel())
        ).joinToString(" · ")
    } else {
        listOf(
            stringResource(R.string.token_cache_hit, hit),
            stringResource(R.string.token_cache_miss, miss),
            stringResource(R.string.token_cache_rate, usage.rateLabel())
        ).joinToString(" · ")
    }
    Text(
        text = label + " · " + stringResource(
            if (usage.cacheStatus == TokenCacheStatus.PARTIAL) {
                R.string.token_cache_partial
            } else {
                R.string.token_cache_official
            }
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun UsageMetric(
    icon: ImageVector,
    contentDescription: String,
    value: String,
    prefix: String = ""
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$prefix$value",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TokenDetailLine(
    label: String,
    totalTokens: Int,
    isEstimated: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = buildString {
                append(formatTokenCount(totalTokens))
                if (isEstimated) append(" 估算")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

fun TokenUsageRecord.roundInputTokens(): Int = inputTokens + toolInputTokens

fun TokenUsageRecord.roundOutputTokens(): Int = outputTokens + toolOutputTokens

fun TokenUsageRecord.roundTotalTokens(): Int = if (
    details.isEmpty() && inputTokens + outputTokens == 0
) {
    if (toolTotalTokens > 0) toolTotalTokens else totalTokens
} else {
    roundInputTokens() + roundOutputTokens()
}

fun List<TokenUsageRecord>.roundTotalTokens(): Int = sumOf(TokenUsageRecord::roundTotalTokens)

private fun TokenCacheSummary.rateLabel(): String = hitRate
    ?.let { rate -> "${(rate * 1000).toInt() / 10.0}%" }
    .orEmpty()

private fun TokenUsageRecord.rateLabel(): String = cacheHitRate
    ?.let { rate -> "${(rate * 1000).toInt() / 10.0}%" }
    .orEmpty()

fun formatTokenCount(tokens: Int): String = when {
    tokens >= 10_000 -> "${(tokens / 1000.0).formatOneDecimal()}k"
    tokens >= 1_000 -> "${(tokens / 1000.0).formatOneDecimal()}k"
    else -> tokens.toString()
}

private fun Double.formatOneDecimal(): String {
    val rounded = kotlin.math.round(this * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}
