package cn.nabr.chatwithchat.data.websearch

import cn.nabr.chatwithchat.data.network.NetworkClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class SearchDecision(
    val shouldSearch: Boolean = false,
    val queries: List<String> = emptyList(),
    val reason: String? = null
) {
    companion object {
        val NoSearch = SearchDecision()
    }
}

@Serializable
private data class SearchDecisionPayload(
    @SerialName("shouldSearch")
    val shouldSearch: Boolean = false,
    val queries: List<String> = emptyList(),
    val reason: String? = null
)

internal object SearchDecisionParser {
    fun parse(rawText: String): SearchDecision {
        val jsonText = rawText.extractJsonObject() ?: return SearchDecision.NoSearch
        val payload = runCatching {
            NetworkClient.json.decodeFromString<SearchDecisionPayload>(jsonText)
        }.getOrNull() ?: return SearchDecision.NoSearch
        val queries = payload.queries
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_SEARCH_DECISION_QUERIES)

        return SearchDecision(
            shouldSearch = payload.shouldSearch && queries.isNotEmpty(),
            queries = queries,
            reason = payload.reason?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    private fun String.extractJsonObject(): String? {
        val start = indexOf('{')
        val end = lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return substring(start, end + 1)
    }
}

internal const val MAX_SEARCH_DECISION_QUERIES = 2

