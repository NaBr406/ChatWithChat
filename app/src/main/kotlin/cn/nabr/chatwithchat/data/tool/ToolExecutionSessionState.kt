package cn.nabr.chatwithchat.data.tool

/**
 * Per-request, in-memory state shared by one tool-loop execution session. It is never serialized
 * into a provider request or persisted with a chat message.
 *
 * [chatId] rides along so the approval path can find conversation-scoped grants without threading a
 * separate parameter through every loop signature. Zero means "no conversation scope", which makes
 * grants neither consulted nor offered.
 */
class ToolExecutionSessionState internal constructor(
    val chatId: Int = 0,
    val turnKey: String? = null
) {
    private val valuesByKey = mutableMapOf<String, Set<String>>()

    fun replaceValues(key: String, values: Collection<String>) {
        val normalizedKey = key.trim()
        if (normalizedKey.isBlank()) return

        valuesByKey[normalizedKey] = values.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(MAX_VALUES_PER_KEY)
            .toSet()
    }

    fun containsValue(key: String, value: String): Boolean = valuesByKey[key.trim()]
        ?.contains(value.trim())
        ?: false

    private companion object {
        const val MAX_VALUES_PER_KEY = 64
    }
}
