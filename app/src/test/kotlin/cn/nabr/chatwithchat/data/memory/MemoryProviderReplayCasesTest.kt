package cn.nabr.chatwithchat.data.memory

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryProviderReplayCasesTest {
    private val codec = MarkdownMemoryCodec()
    private val chunker = MemoryChunker()
    private val nowSeconds = 1_790_410_000L

    @Test
    fun `real export produces provider replay cases`() {
        val exportPath = System.getenv("CHATWITHCHAT_REAL_MEMORY_EXPORT")
        org.junit.Assume.assumeNotNull("Real export path required", exportPath)
        val source = File(checkNotNull(exportPath)).readText(Charsets.UTF_8)
        val sourceEntries = codec.parse(source).entries
        val addressing = sourceEntries.firstOrNull { it.id == ADDRESSING_ID }
            ?: error("Addressing entry missing from parsed export: $ADDRESSING_ID")
        val migrated = MemoryMigrationPlanner.propose(
            markdown = source,
            mutationAt = nowSeconds,
            addressingReviews = listOf(
                MemoryAddressingReview(
                    entryId = addressing.id,
                    sourceTextHash = addressing.text.sha256Utf8(),
                    addressingText = "用户希望被称呼为“大哥”，并将 AI 称为“小c”。",
                    boundaries = listOf(
                        MemoryReviewedBoundary(
                            canonicalKey = "boundary.emoji_usage",
                            text = "用户不希望使用😅表情。",
                            evidenceRefs = listOf("chat:30:user:178")
                        )
                    )
                )
            ),
            projectReviews = listOf(MemoryRealExportReviews.projectReview(source))
        )
        assertTrue(migrated.afterAudit.isHealthy)
        assertTrue(
            MemoryMigrationPlanner.propose(
                migrated.targetMarkdown,
                nowSeconds + 1L,
                projectReviews = listOf(MemoryRealExportReviews.projectReview(source))
            ).isNoOp
        )

        val oldProject = sourceEntries.firstOrNull { it.id == LEGACY_PROJECT_ID }
            ?: error("Legacy project missing from parsed export: $LEGACY_PROJECT_ID")
        val migratedProjects = codec.parse(migrated.targetMarkdown).entries.filter {
            it.canonicalKey == "project.chatwithchat" && it.scope == "project:chatwithchat"
        }
        val newProject = migratedProjects.single { it.validity == MemoryValidity.CURRENT }
        assertTrue(oldProject.validity == MemoryValidity.CURRENT)
        assertTrue(newProject.validity == MemoryValidity.CURRENT)
        assertEquals(MemoryRealExportReviews.PROJECT_TEXT, newProject.text)
        assertEquals(1, migratedProjects.count { it.validity == MemoryValidity.CURRENT })

        val cases = listOf(
            realCase("preferred_address", "你应该怎么称呼我？", source, migrated.targetMarkdown),
            realCase("emoji_boundary", "今天有点尴尬，回复一句简单问候吧。", source, migrated.targetMarkdown),
            realCase("project_alias", "ChatWithChat 是我在维护的什么项目？", source, migrated.targetMarkdown),
            realCase("unrelated", "12 乘 13 等于多少？", source, migrated.targetMarkdown),
            realCase("private_boundary", "你知道我前任的名字吗？", source, migrated.targetMarkdown),
            controlledStaleVersionCase(),
            controlledCompletedProjectCase(),
            controlledCorrectionCase()
        )
        assertTrue(cases.all { it.baseline.ids.none(PRIVATE_ID::equals) && it.optimized.ids.none(PRIVATE_ID::equals) })
        assertTrue(cases.first { it.id == "project_alias" }.baseline.ids.contains(LEGACY_PROJECT_ID))
        assertFalse(cases.first { it.id == "project_alias" }.optimized.memoryPrompt.contains(oldProject.text))
        assertTrue(cases.first { it.id == "project_alias" }.optimized.memoryPrompt.contains(MemoryRealExportReviews.PROJECT_TEXT))
        assertTrue(cases.first { it.id == "stale_version" }.optimized.ids.isEmpty())
        assertTrue(cases.first { it.id == "completed_project" }.optimized.ids.isEmpty())

        val output = File("build/reports/memory/provider-replay-cases.json")
        checkNotNull(output.parentFile).mkdirs()
        output.writeText(
            Json { prettyPrint = true }.encodeToString(
                ProviderReplayCases(
                    sourceHash = source.sha256Utf8(),
                    migratedHash = migrated.targetMarkdown.sha256Utf8(),
                    evaluationAt = nowSeconds,
                    baselineMode = "original_export_with_freshness_disabled_for_query_candidates",
                    cases = cases
                )
            ),
            Charsets.UTF_8
        )
        println("memory-provider-replay-cases|count=${cases.size}|path=${output.absolutePath}")
    }

    private fun realCase(id: String, question: String, source: String, migrated: String): ProviderReplayCase =
        ProviderReplayCase(
            id = id,
            question = question,
            origin = "real_export",
            baseline = recalled(source, question, legacyUnfiltered = true),
            optimized = recalled(migrated, question)
        )

    private fun controlledStaleVersionCase(): ProviderReplayCase {
        val entry = testEntry(
            id = "version_old",
            text = "ChatWithChat 当前版本是 0.8.0。",
            key = "project.chatwithchat.version",
            updatedAt = nowSeconds - 8L * 86_400L
        )
        val markdown = codec.renderLongTerm(listOf(entry))
        return ProviderReplayCase(
            id = "stale_version",
            question = "ChatWithChat 现在的版本是多少？",
            origin = "controlled",
            baseline = recalled(markdown, "ChatWithChat 现在的版本是多少？", legacyUnfiltered = true),
            optimized = recalled(markdown, "ChatWithChat 现在的版本是多少？")
        )
    }

    private fun controlledCompletedProjectCase(): ProviderReplayCase {
        val entry = testEntry(
            id = "completed_project",
            text = "ChatWithChat 的测试迁移任务已完成。",
            key = "project.chatwithchat.migration",
            updatedAt = nowSeconds,
            lifecycle = MemoryLifecycleState.COMPLETED
        )
        val question = "ChatWithChat 的测试迁移任务现在是什么状态？"
        return ProviderReplayCase(
            id = "completed_project",
            question = question,
            origin = "controlled",
            baseline = recalled(codec.renderLongTerm(listOf(entry.copy(lifecycleState = MemoryLifecycleState.ACTIVE))), question),
            optimized = recalled(codec.renderLongTerm(listOf(entry)), question)
        )
    }

    private fun controlledCorrectionCase(): ProviderReplayCase {
        val old = testEntry("old_editor", "用户偏好使用 Vim 编辑器。", "preference.editor", nowSeconds - 1_000L)
        val corrected = testEntry("corrected_editor", "用户明确更正：偏好使用 VS Code 编辑器。", "preference.editor", nowSeconds)
            .copy(source = MemorySource.USER_CONFIRMED)
        val source = codec.renderLongTerm(listOf(old, corrected))
        val migrated = MemoryMigrationPlanner.propose(source, nowSeconds + 1L)
        val question = "我偏好用什么编辑器？"
        return ProviderReplayCase(
            id = "correction",
            question = question,
            origin = "controlled",
            baseline = recalled(source, question, legacyUnfiltered = true),
            optimized = recalled(migrated.targetMarkdown, question)
        )
    }

    private fun testEntry(id: String, text: String, key: String, updatedAt: Long, lifecycle: String? = null) =
        MarkdownMemoryEntry(
            id = id,
            text = text,
            type = if (key.startsWith("project.")) "project_context" else "communication_style",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = updatedAt,
            updatedAt = updatedAt,
            canonicalKey = key,
            scope = if (key.startsWith("project.")) "project:chatwithchat" else MemoryScope.GENERAL,
            recallState = MemoryRecallState.QUERY,
            lifecycleState = lifecycle
        )

    private fun recalled(markdown: String, question: String, legacyUnfiltered: Boolean = false): ProviderReplayContext {
        val chunking = chunker.chunksFor(
            MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown,
            MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        )
        val snapshot = MemoryCorpusSnapshot(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            canonicalSourceHash = markdown.sha256Utf8(),
            recallProjectionHash = chunking.projectionHash,
            generation = 1L,
            chunks = chunking.chunks
        )
        val unusedSource = object : MemoryCorpusSnapshotSource {
            override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> =
                error("rankCandidates uses the supplied snapshot")

            override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> =
                error("rankCandidates uses the supplied snapshot")
        }
        val request = MemoryRetrievalRequest(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            query = question,
            limit = 8,
            candidateLimit = 24,
            includePrivate = false,
            strategy = MemoryRetrievalStrategy.LEXICAL
        )
        val core = snapshot.selectCoreResults(includePrivate = false)
        val query = MarkdownLexicalRetriever(unusedSource, nowMillis = {
            if (legacyUnfiltered) 0L else nowSeconds * 1_000L
        }).rankCandidates(request, question, listOf(snapshot))
            .filterNot { result -> core.any { it.entryId == result.entryId } }
            .take(8)
        val prompt = MemoryPromptBuilder().build(
            coreFacts = core.mapNotNull { it.text.toModelVisibleMemoryFactOrNull() },
            queryFacts = query.mapNotNull { it.text.toModelVisibleMemoryFactOrNull() }
        ).prompt.orEmpty()
        return ProviderReplayContext(
            ids = (core + query).mapNotNull(MemoryRetrievalResult::entryId),
            memoryPrompt = prompt
        )
    }

    private companion object {
        const val ADDRESSING_ID = "import_0db7c068d4b81028079b91ca"
        const val LEGACY_PROJECT_ID = "mem_can_867dd03ca661b8273a6fce9c"
        const val PRIVATE_ID = "mem_can_a3d6531e20bc7c69a44050b4"
    }
}

@Serializable
private data class ProviderReplayCases(
    val sourceHash: String,
    val migratedHash: String,
    val evaluationAt: Long,
    val baselineMode: String,
    val cases: List<ProviderReplayCase>
)

@Serializable
private data class ProviderReplayCase(
    val id: String,
    val question: String,
    val origin: String,
    val baseline: ProviderReplayContext,
    val optimized: ProviderReplayContext
)

@Serializable
private data class ProviderReplayContext(
    val ids: List<String>,
    val memoryPrompt: String
)
