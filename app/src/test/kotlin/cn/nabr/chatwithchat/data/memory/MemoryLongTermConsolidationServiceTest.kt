package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.InMemoryMemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.dao.MemoryLongTermConsolidationDao
import cn.nabr.chatwithchat.data.database.entity.MemoryLongTermConsolidationCheckpoint
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.repository.SettingRepository
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLongTermConsolidationServiceTest {
    @Test
    fun `forced retirement commits obsolete maintenance entry through mutation coordinator`() = runBlocking {
        val diagnostic = entry(
            id = "mem_recall_diagnostic",
            text = "Current recall threshold diagnostic.",
            canonicalKey = "project.chatwithchat.recall_diagnostic"
        )
        val intelligence = RecordingLongTermMemoryIntelligence { request ->
            val candidate = request.candidateGroups.single().entries.single()
            MemoryLongTermConsolidationProposal(
                decisions = listOf(
                    MemoryLongTermCanonicalDecision(
                        action = MemoryLongTermDecisionAction.RETIRE,
                        memoryIds = listOf(candidate.memoryId),
                        canonicalKey = candidate.canonicalKey,
                        scope = candidate.scope,
                        recallState = MemoryRecallState.MAINTENANCE_ONLY,
                        reason = "hard-negative: diagnostic state is transient"
                    )
                )
            )
        }
        val fixture = fixture(
            entries = listOf(diagnostic),
            intelligence = intelligence,
            forceReview = true
        )
        val before = fixture.fileStore.readLongTermMemory().getOrThrow()

        val result = fixture.service().process(fixture.claimedJob)

        val after = fixture.fileStore.readLongTermMemory().getOrThrow()
        val retired = fixture.codec.parse(after).entries.single()
        val mutation = checkNotNull(fixture.mutationCoordinator.findBySemanticJobId(fixture.claimedJob.jobId))
        val receipt = mutation.receipts.single()
        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, result.operationCount)
        assertEquals(1, intelligence.requests.size)
        assertNotEquals(before, after)
        assertEquals(diagnostic.id, retired.id)
        assertEquals(diagnostic.text, retired.text)
        assertEquals(MemoryValidity.OBSOLETE, retired.validity)
        assertEquals(MemoryRecallState.MAINTENANCE_ONLY, retired.recallState)
        assertNull(retired.supersededBy)
        assertEquals(1, receipt.materialMutationCount)

        val replay = fixture.service().process(checkNotNull(fixture.jobDao.getById(fixture.claimedJob.jobId)))

        assertEquals(MemoryLongTermProcessResult.STATUS_DUPLICATE, replay.status)
        assertEquals(1, intelligence.requests.size)
        assertEquals(after, fixture.fileStore.readLongTermMemory().getOrThrow())
    }

    @Test
    fun `fully canonical distinct facts are reviewed without canonical mutation`() = runBlocking {
        val intelligence = RecordingLongTermMemoryIntelligence { MemoryLongTermConsolidationProposal() }
        val fixture = fixture(
            entries = listOf(
                entry(id = "canonical_1", text = "Prefers concise replies.", canonicalKey = "communication.concise_reply"),
                entry(id = "canonical_2", text = "Prefers explicit evidence.", canonicalKey = "communication.explicit_evidence")
            ),
            intelligence = intelligence
        )
        val originalMarkdown = fixture.fileStore.readLongTermMemory().getOrThrow()

        val result = fixture.service().process(fixture.claimedJob)

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals("clean_no_op", result.reason)
        assertEquals(0, result.operationCount)
        assertEquals(1, intelligence.requests.size)
        assertEquals(originalMarkdown, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(fixture.claimedJob.jobId))
        assertEquals(
            MemoryLongTermCheckpointStatus.COMPLETED,
            fixture.checkpointDao.getByJobId(fixture.claimedJob.jobId)?.status
        )
        assertEquals(1, fixture.checkpointDao.getByJobId(fixture.claimedJob.jobId)?.attempt)
        assertNull(fixture.checkpointDao.getByJobId(fixture.claimedJob.jobId)?.lastError)
        assertEquals(
            MemoryMaintenanceJobStatus.SUCCEEDED,
            fixture.jobDao.getById(fixture.claimedJob.jobId)?.status
        )
    }

    @Test
    fun `persisted partition proposal replays after process restart without a second llm call`() = runBlocking {
        val canonicalKey = "communication.response_style"
        val intelligence = RecordingLongTermMemoryIntelligence { request ->
            MemoryLongTermConsolidationProposal(
                decisions = listOf(
                    MemoryLongTermCanonicalDecision(
                        action = MemoryLongTermDecisionAction.CANONICALIZE,
                        memoryIds = request.candidateGroups.flatMap { group -> group.entries }
                            .map(MemoryLongTermCandidateEntry::memoryId),
                        canonicalKey = canonicalKey,
                        scope = MemoryScope.GENERAL,
                        recallState = MemoryRecallState.QUERY,
                        reason = "Same durable communication preference."
                    )
                )
            )
        }
        val crashObserver = CrashAfterFirstPartitionObserver()
        val fixture = fixture(
            entries = listOf(
                entry(id = "unkeyed_1", text = "Keep replies concise and direct."),
                entry(id = "unkeyed_2", text = "Use concise direct answers when possible.")
            ),
            intelligence = intelligence
        )
        val originalMarkdown = fixture.fileStore.readLongTermMemory().getOrThrow()

        val interrupted = runCatching {
            fixture.service(crashObserver).process(fixture.claimedJob)
        }

        assertEquals(CRASH_AFTER_PARTITION, interrupted.exceptionOrNull()?.message)
        assertEquals(1, intelligence.requests.size)
        val persisted = checkNotNull(fixture.checkpointDao.getByJobId(fixture.claimedJob.jobId))
        assertEquals(persisted.entryCount, persisted.partitionCursor)
        assertNotNull(persisted.proposalHash)
        assertNotNull(persisted.proposalJson)
        assertEquals(originalMarkdown, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(fixture.claimedJob.jobId))

        val restartedJob = checkNotNull(fixture.jobDao.getById(fixture.claimedJob.jobId))
        val result = fixture.service(
            clock = Clock.offset(FIXED_CLOCK, Duration.ofDays(5))
        ).process(restartedJob)

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(2, result.operationCount)
        assertEquals(1, intelligence.requests.size)
        assertNotEquals(originalMarkdown, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertNotNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(fixture.claimedJob.jobId))
        val parsed = fixture.codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow())
        assertTrue(parsed.entries.any { current -> current.canonicalKey == canonicalKey })
        assertEquals(
            MemoryLongTermCheckpointStatus.COMPLETED,
            fixture.checkpointDao.getByJobId(fixture.claimedJob.jobId)?.status
        )

        val uninterruptedIntelligence = RecordingLongTermMemoryIntelligence { request ->
            MemoryLongTermConsolidationProposal(
                decisions = listOf(
                    MemoryLongTermCanonicalDecision(
                        action = MemoryLongTermDecisionAction.CANONICALIZE,
                        memoryIds = request.candidateGroups.flatMap { group -> group.entries }
                            .map(MemoryLongTermCandidateEntry::memoryId),
                        canonicalKey = canonicalKey,
                        scope = MemoryScope.GENERAL,
                        recallState = MemoryRecallState.QUERY
                    )
                )
            )
        }
        val uninterrupted = this@MemoryLongTermConsolidationServiceTest.fixture(
            entries = listOf(
                entry(id = "unkeyed_1", text = "Keep replies concise and direct."),
                entry(id = "unkeyed_2", text = "Use concise direct answers when possible.")
            ),
            intelligence = uninterruptedIntelligence
        )
        uninterrupted.service().process(uninterrupted.claimedJob)
        assertEquals(
            uninterrupted.fileStore.readLongTermMemory().getOrThrow(),
            fixture.fileStore.readLongTermMemory().getOrThrow()
        )
    }

    @Test
    fun `multiple partitions scan every frozen entry before completing`() = runBlocking {
        val entries = (0 until MemoryLongTermConsolidationPolicy.MAX_PARTITION_ENTRIES + 7).map { index ->
            entry(id = "entry_$index", text = "lexeme${index}x")
        }
        val intelligence = RecordingLongTermMemoryIntelligence {
            MemoryLongTermConsolidationProposal()
        }
        val fixture = fixture(entries = entries, intelligence = intelligence)
        val frozenIds = fixture.codec.parse(
            fixture.fileStore.readLongTermMemory().getOrThrow()
        ).entries.map(MarkdownMemoryEntry::id)

        val result = fixture.service().process(fixture.claimedJob)

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals("clean_no_op", result.reason)
        assertEquals(2, intelligence.requests.size)
        assertEquals(
            listOf(
                0 to MemoryLongTermConsolidationPolicy.MAX_PARTITION_ENTRIES,
                MemoryLongTermConsolidationPolicy.MAX_PARTITION_ENTRIES to entries.size
            ),
            intelligence.requests.map { request -> request.partitionStart to request.partitionEndExclusive }
        )
        assertEquals(
            frozenIds,
            intelligence.requests.flatMap { request ->
                request.candidateGroups.flatMap(MemoryLongTermCandidateGroup::anchorMemoryIds)
            }
        )
        val checkpoint = checkNotNull(fixture.checkpointDao.getByJobId(fixture.claimedJob.jobId))
        assertEquals(entries.size, checkpoint.partitionCursor)
        assertEquals(MemoryLongTermCheckpointStatus.COMPLETED, checkpoint.status)
        assertNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(fixture.claimedJob.jobId))
    }

    @Test
    fun `invented memory id fails closed and remains retryable`() = runBlocking {
        val intelligence = RecordingLongTermMemoryIntelligence {
            MemoryLongTermConsolidationProposal(
                decisions = listOf(
                    MemoryLongTermCanonicalDecision(
                        action = MemoryLongTermDecisionAction.CANONICALIZE,
                        memoryIds = listOf("real_memory", "invented_memory_id"),
                        canonicalKey = "communication.response_style",
                        scope = MemoryScope.GENERAL,
                        recallState = MemoryRecallState.QUERY
                    )
                )
            )
        }
        val fixture = fixture(
            entries = listOf(entry(id = "real_memory", text = "Use direct answers.")),
            intelligence = intelligence
        )
        val originalMarkdown = fixture.fileStore.readLongTermMemory().getOrThrow()

        val result = fixture.service().process(fixture.claimedJob)

        assertEquals(MemoryLongTermProcessResult.STATUS_RETRYABLE, result.status)
        assertTrue(result.reason?.contains("decision invented a memory id") == true)
        assertEquals(1, intelligence.requests.size)
        assertEquals(originalMarkdown, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(fixture.claimedJob.jobId))
        val checkpoint = checkNotNull(fixture.checkpointDao.getByJobId(fixture.claimedJob.jobId))
        assertEquals(MemoryLongTermCheckpointStatus.PENDING, checkpoint.status)
        assertEquals(0, checkpoint.partitionCursor)
        assertNull(checkpoint.proposalHash)
        assertNull(checkpoint.proposalJson)
        assertEquals(1, checkpoint.attempt)
        assertTrue(checkpoint.lastError?.contains("decision invented a memory id") == true)
        assertEquals(
            MemoryMaintenanceJobStatus.FAILED_RETRYABLE,
            fixture.jobDao.getById(fixture.claimedJob.jobId)?.status
        )
    }

    @Test
    fun `retry keeps the frozen model when catalog ordering changes`() = runBlocking {
        var providerCalls = 0
        val intelligence = RecordingLongTermMemoryIntelligence { request ->
            providerCalls += 1
            if (providerCalls == 1) {
                null
            } else {
                MemoryLongTermConsolidationProposal(
                    decisions = listOf(
                        MemoryLongTermCanonicalDecision(
                            action = MemoryLongTermDecisionAction.CANONICALIZE,
                            memoryIds = request.candidateGroups.flatMap { group -> group.entries }
                                .map(MemoryLongTermCandidateEntry::memoryId),
                            canonicalKey = "communication.response_style",
                            scope = MemoryScope.GENERAL,
                            recallState = MemoryRecallState.QUERY
                        )
                    )
                )
            }
        }
        val fixture = fixture(
            entries = listOf(entry(id = "unkeyed", text = "Use concise direct answers.")),
            intelligence = intelligence
        )

        val first = fixture.service().process(fixture.claimedJob)

        assertEquals(MemoryLongTermProcessResult.STATUS_RETRYABLE, first.status)
        val firstCheckpoint = checkNotNull(fixture.checkpointDao.getByJobId(fixture.claimedJob.jobId))
        assertEquals(PLATFORM.uid, firstCheckpoint.resolvedPlatformUid)
        assertEquals(PLATFORM.model, firstCheckpoint.resolvedModelId)
        fixture.platformCatalog.add(0, SECOND_PLATFORM)
        fixture.modelCatalog.add(0, SECOND_PLATFORM_MODEL)
        checkNotNull(fixture.maintenanceScheduler.retryManually(fixture.claimedJob.jobId))
        val retryJob = checkNotNull(
            fixture.maintenanceScheduler.claimNextRunnable(
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                leaseOwner = "long-term-service-retry"
            )
        )

        val retried = fixture.service().process(retryJob)

        assertEquals(retried.reason, MemoryLongTermProcessResult.STATUS_SUCCEEDED, retried.status)
        assertEquals(listOf(PLATFORM.uid, PLATFORM.uid), intelligence.resolvedPlatforms.map(PlatformV2::uid))
        assertEquals(listOf(PLATFORM.model, PLATFORM.model), intelligence.resolvedPlatforms.map(PlatformV2::model))
        val completed = checkNotNull(fixture.checkpointDao.getByJobId(fixture.claimedJob.jobId))
        assertEquals(PLATFORM.uid, completed.resolvedPlatformUid)
        assertEquals(PLATFORM.model, completed.resolvedModelId)
        assertNull(completed.lastError)
    }

    @Test
    fun `foreground canonical write makes the frozen pass stale without losing new bytes`() = runBlocking {
        val intelligence = RecordingLongTermMemoryIntelligence {
            MemoryLongTermConsolidationProposal()
        }
        val fixture = fixture(
            entries = listOf(entry(id = "unkeyed", text = "Use concise direct answers.")),
            intelligence = intelligence
        )
        val foregroundMarkdown = fixture.codec.renderLongTerm(
            listOf(
                entry(id = "unkeyed", text = "Use concise direct answers."),
                entry(
                    id = "foreground",
                    text = "The user added this while maintenance was running.",
                    canonicalKey = "project.chatwithchat.concurrent_fact"
                )
            )
        )
        val observer = object : MemoryLongTermConsolidationCommitObserver {
            override suspend fun afterPartitionPersisted(checkpoint: MemoryLongTermConsolidationCheckpoint) {
                fixture.fileStore.replaceLongTermMemory(foregroundMarkdown).getOrThrow()
            }
        }

        val result = fixture.service(observer).process(fixture.claimedJob)

        assertEquals(MemoryLongTermProcessResult.STATUS_TERMINAL, result.status)
        assertEquals("stale_long_term_source", result.reason)
        assertEquals(foregroundMarkdown, fixture.fileStore.readLongTermMemory().getOrThrow())
        assertNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(fixture.claimedJob.jobId))
        assertEquals(
            MemoryLongTermCheckpointStatus.STALE_SOURCE,
            fixture.checkpointDao.getByJobId(fixture.claimedJob.jobId)?.status
        )
        assertEquals(
            MemoryMaintenanceJobStatus.DISMISSED,
            fixture.jobDao.getById(fixture.claimedJob.jobId)?.status
        )
        assertNotNull(
            fixture.checkpointDao.getActive(
                activeKey = "memory-long-term-consolidation:active:v1",
                statuses = MemoryLongTermCheckpointStatus.ACTIVE
            )
        )
    }

    @Test
    fun `all persisted crash points resume without repeating llm or changing replay bytes`() = runBlocking {
        CrashPoint.entries.forEach { crashPoint ->
            val intelligence = RecordingLongTermMemoryIntelligence { request ->
                MemoryLongTermConsolidationProposal(
                    decisions = listOf(
                        MemoryLongTermCanonicalDecision(
                            action = MemoryLongTermDecisionAction.CANONICALIZE,
                            memoryIds = request.candidateGroups.flatMap { group -> group.entries }
                                .map(MemoryLongTermCandidateEntry::memoryId),
                            canonicalKey = "communication.response_style",
                            scope = MemoryScope.GENERAL,
                            recallState = MemoryRecallState.QUERY
                        )
                    )
                )
            }
            val fixture = fixture(
                entries = listOf(
                    entry(id = "first", text = "Keep replies concise and direct."),
                    entry(id = "second", text = "Use concise direct answers when possible.")
                ),
                intelligence = intelligence
            )

            val interrupted = runCatching {
                fixture.service(CrashAtCommitPointObserver(crashPoint)).process(fixture.claimedJob)
            }

            assertEquals(crashPoint.error, interrupted.exceptionOrNull()?.message)
            assertEquals(1, intelligence.requests.size)
            val restartedJob = checkNotNull(fixture.jobDao.getById(fixture.claimedJob.jobId))
            val resumed = fixture.service().process(restartedJob)
            assertTrue(
                resumed.status in setOf(
                    MemoryLongTermProcessResult.STATUS_SUCCEEDED,
                    MemoryLongTermProcessResult.STATUS_DUPLICATE
                )
            )
            assertEquals(1, intelligence.requests.size)
            assertEquals(
                MemoryLongTermCheckpointStatus.COMPLETED,
                fixture.checkpointDao.getByJobId(fixture.claimedJob.jobId)?.status
            )
            assertEquals(
                MemoryMaintenanceJobStatus.SUCCEEDED,
                fixture.jobDao.getById(fixture.claimedJob.jobId)?.status
            )
            val stableMarkdown = fixture.fileStore.readLongTermMemory().getOrThrow()
            val parsed = fixture.codec.parse(stableMarkdown)
            assertEquals(
                1,
                parsed.entries.count { current ->
                    current.validity == MemoryValidity.CURRENT &&
                        current.canonicalKey == "communication.response_style"
                }
            )
            val duplicate = fixture.service().process(
                checkNotNull(fixture.jobDao.getById(fixture.claimedJob.jobId))
            )
            assertEquals(MemoryLongTermProcessResult.STATUS_DUPLICATE, duplicate.status)
            assertEquals(stableMarkdown, fixture.fileStore.readLongTermMemory().getOrThrow())
        }
    }

    @Test
    fun `oversize deterministic collision converges through one bounded continuation`() = runBlocking {
        val canonicalKey = "communication.response_style"
        val entries = (0 until MemoryControlledOperationPolicy.MAX_OPERATIONS + 8).map { index ->
            entry(
                id = "collision_$index",
                text = "Response style revision $index",
                canonicalKey = canonicalKey
            )
        }
        val intelligence = RecordingLongTermMemoryIntelligence()
        val fixture = fixture(entries = entries, intelligence = intelligence)
        val initialEntries = fixture.codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow()).entries

        val first = fixture.service().process(fixture.claimedJob)
        val firstMarkdown = fixture.fileStore.readLongTermMemory().getOrThrow()
        val firstEntries = fixture.codec.parse(firstMarkdown).entries
        val firstChangedEntryCount = changedEntryCount(initialEntries, firstEntries)
        val firstMutation = checkNotNull(
            fixture.mutationCoordinator.findBySemanticJobId(fixture.claimedJob.jobId)
        )

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, first.status)
        assertEquals(MemoryControlledOperationPolicy.MAX_OPERATIONS, firstChangedEntryCount)
        assertEquals(firstChangedEntryCount, first.operationCount)
        assertEquals(firstChangedEntryCount, firstMutation.receipts.single().materialMutationCount)
        assertTrue(
            firstEntries.count { current ->
                current.validity == MemoryValidity.CURRENT && current.canonicalKey == canonicalKey
            } > 1
        )
        assertEquals(
            "collision_0",
            firstEntries
                .filter { current ->
                    current.validity == MemoryValidity.CURRENT && current.canonicalKey == canonicalKey
                }
                .minBy(MarkdownMemoryEntry::id)
                .id
        )
        val continuation = checkNotNull(
            fixture.maintenanceScheduler.claimNextRunnable(
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                leaseOwner = "long-term-continuation"
            )
        )
        assertNotEquals(fixture.claimedJob.jobId, continuation.jobId)

        val second = fixture.service().process(continuation)
        val stableMarkdown = fixture.fileStore.readLongTermMemory().getOrThrow()
        val finalEntries = fixture.codec.parse(stableMarkdown).entries
        val secondChangedEntryCount = changedEntryCount(firstEntries, finalEntries)
        val secondMutation = checkNotNull(
            fixture.mutationCoordinator.findBySemanticJobId(continuation.jobId)
        )

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, second.status)
        assertEquals(8, secondChangedEntryCount)
        assertEquals(entries.size, firstChangedEntryCount + secondChangedEntryCount)
        assertEquals(secondChangedEntryCount, second.operationCount)
        assertEquals(secondChangedEntryCount, secondMutation.receipts.single().materialMutationCount)
        assertEquals(0, intelligence.requests.size)
        assertEquals(
            1,
            finalEntries.count { current ->
                current.validity == MemoryValidity.CURRENT && current.canonicalKey == canonicalKey
            }
        )
        assertEquals(
            "collision_0",
            finalEntries.single { current ->
                current.validity == MemoryValidity.CURRENT && current.canonicalKey == canonicalKey
            }.id
        )
        assertEquals(
            firstEntries.single { entry -> entry.id == "collision_0" },
            finalEntries.single { entry -> entry.id == "collision_0" }
        )
        assertEquals(false, checkNotNull(fixture.checkpointDao.getByJobId(continuation.jobId)).continuationRequired)
        assertNull(
            fixture.checkpointDao.getActive(
                activeKey = "memory-long-term-consolidation:active:v1",
                statuses = MemoryLongTermCheckpointStatus.ACTIVE
            )
        )

        val replay = fixture.service().process(checkNotNull(fixture.jobDao.getById(continuation.jobId)))

        assertEquals(MemoryLongTermProcessResult.STATUS_DUPLICATE, replay.status)
        assertEquals(stableMarkdown, fixture.fileStore.readLongTermMemory().getOrThrow())
    }

    @Test
    fun `repeated managed sections are coalesced before semantic continuation`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val major = entry(
            id = "education_major",
            text = "目前即将升入大二，专业是计算机科学与技术（计科）。",
            canonicalKey = "profile.education.major"
        ).copy(type = "stable_profile")
        val institution = entry(
            id = "institution_tier",
            text = "就读于二本院校。",
            canonicalKey = "profile.education.institution_tier"
        ).copy(type = "stable_profile")
        val repeatedSections = buildString {
            appendLine("# ChatWithChat Memory")
            appendLine()
            appendLine(codec.renderLongTermAppend(listOf(major)).trim())
            appendLine()
            appendLine(codec.renderLongTermAppend(listOf(institution)).trim())
        }
        val intelligence = RecordingLongTermMemoryIntelligence { MemoryLongTermConsolidationProposal() }
        val fixture = fixture(
            entries = emptyList(),
            intelligence = intelligence,
            initialMarkdown = repeatedSections
        )

        val result = fixture.service().process(fixture.claimedJob)
        val repairedMarkdown = fixture.fileStore.readLongTermMemory().getOrThrow()

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, result.operationCount)
        assertEquals(0, intelligence.requests.size)
        assertEquals(1, Regex("(?m)^## Stable Profile$").findAll(repairedMarkdown).count())
        assertEquals(
            setOf(major.id, institution.id),
            fixture.codec.parse(repairedMarkdown).entries.map(MarkdownMemoryEntry::id).toSet()
        )
        assertNotNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(fixture.claimedJob.jobId))
    }

    @Test
    fun `duplicate ids and dangling supersession commit as repair only before semantic continuation`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val duplicateFirst = entry(
            id = "duplicate",
            text = "Address the user as Captain.",
            canonicalKey = "identity.preferred_address"
        )
        val duplicateSecond = entry(
            id = "duplicate",
            text = "The user's preferred address is Captain.",
            canonicalKey = "identity.legacy_address"
        )
        val dangling = entry(
            id = "dangling",
            text = "A retired response preference.",
            canonicalKey = "communication.retired_style"
        ).copy(
            validity = MemoryValidity.OBSOLETE,
            supersededBy = "missing_target",
            recallState = MemoryRecallState.MAINTENANCE_ONLY
        )
        val invalidMarkdown = buildString {
            appendLine("# ChatWithChat Memory")
            appendLine()
            appendLine("## Memories")
            appendLine()
            listOf(duplicateFirst, duplicateSecond, dangling).forEach { memory ->
                appendLine(codec.metadataComment(memory))
                appendLine("- ${memory.text}")
                appendLine()
            }
        }
        val intelligence = RecordingLongTermMemoryIntelligence { MemoryLongTermConsolidationProposal() }
        val fixture = fixture(
            entries = emptyList(),
            intelligence = intelligence,
            initialMarkdown = invalidMarkdown
        )

        val repairResult = fixture.service().process(fixture.claimedJob)

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, repairResult.status)
        assertEquals(2, repairResult.operationCount)
        assertEquals(0, intelligence.requests.size)
        assertNotNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(fixture.claimedJob.jobId))
        val repaired = fixture.codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow())
        assertTrue(repaired.skippedEntries.isEmpty())
        assertEquals(3, repaired.entries.size)
        assertEquals(3, repaired.entries.map(MarkdownMemoryEntry::id).distinct().size)
        assertEquals(MemoryValidity.CONTESTED, repaired.entries.single { it.id == "dangling" }.validity)

        val continuation = checkNotNull(
            fixture.maintenanceScheduler.claimNextRunnable(
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                leaseOwner = "relationship-repair-continuation"
            )
        )
        val continuationResult = fixture.service().process(continuation)

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, continuationResult.status)
        assertTrue(intelligence.requests.isNotEmpty())
    }

    @Test
    fun `oversize structural repair converges in bounded passes before clean semantic scan`() = runBlocking {
        val codec = MarkdownMemoryCodec()
        val danglingEntries = (0 until MemoryControlledOperationPolicy.MAX_OPERATIONS + 8).map { index ->
            entry(
                id = "dangling_$index",
                text = "Retired preference $index.",
                canonicalKey = "maintenance.dangling_$index"
            ).copy(
                validity = MemoryValidity.OBSOLETE,
                supersededBy = "missing_target_$index",
                recallState = MemoryRecallState.MAINTENANCE_ONLY
            )
        }
        val invalidMarkdown = buildString {
            appendLine("# ChatWithChat Memory")
            appendLine()
            appendLine("## Memories")
            appendLine()
            danglingEntries.forEach { memory ->
                appendLine(codec.metadataComment(memory))
                appendLine("- ${memory.text}")
                appendLine()
            }
        }
        val intelligence = RecordingLongTermMemoryIntelligence()
        val fixture = fixture(
            entries = emptyList(),
            intelligence = intelligence,
            initialMarkdown = invalidMarkdown
        )

        val first = fixture.service().process(fixture.claimedJob)
        val firstParsed = fixture.codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow())
        val firstMutation = checkNotNull(
            fixture.mutationCoordinator.findBySemanticJobId(fixture.claimedJob.jobId)
        )

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, first.status)
        assertEquals(MemoryControlledOperationPolicy.MAX_OPERATIONS, first.operationCount)
        assertEquals(first.operationCount, firstMutation.receipts.single().materialMutationCount)
        assertEquals(8, firstParsed.skippedEntries.size)
        assertEquals(MemoryControlledOperationPolicy.MAX_OPERATIONS, firstParsed.entries.size)
        assertEquals(0, intelligence.requests.size)

        val repairContinuation = checkNotNull(
            fixture.maintenanceScheduler.claimNextRunnable(
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                leaseOwner = "structural-repair-continuation"
            )
        )
        val second = fixture.service().process(repairContinuation)
        val repaired = fixture.codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow())
        val secondMutation = checkNotNull(
            fixture.mutationCoordinator.findBySemanticJobId(repairContinuation.jobId)
        )

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, second.status)
        assertEquals(8, second.operationCount)
        assertEquals(second.operationCount, secondMutation.receipts.single().materialMutationCount)
        assertTrue(repaired.skippedEntries.isEmpty())
        assertEquals(danglingEntries.size, repaired.entries.size)
        assertTrue(repaired.entries.all { entry -> entry.validity == MemoryValidity.CONTESTED })
        assertEquals(0, intelligence.requests.size)

        val semanticContinuation = checkNotNull(
            fixture.maintenanceScheduler.claimNextRunnable(
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                leaseOwner = "structural-repair-semantic-scan"
            )
        )
        val semanticScan = fixture.service().process(semanticContinuation)
        val semanticCheckpoint = checkNotNull(fixture.checkpointDao.getByJobId(semanticContinuation.jobId))

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, semanticScan.status)
        assertEquals("clean_no_op", semanticScan.reason)
        assertEquals(0, semanticScan.operationCount)
        assertEquals(semanticCheckpoint.entryCount, semanticCheckpoint.partitionCursor)
        assertEquals(danglingEntries.size, semanticCheckpoint.entryCount)
        assertEquals(0, intelligence.requests.size)
        assertNull(fixture.recoveryDao.getMutationGroupBySemanticJobId(semanticContinuation.jobId))
        assertNull(
            fixture.checkpointDao.getActive(
                activeKey = "memory-long-term-consolidation:active:v1",
                statuses = MemoryLongTermCheckpointStatus.ACTIVE
            )
        )
    }

    @Test
    fun `Chinese preferred address variants converge to one active survivor`() = runBlocking {
        val targetKey = "identity.preferred_address"
        val intelligence = RecordingLongTermMemoryIntelligence { request ->
            MemoryLongTermConsolidationProposal(
                decisions = listOf(
                    MemoryLongTermCanonicalDecision(
                        action = MemoryLongTermDecisionAction.CANONICALIZE,
                        memoryIds = request.candidateGroups.single().entries.map(MemoryLongTermCandidateEntry::memoryId),
                        canonicalKey = targetKey,
                        scope = MemoryScope.GENERAL,
                        recallState = MemoryRecallState.CORE
                    )
                )
            )
        }
        val fixture = fixture(
            entries = listOf(
                entry(
                    id = "legacy_address",
                    text = "希望以后称呼我为“大哥”。",
                    canonicalKey = "identity.legacy_address"
                ),
                entry(
                    id = "preferred_address",
                    text = "用户偏好的称呼是“大哥”。",
                    canonicalKey = targetKey
                )
            ),
            intelligence = intelligence
        )

        val result = fixture.service().process(fixture.claimedJob)
        val parsed = fixture.codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow())
        val active = parsed.entries.filter { memory ->
            memory.validity == MemoryValidity.CURRENT &&
                memory.recallState in setOf(MemoryRecallState.CORE, MemoryRecallState.QUERY)
        }

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, intelligence.requests.size)
        assertEquals(1, active.size)
        assertEquals(targetKey, active.single().canonicalKey)
        assertEquals(MemoryScope.GENERAL, active.single().scope)
        assertEquals(MemoryRecallState.CORE, active.single().recallState)
        assertTrue(parsed.entries.filter { it.validity == MemoryValidity.OBSOLETE }.all { it.supersededBy == active.single().id })
    }

    @Test
    fun `singleton preferred address query is promoted into the recall capsule`() = runBlocking {
        val targetKey = "identity.preferred_address"
        val fixture = fixture(
            entries = listOf(
                entry(
                    id = "preferred_address",
                    text = "用户偏好的称呼是“大哥”。",
                    canonicalKey = targetKey
                )
            ),
            intelligence = RecordingLongTermMemoryIntelligence()
        )

        val result = fixture.service().process(fixture.claimedJob)
        val markdown = fixture.fileStore.readLongTermMemory().getOrThrow()
        val parsed = fixture.codec.parse(markdown)
        val active = parsed.entries.single { it.validity == MemoryValidity.CURRENT }
        val chunking = MemoryChunker(fixture.codec).chunksFor(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown = markdown,
            projectionPolicy = MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        )
        val snapshot = MemoryCorpusSnapshot(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            canonicalSourceHash = "canonical",
            recallProjectionHash = chunking.projectionHash,
            generation = 1L,
            chunks = chunking.chunks
        )

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, result.operationCount)
        assertEquals(0, fixture.intelligence.requests.size)
        assertEquals(MemoryRecallState.CORE, active.recallState)
        assertEquals(
            listOf(active.id),
            snapshot.selectCoreResults(includePrivate = true).mapNotNull(MemoryRetrievalResult::entryId)
        )
    }

    @Test
    fun `singleton assistant name query is promoted into the recall capsule`() = runBlocking {
        val targetKey = "identity.assistant_name"
        val fixture = fixture(
            entries = listOf(
                entry(
                    id = "assistant_name",
                    text = "用户为 AI 取名为“小c”，以后称其为小c。",
                    canonicalKey = targetKey
                )
            ),
            intelligence = RecordingLongTermMemoryIntelligence()
        )

        val result = fixture.service().process(fixture.claimedJob)
        val markdown = fixture.fileStore.readLongTermMemory().getOrThrow()
        val parsed = fixture.codec.parse(markdown)
        val active = parsed.entries.single { it.validity == MemoryValidity.CURRENT }
        val chunking = MemoryChunker(fixture.codec).chunksFor(
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            markdown = markdown,
            projectionPolicy = MemoryProjectionPolicy.CHAT_ACTIVE_ONLY
        )
        val snapshot = MemoryCorpusSnapshot(
            corpus = MemoryCorpus.CHAT_RECALL_LONG_TERM,
            sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
            canonicalSourceHash = "canonical",
            recallProjectionHash = chunking.projectionHash,
            generation = 1L,
            chunks = chunking.chunks
        )

        assertEquals(MemoryLongTermProcessResult.STATUS_SUCCEEDED, result.status)
        assertEquals(1, result.operationCount)
        assertEquals(0, fixture.intelligence.requests.size)
        assertEquals(MemoryRecallState.CORE, active.recallState)
        assertEquals(
            listOf(active.id),
            snapshot.selectCoreResults(includePrivate = true).mapNotNull(MemoryRetrievalResult::entryId)
        )
    }

    private suspend fun fixture(
        entries: List<MarkdownMemoryEntry>,
        intelligence: RecordingLongTermMemoryIntelligence,
        initialMarkdown: String? = null,
        forceReview: Boolean = false
    ): Fixture {
        val fileStore = MemoryFileStore(
            paths = MemoryFilePaths(Files.createTempDirectory("memory-long-term-service").toFile()),
            clock = FIXED_CLOCK
        )
        val codec = MarkdownMemoryCodec()
        fileStore.ensureStore().getOrThrow()
        fileStore.replaceLongTermMemory(initialMarkdown ?: codec.renderLongTerm(entries)).getOrThrow()

        val checkpointDao = InMemoryServiceLongTermConsolidationDao()
        val recoveryDao = InMemoryMemoryRecoveryDao()
        val jobDao = InMemoryMaintenanceJobDao()
        val workEnqueuer = RecordingWorkEnqueuer()
        val platformCatalog = mutableListOf(PLATFORM)
        val modelCatalog = mutableListOf(PLATFORM_MODEL)
        val settingRepository = settingRepository(platformCatalog, modelCatalog)
        val maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, FIXED_CLOCK)
        val longTermScheduler = MemoryLongTermConsolidationScheduler(
            memoryFileStore = fileStore,
            markdownMemoryCodec = codec,
            checkpointDao = checkpointDao,
            maintenanceScheduler = maintenanceScheduler,
            settingRepository = settingRepository,
            workEnqueuer = workEnqueuer,
            clock = FIXED_CLOCK
        )
        val mutationCoordinator = MemoryMutationCoordinator(
            recoveryDao = recoveryDao,
            memoryFileStore = fileStore,
            maintenanceScheduler = maintenanceScheduler,
            workEnqueuer = workEnqueuer,
            clock = FIXED_CLOCK
        )
        val plan = if (forceReview) {
            longTermScheduler.scheduleForceNow()
        } else {
            longTermScheduler.ensureScheduled()
        }
        assertTrue(plan.scheduled)
        val claimedJob = checkNotNull(
            maintenanceScheduler.claimNextRunnable(
                family = MemoryMaintenanceJobFamily.SEMANTIC,
                leaseOwner = "long-term-service-test"
            )
        )
        assertEquals(plan.jobId, claimedJob.jobId)
        return Fixture(
            fileStore = fileStore,
            codec = codec,
            checkpointDao = checkpointDao,
            recoveryDao = recoveryDao,
            jobDao = jobDao,
            workEnqueuer = workEnqueuer,
            settingRepository = settingRepository,
            maintenanceScheduler = maintenanceScheduler,
            longTermScheduler = longTermScheduler,
            mutationCoordinator = mutationCoordinator,
            intelligence = intelligence,
            platformCatalog = platformCatalog,
            modelCatalog = modelCatalog,
            claimedJob = claimedJob
        )
    }

    private fun settingRepository(
        platformCatalog: List<PlatformV2>,
        modelCatalog: List<PlatformModelV2>
    ): SettingRepository {
        val handler = java.lang.reflect.InvocationHandler { _, method, arguments ->
            when (method.name) {
                "fetchMemoryEnabled" -> true
                "fetchMemoryModelPreference" -> MemoryModelPreference.Auto
                "fetchPlatformV2s" -> platformCatalog.toList()
                "fetchPlatformModels" -> if (method.parameterCount == 1) {
                    modelCatalog.toList()
                } else {
                    modelCatalog.filter { model -> model.platformUid == arguments?.firstOrNull() }
                }
                else -> error("Unexpected SettingRepository call: ${method.name}")
            }
        }
        return Proxy.newProxyInstance(
            SettingRepository::class.java.classLoader,
            arrayOf(SettingRepository::class.java),
            handler
        ) as SettingRepository
    }

    private fun entry(
        id: String,
        text: String,
        canonicalKey: String? = null
    ): MarkdownMemoryEntry = MarkdownMemoryEntry(
        id = id,
        text = text,
        type = "communication_style",
        sensitivity = MemorySensitivity.NORMAL,
        source = MemorySource.EXPLICIT_USER_STATEMENT,
        createdAt = NOW_EPOCH_SECONDS - 60,
        updatedAt = NOW_EPOCH_SECONDS - 30,
        canonicalKey = canonicalKey,
        scope = MemoryScope.GENERAL,
        lastObservedAt = NOW_EPOCH_SECONDS - 30,
        recallState = MemoryRecallState.QUERY
    )

    private fun changedEntryCount(
        before: List<MarkdownMemoryEntry>,
        after: List<MarkdownMemoryEntry>
    ): Int {
        val beforeById = before.associateBy(MarkdownMemoryEntry::id)
        val afterById = after.associateBy(MarkdownMemoryEntry::id)
        return (beforeById.keys + afterById.keys).count { id -> beforeById[id] != afterById[id] }
    }

    private data class Fixture(
        val fileStore: MemoryFileStore,
        val codec: MarkdownMemoryCodec,
        val checkpointDao: InMemoryServiceLongTermConsolidationDao,
        val recoveryDao: InMemoryMemoryRecoveryDao,
        val jobDao: InMemoryMaintenanceJobDao,
        val workEnqueuer: RecordingWorkEnqueuer,
        val settingRepository: SettingRepository,
        val maintenanceScheduler: MemoryMaintenanceScheduler,
        val longTermScheduler: MemoryLongTermConsolidationScheduler,
        val mutationCoordinator: MemoryMutationCoordinator,
        val intelligence: RecordingLongTermMemoryIntelligence,
        val platformCatalog: MutableList<PlatformV2>,
        val modelCatalog: MutableList<PlatformModelV2>,
        val claimedJob: MemoryMaintenanceJob
    ) {
        fun service(
            observer: MemoryLongTermConsolidationCommitObserver = MemoryLongTermConsolidationCommitObserver.None,
            clock: Clock = FIXED_CLOCK
        ): MemoryLongTermConsolidationService = MemoryLongTermConsolidationService(
            checkpointDao = checkpointDao,
            maintenanceScheduler = maintenanceScheduler,
            settingRepository = settingRepository,
            modelResolver = MemoryModelResolver(settingRepository),
            memoryIntelligence = intelligence,
            memoryFileStore = fileStore,
            markdownMemoryCodec = codec,
            operationController = MemoryLongTermConsolidationOperationController(codec),
            memoryMutationCoordinator = mutationCoordinator,
            longTermScheduler = longTermScheduler,
            commitObserver = observer,
            clock = clock
        )
    }

    private companion object {
        const val NOW_EPOCH_SECONDS = 1_785_283_200L
        const val CRASH_AFTER_PARTITION = "crash_after_partition_persisted"
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.ofEpochSecond(NOW_EPOCH_SECONDS), ZoneOffset.UTC)
        val PLATFORM = PlatformV2(
            uid = "memory-platform",
            name = "Memory Platform",
            compatibleType = ClientType.OPENAI,
            enabled = true,
            apiUrl = "https://memory.example.test/v1",
            token = "token",
            model = "memory-model"
        )
        val PLATFORM_MODEL = PlatformModelV2(
            platformUid = PLATFORM.uid,
            modelId = PLATFORM.model,
            displayName = "Memory Model",
            enabled = true
        )
        val SECOND_PLATFORM = PLATFORM.copy(
            uid = "second-memory-platform",
            name = "Second Memory Platform",
            model = "second-memory-model"
        )
        val SECOND_PLATFORM_MODEL = PLATFORM_MODEL.copy(
            platformUid = SECOND_PLATFORM.uid,
            modelId = SECOND_PLATFORM.model,
            displayName = "Second Memory Model"
        )
    }
}

