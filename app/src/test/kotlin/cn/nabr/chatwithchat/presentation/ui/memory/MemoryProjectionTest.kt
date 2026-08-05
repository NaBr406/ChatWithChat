package cn.nabr.chatwithchat.presentation.ui.memory

import cn.nabr.chatwithchat.data.memory.MarkdownMemoryCodec
import cn.nabr.chatwithchat.data.memory.MarkdownMemoryEntry
import cn.nabr.chatwithchat.data.memory.MemoryRecallState
import cn.nabr.chatwithchat.data.memory.MemoryValidity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryProjectionTest {

    private val codec = MarkdownMemoryCodec()

    @Test
    fun groupsByTypeInFixedOrderAndUnknownTypesUseOther() {
        val markdown = markdown(
            entry("interest", type = "interest", section = "Stable Profile"),
            entry("unknown", type = "new_type", section = "Interest"),
            entry("profile", type = "stable_profile", section = "Other Memories")
        )

        val projection = MemoryProjectionMapper.project(markdown)

        assertEquals(listOf("stable_profile", "interest", "other"), projection.sections.map { it.type })
        assertEquals(listOf("profile"), projection.sections[0].entries.map { it.id })
        assertEquals(listOf("unknown"), projection.sections[2].entries.map { it.id })
    }

    @Test
    fun supportsEveryKnownMemoryType() {
        val knownTypes = MemoryProjectionMapper.CATEGORY_ORDER.dropLast(1)
        val markdown = markdown(
            *knownTypes.mapIndexed { index, type -> entry("known-$index", type = type) }.toTypedArray()
        )

        val projection = MemoryProjectionMapper.project(markdown)

        assertEquals(knownTypes, projection.sections.map { section -> section.type })
    }

    @Test
    fun filtersToCurrentCoreAndQueryEntries() {
        val markdown = markdown(
            entry("core", recallState = MemoryRecallState.CORE, canonicalKey = "test.shared"),
            entry("query", recallState = MemoryRecallState.QUERY),
            entry("maintenance", recallState = MemoryRecallState.MAINTENANCE_ONLY),
            entry(
                "contested",
                validity = MemoryValidity.CONTESTED,
                recallState = MemoryRecallState.MAINTENANCE_ONLY,
                canonicalKey = "test.contested"
            ),
            entry(
                "obsolete",
                validity = MemoryValidity.OBSOLETE,
                recallState = MemoryRecallState.MAINTENANCE_ONLY,
                supersededBy = "core",
                canonicalKey = "test.shared"
            )
        )

        val projection = MemoryProjectionMapper.project(markdown)

        assertEquals(listOf("core", "query"), projection.entries.map { it.id }.sorted())
        assertEquals(
            listOf("contested", "maintenance", "obsolete"),
            projection.historyEntries.map { it.id }.sorted()
        )
        assertEquals(3, projection.hiddenHistoryCount)
    }

    @Test
    fun sortsEntriesByObservationThenUpdatedAtThenId() {
        val markdown = markdown(
            entry("same-z", observed = 20L, updatedAt = 10L),
            entry("same-a", observed = 20L, updatedAt = 11L),
            entry("old", observed = 10L, updatedAt = 99L)
        )

        val projection = MemoryProjectionMapper.project(markdown)

        assertEquals(listOf("same-a", "same-z", "old"), projection.entries.map { it.id })
    }

    @Test
    fun proseAndSkippedEntriesKeepRecognizedDataAndRawMarkdown() {
        val managed = markdown(entry("known", type = "interest"))
        val markdown = managed + "\n手写的补充说明。\n\n<!-- memory:id=broken type=interest -->\n- malformed metadata\n"

        val projection = MemoryProjectionMapper.project(markdown)

        assertEquals(listOf("known"), projection.entries.map { it.id })
        assertEquals(MemoryProjectionParseStatus.PARTIAL, projection.parseStatus)
        assertTrue(projection.hasUnclassifiedContent)
        assertEquals(markdown, projection.rawMarkdown)
    }

    @Test
    fun unrecognizedOnlyMarkdownFallsBackToOriginalText() {
        val markdown = "# ChatWithChat Memory\n\n这是一段手写记忆。\n"

        val projection = MemoryProjectionMapper.project(markdown)

        assertTrue(projection.entries.isEmpty())
        assertTrue(projection.sections.isEmpty())
        assertEquals(MemoryProjectionParseStatus.PARTIAL, projection.parseStatus)
        assertEquals(markdown, projection.rawMarkdown)
    }

    @Test
    fun parserFailureStillKeepsOriginalMarkdown() {
        val markdown = "# ChatWithChat Memory\n\nraw content\n"

        val projection = MemoryProjectionMapper.projectUsingParser(markdown) {
            error("parser failure")
        }

        assertEquals(MemoryProjectionParseStatus.FAILED, projection.parseStatus)
        assertTrue(projection.sections.isEmpty())
        assertEquals(markdown, projection.rawMarkdown)
    }

    private fun markdown(vararg entries: MarkdownMemoryEntry): String = codec.renderLongTerm(entries.toList())

    private fun entry(
        id: String,
        type: String = "interest",
        section: String? = null,
        validity: String = MemoryValidity.CURRENT,
        recallState: String = MemoryRecallState.QUERY,
        supersededBy: String? = null,
        observed: Long = 1L,
        updatedAt: Long = observed,
        canonicalKey: String? = "test.$id"
    ): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = "记忆 $id",
        type = type,
        sensitivity = "normal",
        source = "explicit_user_statement",
        createdAt = 1L,
        updatedAt = updatedAt,
        section = section,
        canonicalKey = canonicalKey,
        lastObservedAt = observed,
        validity = validity,
        supersededBy = supersededBy,
        recallState = recallState
    )
}
