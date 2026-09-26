package cn.nabr.chatwithchat.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFreshnessPolicyTest {
    private val codec = MarkdownMemoryCodec()

    @Test
    fun `legacy metadata remains readable without lifecycle fields`() {
        val markdown = """
            # ChatWithChat Memory

            ## Stable Profile

            <!-- memory:id=mem_language type=stable_profile sensitivity=normal source=explicit_user_statement created=10 updated=20 canonical_key=profile.language scope=general validity=current recall=core -->
            - The user prefers Chinese.
        """.trimIndent()

        val entry = codec.parse(markdown).entries.single()

        assertEquals(null, entry.durability)
        assertEquals(null, entry.freshness)
        assertEquals(MemoryDurability.DURABLE, MemoryFreshnessPolicy.resolve(entry).durability)
        assertTrue(MemoryFreshnessPolicy.allowsCore(entry))
    }

    @Test
    fun `custom lifecycle metadata round trips`() {
        val entry = MarkdownMemoryEntry(
            id = "mem_project",
            text = "ChatWithChat is active.",
            type = "project_context",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 10,
            updatedAt = 20,
            canonicalKey = "project.chatwithchat",
            durability = MemoryDurability.ACTIVE,
            freshness = MemoryFreshness.REFRESHABLE,
            lifecycleState = MemoryLifecycleState.ACTIVE,
            refreshAfterDays = 30
        )

        val parsed = codec.parse(codec.renderLongTerm(listOf(entry))).entries.single()

        assertEquals(entry.durability, parsed.durability)
        assertEquals(entry.freshness, parsed.freshness)
        assertEquals(entry.lifecycleState, parsed.lifecycleState)
        assertEquals(entry.refreshAfterDays, parsed.refreshAfterDays)
        assertFalse(MemoryFreshnessPolicy.allowsCore(parsed))
    }

    @Test
    fun `volatile project facts expire and cannot become core`() {
        val entry = MarkdownMemoryEntry(
            id = "mem_test_result",
            text = "The current test result is green.",
            type = "project_context",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            canonicalKey = "project.chatwithchat.test_result"
        )

        val profile = MemoryFreshnessPolicy.resolve(entry)

        assertEquals(MemoryDurability.EPHEMERAL, profile.durability)
        assertEquals(MemoryFreshness.EXPIRES, profile.freshness)
        assertTrue(MemoryFreshnessPolicy.isStale(entry, 1_000L + 8L * 24L * 60L * 60L * 1_000L))
        assertFalse(MemoryFreshnessPolicy.allowsCore(entry))
    }

    @Test
    fun `production seconds are compared with a millisecond clock`() {
        val updatedAtSeconds = 1_790_306_209L
        val entry = MarkdownMemoryEntry(
            id = "recent_result",
            text = "The latest test passed.",
            type = "project_context",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = updatedAtSeconds,
            updatedAt = updatedAtSeconds,
            canonicalKey = "project.chatwithchat.test_result",
            scope = "project:chatwithchat"
        )
        assertFalse(MemoryFreshnessPolicy.isStale(entry, (updatedAtSeconds + 6L * 24L * 60L * 60L) * 1_000L))
        assertTrue(MemoryFreshnessPolicy.isStale(entry, (updatedAtSeconds + 8L * 24L * 60L * 60L) * 1_000L))
        assertFalse(MemoryFreshnessPolicy.allowsQuery(entry.copy(lifecycleState = MemoryLifecycleState.COMPLETED), updatedAtSeconds * 1_000L))
    }

    @Test
    fun `assistant inferred facts cannot become core`() {
        val entry = MarkdownMemoryEntry(
            id = "mem_inferred",
            text = "The user may prefer robotics topics.",
            type = "interest",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.ASSISTANT_INFERRED,
            createdAt = 1L,
            updatedAt = 1L,
            canonicalKey = "interest.robotics",
            recallState = MemoryRecallState.CORE
        )

        assertFalse(MemoryFreshnessPolicy.allowsCore(entry))
    }

    @Test
    fun `malformed refresh interval is rejected instead of silently dropped`() {
        val markdown = """
            # ChatWithChat Memory

            <!-- memory:id=mem_bad type=project_context sensitivity=normal source=explicit_user_statement updated=20 canonical_key=project.chatwithchat scope=general validity=current recall=query refresh_after_days=oops -->
            - Invalid interval.
        """.trimIndent()

        val parsed = codec.parse(markdown)

        assertTrue(parsed.entries.isEmpty())
        assertEquals("invalid refresh_after_days", parsed.skippedEntries.single().reason)
    }
}
