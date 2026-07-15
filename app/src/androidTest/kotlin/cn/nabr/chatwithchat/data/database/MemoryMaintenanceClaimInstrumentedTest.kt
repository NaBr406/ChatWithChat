package cn.nabr.chatwithchat.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.database.dao.MemoryMaintenanceJobDao
import cn.nabr.chatwithchat.data.database.entity.MemoryMaintenanceJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryMaintenanceClaimInstrumentedTest {

    private lateinit var database: ChatDatabaseV2
    private lateinit var dao: MemoryMaintenanceJobDao

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ChatDatabaseV2::class.java
        ).build()
        dao = database.memoryMaintenanceJobDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun concurrentOwners_claimExactlyOneGlobalSemanticLease() = runBlocking {
        insertJob(jobId = "semantic-1", family = FAMILY_SEMANTIC, createdAt = 1)
        insertJob(jobId = "semantic-2", family = FAMILY_SEMANTIC, createdAt = 2)

        val start = CompletableDeferred<Unit>()
        val claims = (1..8).map { ownerIndex ->
            async(Dispatchers.IO) {
                start.await()
                dao.claimNextRunnable(
                    family = FAMILY_SEMANTIC,
                    leaseOwner = "owner-$ownerIndex",
                    now = 100,
                    leaseExpiresAt = 200
                )
            }
        }
        start.complete(Unit)
        val claimed = claims.awaitAll().filterNotNull()

        assertEquals(1, claimed.size)
        assertEquals("semantic-1", claimed.single().jobId)
        assertEquals(STATUS_RUNNING, claimed.single().status)
        assertEquals(1, claimed.single().attempts)
        assertEquals(0, claimed.single().retryCycle)
        assertEquals(1L, claimed.single().rowVersion)
        assertEquals(200L, claimed.single().leaseExpiresAt)
        assertTrue(claimed.single().leaseOwner.orEmpty().startsWith("owner-"))
        assertEquals(STATUS_PENDING, dao.getById("semantic-2")?.status)
    }

    @Test
    fun activeSemanticLease_blocksSemanticButNotLocalFamilies() = runBlocking {
        insertJob(jobId = "semantic-1", family = FAMILY_SEMANTIC, createdAt = 1)
        insertJob(jobId = "semantic-2", family = FAMILY_SEMANTIC, createdAt = 2)
        insertJob(jobId = "index-1", family = FAMILY_INDEX, createdAt = 3)
        insertJob(jobId = "repair-1", family = FAMILY_REPAIR, createdAt = 4)

        assertNotNull(dao.claimNextRunnable(FAMILY_SEMANTIC, "semantic-owner", 100, 200))
        assertNull(dao.claimNextRunnable(FAMILY_SEMANTIC, "other-semantic-owner", 100, 200))
        assertTrue(!dao.hasRunnableJob(FAMILY_SEMANTIC, now = 100))

        val indexClaim = dao.claimNextRunnable(FAMILY_INDEX, "index-owner", 100, 200)
        val repairClaim = dao.claimNextRunnable(FAMILY_REPAIR, "repair-owner", 100, 200)

        assertEquals("index-1", indexClaim?.jobId)
        assertEquals("repair-1", repairClaim?.jobId)
        assertEquals(STATUS_PENDING, dao.getById("semantic-2")?.status)
    }

    @Test
    fun expiredSemanticLease_stillBlocksUntilRepairReclaimsIt() = runBlocking {
        insertJob(
            jobId = "expired-semantic",
            family = FAMILY_SEMANTIC,
            status = STATUS_RUNNING,
            createdAt = 1,
            attempts = 1,
            leaseOwner = "expired-owner",
            leaseExpiresAt = 90
        )
        insertJob(jobId = "pending-semantic", family = FAMILY_SEMANTIC, createdAt = 2)

        assertNull(dao.claimNextRunnable(FAMILY_SEMANTIC, "new-owner", now = 100, leaseExpiresAt = 200))
        assertEquals(
            1,
            dao.reclaimExpiredLease(
                jobId = "expired-semantic",
                expectedLeaseOwner = "expired-owner",
                expectedRowVersion = 0,
                now = 100,
                newStatus = STATUS_FAILED_RETRYABLE,
                lastError = "lease_expired",
                blockedReason = null,
                updatedAt = 100,
                nextRunAt = 100
            )
        )
        assertNotNull(dao.claimNextRunnable(FAMILY_SEMANTIC, "new-owner", now = 100, leaseExpiresAt = 200))
    }

    @Test
    fun ownerCannotCompleteAfterItsLeaseExpires() = runBlocking {
        insertJob(jobId = "expiring-job", family = FAMILY_INDEX, createdAt = 1)
        val claimed = checkNotNull(
            dao.claimNextRunnable(FAMILY_INDEX, "owner", now = 100, leaseExpiresAt = 110)
        )

        assertEquals(
            0,
            dao.transitionClaimedJob(
                jobId = claimed.jobId,
                leaseOwner = "owner",
                expectedRowVersion = claimed.rowVersion,
                newStatus = STATUS_SUCCEEDED,
                lastError = null,
                blockedReason = null,
                updatedAt = 111,
                nextRunAt = null
            )
        )
        assertEquals(STATUS_RUNNING, dao.getById(claimed.jobId)?.status)
    }

    @Test
    fun claimedTerminalTransition_clearsRunMetadataOnFirstWrite() = runBlocking {
        insertJob(jobId = "terminal-job", family = FAMILY_SEMANTIC, createdAt = 1)
        val claimed = checkNotNull(
            dao.claimNextRunnable(FAMILY_SEMANTIC, "owner", now = 100, leaseExpiresAt = 200)
        )

        assertNotNull(claimed.startedAt)
        assertEquals(
            1,
            dao.transitionClaimedJob(
                jobId = claimed.jobId,
                leaseOwner = "owner",
                expectedRowVersion = claimed.rowVersion,
                newStatus = STATUS_FAILED_TERMINAL,
                lastError = "terminal_reason",
                blockedReason = "terminal_reason",
                updatedAt = 101,
                nextRunAt = null
            )
        )

        val terminal = checkNotNull(dao.getById(claimed.jobId))
        assertEquals(STATUS_FAILED_TERMINAL, terminal.status)
        assertEquals("terminal_reason", terminal.lastError)
        assertEquals("terminal_reason", terminal.blockedReason)
        assertNull(terminal.startedAt)
        assertNull(terminal.nextRunAt)
        assertNull(terminal.leaseOwner)
        assertNull(terminal.leaseExpiresAt)
        assertEquals(claimed.rowVersion + 1, terminal.rowVersion)
    }

    @Test
    fun activeOwner_canExtendLeaseWithoutChangingClaimGeneration() = runBlocking {
        insertJob(jobId = "renewable-job", family = FAMILY_SEMANTIC, createdAt = 1)
        val claimed = checkNotNull(
            dao.claimNextRunnable(FAMILY_SEMANTIC, "owner", now = 100, leaseExpiresAt = 110)
        )

        assertEquals(
            1,
            dao.renewClaimedLease(
                jobId = claimed.jobId,
                leaseOwner = "owner",
                expectedRowVersion = claimed.rowVersion,
                now = 105,
                leaseExpiresAt = 200
            )
        )
        val renewed = checkNotNull(dao.getById(claimed.jobId))
        assertEquals(claimed.rowVersion, renewed.rowVersion)
        assertEquals(200L, renewed.leaseExpiresAt)
        assertEquals(
            0,
            dao.renewClaimedLease(
                jobId = claimed.jobId,
                leaseOwner = "stale-owner",
                expectedRowVersion = claimed.rowVersion,
                now = 106,
                leaseExpiresAt = 300
            )
        )
        assertEquals(
            0,
            dao.renewClaimedLease(
                jobId = claimed.jobId,
                leaseOwner = "owner",
                expectedRowVersion = claimed.rowVersion,
                now = 201,
                leaseExpiresAt = 300
            )
        )
    }

    @Test
    fun claimCas_requiresDueStatusAndRowVersion() = runBlocking {
        insertJob(
            jobId = "future-index",
            family = FAMILY_INDEX,
            createdAt = 1,
            nextRunAt = 200
        )
        insertJob(jobId = "due-index", family = FAMILY_INDEX, createdAt = 2)

        assertEquals(
            0,
            dao.claimRunnableCandidate(
                jobId = "future-index",
                family = FAMILY_INDEX,
                expectedStatus = STATUS_PENDING,
                expectedRowVersion = 0,
                leaseOwner = "owner",
                now = 100,
                leaseExpiresAt = 200
            )
        )
        assertEquals(
            0,
            dao.claimRunnableCandidate(
                jobId = "due-index",
                family = FAMILY_INDEX,
                expectedStatus = STATUS_FAILED_RETRYABLE,
                expectedRowVersion = 0,
                leaseOwner = "owner",
                now = 100,
                leaseExpiresAt = 200
            )
        )
        assertEquals(
            0,
            dao.claimRunnableCandidate(
                jobId = "due-index",
                family = FAMILY_INDEX,
                expectedStatus = STATUS_PENDING,
                expectedRowVersion = 1,
                leaseOwner = "owner",
                now = 100,
                leaseExpiresAt = 200
            )
        )
        assertEquals(
            1,
            dao.claimRunnableCandidate(
                jobId = "due-index",
                family = FAMILY_INDEX,
                expectedStatus = STATUS_PENDING,
                expectedRowVersion = 0,
                leaseOwner = "owner",
                now = 100,
                leaseExpiresAt = 200
            )
        )
    }

    @Test
    fun legacyRunningJobWithoutLease_isExpiredAndCanBeRecovered() = runBlocking {
        insertJob(
            jobId = "legacy-running",
            family = FAMILY_SEMANTIC,
            status = STATUS_RUNNING,
            createdAt = 1,
            attempts = 2
        )

        val expired = dao.getExpiredLeases(now = 100, limit = 10)

        assertEquals(listOf("legacy-running"), expired.map { it.jobId })
        assertEquals(
            1,
            dao.reclaimExpiredLease(
                jobId = "legacy-running",
                expectedLeaseOwner = null,
                expectedRowVersion = 0,
                now = 100,
                newStatus = STATUS_FAILED_RETRYABLE,
                lastError = "interrupted_before_schema_15_lease",
                blockedReason = null,
                updatedAt = 100,
                nextRunAt = 100
            )
        )
        val recovered = dao.getById("legacy-running")
        assertEquals(STATUS_FAILED_RETRYABLE, recovered?.status)
        assertEquals(2, recovered?.attempts)
        assertEquals(1L, recovered?.rowVersion)
        assertNull(recovered?.leaseOwner)
        assertNull(recovered?.leaseExpiresAt)
        assertNull(recovered?.startedAt)
        assertEquals(100L, recovered?.nextRunAt)
    }

    @Test
    fun staleOwnerAndVersion_cannotCompleteReplacementLease() = runBlocking {
        insertJob(jobId = "semantic-retry", family = FAMILY_SEMANTIC, createdAt = 1)

        val firstLease = checkNotNull(
            dao.claimNextRunnable(FAMILY_SEMANTIC, "owner-1", now = 100, leaseExpiresAt = 110)
        )
        assertEquals(1L, firstLease.rowVersion)
        assertEquals(
            1,
            dao.reclaimExpiredLease(
                jobId = firstLease.jobId,
                expectedLeaseOwner = "owner-1",
                expectedRowVersion = firstLease.rowVersion,
                now = 111,
                newStatus = STATUS_FAILED_RETRYABLE,
                lastError = "lease_expired",
                blockedReason = null,
                updatedAt = 111,
                nextRunAt = 111
            )
        )

        val replacementLease = checkNotNull(
            dao.claimNextRunnable(FAMILY_SEMANTIC, "owner-2", now = 111, leaseExpiresAt = 200)
        )
        assertEquals(3L, replacementLease.rowVersion)
        assertEquals(2, replacementLease.attempts)
        assertEquals(0, replacementLease.retryCycle)

        assertEquals(
            0,
            dao.transitionClaimedJob(
                jobId = replacementLease.jobId,
                leaseOwner = "owner-1",
                expectedRowVersion = firstLease.rowVersion,
                newStatus = STATUS_SUCCEEDED,
                lastError = null,
                blockedReason = null,
                updatedAt = 112,
                nextRunAt = null
            )
        )
        assertEquals(
            0,
            dao.transitionClaimedJob(
                jobId = replacementLease.jobId,
                leaseOwner = "owner-1",
                expectedRowVersion = replacementLease.rowVersion,
                newStatus = STATUS_SUCCEEDED,
                lastError = null,
                blockedReason = null,
                updatedAt = 112,
                nextRunAt = null
            )
        )
        assertEquals(
            1,
            dao.transitionClaimedJob(
                jobId = replacementLease.jobId,
                leaseOwner = "owner-2",
                expectedRowVersion = replacementLease.rowVersion,
                newStatus = STATUS_SUCCEEDED,
                lastError = null,
                blockedReason = null,
                updatedAt = 112,
                nextRunAt = null
            )
        )
        val completed = dao.getById(replacementLease.jobId)
        assertEquals(STATUS_SUCCEEDED, completed?.status)
        assertEquals(4L, completed?.rowVersion)
        assertNull(completed?.leaseOwner)
        assertNull(completed?.leaseExpiresAt)
    }

    @Test
    fun unclaimedTransition_cannotOverwriteConcurrentClaimOrNewerVersion() = runBlocking {
        insertJob(jobId = "manual-retry", family = FAMILY_SEMANTIC, createdAt = 1)
        insertJob(jobId = "claimed-before-dismiss", family = FAMILY_SEMANTIC, createdAt = 2)

        assertEquals(
            1,
            dao.transitionUnclaimedJob(
                jobId = "manual-retry",
                expectedStatus = STATUS_PENDING,
                expectedRowVersion = 0,
                newStatus = STATUS_PENDING,
                attempts = 0,
                retryCycle = 1,
                lastError = null,
                blockedReason = null,
                updatedAt = 100,
                nextRunAt = 100
            )
        )
        assertEquals(
            0,
            dao.transitionUnclaimedJob(
                jobId = "manual-retry",
                expectedStatus = STATUS_PENDING,
                expectedRowVersion = 0,
                newStatus = STATUS_PENDING,
                attempts = 0,
                retryCycle = 2,
                lastError = null,
                blockedReason = null,
                updatedAt = 101,
                nextRunAt = 101
            )
        )

        val claimed = checkNotNull(
            dao.claimNextRunnable(FAMILY_SEMANTIC, "semantic-owner", now = 100, leaseExpiresAt = 200)
        )
        assertEquals("manual-retry", claimed.jobId)
        assertEquals(
            0,
            dao.transitionUnclaimedJob(
                jobId = claimed.jobId,
                expectedStatus = STATUS_PENDING,
                expectedRowVersion = 1,
                newStatus = STATUS_SUCCEEDED,
                attempts = claimed.attempts,
                retryCycle = claimed.retryCycle,
                lastError = null,
                blockedReason = null,
                updatedAt = 102,
                nextRunAt = null
            )
        )
        assertEquals(STATUS_RUNNING, dao.getById(claimed.jobId)?.status)
    }

    @Test
    fun earliestFutureRun_isScopedToFamily() = runBlocking {
        insertJob(jobId = "semantic-later", family = FAMILY_SEMANTIC, createdAt = 1, nextRunAt = 300)
        insertJob(
            jobId = "semantic-sooner",
            family = FAMILY_SEMANTIC,
            status = STATUS_FAILED_RETRYABLE,
            createdAt = 2,
            nextRunAt = 250
        )
        insertJob(jobId = "index-sooner", family = FAMILY_INDEX, createdAt = 3, nextRunAt = 200)

        assertEquals(250L, dao.getEarliestFutureRunAtForFamily(FAMILY_SEMANTIC, now = 100))
        assertEquals(200L, dao.getEarliestFutureRunAtForFamily(FAMILY_INDEX, now = 100))
        assertNull(dao.getEarliestFutureRunAtForFamily(FAMILY_REPAIR, now = 100))
    }

    private suspend fun insertJob(
        jobId: String,
        family: String,
        status: String = STATUS_PENDING,
        createdAt: Long,
        nextRunAt: Long? = null,
        attempts: Int = 0,
        leaseOwner: String? = null,
        leaseExpiresAt: Long? = null
    ) {
        val inserted = dao.insertIgnore(
            MemoryMaintenanceJob(
                jobId = jobId,
                type = "${family}_test",
                status = status,
                idempotencyKey = "key-$jobId",
                payloadJson = "{}",
                attempts = attempts,
                lastError = null,
                createdAt = createdAt,
                startedAt = createdAt.takeIf { status == STATUS_RUNNING },
                updatedAt = createdAt,
                nextRunAt = nextRunAt,
                family = family,
                leaseOwner = leaseOwner,
                leaseExpiresAt = leaseExpiresAt
            )
        )
        assertTrue(inserted != -1L)
    }

    companion object {
        private const val FAMILY_SEMANTIC = "semantic"
        private const val FAMILY_INDEX = "index"
        private const val FAMILY_REPAIR = "repair"
        private const val STATUS_PENDING = "pending"
        private const val STATUS_RUNNING = "running"
        private const val STATUS_SUCCEEDED = "succeeded"
        private const val STATUS_FAILED_RETRYABLE = "failed_retryable"
        private const val STATUS_FAILED_TERMINAL = "failed_terminal"
    }
}
