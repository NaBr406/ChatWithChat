package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.model.ClientType

/**
 * Whether a model may use OpenAI's server-side compaction (`context_management` on `/v1/responses`).
 *
 * This gate exists only as a safety net. Server-side compaction shipped with the Responses API and
 * is native from GPT-5.4 onward, so nearly every official model in service supports it; the gate is
 * **not** there to exclude mainstream models. It exists because the app lets users type any model
 * name into `PlatformV2.model`: a typo, a gateway alias, or a retired name would otherwise turn
 * every request into a hard failure. Unknown families default to *unsupported* — the model still
 * chats, just on the app-side checkpoint path.
 *
 * This table will go stale. All updates land here and nowhere else: a new family starts as
 * unsupported until someone confirms against provider documentation and adds it.
 */
object NativeCompactionModelGate {

    /**
     * Families confirmed to support server-side compaction. Server-side compaction was introduced by
     * GPT-5.2 and is native from GPT-5.4 onward, so `gpt-5` and `gpt-5.1` are deliberately absent:
     * they predate the feature, and a bare `gpt-5` prefix would silently admit them.
     *
     * A newly released family — `gpt-5.5`, say — starts out unsupported and must be added here by
     * hand after checking the provider documentation.
     */
    private val SUPPORTED_FAMILIES: List<String> = listOf(
        "gpt-5.2",
        "gpt-5.3",
        "gpt-5.4"
    )

    /**
     * True only for the official OpenAI client type. Third-party OpenAI-compatible gateways reached
     * through `CUSTOM` must never receive `context_management`, whatever the model name says.
     */
    fun supportsServerSideCompaction(clientType: ClientType, model: String): Boolean {
        if (clientType != ClientType.OPENAI) return false

        val normalized = model.trim().lowercase().substringAfterLast('/')
        if (normalized.isBlank()) return false

        return SUPPORTED_FAMILIES.any { normalized.startsWith(it) }
    }
}
