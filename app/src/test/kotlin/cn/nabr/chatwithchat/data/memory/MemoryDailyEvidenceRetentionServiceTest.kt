package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.InMemoryMemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.entity.MemoryDailyEvidence
import cn.nabr.chatwithchat.data.database.entity.MemoryDailyEvidenceDecision
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDailyEvidenceRetentionServiceTest {

    @Test
    fun `retention removes only old ignored uncited current evidence`() = runBlocking {
        val fixture = Fixture()
        val removable = fixture.entry("remove", "Old ignored observation.")
        val cited = fixture.entry("cited", "Old cited observation.")
        val pending = fixture.entry("pending", "Old pending observation.")
        val newerVersion = fixture.entry("newer", "Old observation with newer version.")
        fixture.writeDaily(removable, cited, pending, newerVersion)
        fixture.insertLedger(removable, MemoryDailyEvidenceDecision.IGNORED)
        val citedEvidence = fixture.insertLedger(cited, MemoryDailyEvidenceDecision.IGNORED)
        fixture.insertLedger(pending, MemoryDailyEvidenceDecision.PENDING)
        val oldVersion = fixture.insertLedger(newerVersion, MemoryDailyEvidenceDecision.IGNORED)
        fixture.recoveryDao.insertDailyEvidenceIgnore(
            oldVersion.copy(
                contentHash = "f".repeat(64),
                decision = MemoryDailyEvidenceDecision.PENDING,
                updatedAt = oldVersion.updatedAt + 1L
            )
        )
        fixture.fileStore.replaceLongTermMemory(
            fixture.codec.renderLongTerm(
                listOf(
                    fixture.entry("long-term", "Promoted fact.").copy(
                        evidenceRefs = listOf(citedEvidence.evidenceKey)
                    )
                )
            )
        ).getOrThrow()

        val result = fixture.service.retain()

        val remaining = fixture.codec.parse(fixture.fileStore.readDailyMemory(Fixture.OLD_DATE).getOrThrow())
            .entries
            .map { entry -> entry.id }
        assertEquals(1, result.removedEntryCount)
        assertEquals(1, result.mutatedFileCount)
        assertEquals(listOf("cited", "newer", "pending"), remaining.sorted())
        assertTrue(
            fixture.recoveryDao.getMutationGroupsByStates(listOf(MemoryMutationState.INDEXED)).isNotEmpty()
        )
    }

    private class Fixture {
        val codec = MarkdownMemoryCodec()
        val fileStore = MemoryFileStore(
            MemoryFilePaths(Files.createTempDirectory("daily-evidence-retention").toFile()),
            CLOCK
        )
        val recoveryDao = InMemoryMemoryRecoveryDao()
        private val scheduler = MemoryMaintenanceScheduler(InMemoryMaintenanceJobDao(), CLOCK)
        private val coordinator = MemoryMutationCoordinator(
            recoveryDao = recoveryDao,
            memoryFileStore = fileStore,
            maintenanceScheduler = scheduler,
            workEnqueuer = RecordingWorkEnqueuer(),
            clock = CLOCK
        )
        val service = MemoryDailyEvidenceRetentionService(
            memoryFileStore = fileStore,
            markdownMemoryCodec = codec,
            recoveryDao = recoveryDao,
            memoryMutationCoordinator = coordinator,
            clock = CLOCK
        )

        init {
            fileStore.ensureStore().getOrThrow()
        }

        fun entry(id: String, text: String) = MarkdownMemoryEntry(
            id = id,
            text = text,
            type = "communication_style",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 1L,
            updatedAt = 2L,
            lastObservedAt = 2L
        )

        fun writeDaily(vararg entries: MarkdownMemoryEntry) {
            fileStore.appendDailyNote(codec.renderDailyAppend(entries.toList()), OLD_DATE).getOrThrow()
        }

        suspend fun insertLedger(entry: MarkdownMemoryEntry, decision: String): MemoryDailyEvidence {
            val sourcePath = "memory/${OLD_DATE}.md"
            val evidence = MemoryDailyEvidenceIdentity.fromEntry(entry, sourcePath, OLD_DATE, CLOCK)
            val ledger = MemoryDailyEvidence(
                evidenceKey = evidence.evidenceKey,
                contentHash = evidence.contentHash,
                sourcePath = sourcePath,
                sourceDate = evidence.sourceDate,
                decision = decision,
                lastWindowId = "daily_old",
                lastCheckpointId = "checkpoint_old",
                pendingDecision = null,
                mutationGroupId = null,
                targetEntryId = null,
                createdAt = 10L,
                updatedAt = 20L,
                decidedAt = 20L
            )
            recoveryDao.insertDailyEvidenceIgnore(ledger)
            return ledger
        }

        companion object {
            val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC)
            val OLD_DATE: LocalDate = LocalDate.parse("2026-06-20")
        }
    }
}
