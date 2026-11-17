package cn.nabr.chatwithchat.data.generation

data class ChatGenerationState(
    val activeAttemptIds: List<String> = emptyList(),
    val generatingAttemptIds: List<String> = emptyList()
) {
    val activeCount: Int get() = activeAttemptIds.size
}
