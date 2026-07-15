package cn.nabr.chatwithchat.presentation.ui.chat

import java.io.File

internal data class AttachmentAdmissionCandidate(
    val path: String,
    val identity: String,
    val sizeBytes: Long,
    val isSupported: Boolean
)

internal data class AttachmentBatchAdmission(
    val accepted: List<AttachmentAdmissionCandidate>,
    val duplicateCount: Int,
    val invalidCount: Int,
    val oversizedCount: Int,
    val totalLimitCount: Int
) {
    val rejectedCount: Int
        get() = invalidCount + oversizedCount + totalLimitCount
}

internal fun AttachmentBatchAdmission.totalRejectedCount(copyFailureCount: Int): Int =
    copyFailureCount.coerceAtLeast(0) + rejectedCount

internal fun wouldExceedAttachmentCopyLimit(
    copiedBytes: Long,
    nextChunkBytes: Int,
    maxBytes: Long
): Boolean {
    if (copiedBytes < 0L || nextChunkBytes < 0 || maxBytes < 0L) return true
    return nextChunkBytes.toLong() > maxBytes - copiedBytes
}

internal fun admitAttachmentBatch(
    existingIdentities: Set<String>,
    existingBytes: Long,
    candidates: List<AttachmentAdmissionCandidate>,
    maxBytes: Long
): AttachmentBatchAdmission {
    val accepted = mutableListOf<AttachmentAdmissionCandidate>()
    val seenIdentities = existingIdentities.toMutableSet()
    var runningBytes = existingBytes.coerceAtLeast(0L)
    var duplicateCount = 0
    var invalidCount = 0
    var oversizedCount = 0
    var totalLimitCount = 0

    candidates.forEach { candidate ->
        when {
            !seenIdentities.add(candidate.identity) -> duplicateCount += 1
            !candidate.isSupported || candidate.sizeBytes <= 0L -> invalidCount += 1
            candidate.sizeBytes > maxBytes -> oversizedCount += 1
            candidate.sizeBytes > maxBytes - runningBytes -> totalLimitCount += 1
            else -> {
                accepted += candidate
                runningBytes += candidate.sizeBytes
            }
        }
    }

    return AttachmentBatchAdmission(
        accepted = accepted,
        duplicateCount = duplicateCount,
        invalidCount = invalidCount,
        oversizedCount = oversizedCount,
        totalLimitCount = totalLimitCount
    )
}

internal fun attachmentIdentity(filePath: String): String {
    val file = File(filePath)
    val embeddedDigest = file.name
        .removePrefix("attachment_")
        .substringBefore('_')
        .takeIf { candidate ->
            candidate.length == SHA_256_HEX_LENGTH && candidate.all { it.isDigit() || it in 'a'..'f' }
        }
    return embeddedDigest?.let { "sha256:$it" }
        ?: runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
}

internal fun attachmentPathsToDiscard(
    existingPaths: Set<String>,
    candidates: List<AttachmentAdmissionCandidate>,
    admission: AttachmentBatchAdmission
): Set<String> {
    val acceptedPaths = admission.accepted.mapTo(mutableSetOf()) { it.path }
    return candidates
        .asSequence()
        .map { it.path }
        .filterNot { it in existingPaths || it in acceptedPaths }
        .toSet()
}

internal fun ChatAttachmentDraft.ownedPathsToDeleteOnDiscard(): Set<String> = buildSet {
    if (cleanupOnDiscard) add(sourceFilePath)
    preparedFilePath
        ?.takeIf { cleanupPreparedOnDiscard && it != sourceFilePath }
        ?.let(::add)
}

private const val SHA_256_HEX_LENGTH = 64
