package cn.nabr.chatwithchat.data.websearch

/**
 * 搜索后端。
 *
 * SearXNG 要自己搭实例，换来的是查询词不出自己的机器；AnySearch 是厂商托管的 API，零配置就能用，
 * 代价是查询词要发给第三方。默认落在 SearXNG 上，老用户升级后行为不变。
 */
enum class WebSearchBackend(val storageValue: String) {
    Searxng("searxng"),
    AnySearch("anysearch");

    companion object {
        fun fromStorageValue(value: String?): WebSearchBackend = when (value?.trim()?.lowercase()) {
            AnySearch.storageValue -> AnySearch
            else -> Searxng
        }
    }
}
