package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.dao.PlatformModelV2Dao
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the *model's* input context window, in tokens.
 *
 * This is deliberately separate from [ProviderContextPolicy.maxContextTokens], which is only the
 * local construction budget for the legacy extractive path. Confusing the two makes RAW_PREFERRED
 * compact on the very first turn.
 *
 * Resolution order:
 *  1. The value discovered from the provider's model-list endpoint and persisted on
 *     `platform_model_v2.context_window`.
 *  2. [ModelContextWindowTable], a small hand-maintained model-family prefix table.
 *  3. [ProviderContextPolicy.fallbackModelContextWindow] for this [ClientType].
 *
 * A window is never inferred from the model *name* (a model called `foo-1m` or `bar-128k` proves
 * nothing about what the endpoint will actually accept).
 */
@Singleton
class ContextWindowResolver @Inject constructor(
    private val platformModelV2Dao: PlatformModelV2Dao
) {

    suspend fun resolve(platform: PlatformV2, model: String = platform.model): Int {
        val policy = ProviderContextPolicy.forClientType(platform.compatibleType)

        // Ollama short-circuit: never trust a discovered or tabled window. The OpenAI-compatible
        // endpoint this app uses cannot pass num_ctx, so the server's own limit (default 4096)
        // governs and silently truncates anything larger. See Task 0 of the context-management plan.
        if (platform.compatibleType == ClientType.OLLAMA) {
            return policy.fallbackModelContextWindow
        }

        val persisted = runCatching {
            platformModelV2Dao.getModel(platform.uid, model.trim())
        }.getOrNull()?.contextWindow?.takeIf { it > 0 }
        if (persisted != null) return persisted

        return resolveWithoutPersistence(platform.compatibleType, model)
    }

    /**
     * Steps 2 and 3 only. Exposed for callers that already know no persisted row exists, and for
     * tests that must not touch Room.
     */
    fun resolveWithoutPersistence(clientType: ClientType, model: String): Int {
        val policy = ProviderContextPolicy.forClientType(clientType)
        if (clientType == ClientType.OLLAMA) return policy.fallbackModelContextWindow

        return ModelContextWindowTable.lookup(clientType, model)
            ?: policy.fallbackModelContextWindow
    }
}

/**
 * Hand-maintained prefix table of known model families.
 *
 * Kept intentionally small and conservative, and deliberately centralised in one place: it *will*
 * go stale. An unknown or newly released family must fall through to the provider fallback rather
 * than be guessed at. Add entries only for windows confirmed against provider documentation.
 */
internal object ModelContextWindowTable {

    private val OPENAI_FAMILIES: List<Pair<String, Int>> = listOf(
        "gpt-5.4" to 1_000_000,
        "gpt-5.3" to 400_000,
        "gpt-5.2" to 400_000,
        "gpt-5" to 400_000,
        "gpt-4.1" to 1_047_576,
        "gpt-4o" to 128_000,
        "o4" to 200_000,
        "o3" to 200_000,
        "o1" to 200_000,
        "gpt-4-turbo" to 128_000,
        "gpt-4" to 8_192,
        "gpt-3.5-turbo" to 16_385
    )

    /**
     * Third-party gateways reached through [ClientType.CUSTOM]. Only families whose window is
     * stable and well documented belong here; a gateway is free to serve something else under the
     * same name, so these are a best-effort improvement over the blanket fallback, not a guarantee.
     */
    private val CUSTOM_FAMILIES: List<Pair<String, Int>> = listOf(
        // DeepSeek's current v4 API documentation lists a 1M input context for both models.
        // Keep the entries ahead of the older 64K v3 families so model discovery fallback does
        // not collapse these models to the generic Custom-provider window.
        "deepseek-v4-flash" to 1_000_000,
        "deepseek-v4-pro" to 1_000_000,
        "deepseek-reasoner" to 65_536,
        "deepseek-chat" to 65_536,
        "qwen3" to 32_768,
        "qwen2.5" to 32_768,
        "glm-4" to 128_000,
        "kimi-k2" to 128_000,
        "moonshot-v1-128k" to 128_000,
        "moonshot-v1-32k" to 32_768,
        "moonshot-v1-8k" to 8_192
    )

    fun lookup(clientType: ClientType, model: String): Int? {
        val normalized = model.trim().lowercase().substringAfterLast('/')
        if (normalized.isBlank()) return null

        val families = when (clientType) {
            ClientType.OPENAI -> OPENAI_FAMILIES
            ClientType.CUSTOM -> CUSTOM_FAMILIES
            else -> return null
        }

        // Longest prefix wins so that "gpt-5.4" is not shadowed by "gpt-5".
        return families
            .filter { (prefix, _) -> normalized.startsWith(prefix) }
            .maxByOrNull { (prefix, _) -> prefix.length }
            ?.second
    }
}
