package dev.chungjungsoo.gptmobile.data.dto

import dev.chungjungsoo.gptmobile.data.database.entity.MessageSourceMetadata

sealed class ApiState {
    data object Loading : ApiState()
    data class Thinking(val thinkingChunk: String) : ApiState()
    data class Success(val textChunk: String) : ApiState()
    data class SourcesUpdated(val sources: List<MessageSourceMetadata>) : ApiState()
    data class ToolStarted(val toolName: String, val label: String) : ApiState()
    data class ToolFinished(val toolName: String, val label: String) : ApiState()
    data class ToolFailed(val toolName: String, val message: String) : ApiState()
    data class Error(val message: String) : ApiState()
    data object Done : ApiState()
}
