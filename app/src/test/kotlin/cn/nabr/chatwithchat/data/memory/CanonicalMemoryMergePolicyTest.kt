package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalMemoryMergePolicyTest {

    private val codec = MarkdownMemoryCodec()
    private val policy = CanonicalMemoryMergePolicy(codec)

    @Test
    fun `same fact only advances observation and replay is byte identical`() {
        val existing = canonicalEntry(
            id = "mem_response_style",
            text = "The user prefers concise answers.",
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 10,
            updatedAt = 20,
            lastObservedAt = 20,
            evidenceRefs = listOf("turn:existing")
        )
        val base = codec.renderLongTerm(listOf(existing))
        val observation = candidate(
            text = "  THE user prefers concise\nanswers.  ",
            source = existing.source,
            evidenceAt = 40,
            evidenceRefs = listOf("turn:new")
        )

        val first = policy.merge(base, listOf(observation), mutationAt = 100)
        val observed = currentEntries(first.markdown).single()

        assertEquals(1, first.acceptedCandidateCount)
        assertEquals(0, first.materialMutationCount)
        assertFalse(first.requiresIndexSync)
        assertEquals(existing.id, observed.id)
        assertEquals(existing.text, observed.text)
        assertEquals(existing.createdAt, observed.createdAt)
        assertEquals(existing.updatedAt, observed.updatedAt)
        assertEquals(40, observed.lastObservedAt)
        assertEquals(listOf("turn:existing", "turn:new"), observed.evidenceRefs)

        val replay = policy.merge(first.markdown, listOf(observation), mutationAt = 101)

        assertEquals(first.markdown, replay.markdown)
        assertEquals(0, replay.materialMutationCount)
        assertFalse(replay.requiresIndexSync)
    }

    @Test
    fun `same fact with stronger newer candidate remains observation only`() {
        val existing = canonicalEntry(
            id = "mem_same_fact_trust",
            text = "The user prefers concise answers.",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 10,
            updatedAt = 20,
            lastObservedAt = 20,
            recallState = MemoryRecallState.QUERY,
            evidenceRefs = listOf("turn:existing")
        )
        val sameFact = candidate(
            text = "  THE user prefers concise\nanswers.  ",
            sensitivity = MemorySensitivity.SENSITIVE,
            source = MemorySource.USER_CONFIRMED,
            evidenceAt = 50,
            recallState = MemoryRecallState.CORE,
            evidenceRefs = listOf("turn:confirmed"),
            targetMemoryId = existing.id
        )

        val result = policy.merge(codec.renderLongTerm(listOf(existing)), listOf(sameFact), mutationAt = 100)
        val observed = currentEntries(result.markdown).single()

        assertEquals(1, result.acceptedCandidateCount)
        assertEquals(0, result.materialMutationCount)
        assertFalse(result.requiresIndexSync)
        assertEquals(existing.id, observed.id)
        assertEquals(existing.text, observed.text)
        assertEquals(existing.sensitivity, observed.sensitivity)
        assertEquals(existing.source, observed.source)
        assertEquals(existing.createdAt, observed.createdAt)
        assertEquals(existing.updatedAt, observed.updatedAt)
        assertEquals(existing.recallState, observed.recallState)
        assertEquals(50, observed.lastObservedAt)
        assertEquals(listOf("turn:confirmed", "turn:existing"), observed.evidenceRefs)
    }

    @Test
    fun `same fact legacy target backfills identity as material without index sync`() {
        val legacy = canonicalEntry(
            id = "mem_legacy_unkeyed",
            text = "The user is working on ChatWithChat.",
            type = "project_context",
            sensitivity = MemorySensitivity.PRIVATE,
            source = MemorySource.ASSISTANT_INFERRED,
            canonicalKey = null,
            createdAt = 11,
            updatedAt = 22,
            lastObservedAt = 22,
            recallState = MemoryRecallState.QUERY,
            evidenceRefs = listOf("turn:legacy"),
            extraMetadata = mapOf("future_flag" to "enabled")
        )
        val backfill = candidate(
            text = "  THE user is working on\nChatWithChat.  ",
            type = legacy.type,
            sensitivity = MemorySensitivity.SENSITIVE,
            source = MemorySource.USER_CONFIRMED,
            canonicalKey = "project.chatwithchat",
            scope = "project:chatwithchat",
            evidenceAt = 40,
            recallState = MemoryRecallState.CORE,
            evidenceRefs = listOf("turn:backfill"),
            targetMemoryId = legacy.id
        )

        val result = policy.merge(codec.renderLongTerm(listOf(legacy)), listOf(backfill), mutationAt = 100)
        val backfilled = currentEntries(result.markdown).single()

        assertEquals(1, result.acceptedCandidateCount)
        assertEquals(1, result.materialMutationCount)
        assertFalse(result.requiresIndexSync)
        assertEquals(legacy.id, backfilled.id)
        assertEquals(legacy.text, backfilled.text)
        assertEquals(legacy.sensitivity, backfilled.sensitivity)
        assertEquals(legacy.source, backfilled.source)
        assertEquals(legacy.createdAt, backfilled.createdAt)
        assertEquals(100, backfilled.updatedAt)
        assertEquals(legacy.recallState, backfilled.recallState)
        assertEquals(backfill.canonicalKey, backfilled.canonicalKey)
        assertEquals(backfill.scope, backfilled.scope)
        assertEquals(40, backfilled.lastObservedAt)
        assertEquals(listOf("turn:backfill", "turn:legacy"), backfilled.evidenceRefs)
        assertEquals(mapOf("future_flag" to "enabled"), backfilled.extraMetadata)
    }

    @Test
    fun `stronger older evidence wins while weaker newer evidence is rejected`() {
        val inferred = canonicalEntry(
            id = "mem_inferred",
            text = "The user prefers detailed answers.",
            source = MemorySource.ASSISTANT_INFERRED,
            lastObservedAt = 200
        )
        val stronger = candidate(
            text = "The user prefers concise answers.",
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            evidenceAt = 100,
            targetMemoryId = inferred.id
        )

        val strongerResult = policy.merge(codec.renderLongTerm(listOf(inferred)), listOf(stronger), mutationAt = 300)
        val strongerEntries = codec.parse(strongerResult.markdown).entries
        val active = strongerEntries.single { entry -> entry.validity == MemoryValidity.CURRENT }
        val history = strongerEntries.single { entry -> entry.validity == MemoryValidity.OBSOLETE }

        assertEquals(1, strongerResult.acceptedCandidateCount)
        assertEquals(1, strongerResult.materialMutationCount)
        assertTrue(strongerResult.requiresIndexSync)
        assertEquals(inferred.id, active.id)
        assertEquals(stronger.text, active.text)
        assertEquals(MemorySource.EXPLICIT_USER_STATEMENT, active.source)
        assertEquals(inferred.text, history.text)
        assertEquals(active.id, history.supersededBy)

        val confirmed = canonicalEntry(
            id = "mem_confirmed",
            text = "The user prefers concise answers.",
            source = MemorySource.USER_CONFIRMED,
            lastObservedAt = 100
        )
        val weaker = candidate(
            text = "The user prefers detailed answers.",
            source = MemorySource.ASSISTANT_INFERRED,
            evidenceAt = 400,
            targetMemoryId = confirmed.id
        )
        val confirmedBase = codec.renderLongTerm(listOf(confirmed))

        val weakerResult = policy.merge(confirmedBase, listOf(weaker), mutationAt = 500)

        assertEquals(confirmedBase, weakerResult.markdown)
        assertEquals(0, weakerResult.acceptedCandidateCount)
        assertEquals(0, weakerResult.materialMutationCount)
        assertFalse(weakerResult.requiresIndexSync)
    }

    @Test
    fun `equal trust uses evidence time and rejects stale candidate`() {
        val existing = canonicalEntry(
            id = "mem_equal_trust",
            text = "The user prefers concise answers.",
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            lastObservedAt = 100
        )
        val base = codec.renderLongTerm(listOf(existing))
        val stale = candidate(
            text = "The user prefers detailed answers.",
            source = existing.source,
            evidenceAt = 99,
            targetMemoryId = existing.id
        )
        val newer = stale.copy(evidenceAt = 101)

        val staleResult = policy.merge(base, listOf(stale), mutationAt = 200)
        val newerResult = policy.merge(base, listOf(newer), mutationAt = 200)

        assertEquals(base, staleResult.markdown)
        assertEquals(0, staleResult.acceptedCandidateCount)
        assertEquals(existing.id, currentEntries(newerResult.markdown).single().id)
        assertEquals(newer.text, currentEntries(newerResult.markdown).single().text)
        assertEquals(1, newerResult.materialMutationCount)
        assertTrue(newerResult.requiresIndexSync)
    }

    @Test
    fun `semantic replacement keeps active id and deterministic history across replay`() {
        val existing = canonicalEntry(
            id = "mem_stable_active",
            text = "The user prefers detailed answers.",
            source = MemorySource.ASSISTANT_INFERRED,
            createdAt = 11,
            updatedAt = 22,
            lastObservedAt = 22
        )
        val base = codec.renderLongTerm(listOf(existing))
        val replacement = candidate(
            text = "The user prefers concise answers.",
            source = MemorySource.USER_CONFIRMED,
            evidenceAt = 50,
            evidenceRefs = listOf("turn:confirmed"),
            targetMemoryId = existing.id
        )

        val first = policy.merge(base, listOf(replacement), mutationAt = 80)
        val second = policy.merge(base, listOf(replacement), mutationAt = 80)
        val entries = codec.parse(first.markdown).entries
        val active = entries.single { entry -> entry.validity == MemoryValidity.CURRENT }
        val history = entries.single { entry -> entry.validity == MemoryValidity.OBSOLETE }

        assertEquals(first.markdown, second.markdown)
        assertEquals(existing.id, active.id)
        assertEquals(existing.createdAt, active.createdAt)
        assertEquals(80, active.updatedAt)
        assertEquals(50, active.lastObservedAt)
        assertNotEquals(active.id, history.id)
        assertEquals(existing.text, history.text)
        assertEquals(MemoryValidity.OBSOLETE, history.validity)
        assertEquals(MemoryRecallState.MAINTENANCE_ONLY, history.recallState)
        assertEquals(active.id, history.supersededBy)

        val replay = policy.merge(first.markdown, listOf(replacement), mutationAt = 90)
        val replayEntries = codec.parse(replay.markdown).entries

        assertEquals(first.markdown, replay.markdown)
        assertEquals(1, replayEntries.count { entry -> entry.validity == MemoryValidity.CURRENT })
        assertEquals(1, replayEntries.count { entry -> entry.validity == MemoryValidity.OBSOLETE })
        assertEquals(0, replay.materialMutationCount)
        assertFalse(replay.requiresIndexSync)
    }

    @Test
    fun `duplicate current entries converge to stable survivor and retarget history`() {
        val lowerId = canonicalEntry(
            id = "mem_active_a",
            text = "The user prefers detailed answers.",
            source = MemorySource.ASSISTANT_INFERRED,
            lastObservedAt = 200
        )
        val strongerFact = canonicalEntry(
            id = "mem_active_z",
            text = "The user prefers concise answers.",
            source = MemorySource.USER_CONFIRMED,
            lastObservedAt = 100
        )
        val existingHistory = canonicalEntry(
            id = "mem_existing_history",
            text = "The user once preferred medium-length answers.",
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            validity = MemoryValidity.OBSOLETE,
            supersededBy = strongerFact.id,
            recallState = MemoryRecallState.MAINTENANCE_ONLY
        )
        val base = codec.renderLongTerm(listOf(lowerId, strongerFact, existingHistory))
        val repeatedFact = candidate(
            text = strongerFact.text,
            source = strongerFact.source,
            evidenceAt = strongerFact.lastObservedAt,
            targetMemoryId = strongerFact.id
        )

        val result = policy.merge(base, listOf(repeatedFact), mutationAt = 300)
        val entries = codec.parse(result.markdown).entries
        val active = entries.single { entry -> entry.validity == MemoryValidity.CURRENT }

        assertEquals(lowerId.id, active.id)
        assertEquals(strongerFact.text, active.text)
        assertEquals(MemorySource.USER_CONFIRMED, active.source)
        assertEquals(1, entries.count { entry -> entry.validity == MemoryValidity.CURRENT })
        assertTrue(entries.filter { entry -> entry.validity == MemoryValidity.OBSOLETE }.isNotEmpty())
        assertTrue(
            entries
                .filter { entry -> entry.validity == MemoryValidity.OBSOLETE }
                .all { entry -> entry.supersededBy == active.id }
        )
        assertTrue(result.materialMutationCount > 0)
        assertTrue(result.requiresIndexSync)

        val replay = policy.merge(
            result.markdown,
            listOf(repeatedFact.copy(targetMemoryId = active.id)),
            mutationAt = 301
        )

        assertEquals(result.markdown, replay.markdown)
    }

    @Test
    fun `different canonical keys and scopes coexist`() {
        val candidates = listOf(
            candidate(
                text = "The user's legal name is Lin Chen.",
                canonicalKey = "identity.legal_name",
                scope = MemoryScope.GENERAL,
                evidenceAt = 10
            ),
            candidate(
                text = "The user prefers the nickname A-Lin.",
                canonicalKey = "identity.preferred_address",
                scope = MemoryScope.GENERAL,
                evidenceAt = 11
            ),
            candidate(
                text = "At work, address the user as Engineer Chen.",
                canonicalKey = "identity.preferred_address",
                scope = MemoryScope.WORK,
                evidenceAt = 12
            )
        )

        val result = policy.merge(emptyDocument(), candidates, mutationAt = 20)
        val active = currentEntries(result.markdown)

        assertEquals(3, result.acceptedCandidateCount)
        assertEquals(3, result.materialMutationCount)
        assertTrue(result.requiresIndexSync)
        assertEquals(
            setOf(
                "identity.legal_name" to MemoryScope.GENERAL,
                "identity.preferred_address" to MemoryScope.GENERAL,
                "identity.preferred_address" to MemoryScope.WORK
            ),
            active.map { entry -> checkNotNull(entry.canonicalKey) to entry.scope }.toSet()
        )
        assertEquals(3, active.map { entry -> entry.id }.distinct().size)
    }

    @Test
    fun `candidate permutation has identical winner ids and markdown`() {
        val firstIdentityOlder = candidate(
            text = "The user prefers detailed answers.",
            evidenceAt = 20,
            evidenceRefs = listOf("turn:older")
        )
        val firstIdentityNewer = firstIdentityOlder.copy(
            text = "The user prefers concise answers.",
            evidenceAt = 30,
            evidenceRefs = listOf("turn:newer")
        )
        val otherIdentity = candidate(
            text = "The user prefers Chinese responses.",
            canonicalKey = "locale.response_language",
            evidenceAt = 25,
            evidenceRefs = listOf("turn:language")
        )
        val orders = listOf(
            listOf(firstIdentityOlder, firstIdentityNewer, otherIdentity),
            listOf(firstIdentityNewer, otherIdentity, firstIdentityOlder),
            listOf(otherIdentity, firstIdentityOlder, firstIdentityNewer),
            listOf(otherIdentity, firstIdentityNewer, firstIdentityOlder)
        )

        val results = orders.map { candidates -> policy.merge(emptyDocument(), candidates, mutationAt = 40) }

        assertTrue(results.all { result -> result.markdown == results.first().markdown })
        assertTrue(results.all { result -> result.acceptedCandidateCount == results.first().acceptedCandidateCount })
        assertTrue(results.all { result -> result.materialMutationCount == results.first().materialMutationCount })
        val active = currentEntries(results.first().markdown)
        assertEquals(2, active.size)
        assertEquals(
            firstIdentityNewer.text,
            active.single { entry -> entry.canonicalKey == firstIdentityNewer.canonicalKey }.text
        )
    }

    @Test
    fun `equal trust and evidence time uses stable tie break`() {
        val left = candidate(
            text = "The user prefers concise answers.",
            evidenceAt = 30,
            evidenceRefs = listOf("turn:a")
        )
        val right = left.copy(
            text = "The user prefers detailed answers.",
            evidenceRefs = listOf("turn:b")
        )

        val leftFirst = policy.merge(emptyDocument(), listOf(left, right), mutationAt = 40)
        val rightFirst = policy.merge(emptyDocument(), listOf(right, left), mutationAt = 40)

        assertEquals(leftFirst.markdown, rightFirst.markdown)
        assertEquals(1, currentEntries(leftFirst.markdown).size)
        assertTrue(currentEntries(leftFirst.markdown).single().text in setOf(left.text, right.text))
    }

    @Test
    fun `invalid candidate type identity lifecycle and evidence fail closed`() {
        val existing = canonicalEntry(id = "mem_validation", text = "The user prefers concise answers.")
        val base = codec.renderLongTerm(listOf(existing))
        val invalidCandidates = listOf(
            candidate(type = "unsupported_type"),
            candidate(canonicalKey = "identity"),
            candidate(scope = "project:Upper"),
            candidate(recallState = MemoryRecallState.MAINTENANCE_ONLY),
            candidate(source = "unknown_source"),
            candidate(evidenceAt = -1),
            candidate(evidenceRefs = listOf("unsafe ref")),
            candidate(
                type = "project_context",
                targetMemoryId = existing.id
            )
        )

        invalidCandidates.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                policy.merge(base, listOf(invalid), mutationAt = 100)
            }
        }
        assertEquals(base, codec.parse(base).rawMarkdown)
    }

    @Test
    fun `merged evidence is deterministic and bounded`() {
        val existingRefs = (0 until 20)
            .map { index -> "turn:existing-${index.toString().padStart(2, '0')}" }
            .reversed()
        val candidateRefs = (0 until 20).map { index -> "turn:new-${index.toString().padStart(2, '0')}" }
        val existing = canonicalEntry(
            id = "mem_evidence",
            text = "The user prefers concise answers.",
            evidenceRefs = existingRefs
        )
        val base = codec.renderLongTerm(listOf(existing))
        val forward = candidate(
            text = existing.text,
            evidenceAt = 40,
            evidenceRefs = candidateRefs,
            targetMemoryId = existing.id
        )
        val reverse = forward.copy(evidenceRefs = candidateRefs.reversed())

        val forwardResult = policy.merge(base, listOf(forward), mutationAt = 50)
        val reverseResult = policy.merge(base, listOf(reverse), mutationAt = 50)
        val evidence = currentEntries(forwardResult.markdown).single().evidenceRefs

        assertEquals(forwardResult.markdown, reverseResult.markdown)
        assertEquals(evidence.distinct(), evidence)
        assertEquals(evidence.sorted(), evidence)
        assertTrue(evidence.size <= 24)
        assertTrue(evidence.sumOf(String::length) + (evidence.size - 1).coerceAtLeast(0) <= 1_024)
        assertFalse(forwardResult.requiresIndexSync)
    }

    @Test
    fun `targeted merge preserves handwritten footer and unrelated entry`() {
        val target = canonicalEntry(
            id = "mem_target",
            text = "The user prefers detailed answers.",
            source = MemorySource.ASSISTANT_INFERRED,
            extraMetadata = mapOf("future_flag" to "enabled")
        )
        val unrelated = canonicalEntry(
            id = "mem_unrelated",
            text = "The user is working on ChatWithChat.",
            type = "project_context",
            canonicalKey = "project.chatwithchat",
            scope = "project:chatwithchat",
            evidenceRefs = listOf("turn:project")
        )
        val footer = "## 手写附录\n\n  Keep this spacing and handwritten text.\n\n<!-- unrelated:future -->\n"
        val base = codec.renderLongTerm(listOf(target, unrelated)) + "\n" + footer
        val replacement = candidate(
            text = "The user prefers concise answers.",
            source = MemorySource.USER_CONFIRMED,
            evidenceAt = 60,
            evidenceRefs = listOf("turn:confirmed"),
            targetMemoryId = target.id
        )

        val result = policy.merge(base, listOf(replacement), mutationAt = 70)
        val parsed = codec.parse(result.markdown)
        val kept = parsed.entries.single { entry -> entry.id == unrelated.id }
        val active = parsed.entries.single { entry -> entry.validity == MemoryValidity.CURRENT && entry.id == target.id }

        assertTrue(result.markdown.contains(footer))
        assertEquals(unrelated.copy(section = "Projects"), kept)
        assertEquals("enabled", active.extraMetadata["future_flag"])
        assertEquals(replacement.text, active.text)
    }

    @Test
    fun `canonical rebinding is restricted to the explicit whole corpus mode`() {
        val legacy = canonicalEntry(
            id = "legacy_address",
            text = "Address the user as Captain.",
            canonicalKey = "identity.nickname"
        )
        val canonical = canonicalEntry(
            id = "preferred_address",
            text = "The user's preferred address is Captain.",
            canonicalKey = "identity.preferred_address",
            source = MemorySource.USER_CONFIRMED,
            lastObservedAt = 40
        )
        val base = codec.renderLongTerm(listOf(legacy, canonical))
        val candidates = listOf(legacy, canonical).map { entry ->
            candidate(
                text = entry.text,
                source = entry.source,
                canonicalKey = "identity.preferred_address",
                evidenceAt = entry.lastObservedAt,
                targetMemoryId = entry.id
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            policy.merge(base, candidates, mutationAt = 50)
        }

        val merged = policy.merge(
            baseMarkdown = base,
            candidates = candidates,
            mutationAt = 50,
            allowCanonicalRebinding = true
        )
        val parsed = codec.parse(merged.markdown)
        val active = parsed.entries.filter { entry -> entry.validity == MemoryValidity.CURRENT }

        assertEquals(1, active.size)
        assertEquals("identity.preferred_address", active.single().canonicalKey)
        assertTrue(
            parsed.entries
                .filter { entry -> entry.validity == MemoryValidity.OBSOLETE }
                .all { entry -> entry.canonicalKey == active.single().canonicalKey }
        )
    }

    private fun currentEntries(markdown: String): List<MarkdownMemoryEntry> = codec
        .parse(markdown)
        .entries
        .filter { entry -> entry.validity == MemoryValidity.CURRENT }

    private fun emptyDocument(): String = "# ChatWithChat Memory\n"

    private fun canonicalEntry(
        id: String,
        text: String,
        type: String = "communication_style",
        sensitivity: String = MemorySensitivity.NORMAL,
        source: String = MemorySource.EXPLICIT_USER_STATEMENT,
        canonicalKey: String? = "communication.response_style",
        scope: String = MemoryScope.GENERAL,
        createdAt: Long = 10,
        updatedAt: Long = 20,
        lastObservedAt: Long = updatedAt,
        validity: String = MemoryValidity.CURRENT,
        supersededBy: String? = null,
        recallState: String = MemoryRecallState.QUERY,
        evidenceRefs: List<String> = listOf("turn:existing"),
        extraMetadata: Map<String, String> = emptyMap()
    ): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = type,
        sensitivity = sensitivity,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt,
        canonicalKey = canonicalKey,
        scope = scope,
        lastObservedAt = lastObservedAt,
        validity = validity,
        supersededBy = supersededBy,
        recallState = recallState,
        evidenceRefs = evidenceRefs,
        extraMetadata = extraMetadata
    )

    private fun candidate(
        text: String = "The user prefers concise answers.",
        type: String = "communication_style",
        sensitivity: String = MemorySensitivity.NORMAL,
        source: String = MemorySource.EXPLICIT_USER_STATEMENT,
        canonicalKey: String = "communication.response_style",
        scope: String = MemoryScope.GENERAL,
        evidenceAt: Long = 30,
        recallState: String = MemoryRecallState.QUERY,
        evidenceRefs: List<String> = listOf("turn:candidate"),
        targetMemoryId: String? = null
    ): CanonicalMemoryCandidate = CanonicalMemoryCandidate(
        text = text,
        type = type,
        sensitivity = sensitivity,
        source = source,
        canonicalKey = canonicalKey,
        scope = scope,
        evidenceAt = evidenceAt,
        recallState = recallState,
        evidenceRefs = evidenceRefs,
        targetMemoryId = targetMemoryId
    )
}
