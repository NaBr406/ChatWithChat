package cn.nabr.chatwithchat.data.dto

import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.token.TokenUsageRecord

sealed class ApiState {
    data object Loading : ApiState()
    data class Thinking(val thinkingChunk: String) : ApiState()
    data class Success(val textChunk: String) : ApiState()
    data class SourcesUpdated(val sources: List<MessageSourceMetadata>) : ApiState()
    data class UsageUpdated(val usage: TokenUsageRecord) : ApiState()
    data class ToolStarted(val toolName: String, val label: String) : ApiState()
    data class ToolFinished(val toolName: String, val label: String) : ApiState()
    data class ToolFailed(
        val toolName: String,
        val message: String,
        val errorCode: String? = null
    ) : ApiState()
    data class Error(val message: String) : ApiState()
    data object Done : ApiState()
}
