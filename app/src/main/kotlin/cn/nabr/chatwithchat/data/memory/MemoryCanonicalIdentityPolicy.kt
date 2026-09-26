package cn.nabr.chatwithchat.data.memory

/**
 * Canonical identity changes are intentionally narrower than canonical-key syntax validation.
 * Preferred user address and the assistant name are one durable addressing bundle. The assistant
 * key remains accepted as a legacy alias so old documents and model proposals can be repaired
 * without losing either side of the bundle.
 */
internal object MemoryCanonicalIdentityPolicy {
    const val PREFERRED_ADDRESS_KEY = "identity.preferred_address"
    const val ASSISTANT_NAME_KEY = "identity.assistant_name"

    private val LEGACY_REBINDINGS = mapOf(
        "identity.nickname" to PREFERRED_ADDRESS_KEY,
        "identity.legacy_address" to PREFERRED_ADDRESS_KEY,
        ASSISTANT_NAME_KEY to PREFERRED_ADDRESS_KEY
    )

    private val CANONICAL_ALIASES = mapOf(
        "project.gptmobile_ai_app" to "project.chatwithchat"
    )

    private const val LEGACY_CHATWITHCHAT_SCOPE = "project:gptmobile-ai-app"
    private const val CHATWITHCHAT_SCOPE = "project:chatwithchat"

    private val ADDRESSING_KEYS = setOf(
        PREFERRED_ADDRESS_KEY,
        ASSISTANT_NAME_KEY,
        "identity.nickname",
        "identity.legacy_address"
    )

    fun normalizeCanonicalKey(canonicalKey: String, scope: String): String =
        when {
            scope == MemoryScope.GENERAL && canonicalKey in ADDRESSING_KEYS -> PREFERRED_ADDRESS_KEY
            canonicalKey in CANONICAL_ALIASES -> CANONICAL_ALIASES.getValue(canonicalKey)
            else -> canonicalKey
        }

    fun normalizeScope(canonicalKey: String?, scope: String): String =
        if (canonicalKey in setOf("project.gptmobile_ai_app", "project.chatwithchat") &&
            scope == LEGACY_CHATWITHCHAT_SCOPE
        ) CHATWITHCHAT_SCOPE else scope

    fun isAddressingKey(canonicalKey: String?): Boolean = canonicalKey in ADDRESSING_KEYS

    fun isAddressingIdentity(canonicalKey: String?, scope: String): Boolean =
        scope == MemoryScope.GENERAL && isAddressingKey(canonicalKey)

    fun allowsRebinding(
        fromKey: String?,
        fromScope: String,
        toKey: String,
        toScope: String
    ): Boolean {
        if (fromKey == null) return true
        val normalizedFromKey = normalizeCanonicalKey(fromKey, fromScope)
        val normalizedToKey = normalizeCanonicalKey(toKey, toScope)
        val normalizedFromScope = normalizeScope(fromKey, fromScope)
        val normalizedToScope = normalizeScope(toKey, toScope)
        if (normalizedFromKey == normalizedToKey && normalizedFromScope == normalizedToScope) return true
        if (normalizedFromScope != normalizedToScope) return false
        if (
            LEGACY_REBINDINGS[fromKey] == toKey &&
            (fromKey != ASSISTANT_NAME_KEY || fromScope == MemoryScope.GENERAL)
        ) {
            return true
        }
        return !isIdentityKey(normalizedFromKey) &&
            !isIdentityKey(normalizedToKey) &&
            canonicalNamespace(normalizedFromKey) == canonicalNamespace(normalizedToKey)
    }

    fun isIdentityKey(canonicalKey: String?): Boolean = canonicalKey?.startsWith("identity.") == true

    fun canonicalNamespace(canonicalKey: String): String = canonicalKey.substringBeforeLast('.')
}
