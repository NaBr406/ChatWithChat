package cn.nabr.chatwithchat.data.websearch

interface WebSearchRepository {
    suspend fun search(query: String, limit: Int): Result<List<WebSearchResult>>
}

