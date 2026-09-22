package cn.nabr.chatwithchat.data.datastore

/**
 * Durable, provider-scoped override for the model context window.
 *
 * This is deliberately separate from the discovered model metadata: discovery can be wrong, missing, or
 * intentionally ignored (Ollama), while the user may know the actual server-side value. When present,
 * this override is authoritative for budget assembly.
 */
interface ContextWindowOverrideStore {
    suspend fun getContextWindowOverride(platformUid: String): Int?

    suspend fun setContextWindowOverride(platformUid: String, tokens: Int?)

    companion object {
        val None: ContextWindowOverrideStore = object : ContextWindowOverrideStore {
            override suspend fun getContextWindowOverride(platformUid: String): Int? = null

            override suspend fun setContextWindowOverride(platformUid: String, tokens: Int?) = Unit
        }
    }
}
