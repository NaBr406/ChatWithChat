package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TieredMemoryRecallTest {

    @Test
    fun `core selector keeps every eligible current core fact without key or scope filters`() {
        val snapshot = snapshot(
            listOf(
                coreChunk(
                    id = "address_inferred",
                    text = "Use the inferred address.",
                    canonicalKey = "identity.preferred_address",
                    source = MemorySource.ASSISTANT_INFERRED,
                    updatedAt = 100
                ),
                coreChunk(
                    id = "address_confirmed",
                    text = "Call the user Alex.",
                    canonicalKey = "identity.preferred_address",
                    source = MemorySource.USER_CONFIRMED,
                    updatedAt = 10
                ),
                coreChunk("assistant_name", "My name is Small C.", "identity.assistant_name"),
                coreChunk("language", "Reply in Chinese.", "locale.response_language"),
                coreChunk("style", "Keep answers concise.", "communication.response_style"),
                coreChunk("boundary", "Never publish private credentials.", "boundary.credentials", type = "boundary"),
                coreChunk("unsupported", "Current project is Apollo.", "project.apollo", type = "project_context"),
                coreChunk(
                    id = "work_address",
                    text = "Use the work title Director.",
                    canonicalKey = "identity.preferred_address",
                    scope = MemoryScope.WORK
                )
            )
        )

        val core = snapshot.selectCoreResults(includePrivate = true)

        assertEquals(8, core.size)
        assertTrue(core.any { result -> result.entryId == "unsupported" })
        assertTrue(core.any { result -> result.entryId == "work_address" })
        assertTrue(core.any { result -> result.entryId == "address_inferred" })
    }

    @Test
    fun `query-only legacy communication style does not enter core`() {
        val first = legacyChunk("legacy_first", "Legacy first style.", updatedAt = 5)
        val latest = legacyChunk("legacy_latest", "Legacy latest style.", updatedAt = 10)
        val legacyCore = snapshot(listOf(first, latest)).selectCoreResults(includePrivate = true)

        assertTrue(legacyCore.isEmpty())

        val canonical = coreChunk("canonical", "Canonical response style.", "communication.response_style")
        val canonicalCore = snapshot(listOf(first, latest, canonical)).selectCoreResults(includePrivate = true)

        assertEquals(listOf("canonical"), canonicalCore.mapNotNull(MemoryRetrievalResult::entryId))
    }

    @Test
    fun `query facts are not promoted by the core selector`() {
        val preferredAddress = coreChunk(
            id = "preferred_address_query",
            text = "希望以后被称呼为大哥。",
            canonicalKey = "identity.preferred_address"
        ).copy(recallState = MemoryRecallState.QUERY)
        val assistantName = coreChunk(
            id = "assistant_name_query",
            text = "用户为 AI 取名为小c。",
            canonicalKey = "identity.assistant_name"
        ).copy(recallState = MemoryRecallState.QUERY)
        val workAddress = preferredAddress.copy(
            entryId = "work_address_query",
            scope = MemoryScope.WORK
        )
        val obsoleteAddress = preferredAddress.copy(
            entryId = "obsolete_address_query",
            validity = MemoryValidity.OBSOLETE,
            recallState = MemoryRecallState.MAINTENANCE_ONLY,
            supersededBy = "preferred_address_query"
        )
        val unrelatedQuery = coreChunk(
            id = "unrelated_query",
            text = "用户喜欢简短回复。",
            canonicalKey = "communication.response_style"
        ).copy(recallState = MemoryRecallState.QUERY)

        val core = snapshot(
            listOf(preferredAddress, assistantName, workAddress, obsoleteAddress, unrelatedQuery)
        ).selectCoreResults(includePrivate = true)

        assertTrue(core.isEmpty())
    }

    @Test
    fun `equal trust core conflicts use evidence observation time before update time`() {
        val olderEvidence = coreChunk(
            id = "older_evidence",
            text = "Call the user Old.",
            canonicalKey = "identity.preferred_address",
            updatedAt = 100
        ).copy(lastObservedAt = 10)
        val newerEvidence = coreChunk(
            id = "newer_evidence",
            text = "Call the user New.",
            canonicalKey = "identity.preferred_address",
            updatedAt = 20
        ).copy(lastObservedAt = 90)

        val core = snapshot(listOf(olderEvidence, newerEvidence)).selectCoreResults(includePrivate = true)

        assertEquals(
            listOf("newer_evidence", "older_evidence"),
            core.mapNotNull(MemoryRetrievalResult::entryId)
        )
    }

    private fun snapshot(chunks: List<MemoryCorpusChunk>) = MemoryCorpusSnapshot(
        corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
        canonicalSourceHash = "canonical",
        recallProjectionHash = "projection",
        generation = 7,
        chunks = chunks
    )

    private fun coreChunk(
        id: String,
        text: String,
        canonicalKey: String,
        source: String = MemorySource.EXPLICIT_USER_STATEMENT,
        updatedAt: Long = 20,
        scope: String = MemoryScope.GENERAL,
        type: String = "stable_profile"
    ) = chunk(id, text, type, source, updatedAt).copy(
        canonicalKey = canonicalKey,
        scope = scope,
        validity = MemoryValidity.CURRENT,
        recallState = MemoryRecallState.CORE
    )

    private fun legacyChunk(
        id: String,
        text: String,
        updatedAt: Long
    ) = chunk(id, text, "communication_style", MemorySource.EXPLICIT_USER_STATEMENT, updatedAt).copy(
        scope = MemoryScope.GENERAL,
        validity = MemoryValidity.CURRENT,
        recallState = MemoryRecallState.QUERY
    )

    private fun chunk(
        id: String,
        text: String,
        type: String,
        source: String,
        updatedAt: Long
    ) = MemoryCorpusChunk(
        chunkId = "MEMORY.md#$id#0",
        entryId = id,
        sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
        chunkIndex = 0,
        heading = "Stable Preferences",
        text = text,
        type = type,
        sensitivity = MemorySensitivity.NORMAL,
        source = source,
        chatId = null,
        createdAt = 1,
        updatedAt = updatedAt
    )
}