private class RecordingLongTermMemoryIntelligence(
    private val proposal: (MemoryLongTermConsolidationPartitionRequest) -> MemoryLongTermConsolidationProposal? = {
        error("Long-term intelligence was not expected")
    }
) : MemoryIntelligence {
    val requests = mutableListOf<MemoryLongTermConsolidationPartitionRequest>()
    val resolvedPlatforms = mutableListOf<PlatformV2>()

    override suspend fun consolidateMemoryBatch(
        request: MemoryBatchConsolidationRequest,
        resolvedPlatform: PlatformV2
    ): MemoryBatchConsolidationProposal? = error("Batch consolidation was not expected")

    override suspend fun distillDailyMemory(
        request: MemoryDailyDistillationFrozenInput,
        resolvedPlatform: PlatformV2
    ): MemoryDailyDistillationProposal? = error("Daily distillation was not expected")

    override suspend fun consolidateLongTermMemory(
        request: MemoryLongTermConsolidationPartitionRequest,
        resolvedPlatform: PlatformV2
    ): MemoryLongTermConsolidationProposal? {
        requests += request
        resolvedPlatforms += resolvedPlatform
        return proposal(request)
    }
}

private class CrashAfterFirstPartitionObserver : MemoryLongTermConsolidationCommitObserver {
    private var partitionPersistCount = 0

