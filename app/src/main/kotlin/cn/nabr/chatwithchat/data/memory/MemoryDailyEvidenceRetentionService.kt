package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.dao.MemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.entity.MemoryDailyEvidenceDecision
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Removes only fully-decided, uncited daily evidence after the evidence retention period. */
class MemoryDailyEvidenceRetentionService(
    private val memoryFileStore: MemoryFileStore,
    private val markdownMemoryCodec: MarkdownMemoryCodec,
    private val recoveryDao: MemoryRecoveryDao,
    private val memoryMutationCoordinator: MemoryMutationCoordinator,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    suspend fun retain(): MemoryDailyEvidenceRetentionResult {
        val corpus = memoryFileStore.readCorpusFiles(MemoryCorpus.MAINTENANCE_WORKING_SET).getOrThrow()
        val longTerm = corpus.files.singleOrNull { file ->
            file.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
        } ?: return MemoryDailyEvidenceRetentionResult()
        val parsedLongTerm = markdownMemoryCodec.parse(String(longTerm.bytes, StandardCharsets.UTF_8))
        if (parsedLongTerm.skippedEntries.isNotEmpty()) return MemoryDailyEvidenceRetentionResult()
        val citedEvidenceRefs = parsedLongTerm.entries
            .asSequence()
            .filter { entry -> entry.validity == MemoryValidity.CURRENT }
            .flatMap { entry -> entry.evidenceRefs.asSequence() }
            .toSet()
        val today = LocalDate.now(clock)
        val activeWindowStart = today.minusDays(DAILY_WINDOW_DAYS.toLong())
        val retentionCutoff = today.minusDays(RETENTION_DAYS.toLong())
        val targets = corpus.files.mapNotNull { file ->
            val date = dailyDateOrNull(file.sourcePath) ?: return@mapNotNull null
            if (date >= activeWindowStart || date >= retentionCutoff) return@mapNotNull null
            val markdown = String(file.bytes, StandardCharsets.UTF_8)
            val parsed = markdownMemoryCodec.parse(markdown)
            if (parsed.skippedEntries.isNotEmpty()) return@mapNotNull null
            val retainedEntries = parsed.entries.filter { entry ->
                !isRetainable(entry, file.sourcePath, date, citedEvidenceRefs)
            }
            if (retainedEntries.size == parsed.entries.size) return@mapNotNull null
            MemoryMutationTarget(
                sourcePath = file.sourcePath,
                baseContent = markdown,
                targetContent = markdownMemoryCodec.renderDaily(date, retainedEntries),
                targetIndexFingerprint = null,
                materialMutationCount = parsed.entries.size - retainedEntries.size
            )
        }
        if (targets.isEmpty()) return MemoryDailyEvidenceRetentionResult()

        // The unchanged MEMORY.md receipt makes the citation snapshot a CAS precondition.
        val longTermMarkdown = String(longTerm.bytes, StandardCharsets.UTF_8)
        val guardedTargets = listOf(
            MemoryMutationTarget(
                sourcePath = MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME,
                baseContent = longTermMarkdown,
                targetContent = longTermMarkdown,
                targetIndexFingerprint = null
            )
        ) + targets
        val operationKey = "daily-evidence-retention:${targets.joinToString("|") { target ->
            "${target.sourcePath}:${target.baseContent.sha256Utf8()}:${target.targetContent.sha256Utf8()}"
        }.sha256Utf8()}"
        val prepared = memoryMutationCoordinator.prepareRepairMutation(operationKey, guardedTargets)
        return when (memoryMutationCoordinator.reconcile(prepared)) {
            is MemoryMutationCommitResult.CanonicalCommitted -> MemoryDailyEvidenceRetentionResult(
                removedEntryCount = targets.sumOf(MemoryMutationTarget::materialMutationCount),
                mutatedFileCount = targets.size
            )
            is MemoryMutationCommitResult.Conflict -> MemoryDailyEvidenceRetentionResult(conflictCount = 1)
        }
    }

    private suspend fun isRetainable(
        entry: MarkdownMemoryEntry,
        sourcePath: String,
        sourceDate: LocalDate,
        citedEvidenceRefs: Set<String>
    ): Boolean {
        val evidence = MemoryDailyEvidenceIdentity.fromEntry(entry, sourcePath, sourceDate, clock)
        if (evidence.evidenceKey in citedEvidenceRefs) return false
        val ledger = recoveryDao.getDailyEvidence(evidence.evidenceKey, evidence.contentHash) ?: return false
        if (
            ledger.decision != MemoryDailyEvidenceDecision.IGNORED ||
            ledger.pendingDecision != null ||
            ledger.sourcePath != sourcePath ||
            ledger.sourceDate != evidence.sourceDate
        ) {
            return false
        }
        return !recoveryDao.hasNewerDailyEvidenceVersion(
            evidenceKey = ledger.evidenceKey,
            contentHash = ledger.contentHash,
            updatedAt = ledger.updatedAt
        )
    }

    private fun dailyDateOrNull(sourcePath: String): LocalDate? = runCatching {
        val match = DAILY_SOURCE_REGEX.matchEntire(sourcePath) ?: return null
        LocalDate.parse(match.groupValues[1], DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull()

    private companion object {
        const val DAILY_WINDOW_DAYS = 14
        const val RETENTION_DAYS = 30
        val DAILY_SOURCE_REGEX = Regex("memory/(\\d{4}-\\d{2}-\\d{2})\\.md")
    }
}

data class MemoryDailyEvidenceRetentionResult(
    val removedEntryCount: Int = 0,
    val mutatedFileCount: Int = 0,
    val conflictCount: Int = 0
)
