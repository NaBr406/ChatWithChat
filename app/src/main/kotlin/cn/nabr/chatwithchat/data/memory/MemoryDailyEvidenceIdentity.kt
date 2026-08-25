package cn.nabr.chatwithchat.data.memory

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Stable ledger identity for one managed entry in one daily file. */
internal object MemoryDailyEvidenceIdentity {
    fun fromEntry(
        entry: MarkdownMemoryEntry,
        sourcePath: String,
        sourceDate: LocalDate,
        clock: Clock
    ): MemoryDailyDistillationEvidence {
        val evidenceKeyIdentity = listOf(sourcePath, entry.id).joinToString(separator = "\u0000")
        val contentIdentity = listOf(
            evidenceKeyIdentity,
            entry.text,
            entry.type,
            entry.sensitivity,
            entry.source,
            entry.createdAt.toString(),
            entry.updatedAt.toString(),
            entry.lastObservedAt.toString(),
            entry.evidenceRefs.sorted().joinToString(separator = "\u0001"),
            entry.canonicalKey.orEmpty(),
            entry.scope
        ).joinToString(separator = "\u0000")
        val observedDate = Instant.ofEpochSecond(entry.lastObservedAt)
            .atZone(clock.zone)
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
        return MemoryDailyDistillationEvidence(
            evidenceKey = "daily_${evidenceKeyIdentity.sha256Utf8().take(EVIDENCE_KEY_HASH_LENGTH)}",
            contentHash = contentIdentity.sha256Utf8(),
            sourcePath = sourcePath,
            sourceDate = sourceDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            entryId = entry.id,
            text = entry.text,
            type = entry.type,
            sensitivity = entry.sensitivity,
            source = entry.source,
            createdAt = entry.createdAt,
            updatedAt = entry.updatedAt,
            lastObservedAt = entry.lastObservedAt,
            evidenceRefs = entry.evidenceRefs.sorted(),
            observationDates = listOf(
                sourceDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                observedDate
            ).distinct().sorted()
        )
    }

    private const val EVIDENCE_KEY_HASH_LENGTH = 24
}