    override suspend fun afterPartitionPersisted(checkpoint: MemoryLongTermConsolidationCheckpoint) {
        partitionPersistCount += 1
        if (partitionPersistCount == 1) error("crash_after_partition_persisted")
    }
}

private enum class CrashPoint(val error: String) {
    PARTITION_PERSISTED("crash_after_partition_persisted"),
    MUTATION_PREPARED("crash_after_mutation_prepared"),
    CANONICAL_FILE_COMMITTED("crash_after_canonical_file_committed"),
    CHECKPOINT_COMPLETED("crash_after_checkpoint_completed")
}

private class CrashAtCommitPointObserver(
    private val crashPoint: CrashPoint
) : MemoryLongTermConsolidationCommitObserver {
    override suspend fun afterPartitionPersisted(checkpoint: MemoryLongTermConsolidationCheckpoint) {
        crashIf(CrashPoint.PARTITION_PERSISTED)
    }

    override suspend fun afterPrepared(mutation: MemoryPreparedMutation) {
        crashIf(CrashPoint.MUTATION_PREPARED)
    }

    override suspend fun afterCanonicalFileCommit(mutation: MemoryPreparedMutation) {
        crashIf(CrashPoint.CANONICAL_FILE_COMMITTED)
    }

    override suspend fun afterCheckpointCompletion(checkpoint: MemoryLongTermConsolidationCheckpoint) {
        crashIf(CrashPoint.CHECKPOINT_COMPLETED)
    }

    private fun crashIf(point: CrashPoint) {
        if (crashPoint == point) error(crashPoint.error)
    }
}

