package cn.nabr.chatwithchat.data.websearch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AnySearch 的 REST 层。
 *
 * 官方文档只公开了 MCP 工具层，裸 HTTP 形状是对着真实响应确认的：请求 `POST /v1/search`，响应统一包一层
 * `{code, message, request_id, data}`，`code != 0` 时 HTTP 状态码仍可能是 200，所以业务码必须单独判。
 */
@Serializable
data class AnySearchSearchRequest(
    val query: String,
    @SerialName("max_results")
    val maxResults: Int
)

@Serializable
data class AnySearchSearchResponse(
    val code: Int = 0,
    val message: String? = null,
    val data: AnySearchSearchData? = null
)

@Serializable
data class AnySearchSearchData(
    val results: List<AnySearchSearchResult> = emptyList()
)

@Serializable
data class AnySearchSearchResult(
    val title: String? = null,
    val url: String? = null,
    val snippet: String? = null,
    val content: String? = null
) {
    fun toWebSearchResult(): WebSearchResult? {
        val resolvedUrl = url?.trim()?.takeIfHttpUrl() ?: return null

        // snippet 是检索摘要（实测 300 字上下），content 是正文全文（实测 4 KB 起）。摘要缺失时退到正文，
        // 但必须自己再截一刀：这段会原样进模型上下文。
        val resolvedSnippet = snippet?.trim()?.takeIf { it.isNotBlank() }
            ?: content?.trim()?.take(MAX_FALLBACK_SNIPPET_CHARS).orEmpty()

        return WebSearchResult(
            title = title?.trim().orEmpty(),
            url = resolvedUrl,
            snippet = resolvedSnippet,
            source = ANYSEARCH_SOURCE
        )
    }
}

private const val ANYSEARCH_SOURCE = "anysearch"
private const val MAX_FALLBACK_SNIPPET_CHARS = 500
