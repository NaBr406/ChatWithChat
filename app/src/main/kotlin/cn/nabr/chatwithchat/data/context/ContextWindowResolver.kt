package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.database.dao.PlatformModelV2Dao
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.datastore.ContextWindowOverrideStore
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
    private val platformModelV2Dao: PlatformModelV2Dao,
    private val contextWindowOverrideStore: ContextWindowOverrideStore = ContextWindowOverrideStore.None
) {

    suspend fun resolve(platform: PlatformV2, model: String = platform.model): Int =
        resolveDetailed(platform, model).tokens

    /**
     * Resolves the window together with its provenance so the settings UI can warn when the number is
     * only a conservative provider fallback rather than a discovered or curated model value.
     */
    suspend fun resolveDetailed(
        platform: PlatformV2,
        model: String = platform.model
    ): ContextWindowResolution {
        val userOverride = runCatching {
            contextWindowOverrideStore.getContextWindowOverride(platform.uid)
        }.getOrNull()?.takeIf { it > 0 }
        val persisted = if (platform.compatibleType == ClientType.OLLAMA) {
            null
        } else {
            runCatching {
                platformModelV2Dao.getModel(platform.uid, model.trim())
            }.getOrNull()?.contextWindow?.takeIf { it > 0 }
        }

        return resolveContextWindow(
            userOverride = userOverride,
            persistedWindow = persisted,
            clientType = platform.compatibleType,
            model = model
        )
    }

    /**
     * Steps 2 and 3 only. Exposed for callers that already know no persisted row exists, and for
     * tests that must not touch Room.
     */
    fun resolveWithoutPersistence(clientType: ClientType, model: String): Int =
        resolveWithoutPersistenceDetailed(clientType, model).tokens

    fun resolveWithoutPersistenceDetailed(
        clientType: ClientType,
        model: String
    ): ContextWindowResolution = resolveContextWindowWithoutPersistence(clientType, model)
}

internal fun resolveContextWindowWithoutPersistence(
    clientType: ClientType,
    model: String
): ContextWindowResolution = resolveContextWindow(
    userOverride = null,
    persistedWindow = null,
    clientType = clientType,
    model = model
)

internal fun resolveContextWindow(
    userOverride: Int?,
    persistedWindow: Int?,
    clientType: ClientType,
    model: String
): ContextWindowResolution {
    val automatic = when {
        clientType == ClientType.OLLAMA -> ContextWindowResolution(
            tokens = ProviderContextPolicy.forClientType(clientType).fallbackModelContextWindow,
            source = ContextWindowSource.OLLAMA_FALLBACK
        )

        persistedWindow != null && persistedWindow > 0 -> ContextWindowResolution(
            tokens = persistedWindow,
            source = ContextWindowSource.DISCOVERED
        )

        else -> {
            val tabled = ModelContextWindowTable.lookup(clientType, model)
            if (tabled != null) {
                ContextWindowResolution(tokens = tabled, source = ContextWindowSource.MODEL_TABLE)
            } else {
                ContextWindowResolution(
                    tokens = ProviderContextPolicy.forClientType(clientType).fallbackModelContextWindow,
                    source = ContextWindowSource.PROVIDER_FALLBACK
                )
            }
        }
    }

    // A manual provider value is a fallback for an otherwise unknown window, not an override of a
    // real provider-discovered or curated model value. This keeps the selection UI scoped to the
    // unknown-model case and prevents stale user input from shadowing better automatic data later.
    return if (automatic.source.isEstimated) {
        userOverride?.takeIf { it > 0 }?.let { tokens ->
            ContextWindowResolution(
                tokens = tokens,
                source = ContextWindowSource.USER_OVERRIDE,
                detectedSource = automatic.source
            )
        } ?: automatic
    } else {
        automatic
    }
}

enum class ContextWindowSource {
    /** A positive value explicitly selected by the user for this provider. */
    USER_OVERRIDE,

    /** A positive value discovered from the provider and persisted on `platform_model_v2`. */
    DISCOVERED,

    /** A curated model-family value maintained in [ModelContextWindowTable]. */
    MODEL_TABLE,

    /** The provider-specific conservative default because no trusted value was available. */
    PROVIDER_FALLBACK,

    /** Ollama always uses its conservative default because the OpenAI-compatible endpoint hides num_ctx. */
    OLLAMA_FALLBACK;

    val isEstimated: Boolean
        get() = this == PROVIDER_FALLBACK || this == OLLAMA_FALLBACK
}

data class ContextWindowResolution(
    val tokens: Int,
    val source: ContextWindowSource,
    /** The automatic result before a user fallback value was applied. */
    val detectedSource: ContextWindowSource = source
)

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
