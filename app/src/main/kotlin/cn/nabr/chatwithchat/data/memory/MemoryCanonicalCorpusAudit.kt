package cn.nabr.chatwithchat.data.memory

internal data class MemoryCanonicalCorpusAudit(
    val activeDuplicateKeys: List<String>,
    val aliasKeys: List<String>,
    val missingCanonicalKeyIds: List<String>,
    val obsoleteVisibleIds: List<String>,
    val identityBundleIds: List<String>
) {
    val isHealthy: Boolean
        get() = activeDuplicateKeys.isEmpty() &&
            aliasKeys.isEmpty() &&
            missingCanonicalKeyIds.isEmpty() &&
            obsoleteVisibleIds.isEmpty() &&
            identityBundleIds.isEmpty()
}

internal object MemoryCanonicalCorpusAuditor {
    fun audit(entries: List<MarkdownMemoryEntry>): MemoryCanonicalCorpusAudit {
        val current = entries.filter { it.validity == MemoryValidity.CURRENT }
        val duplicateKeys = current
            .filter { it.canonicalKey != null }
            .groupBy { entry ->
                MemoryCanonicalIdentityPolicy.normalizeCanonicalKey(
                    checkNotNull(entry.canonicalKey),
                    entry.scope
                ) to MemoryCanonicalIdentityPolicy.normalizeScope(entry.canonicalKey, entry.scope)
            }
            .filterValues { it.size > 1 }
            .keys
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .map { (key, scope) -> "$key@$scope" }
        val aliases = entries
            .mapNotNull { entry ->
                entry.canonicalKey
                    ?.takeIf { key ->
                        MemoryCanonicalIdentityPolicy.normalizeCanonicalKey(key, entry.scope) != key
                            || MemoryCanonicalIdentityPolicy.normalizeScope(key, entry.scope) != entry.scope
                    }
                    ?.let { key -> "${entry.id}:$key" }
            }
            .sorted()
        val missing = current
            .filter { it.canonicalKey == null }
            .map(MarkdownMemoryEntry::id)
            .sorted()
        val obsoleteVisible = entries
            .filter { it.validity == MemoryValidity.OBSOLETE && it.recallState != MemoryRecallState.MAINTENANCE_ONLY }
            .map(MarkdownMemoryEntry::id)
            .sorted()
        val identityBundle = current
            .filter { entry ->
                entry.canonicalKey?.let { key ->
                    MemoryCanonicalIdentityPolicy.isAddressingIdentity(key, entry.scope) &&
                        entry.extraMetadata[ADDRESSING_REVIEW_HASH] != entry.text.sha256Utf8()
                } == true
            }
            .map(MarkdownMemoryEntry::id)
            .sorted()
        return MemoryCanonicalCorpusAudit(
            activeDuplicateKeys = duplicateKeys,
            aliasKeys = aliases,
            missingCanonicalKeyIds = missing,
            obsoleteVisibleIds = obsoleteVisible,
            identityBundleIds = identityBundle
        )
    }

    const val ADDRESSING_REVIEW_HASH = "addressing_review_hash"
}