private class InMemoryServiceLongTermConsolidationDao : MemoryLongTermConsolidationDao {
    private val checkpoints = mutableListOf<MemoryLongTermConsolidationCheckpoint>()

    override suspend fun insertIgnore(checkpoint: MemoryLongTermConsolidationCheckpoint): Long {
        val conflicts = checkpoints.any { current ->
            current.checkpointId == checkpoint.checkpointId ||
                current.jobId == checkpoint.jobId ||
                (checkpoint.activeKey != null && current.activeKey == checkpoint.activeKey)
        }
        if (conflicts) return -1L
        checkpoints += checkpoint
        return checkpoints.size.toLong()
    }

    override suspend fun getById(checkpointId: String): MemoryLongTermConsolidationCheckpoint? =
        checkpoints.firstOrNull { checkpoint -> checkpoint.checkpointId == checkpointId }

    override suspend fun getByJobId(jobId: String): MemoryLongTermConsolidationCheckpoint? =
        checkpoints.firstOrNull { checkpoint -> checkpoint.jobId == jobId }

    override suspend fun getActive(
        activeKey: String,
        statuses: List<String>
    ): MemoryLongTermConsolidationCheckpoint? = checkpoints
        .filter { checkpoint -> checkpoint.activeKey == activeKey && checkpoint.status in statuses }
        .sortedWith(compareBy<MemoryLongTermConsolidationCheckpoint> { it.createdAt }.thenBy { it.checkpointId })
        .firstOrNull()

