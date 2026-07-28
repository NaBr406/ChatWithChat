package cn.nabr.chatwithchat.data.memory

internal data class MarkdownMemoryMetadataParse(
    val values: Map<String, String>,
    val error: String? = null
)

internal object MarkdownMemoryMetadataPolicy {
    const val COMMENT_PREFIX = "<!-- memory:"
    const val COMMENT_SUFFIX = "-->"

    val KNOWN_KEYS = setOf(
        "id",
        "type",
        "sensitivity",
        "source",
        "chat",
        "created",
        "updated",
        "canonical_key",
        "scope",
        "observed",
        "validity",
        "superseded_by",
        "recall",
        "evidence"
    )

    fun parseComment(commentLine: String): MarkdownMemoryMetadataParse {
        if (commentLine.length > MAX_COMMENT_CHARS) {
            return MarkdownMemoryMetadataParse(emptyMap(), "metadata comment too long")
        }
        if (!commentLine.startsWith(COMMENT_PREFIX) || !commentLine.endsWith(COMMENT_SUFFIX)) {
            return MarkdownMemoryMetadataParse(emptyMap(), "malformed metadata comment")
        }
        val body = commentLine
            .removePrefix(COMMENT_PREFIX)
            .removeSuffix(COMMENT_SUFFIX)
            .trim()
        if (body.isBlank()) return MarkdownMemoryMetadataParse(emptyMap(), "missing metadata")

        val tokens = body.split(METADATA_WHITESPACE_REGEX)
        if (tokens.size > MAX_METADATA_FIELDS) {
            return MarkdownMemoryMetadataParse(emptyMap(), "too many metadata fields")
        }
        val values = linkedMapOf<String, String>()
        tokens.forEach { token ->
            val separatorIndex = token.indexOf('=')
            if (separatorIndex <= 0 || separatorIndex == token.lastIndex || token.indexOf('=', separatorIndex + 1) >= 0) {
                return MarkdownMemoryMetadataParse(values, "malformed metadata token")
            }
            val key = token.substring(0, separatorIndex)
            val value = token.substring(separatorIndex + 1)
            if (!isSafeMetadataKey(key)) {
                return MarkdownMemoryMetadataParse(values, "unsafe metadata key")
            }
            if (!isSafeMetadataValue(value)) {
                return MarkdownMemoryMetadataParse(values, "unsafe metadata value")
            }
            if (values.put(key, value) != null) {
                return MarkdownMemoryMetadataParse(values, "duplicate metadata key")
            }
        }
        return MarkdownMemoryMetadataParse(values)
    }

    fun validateEntry(entry: MarkdownMemoryEntry): String? {
        if (!isSafeReference(entry.id)) return "invalid id"
        if (!isSafeSlug(entry.type)) return "invalid type"
        if (entry.sensitivity !in VALID_SENSITIVITIES) return "invalid sensitivity"
        if (entry.source !in VALID_SOURCES) return "invalid source"
        if (entry.chatId != null && entry.chatId < 0) return "invalid chat"
        if (entry.createdAt < 0L || entry.updatedAt < 0L || entry.lastObservedAt < 0L) return "invalid timestamp"
        if (entry.canonicalKey != null && !isCanonicalKey(entry.canonicalKey)) return "invalid canonical key"
        if (!isScope(entry.scope)) return "invalid scope"
        if (entry.validity !in MemoryValidity.VALUES) return "invalid validity"
        if (entry.recallState !in MemoryRecallState.VALUES) return "invalid recall state"
        if (entry.supersededBy != null && !isSafeReference(entry.supersededBy)) return "invalid supersession target"
        if (!areEvidenceRefsValid(entry.evidenceRefs)) return "invalid evidence refs"
        if (entry.extraMetadata.size > MAX_EXTRA_METADATA_FIELDS) return "too many extra metadata fields"
        if (entry.extraMetadata.any { (key, value) ->
                key in KNOWN_KEYS || !isSafeMetadataKey(key) || !isSafeMetadataValue(value)
            }
        ) {
            return "invalid extra metadata"
        }
        return validateLifecycle(entry)
    }

    fun parseTimestamp(value: String?): Long? = value
        ?.takeIf { raw -> raw.isNotEmpty() && raw.all(Char::isDigit) }
        ?.toLongOrNull()
        ?.takeIf { timestamp -> timestamp >= 0L }

    fun parseChatId(value: String?): Int? = value
        ?.takeIf { raw -> raw.isNotEmpty() && raw.all(Char::isDigit) }
        ?.toIntOrNull()
        ?.takeIf { chatId -> chatId >= 0 }

    fun encodeEvidenceRefs(values: List<String>): String {
        require(areEvidenceRefsValid(values)) { "invalid evidence refs" }
        return values.distinct().joinToString(",")
    }

