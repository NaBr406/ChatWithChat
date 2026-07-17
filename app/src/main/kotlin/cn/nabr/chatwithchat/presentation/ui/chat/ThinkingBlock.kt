package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.nabr.chatwithchat.R
import cn.nabr.chatwithchat.presentation.common.AppleBlue
import cn.nabr.chatwithchat.presentation.common.settingsMaterialColors
import cn.nabr.chatwithchat.presentation.theme.ChatWithChatTheme

internal const val THINKING_HEADER_TEST_TAG = "thinking-header"
internal const val THINKING_CONTENT_TEST_TAG = "thinking-content"

@Composable
fun ThinkingBlock(
    modifier: Modifier = Modifier,
    thoughts: String,
    contentIdentity: Any = thoughts,
    isLoading: Boolean = false,
    isThinking: Boolean = isLoading,
    initiallyExpanded: Boolean = isLoading
) {
    if (thoughts.isBlank()) return

    val materialColors = settingsMaterialColors()
    val visibleThoughts = rememberSmoothedStreamingText(
        targetText = thoughts,
        isStreaming = isThinking,
        animateInitialText = isLoading,
        contentIdentity = contentIdentity
    )
    val isThoughtTextAnimating = isThinking || visibleThoughts != thoughts
    var isExpanded by rememberSaveable(contentIdentity) { mutableStateOf(initiallyExpanded) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "thinking_expansion"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .testTag(THINKING_HEADER_TEST_TAG),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Psychology,
                contentDescription = null,
                tint = AppleBlue,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(
                    if (isThoughtTextAnimating) R.string.thinking_in_progress_compact else R.string.thinking_complete
                ),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp
                ),
                color = materialColors.primaryLabel
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (isExpanded) {
                    stringResource(R.string.collapse)
                } else {
                    stringResource(R.string.expand)
                },
                tint = materialColors.tertiaryLabel,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotationAngle)
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top,
                clip = true
            ) + fadeIn(
                animationSpec = tween(durationMillis = 120),
                initialAlpha = 0.72f
            ),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 140, easing = FastOutLinearInEasing),
                shrinkTowards = Alignment.Top,
                clip = true
            ) + fadeOut(
                animationSpec = tween(durationMillis = 90)
            )
        ) {
            ChatMarkdown(
                content = appendStreamingTextTail(visibleThoughts, isThoughtTextAnimating),
                contentIdentity = contentIdentity,
                renderMath = true,
                useMathJax = !isThoughtTextAnimating,
                contentColor = materialColors.secondaryLabel,
                style = ChatMarkdownStyle.THINKING,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 30.dp, end = 4.dp)
                    .testTag(THINKING_CONTENT_TEST_TAG)
            )
        }
    }
}

@Preview(name = "Thinking - Completed", widthDp = 390)
@Composable
private fun ThinkingBlockPreview() {
    ChatWithChatTheme {
        ThinkingBlock(
            thoughts = """
                1. First, identify the user's goal.
                2. Check the available context and constraints.
                3. Produce a concise final answer.
            """.trimIndent(),
            isLoading = false,
            initiallyExpanded = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(name = "Thinking - Streaming", widthDp = 390)
@Composable
private fun ThinkingBlockLoadingPreview() {
    ChatWithChatTheme {
        ThinkingBlock(
            thoughts = """
                1. Analyzing the request and the current project style.
                2. Comparing the reasoning hierarchy with the final response.
                3. Checking the streaming layout behavior.
            """.trimIndent(),
            isLoading = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}