    override suspend fun getLatestCompleted(completedStatus: String): MemoryLongTermConsolidationCheckpoint? = checkpoints
        .filter { checkpoint -> checkpoint.status == completedStatus }
        .sortedWith(
            compareByDescending<MemoryLongTermConsolidationCheckpoint> { it.completedAt ?: Long.MIN_VALUE }
                .thenByDescending { it.checkpointId }
        )
        .firstOrNull()

    override suspend fun sumMaterialMutationsAfterGeneration(
        sourcePath: String,
        afterGeneration: Long
    ): Long {
        assertEquals(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, sourcePath)
        assertTrue(afterGeneration >= 0)
        return 0
    }

    override suspend fun getLatestCommittedGeneration(sourcePath: String): Long {
        assertEquals(MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME, sourcePath)
        return 0
    }

    override suspend fun advancePartitionCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedBaseSourceHash: String,
        expectedOrderedSnapshotHash: String,
        expectedPartitionCursor: Int,
        expectedProposalHash: String?,
        expectedProposalJson: String?,
        newPartitionCursor: Int,
        newProposalHash: String?,
        newProposalJson: String?,
        updatedAt: Long
    ): Int {
        val index = checkpoints.indexOfFirst { checkpoint -> checkpoint.checkpointId == checkpointId }
        if (index == -1) return 0
        val current = checkpoints[index]
        val matches = current.status == expectedStatus &&
            current.rowVersion == expectedRowVersion &&
            current.baseSourceHash == expectedBaseSourceHash &&
            current.orderedSnapshotHash == expectedOrderedSnapshotHash &&
            current.partitionCursor == expectedPartitionCursor &&
            current.proposalHash == expectedProposalHash &&
            current.proposalJson == expectedProposalJson &&
            newPartitionCursor in current.partitionCursor..current.entryCount &&
            updatedAt >= current.updatedAt
        if (!matches) return 0
        checkpoints[index] = current.copy(
            partitionCursor = newPartitionCursor,
            proposalHash = newProposalHash,
            proposalJson = newProposalJson,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }

    override suspend fun setContinuationRequiredCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedContinuationRequired: Boolean,
        continuationRequired: Boolean,
        updatedAt: Long
    ): Int {
        val index = checkpoints.indexOfFirst { checkpoint -> checkpoint.checkpointId == checkpointId }
        if (index == -1) return 0
        val current = checkpoints[index]
        val matches = current.status == expectedStatus &&
            current.rowVersion == expectedRowVersion &&
            current.continuationRequired == expectedContinuationRequired &&
            updatedAt >= current.updatedAt
        if (!matches) return 0
        checkpoints[index] = current.copy(
            continuationRequired = continuationRequired,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }

    override suspend fun bindResolvedModelCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        platformUid: String,
        modelId: String,
        resolvedAt: Long,
        updatedAt: Long
    ): Int {
        val index = checkpoints.indexOfFirst { checkpoint -> checkpoint.checkpointId == checkpointId }
        if (index == -1) return 0
        val current = checkpoints[index]
        val matches = current.status == expectedStatus &&
            current.rowVersion == expectedRowVersion &&
            current.resolvedPlatformUid == null &&
            current.resolvedModelId == null &&
            current.resolvedAt == null &&
            updatedAt >= current.updatedAt
        if (!matches) return 0
        checkpoints[index] = current.copy(
            resolvedPlatformUid = platformUid,
            resolvedModelId = modelId,
            resolvedAt = resolvedAt,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }

    override suspend fun recordAttemptCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        attempt: Int,
        updatedAt: Long
    ): Int {
        val index = checkpoints.indexOfFirst { checkpoint -> checkpoint.checkpointId == checkpointId }
        if (index == -1) return 0
        val current = checkpoints[index]
        if (
            current.status != expectedStatus ||
            current.rowVersion != expectedRowVersion ||
            updatedAt < current.updatedAt
        ) {
            return 0
        }
        checkpoints[index] = current.copy(
            attempt = attempt,
            lastError = null,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }

    override suspend fun recordErrorCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        lastError: String,
        updatedAt: Long
    ): Int {
        val index = checkpoints.indexOfFirst { checkpoint -> checkpoint.checkpointId == checkpointId }
        if (index == -1) return 0
        val current = checkpoints[index]
        if (
            current.status != expectedStatus ||
            current.rowVersion != expectedRowVersion ||
            updatedAt < current.updatedAt
        ) {
            return 0
        }
        checkpoints[index] = current.copy(
            lastError = lastError,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }

    override suspend fun transitionCas(
        checkpointId: String,
        expectedStatus: String,
        expectedRowVersion: Long,
        expectedResultSourceHash: String,
        expectedMutationGroupId: String?,
        newStatus: String,
        newActiveKey: String?,
        newResultSourceHash: String,
        newCompletedGeneration: Long?,
        newMutationGroupId: String?,
        lastError: String?,
        completedAt: Long?,
        updatedAt: Long
    ): Int {
        val index = checkpoints.indexOfFirst { checkpoint -> checkpoint.checkpointId == checkpointId }
        if (index == -1) return 0
        val current = checkpoints[index]
        val matches = current.status == expectedStatus &&
            current.rowVersion == expectedRowVersion &&
            current.resultSourceHash == expectedResultSourceHash &&
            current.mutationGroupId == expectedMutationGroupId &&
            updatedAt >= current.updatedAt
        if (!matches) return 0
        checkpoints[index] = current.copy(
            status = newStatus,
            activeKey = newActiveKey,
            resultSourceHash = newResultSourceHash,
            completedGeneration = newCompletedGeneration,
            mutationGroupId = newMutationGroupId,
            lastError = lastError,
            completedAt = completedAt,
            updatedAt = updatedAt,
            rowVersion = current.rowVersion + 1
        )
        return 1
    }
}