    fun decodeEvidenceRefs(value: String?): List<String>? {
        if (value == null) return emptyList()
        if (value.isBlank()) return null
        val refs = value.split(',')
        if (!areEvidenceRefsValid(refs) || refs.distinct().size != refs.size) return null
        return refs
    }

    fun isCanonicalKey(value: String): Boolean =
        value.length <= MAX_CANONICAL_KEY_CHARS && CANONICAL_KEY_REGEX.matches(value)

    fun isScope(value: String): Boolean = when (value) {
        MemoryScope.GENERAL,
        MemoryScope.WORK,
        MemoryScope.PERSONAL -> true
        else -> PROJECT_SCOPE_REGEX.matches(value) || CHAT_SCOPE_REGEX.matches(value)
    }

    fun isSafeReference(value: String): Boolean =
        value.length in 1..MAX_REFERENCE_CHARS && REFERENCE_REGEX.matches(value)

    fun isSafeMetadataKey(value: String): Boolean =
        value.length in 1..MAX_METADATA_KEY_CHARS && METADATA_KEY_REGEX.matches(value)

    fun isSafeMetadataValue(value: String): Boolean =
        value.length in 1..MAX_METADATA_VALUE_CHARS &&
            value.all { character ->
                character.code in PRINTABLE_ASCII_RANGE &&
                    !character.isWhitespace() &&
                    character != '=' &&
                    character != '<' &&
                    character != '>'
            }

    private fun validateLifecycle(entry: MarkdownMemoryEntry): String? = when (entry.validity) {
        MemoryValidity.CURRENT -> when {
            entry.supersededBy != null -> "current entry cannot be superseded"
            entry.recallState == MemoryRecallState.CORE && entry.canonicalKey == null -> "core entry requires identity"
            else -> null
        }
        MemoryValidity.CONTESTED -> when {
            entry.canonicalKey == null -> "contested entry requires identity"
            entry.supersededBy != null -> "contested entry cannot be superseded"
            entry.recallState != MemoryRecallState.MAINTENANCE_ONLY -> "contested entry must be maintenance only"
            else -> null
        }
        MemoryValidity.OBSOLETE -> when {
            entry.canonicalKey == null -> "obsolete entry requires identity"
            entry.supersededBy == null -> "obsolete entry requires supersession target"
            entry.recallState != MemoryRecallState.MAINTENANCE_ONLY -> "obsolete entry must be maintenance only"
            else -> null
        }
        else -> "invalid validity"
    }

    private fun isSafeSlug(value: String): Boolean =
        value.length in 1..MAX_SLUG_CHARS && SLUG_REGEX.matches(value)

    private fun areEvidenceRefsValid(values: List<String>): Boolean =
        values.size <= MAX_EVIDENCE_REFS &&
            values.distinct().size == values.size &&
            values.sumOf { value -> value.length } + (values.size - 1).coerceAtLeast(0) <= MAX_EVIDENCE_TOTAL_CHARS &&
            values.all(::isSafeReference)

    private const val MAX_COMMENT_CHARS = 2_048
    private const val MAX_METADATA_FIELDS = 24
    private const val MAX_EXTRA_METADATA_FIELDS = 8
    private const val MAX_METADATA_KEY_CHARS = 32
    private const val MAX_METADATA_VALUE_CHARS = 1_024
    private const val MAX_REFERENCE_CHARS = 128
    private const val MAX_CANONICAL_KEY_CHARS = 96
    private const val MAX_SLUG_CHARS = 64
    private const val MAX_EVIDENCE_REFS = 24
    private const val MAX_EVIDENCE_TOTAL_CHARS = 1_024
    private val PRINTABLE_ASCII_RANGE = 0x21..0x7e
    private val METADATA_WHITESPACE_REGEX = Regex("\\s+")
    private val METADATA_KEY_REGEX = Regex("[a-z][a-z0-9_]*")
    private val REFERENCE_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")
    private val SLUG_REGEX = Regex("[a-z][a-z0-9_]*")
    private val CANONICAL_KEY_REGEX = Regex("[a-z0-9][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)+")
    private val PROJECT_SCOPE_REGEX = Regex("project:[a-z0-9][a-z0-9_-]{0,63}")
    private val CHAT_SCOPE_REGEX = Regex("chat:[0-9]{1,20}")
    private val VALID_SENSITIVITIES = setOf(
        MemorySensitivity.NORMAL,
        MemorySensitivity.PRIVATE,
        MemorySensitivity.SENSITIVE
    )
    private val VALID_SOURCES = setOf(
        MemorySource.ASSISTANT_INFERRED,
        MemorySource.EXPLICIT_USER_STATEMENT,
        MemorySource.USER_CONFIRMED
    )
}
