package cn.nabr.chatwithchat.presentation.ui.chat

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentBatchPolicyTest {
    @Test
    fun batchAdmission_preservesOrderAndAllowsPartialSuccess() {
        val result = admitAttachmentBatch(
            existingIdentities = emptySet(),
            existingBytes = 0L,
            candidates = listOf(
                candidate("first", sizeBytes = 4L),
                candidate("too-large", sizeBytes = 11L),
                candidate("second", sizeBytes = 6L)
            ),
            maxBytes = 10L
        )

        assertEquals(listOf("first", "second"), result.accepted.map { it.path })
        assertEquals(1, result.oversizedCount)
    }

    @Test
    fun batchAdmission_countsExistingDraftsBeforeAcceptingNewFiles() {
        val result = admitAttachmentBatch(
            existingIdentities = setOf("existing"),
            existingBytes = 7L,
            candidates = listOf(
                candidate("accepted", sizeBytes = 3L),
                candidate("rejected", sizeBytes = 1L)
            ),
            maxBytes = 10L
        )

        assertEquals(listOf("accepted"), result.accepted.map { it.path })
        assertEquals(1, result.totalLimitCount)
    }

    @Test
    fun batchAdmission_deduplicatesAgainstExistingAndEarlierCandidates() {
        val result = admitAttachmentBatch(
            existingIdentities = setOf("same-as-existing"),
            existingBytes = 1L,
            candidates = listOf(
                candidate("existing-copy", identity = "same-as-existing"),
                candidate("new"),
                candidate("new-copy", identity = "new")
            ),
            maxBytes = 10L
        )

        assertEquals(listOf("new"), result.accepted.map { it.path })
        assertEquals(2, result.duplicateCount)
    }

    @Test
    fun batchAdmission_rejectsUnsupportedAndUnreadableFilesWithoutBlockingValidOnes() {
        val result = admitAttachmentBatch(
            existingIdentities = emptySet(),
            existingBytes = 0L,
            candidates = listOf(
                candidate("unsupported", isSupported = false),
                candidate("missing", sizeBytes = -1L),
                candidate("empty", sizeBytes = 0L),
                candidate("valid")
            ),
            maxBytes = 10L
        )

        assertEquals(listOf("valid"), result.accepted.map { it.path })
        assertEquals(3, result.invalidCount)
    }

    @Test
    fun totalRejectedCount_combinesCopyAndAdmissionFailuresOnce() {
        val admission = admitAttachmentBatch(
            existingIdentities = emptySet(),
            existingBytes = 0L,
            candidates = listOf(
                candidate("unsupported", isSupported = false),
                candidate("oversized", sizeBytes = 11L)
            ),
            maxBytes = 10L
        )

        assertEquals(4, admission.totalRejectedCount(copyFailureCount = 2))
    }

    @Test
    fun copyLimit_allowsExactBoundaryAndRejectsFirstByteBeyondIt() {
        assertFalse(wouldExceedAttachmentCopyLimit(copiedBytes = 7L, nextChunkBytes = 3, maxBytes = 10L))
        assertTrue(wouldExceedAttachmentCopyLimit(copiedBytes = 10L, nextChunkBytes = 1, maxBytes = 10L))
    }

    @Test
    fun attachmentExtension_fallsBackToPickerMimeTypeWhenDisplayNameHasNoExtension() {
        assertEquals("jpg", attachmentExtension("cloud-photo", "image/jpeg"))
        assertEquals("png", attachmentExtension("cloud-photo", "image/png"))
        assertEquals("custom", attachmentExtension("photo.custom", "image/jpeg"))
        assertEquals("", attachmentExtension("cloud-photo", "application/octet-stream"))
    }

    @Test
    fun attachmentIdentity_usesEmbeddedDigestAcrossIndependentCopies() {
        val digest = "a".repeat(64)

        assertEquals(
            "sha256:$digest",
            attachmentIdentity("/cache/attachment_${digest}_first.jpg")
        )
        assertEquals(
            attachmentIdentity("/cache/attachment_${digest}_first.jpg"),
            attachmentIdentity("/cache/attachment_${digest}_second.jpg")
        )
    }

    @Test
    fun duplicateCopy_isDiscardedWithoutDeletingExistingOrAcceptedPaths() {
        val duplicateIdentity = "sha256:${"b".repeat(64)}"
        val candidates = listOf(
            candidate("new-copy", identity = duplicateIdentity),
            candidate("accepted")
        )
        val admission = admitAttachmentBatch(
            existingIdentities = setOf(duplicateIdentity),
            existingBytes = 1L,
            candidates = candidates,
            maxBytes = 10L
        )

        assertEquals(
            setOf("new-copy"),
            attachmentPathsToDiscard(
                existingPaths = setOf("existing-copy"),
                candidates = candidates,
                admission = admission
            )
        )
    }

    @Test
    fun externalSourceWithPreparedCopy_deletesOnlyDraftOwnedPreparedFile() {
        val draft = ChatAttachmentDraft(
            sourceFilePath = "/external/original.jpg",
            preparedFilePath = "/app/prepared.jpg",
            cleanupOnDiscard = false,
            cleanupPreparedOnDiscard = true
        )

        assertEquals(setOf("/app/prepared.jpg"), draft.ownedPathsToDeleteOnDiscard())
    }

    @Test
    fun persistedAttachment_deletesNoHistoricalFilesOnEditDiscard() {
        val draft = ChatAttachmentDraft(
            sourceFilePath = "/app/history.jpg",
            preparedFilePath = "/app/history-prepared.jpg",
            cleanupOnDiscard = false,
            cleanupPreparedOnDiscard = false
        )

        assertEquals(emptySet<String>(), draft.ownedPathsToDeleteOnDiscard())
    }

    @Test
    fun chatSubmission_waitsForCopyAdmissionAndPreprocessing() {
        assertFalse(
            canSubmitChatMessage(
                chatEnabled = true,
                sendButtonEnabled = true,
                hasQuestionText = true,
                hasSendableAttachment = false,
                hasUnreadyAttachment = false,
                isAttachmentBusy = true
            )
        )
        assertFalse(
            canSubmitChatMessage(
                chatEnabled = true,
                sendButtonEnabled = true,
                hasQuestionText = true,
                hasSendableAttachment = false,
                hasUnreadyAttachment = true,
                isAttachmentBusy = false
            )
        )
        assertTrue(
            canSubmitChatMessage(
                chatEnabled = true,
                sendButtonEnabled = true,
                hasQuestionText = false,
                hasSendableAttachment = true,
                hasUnreadyAttachment = false,
                isAttachmentBusy = false
            )
        )
    }

    @Test
    fun abandonedCameraCleanup_preservesOnlyActivePendingCapture() {
        val directory = Files.createTempDirectory("camera-pending-test").toFile()
        val active = directory.resolve("camera_pending_active.jpg").apply { writeText("active") }
        val abandoned = directory.resolve("camera_pending_abandoned.jpg").apply { writeText("abandoned") }
        val unrelated = directory.resolve("history.jpg").apply { writeText("history") }

        try {
            assertEquals(
                1,
                cleanupAbandonedCameraPhotoFiles(directory, active.absolutePath)
            )
            assertTrue(active.exists())
            assertFalse(abandoned.exists())
            assertTrue(unrelated.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun candidate(
        path: String,
        identity: String = path,
        sizeBytes: Long = 1L,
        isSupported: Boolean = true
    ) = AttachmentAdmissionCandidate(
        path = path,
        identity = identity,
        sizeBytes = sizeBytes,
        isSupported = isSupported
    )
}
