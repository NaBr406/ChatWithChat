package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.token.TokenUsageRecord
import cn.nabr.chatwithchat.data.token.sumTokenUsage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.FlowCollector

internal const val MAX_OUTPUT_CONTINUATIONS = 4
internal const val OUTPUT_CONTINUATION_INSTRUCTION =
    "上一段回答因输出 token 上限被截断。请从上面助手正文的最后一个字符之后继续，完成原任务。" +
        "只输出剩余内容，不要重复已有内容，不要添加续写说明。" +
        "若截断在代码、SVG、JSON 或 Markdown 代码块内部，直接接续其内容，不要重新打开代码块。"

internal data class OutputContinuationRound<T>(
    val value: T,
    val truncated: Boolean = false,
    val usage: TokenUsageRecord? = null,
    val errorMessage: String? = null,
    val hasToolCalls: Boolean = false
)

/** Each request owns its reconnect checkpoint; completed segments are never rolled back. */
internal suspend fun <Request, Result> FlowCollector<ApiState>.withOutputContinuation(
    initialRequest: Request,
    nextRequest: (Request, String, String) -> Request,
    runRound: suspend FlowCollector<ApiState>.(Request) -> OutputContinuationRound<Result>
): OutputContinuationRound<Result> {
    val downstream = this
    val text = StringBuilder()
    val thoughts = StringBuilder()
    val usages = mutableListOf<TokenUsageRecord>()
    var request = initialRequest
    var continuations = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val previousLength = text.length
        val checkpoints = mutableMapOf<String, Pair<Int, Int>>()
        val collector = FlowCollector<ApiState> { state ->
            when (state) {
                is ApiState.Success -> text.append(state.textChunk)
                is ApiState.Thinking -> thoughts.append(state.thinkingChunk)
                is ApiState.StreamCheckpoint -> checkpoints[state.requestId] = text.length to thoughts.length
                is ApiState.StreamRollback -> checkpoints[state.requestId]?.let { (textLength, thoughtLength) ->
                    text.setLength(textLength)
                    thoughts.setLength(thoughtLength)
                }
                is ApiState.StreamCheckpointReleased -> checkpoints.remove(state.requestId)
                else -> {}
            }
            downstream.emit(state)
        }
        val round = collector.runRound(request)
        round.usage?.let(usages::add)
        val usage = usages.lastOrNull()?.let { latest ->
            if (usages.size == 1) latest else usages.sumTokenUsage(latest.provider, latest.platformUid, latest.model)
        }
        val failure = round.errorMessage ?: when {
            !round.truncated -> null
            round.hasToolCalls -> "输出达到上限，工具调用参数可能不完整，已停止执行。请缩小单次工具输出后重试。"
            text.length == previousLength -> "输出达到上限，但没有可接续的正文，已停止自动续写。"
            continuations >= MAX_OUTPUT_CONTINUATIONS -> "已自动续写 $MAX_OUTPUT_CONTINUATIONS 次，仍达到输出上限。已保留生成内容。"
            else -> null
        }
        val result = round.copy(usage = usage, errorMessage = failure)
        if (!round.truncated || failure != null) return result
        // Publish completed usage before another request, including when that request later fails
        // or is cancelled. The final caller replaces this with the complete aggregate.
        usage?.let { emit(ApiState.UsageUpdated(it)) }
        continuations++
        request = nextRequest(initialRequest, text.toString(), thoughts.toString())
    }
}
