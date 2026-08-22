package cn.nabr.chatwithchat.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 分步执行时推理会中断又恢复：第一轮正文 -> 第二轮 <thinking> -> 第二轮正文 -> ……
 * 所以"推理是否还在流"不能用"正文是否还空着"来判断——正文第一次落地后那个条件就永久为假，
 * 后续每一段推理都会被当成已结束，逐帧平滑退化成整批瞬移，把下方正文顶得一跳一跳。
 * 改成看最近一次增长发生在哪一侧：思考变长就是在推理，正文变长就是推理告一段落。
 */
internal class ThoughtStreamActivity(
    thoughts: String,
    text: String
) {
    private var lastThoughts = thoughts
    private var lastText = text
    private var isActive = thoughts.isNotBlank()

    fun update(thoughts: String, text: String): Boolean {
        when {
            // 同一次发布里两边都长了（推理刚收尾、正文紧接着开头）算推理仍在进行，
            // 最多晚一次发布才切走，比提前切走要好：提前切会立刻触发收尾动画。
            thoughts != lastThoughts -> isActive = true
            text != lastText -> isActive = false
        }
        lastThoughts = thoughts
        lastText = text
        return isActive
    }
}

@Composable
internal fun rememberThoughtStreamActive(
    thoughts: String,
    text: String,
    isLoading: Boolean,
    contentIdentity: Any
): Boolean {
    val activity = remember(contentIdentity) { ThoughtStreamActivity(thoughts, text) }
    var isThoughtStreamActive by remember(contentIdentity) { mutableStateOf(thoughts.isNotBlank()) }

    LaunchedEffect(contentIdentity, thoughts, text) {
        isThoughtStreamActive = activity.update(thoughts, text)
    }

    return isLoading && thoughts.isNotBlank() && isThoughtStreamActive
}
