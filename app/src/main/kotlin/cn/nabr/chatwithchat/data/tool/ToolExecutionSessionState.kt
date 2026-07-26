package cn.nabr.chatwithchat.data.tool

/**
 * Per-request, in-memory state shared by one tool-loop execution session. It is never serialized
 * into a provider request or persisted with a chat message.
 */
class ToolExecutionSessionState internal constructor() {
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
