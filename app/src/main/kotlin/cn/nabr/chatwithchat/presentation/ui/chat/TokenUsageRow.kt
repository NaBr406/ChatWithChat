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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.nabr.chatwithchat.data.token.TokenUsageRecord

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TokenUsageRow(
    usage: TokenUsageRecord?,
    turnUsages: List<TokenUsageRecord>,
    modifier: Modifier = Modifier
) {
    if (usage == null) return

    var isExpanded by remember(usage, turnUsages) { mutableStateOf(false) }
    val rotation = if (isExpanded) 180f else 0f
    val rowUsage = usage
    val turnTotal = remember(turnUsages) { turnUsages.roundTotalTokens() }
    val allPlatformTotal = remember(turnUsages) { turnUsages.sumOf { it.totalTokens } }
    val toolDetails = remember(rowUsage) { rowUsage.details.filter { it.isToolRelated } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(0.82f)
    ) {
        FlowRow(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            UsageMetric(Icons.Rounded.ArrowUpward, "输入 token", formatTokenCount(rowUsage.inputTokens))
            UsageMetric(Icons.Rounded.ArrowDownward, "输出 token", formatTokenCount(rowUsage.outputTokens))
            UsageMetric(Icons.Rounded.Functions, "总 token", formatTokenCount(rowUsage.totalTokens))
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
                    .rotate(rotation)
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
                TokenDetailLine("当前可见回答", rowUsage.totalTokens, rowUsage.isEstimated)
                TokenDetailLine("本轮合并总量", turnTotal, turnUsages.any { it.isEstimated })
                TokenDetailLine("所有平台回答合计", allPlatformTotal, turnUsages.any { it.isEstimated })
                TokenDetailLine("工具循环合计", rowUsage.toolTotalTokens, rowUsage.isEstimated)
                if (toolDetails.isNotEmpty()) {
                    Text(
                        text = "工具请求明细",
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
            }
        }
    }
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
            modifier = Modifier.size(14.dp)
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

fun List<TokenUsageRecord>.roundTotalTokens(): Int = sumOf { usage ->
    if (usage.toolTotalTokens > 0) usage.toolTotalTokens else usage.totalTokens
}

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
