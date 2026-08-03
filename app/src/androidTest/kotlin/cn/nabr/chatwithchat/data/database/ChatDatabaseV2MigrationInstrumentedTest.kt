package cn.nabr.chatwithchat.data.database

import android.database.Cursor
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceJobFamily
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceRepairer
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceScheduler
import cn.nabr.chatwithchat.data.memory.MemoryMaintenanceWorkEnqueuer
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDatabaseV2MigrationInstrumentedTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChatDatabaseV2::class.java
    )

    @Before
    fun clearDatabase() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun deleteDatabase() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migration14To15To16To17_preservesBusinessDataAndCreatesRecoveryState() {
        migrationHelper.createDatabase(TEST_DATABASE, 14).apply {
            insertSchema14Rows(this)
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            17,
            true,
            ChatDatabaseV2Migrations.MIGRATION_14_15,
            ChatDatabaseV2Migrations.MIGRATION_15_16,
            ChatDatabaseV2Migrations.MIGRATION_16_17
        ).use { database ->
            assertEquals(1L, database.singleLong("SELECT COUNT(*) FROM chats_v2"))
            assertEquals("kept chat", database.singleString("SELECT title FROM chats_v2 WHERE chat_id = 7"))
            assertEquals("kept message", database.singleString("SELECT content FROM messages_v2 WHERE message_id = 11"))
            assertEquals("provider-1", database.singleString("SELECT uid FROM platform_v2 WHERE platform_id = 3"))
            assertEquals("model-1", database.singleString("SELECT model_id FROM platform_model_v2 WHERE platform_uid = 'provider-1'"))
            assertEquals("medium", database.singleString("SELECT reasoning_mode FROM chat_platform_model_v2 WHERE chat_id = 7"))
            assertEquals(0L, database.singleLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'memory_document'"))
            assertEquals(0L, database.singleLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'memory_chunk'"))
            assertLegacySemanticTablesAbsent(database)
            assertEquals("batch-1", database.singleString("SELECT batch_id FROM memory_activity_log WHERE log_id = 'log-1'"))
            assertEquals(11L, database.singleLong("SELECT last_observed_user_message_id FROM memory_chat_checkpoint WHERE chat_id = 7"))
            assertEquals("turn-hash", database.singleString("SELECT content_hash FROM memory_pending_turn WHERE turn_key = '7:11'"))

            database.query(
                """
                SELECT status, attempts, family, generation, row_version, lease_owner,
                    lease_expires_at, retry_cycle, blocked_reason
                FROM memory_maintenance_job
                WHERE job_id = 'semantic-job'
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("running", cursor.getString(0))
                assertEquals(2, cursor.getInt(1))
                assertEquals("semantic", cursor.getString(2))
                assertEquals(0L, cursor.getLong(3))
                assertEquals(0L, cursor.getLong(4))
                assertNull(cursor.getString(5))
                assertTrue(cursor.isNull(6))
                assertEquals(0, cursor.getInt(7))
                assertNull(cursor.getString(8))
            }
            assertEquals(
                "index",
                database.singleString("SELECT family FROM memory_maintenance_job WHERE job_id = 'index-job'")
            )
            assertEquals(
                "repair",
                database.singleString("SELECT family FROM memory_maintenance_job WHERE job_id = 'unknown-job'")
            )
            assertEquals(
                "dismissed",
                database.singleString("SELECT status FROM memory_maintenance_job WHERE job_id = 'index-job'")
            )

            listOf(
                "memory_mutation_group",
                "memory_mutation_receipt",
                "memory_corpus_state",
                "memory_distillation_checkpoint"
            ).forEach { tableName ->
                assertEquals(
                    1L,
                    database.singleLong(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$tableName'"
                    )
                )
            }
            assertEquals(
                "0",
                database.singleString(
                    "SELECT dflt_value FROM pragma_table_info('memory_mutation_group') WHERE name = 'expected_receipt_count'"
                )
            )
            assertEquals(
                "0",
                database.singleString(
                    "SELECT dflt_value FROM pragma_table_info('memory_mutation_receipt') WHERE name = 'row_version'"
                )
            )
            assertEquals(
                "0",
                database.singleString(
                    "SELECT dflt_value FROM pragma_table_info('memory_distillation_checkpoint') WHERE name = 'row_version'"
                )
            )
            insertDistillationCheckpoint(database, checkpointId = "checkpoint-1", batchKey = "batch-0001")
            insertDistillationCheckpoint(database, checkpointId = "checkpoint-2", batchKey = "batch-0002")
            assertEquals(
                2L,
                database.singleLong(
                    "SELECT COUNT(*) FROM memory_distillation_checkpoint WHERE daily_source_path = 'memory/2026-07-11.md' AND daily_source_hash = 'daily-hash'"
                )
            )
            database.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
            assertEquals("ok", database.singleString("PRAGMA integrity_check"))
            assertEquals(17L, database.singleLong("PRAGMA user_version"))
            assertEquals(SCHEMA_17_TABLES, database.userTableNames())
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDatabase = Room.databaseBuilder(context, ChatDatabaseV2::class.java, TEST_DATABASE)
            .addMigrations(
                ChatDatabaseV2Migrations.MIGRATION_14_15,
                ChatDatabaseV2Migrations.MIGRATION_15_16,
                ChatDatabaseV2Migrations.MIGRATION_16_17,
                ChatDatabaseV2Migrations.MIGRATION_17_18,
                ChatDatabaseV2Migrations.MIGRATION_18_19,
                ChatDatabaseV2Migrations.MIGRATION_19_20
            )
            .build()
        try {
            runBlocking {
                val oldJob = roomDatabase.memoryMaintenanceJobDao().getById("semantic-job")
                assertEquals("semantic", oldJob?.family)
                assertEquals("running", oldJob?.status)
                assertNull(roomDatabase.memoryRecoveryDao().getCorpusState("chat_recall_long_term"))
                assertTrue(roomDatabase.memoryRecoveryDao().getMutationReceiptsByStates(listOf("prepared")).isEmpty())
                assertEquals(
                    "checkpoint-2",
                    roomDatabase.memoryRecoveryDao().getDistillationCheckpoint(
                        dailySourcePath = "memory/2026-07-11.md",
                        dailySourceHash = "daily-hash",
                        batchKey = "batch-0002"
                    )?.checkpointId
                )
                assertEquals("kept chat", roomDatabase.chatRoomDao().getChatRooms().single().title)
                assertEquals("kept message", roomDatabase.messageDao().loadMessages(7).single().content)
                assertEquals("batch-1", roomDatabase.memoryActivityLogDao().observeLatest(10).first().single().batchId)
            }
        } finally {
            roomDatabase.close()
        }
    }

    @Test
    fun migration15To16_preservesEveryRetainedTableAndDismissesActiveLegacyJobs() {
        var expectedCounts = emptyMap<String, Long>()
        var expectedRows = emptyMap<String, List<List<String>>>()
        migrationHelper.createDatabase(TEST_DATABASE, 15).apply {
            insertSchema15Rows(this)
            expectedCounts = SCHEMA_16_TABLES.associateWith { tableName ->
                singleLong("SELECT COUNT(*) FROM `$tableName`")
            }
            expectedRows = UNCHANGED_SCHEMA_16_TABLES.associateWith { tableName -> snapshotRows(tableName) }
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            16,
            true,
            ChatDatabaseV2Migrations.MIGRATION_15_16
        ).use { database ->
            expectedCounts.forEach { (tableName, expectedCount) ->
                assertEquals(
                    "retained row count for $tableName",
                    expectedCount,
                    database.singleLong("SELECT COUNT(*) FROM `$tableName`")
                )
            }
            expectedRows.forEach { (tableName, expectedTableRows) ->
                assertEquals(
                    "retained values for $tableName",
                    expectedTableRows,
                    database.snapshotRows(tableName)
                )
            }
            assertEquals(SCHEMA_16_TABLES, database.userTableNames())
            assertEquals(
                0L,
                database.singleLong(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND tbl_name IN ('memory_chunk', 'memory_document')"
                )
            )

            database.query(
                """
                SELECT job_id, status, last_error, blocked_reason, next_run_at, started_at,
                    lease_owner, lease_expires_at, row_version, attempts, payload_json,
                    updated_at, retry_cycle
                FROM memory_maintenance_job
                WHERE job_id IN (
                    'legacy-pending', 'legacy-running', 'legacy-retryable',
                    'legacy-waiting', 'legacy-blocked', 'legacy-terminal'
                )
                ORDER BY job_id
                """.trimIndent()
            ).use { cursor ->
                assertEquals(LEGACY_ACTIVE_JOB_IDS.size, cursor.count)
                while (cursor.moveToNext()) {
                    val jobId = cursor.getString(0)
                    assertTrue(jobId in LEGACY_ACTIVE_JOB_IDS)
                    assertEquals("dismissed", cursor.getString(1))
                    assertEquals("schema16_legacy_room_index_removed", cursor.getString(2))
                    assertTrue(cursor.isNull(3))
                    assertTrue(cursor.isNull(4))
                    assertTrue(cursor.isNull(5))
                    assertTrue(cursor.isNull(6))
                    assertTrue(cursor.isNull(7))
                    assertEquals(8L, cursor.getLong(8))
                    assertEquals(2, cursor.getInt(9))
                    assertEquals("{\"sentinel\":\"$jobId\"}", cursor.getString(10))
                    assertEquals(220L, cursor.getLong(11))
                    assertEquals(3, cursor.getInt(12))
                }
            }
            assertSchema15JobUnchanged(database, "legacy-succeeded", "succeeded")
            assertSchema15JobUnchanged(database, "legacy-dismissed", "dismissed")
            assertSchema15JobUnchanged(database, "modern-sync", "pending")

            assertEquals(16L, database.singleLong("PRAGMA user_version"))
            database.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
            assertEquals("ok", database.singleString("PRAGMA integrity_check"))
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDatabase = Room.databaseBuilder(context, ChatDatabaseV2::class.java, TEST_DATABASE)
            .addMigrations(
                ChatDatabaseV2Migrations.MIGRATION_15_16,
                ChatDatabaseV2Migrations.MIGRATION_16_17,
                ChatDatabaseV2Migrations.MIGRATION_17_18,
                ChatDatabaseV2Migrations.MIGRATION_18_19,
                ChatDatabaseV2Migrations.MIGRATION_19_20
            )
            .build()
        try {
            runBlocking {
                assertEquals("schema 16 chat", roomDatabase.chatRoomDao().getChatRooms().single().title)
                assertEquals("schema 16 message", roomDatabase.messageDao().loadMessages(70).single().content)
                assertEquals("provider-16", roomDatabase.platformDao().getPlatform(30)?.uid)
                assertEquals("model-16", roomDatabase.platformModelDao().getModel("provider-16", "model-16")?.modelId)
                assertEquals("medium", roomDatabase.chatPlatformModelDao().getByChatId(70).single().reasoningMode)
                assertEquals("dismissed", roomDatabase.memoryMaintenanceJobDao().getById("legacy-pending")?.status)
                val recoveryDao = roomDatabase.memoryRecoveryDao()
                val mutationGroup = recoveryDao.getMutationGroup("group-15")
                assertEquals(5L, mutationGroup?.generation)
                assertEquals("index_pending", mutationGroup?.state)
                assertEquals(1, mutationGroup?.expectedReceiptCount)
                val mutationReceipt = recoveryDao.getMutationReceipt("receipt-15")
                assertEquals("group-15", mutationReceipt?.groupId)
                assertEquals("index_pending", mutationReceipt?.state)
                val corpusState = recoveryDao.getCorpusState("chat_recall_long_term")
                assertEquals("target-hash-15", corpusState?.recallProjectionHash)
                assertEquals(5L, corpusState?.generation)
                assertEquals("pending", corpusState?.indexStatus)
                assertEquals(
                    "checkpoint-15",
                    recoveryDao.getDistillationCheckpoint(
                        dailySourcePath = "memory/2026-07-12.md",
                        dailySourceHash = "daily-hash-15",
                        batchKey = "batch-15"
                    )?.checkpointId
                )
                assertEquals(110, roomDatabase.memoryTurnBatchDao().getCheckpoint(70)?.lastObservedUserMessageId)
                assertEquals("turn-15", roomDatabase.memoryTurnBatchDao().getPendingTurn(70, 110)?.turnKey)
                assertEquals("activity-15", roomDatabase.memoryActivityLogDao().observeLatest(10).first().single().logId)

                val workEnqueuer = RecordingWorkEnqueuer()
                val repairResult = MemoryMaintenanceRepairer(
                    maintenanceScheduler = MemoryMaintenanceScheduler(
                        jobDao = roomDatabase.memoryMaintenanceJobDao(),
                        clock = FIXED_CLOCK
                    ),
                    workScheduler = workEnqueuer
                ).repairAndEnqueue(reopenWaitingRepair = true)
                assertEquals(0, repairResult.resetCount)
                assertEquals(0, repairResult.reopenedCount)
                assertTrue(repairResult.schedulingSucceeded)
                assertEquals(listOf(MemoryMaintenanceJobFamily.INDEX), workEnqueuer.families)
                LEGACY_ACTIVE_JOB_IDS.forEach { jobId ->
                    assertEquals("dismissed", roomDatabase.memoryMaintenanceJobDao().getById(jobId)?.status)
                }
            }
            assertEquals(SCHEMA_20_TABLES, roomDatabase.openHelper.writableDatabase.userTableNames())
            assertLegacySemanticTablesAbsent(roomDatabase.openHelper.writableDatabase)
        } finally {
            roomDatabase.close()
        }
    }

    @Test
    fun migration16To17_preservesEveryRetainedTableAndCanonicalMemoryBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val memoryFile = File(File(context.filesDir, "memory_store"), "MEMORY.md")
        val originalMemoryBytes = memoryFile.takeIf { it.isFile }?.readBytes()
        val sentinelMemoryBytes = "# Schema 16 canonical memory\r\n\r\n- preserve these exact bytes\r\n"
            .toByteArray(Charsets.UTF_8)

        try {
            memoryFile.parentFile?.mkdirs()
            memoryFile.writeBytes(sentinelMemoryBytes)
            val memoryHashBefore = sentinelMemoryBytes.sha256()
            var expectedCounts = emptyMap<String, Long>()
            var expectedRows = emptyMap<String, List<List<String>>>()

            migrationHelper.createDatabase(TEST_DATABASE, 16).apply {
                insertSchema16Rows(this)
                expectedCounts = SCHEMA_17_TABLES.associateWith { tableName ->
                    singleLong("SELECT COUNT(*) FROM `$tableName`")
                }
                expectedRows = SCHEMA_17_TABLES.associateWith { tableName -> snapshotRows(tableName) }
                assertEquals(1L, singleLong("SELECT COUNT(*) FROM personal_memory"))
                assertEquals(1L, singleLong("SELECT COUNT(*) FROM chat_classification"))
                close()
            }

            migrationHelper.runMigrationsAndValidate(
                TEST_DATABASE,
                17,
                true,
                ChatDatabaseV2Migrations.MIGRATION_16_17
            ).use { database ->
                expectedCounts.forEach { (tableName, expectedCount) ->
                    assertEquals(
                        "retained row count for $tableName",
                        expectedCount,
                        database.singleLong("SELECT COUNT(*) FROM `$tableName`")
                    )
                }
                expectedRows.forEach { (tableName, expectedTableRows) ->
                    assertEquals(
                        "retained values for $tableName",
                        expectedTableRows,
                        database.snapshotRows(tableName)
                    )
                }
                assertEquals(SCHEMA_17_TABLES, database.userTableNames())
                assertLegacySemanticTablesAbsent(database)
                assertEquals(17L, database.singleLong("PRAGMA user_version"))
                database.query("PRAGMA foreign_key_check").use { cursor ->
                    assertEquals(0, cursor.count)
                }
                assertEquals("ok", database.singleString("PRAGMA integrity_check"))
            }

            val memoryBytesAfter = memoryFile.readBytes()
            assertArrayEquals(sentinelMemoryBytes, memoryBytesAfter)
            assertEquals(memoryHashBefore, memoryBytesAfter.sha256())
        } finally {
            if (originalMemoryBytes == null) {
                memoryFile.delete()
            } else {
                memoryFile.parentFile?.mkdirs()
                memoryFile.writeBytes(originalMemoryBytes)
            }
        }
    }

    @Test
    fun migration17To18_preservesMessagesAndCreatesStickerCatalog() {
        migrationHelper.createDatabase(TEST_DATABASE, 17).apply {
            execSQL(
                "INSERT INTO chats_v2 (chat_id, title, enabled_platform, created_at, updated_at) VALUES (1, 'sticker chat', '', 1, 1)"
            )
            execSQL(
                """
                INSERT INTO messages_v2 (
                    message_id, chat_id, thoughts, content, attachments, revisions,
                    active_revision_index, source_metadata, token_usage, linked_message_id,
                    platform_type, created_at
                ) VALUES (1, 1, '', 'existing assistant text', '', '[]', -1, '', NULL, 0, 'provider-1', 2)
                """.trimIndent()
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            18,
            true,
            ChatDatabaseV2Migrations.MIGRATION_17_18
        ).use { database ->
            assertEquals("", database.singleString("SELECT sticker_refs FROM messages_v2 WHERE message_id = 1"))
            assertEquals(SCHEMA_18_TABLES, database.userTableNames())
            database.execSQL(
                "INSERT INTO sticker_packs (pack_id, display_name, is_builtin, created_at, updated_at) VALUES ('user.my_stickers', 'My stickers', 0, 3, 3)"
            )
            database.execSQL(
                """
                INSERT INTO sticker_assets (
                    asset_key, storage_kind, relative_path, media_kind, mime_type,
                    poster_asset_key, duration_ms, loop_count, byte_size, width, height
                ) VALUES (
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'local_file', 'assets/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jpg',
                    'static_raster', 'image/jpeg', NULL, NULL, NULL, 12, 4, 3
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO sticker_items (
                    sticker_id, pack_id, asset_key, title, alt_text, tags_json,
                    aliases_json, enabled, is_builtin, created_at, updated_at
                ) VALUES (
                    'user.fixture', 'user.my_stickers',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'fixture', 'fixture alt', '[]', '[]', 1, 0, 3, 3
                )
                """.trimIndent()
            )
            database.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
            assertEquals("ok", database.singleString("PRAGMA integrity_check"))
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDatabase = Room.databaseBuilder(context, ChatDatabaseV2::class.java, TEST_DATABASE)
            .addMigrations(
                ChatDatabaseV2Migrations.MIGRATION_17_18,
                ChatDatabaseV2Migrations.MIGRATION_18_19,
                ChatDatabaseV2Migrations.MIGRATION_19_20
            )
            .build()
        try {
            runBlocking {
                assertTrue(roomDatabase.messageDao().loadMessages(1).single().stickerRefs.isEmpty())
                assertEquals("user.fixture", roomDatabase.stickerCatalogDao().getItem("user.fixture")?.stickerId)
            }
        } finally {
            roomDatabase.close()
        }
    }

    @Test
    fun migration18To19_preservesPopulatedRowsAndCreatesLongTermConsolidationState() {
        var expectedUnchangedRows = emptyMap<String, List<List<String>>>()
        migrationHelper.createDatabase(TEST_DATABASE, 18).apply {
            insertSchema18LongTermMigrationRows(this)
            expectedUnchangedRows = UNCHANGED_SCHEMA_18_TO_19_TABLES.associateWith { tableName ->
                snapshotRows(tableName).also { rows ->
                    assertTrue("schema 18 fixture should populate $tableName", rows.isNotEmpty())
                }
            }
            assertEquals(2L, singleLong("SELECT COUNT(*) FROM memory_maintenance_job"))
            assertEquals(1L, singleLong("SELECT COUNT(*) FROM memory_mutation_receipt"))
            assertEquals(3L, singleLong("SELECT COUNT(*) FROM memory_activity_log"))
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            19,
            true,
            ChatDatabaseV2Migrations.MIGRATION_18_19
        ).use { database ->
            expectedUnchangedRows.forEach { (tableName, expectedRows) ->
                assertEquals(
                    "retained rows for $tableName",
                    expectedRows,
                    database.snapshotRows(tableName)
                )
            }
            database.query(
                """
                SELECT status, payload_json, family, generation, row_version,
                    attempts, last_error, created_at, started_at, updated_at, next_run_at,
                    lease_owner, lease_expires_at, retry_cycle, blocked_reason,
                    resolved_platform_uid, resolved_model_id, resolved_at
                FROM memory_maintenance_job
                WHERE job_id = 'sync-vector-18'
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("pending", cursor.getString(0))
                assertEquals(SCHEMA_18_SYNC_PAYLOAD, cursor.getString(1))
                assertEquals("index", cursor.getString(2))
                assertEquals(18L, cursor.getLong(3))
                assertEquals(4L, cursor.getLong(4))
                assertEquals(0, cursor.getInt(5))
                assertTrue(cursor.isNull(6))
                assertEquals(300L, cursor.getLong(7))
                assertTrue(cursor.isNull(8))
                assertEquals(302L, cursor.getLong(9))
                assertEquals(303L, cursor.getLong(10))
                assertTrue(cursor.isNull(11))
                assertTrue(cursor.isNull(12))
                assertEquals(0, cursor.getInt(13))
                assertTrue(cursor.isNull(14))
                assertTrue(cursor.isNull(15))
                assertTrue(cursor.isNull(16))
                assertTrue(cursor.isNull(17))
            }

            assertMigratedLegacyActivity(
                database = database,
                logId = "activity-18-model-call",
                expectedBatchId = "batch-18-model-call",
                expectedCategory = "model_call",
                expectedStatus = "running",
                expectedDetail = "model call sentinel",
                expectedOperationCount = null,
                expectedCompletedAt = null,
                expectedUpdatedAt = 306L
            )
            assertMigratedLegacyActivity(
                database = database,
                logId = "activity-18-generation",
                expectedBatchId = "batch-18-generation",
                expectedCategory = "memory_generation",
                expectedStatus = "succeeded",
                expectedDetail = "generation sentinel",
                expectedOperationCount = 2,
                expectedCompletedAt = 307L,
                expectedUpdatedAt = 307L
            )
            assertMigratedLegacyActivity(
                database = database,
                logId = "activity-18-organization",
                expectedBatchId = "batch-18-organization",
                expectedCategory = "memory_organization",
                expectedStatus = "succeeded",
                expectedDetail = "organization sentinel",
                expectedOperationCount = 4,
                expectedCompletedAt = 308L,
                expectedUpdatedAt = 308L
            )
            assertEquals(
                3L,
                database.singleLong(
                    """
                    SELECT COUNT(*)
                    FROM memory_activity_log
                    WHERE attempt = 2
                        AND job_id IS NULL
                        AND category IN ('model_call', 'memory_generation', 'memory_organization')
                    """.trimIndent()
                )
            )

            database.query(
                """
                SELECT group_id, generation, source_path, base_source_hash,
                    target_source_hash, state, row_version, material_mutation_count
                FROM memory_mutation_receipt
                WHERE receipt_id = 'receipt-18'
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("group-18", cursor.getString(0))
                assertEquals(18L, cursor.getLong(1))
                assertEquals("MEMORY.md", cursor.getString(2))
                assertEquals(SCHEMA_18_BASE_SOURCE_HASH, cursor.getString(3))
                assertEquals(SCHEMA_18_TARGET_SOURCE_HASH, cursor.getString(4))
                assertEquals("index_pending", cursor.getString(5))
                assertEquals(3L, cursor.getLong(6))
                assertEquals(0, cursor.getInt(7))
            }

            assertEquals(
                "0",
                database.singleString(
                    "SELECT dflt_value FROM pragma_table_info('memory_activity_log') WHERE name = 'row_version'"
                )
            )
            assertEquals(
                "0",
                database.singleString(
                    "SELECT dflt_value FROM pragma_table_info('memory_activity_log') WHERE name = 'retry_cycle'"
                )
            )
            assertEquals(
                "0",
                database.singleString(
                    "SELECT dflt_value FROM pragma_table_info('memory_mutation_receipt') WHERE name = 'material_mutation_count'"
                )
            )
            assertEquals(
                "0",
                database.singleString(
                    "SELECT dflt_value FROM pragma_table_info('memory_long_term_consolidation_checkpoint') WHERE name = 'partition_cursor'"
                )
            )
            assertEquals(
                "0",
                database.singleString(
                    "SELECT dflt_value FROM pragma_table_info('memory_long_term_consolidation_checkpoint') WHERE name = 'continuation_required'"
                )
            )
            assertEquals(
                1L,
                database.singleLong(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_memory_activity_log_job_id_retry_cycle_attempt' AND sql LIKE 'CREATE UNIQUE INDEX%'"
                )
            )

            assertEquals(
                0L,
                database.singleLong("SELECT COUNT(*) FROM memory_long_term_consolidation_checkpoint")
            )
            database.execSQL(
                """
                INSERT INTO memory_maintenance_job (
                    job_id, type, status, idempotency_key, payload_json, attempts,
                    last_error, created_at, started_at, updated_at, next_run_at,
                    family, generation, row_version, lease_owner, lease_expires_at,
                    retry_cycle, blocked_reason
                ) VALUES (
                    'whole-corpus-19', 'consolidate_long_term_memory', 'pending',
                    'whole-corpus-key-19', ?, 0, NULL,
                    400, NULL, 400, NULL, 'semantic', 18, 0, NULL, NULL, 0, NULL
                )
                """.trimIndent(),
                arrayOf<Any>(SCHEMA_19_LONG_TERM_PAYLOAD)
            )
            database.execSQL(
                """
                INSERT INTO memory_long_term_consolidation_checkpoint (
                    checkpoint_id, job_id, active_key, trigger_reason, source_path,
                    base_source_hash, result_source_hash, base_generation,
                    recall_projection_hash, entry_count, ordered_snapshot_hash,
                    ordered_entry_ids_json, status, created_at, updated_at
                ) VALUES (
                    'checkpoint-19', 'whole-corpus-19', 'memory-long-term-consolidation:active:v1',
                    'material_threshold', 'MEMORY.md', ?, ?, 18, ?, 2, ?,
                    '[\"memory-a\",\"memory-b\"]', 'pending', 400, 401
                )
                """.trimIndent(),
                arrayOf<Any>(
                    SCHEMA_19_LONG_TERM_BASE_HASH,
                    SCHEMA_19_LONG_TERM_BASE_HASH,
                    SCHEMA_18_RECALL_PROJECTION_HASH,
                    SCHEMA_19_LONG_TERM_SNAPSHOT_HASH
                )
            )
            database.execSQL(
                """
                INSERT OR IGNORE INTO memory_long_term_consolidation_checkpoint (
                    checkpoint_id, job_id, active_key, trigger_reason, source_path,
                    base_source_hash, result_source_hash, base_generation,
                    recall_projection_hash, entry_count, ordered_snapshot_hash,
                    ordered_entry_ids_json, status, created_at, updated_at
                ) VALUES (
                    'checkpoint-duplicate-active', 'whole-corpus-duplicate',
                    'memory-long-term-consolidation:active:v1', 'weekly_due', 'MEMORY.md',
                    ?, ?, 18, ?, 0, ?, '[]', 'pending', 402, 402
                )
                """.trimIndent(),
                arrayOf<Any>(
                    SCHEMA_19_DUPLICATE_BASE_HASH,
                    SCHEMA_19_DUPLICATE_BASE_HASH,
                    SCHEMA_19_DUPLICATE_RECALL_HASH,
                    SCHEMA_19_DUPLICATE_SNAPSHOT_HASH
                )
            )
            assertEquals(
                1L,
                database.singleLong(
                    """
                    SELECT COUNT(*)
                    FROM memory_long_term_consolidation_checkpoint
                    WHERE active_key = 'memory-long-term-consolidation:active:v1'
                    """.trimIndent()
                )
            )
            database.query(
                """
                SELECT partition_cursor, continuation_required, material_mutation_count_at_start, attempt,
                    proposal_hash, resolved_platform_uid, mutation_group_id, row_version
                FROM memory_long_term_consolidation_checkpoint
                WHERE checkpoint_id = 'checkpoint-19'
                """.trimIndent()
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertEquals(0, cursor.getInt(2))
                assertEquals(0, cursor.getInt(3))
                assertNull(cursor.getString(4))
                assertNull(cursor.getString(5))
                assertNull(cursor.getString(6))
                assertEquals(0L, cursor.getLong(7))
            }

            assertEquals(SCHEMA_19_TABLES, database.userTableNames())
            assertEquals(19L, database.singleLong("PRAGMA user_version"))
            database.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
            assertEquals("ok", database.singleString("PRAGMA integrity_check"))
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDatabase = Room.databaseBuilder(context, ChatDatabaseV2::class.java, TEST_DATABASE)
            .addMigrations(ChatDatabaseV2Migrations.MIGRATION_18_19, ChatDatabaseV2Migrations.MIGRATION_19_20)
            .build()
        try {
            runBlocking {
                val checkpoint = roomDatabase.memoryLongTermConsolidationDao().getById("checkpoint-19")
                assertEquals("whole-corpus-19", checkpoint?.jobId)
                assertEquals("memory-long-term-consolidation:active:v1", checkpoint?.activeKey)
                assertEquals(0, checkpoint?.partitionCursor)
                assertEquals(false, checkpoint?.continuationRequired)
                val pendingIndexJob = roomDatabase.memoryMaintenanceJobDao().getById("sync-vector-18")
                assertEquals("sync_vector_index", pendingIndexJob?.type)
                assertEquals("pending", pendingIndexJob?.status)
                assertEquals(SCHEMA_18_SYNC_PAYLOAD, pendingIndexJob?.payloadJson)
                assertNull(pendingIndexJob?.resolvedPlatformUid)
                assertEquals("succeeded", roomDatabase.memoryMaintenanceJobDao().getById("daily-18")?.status)
                assertEquals(0, roomDatabase.memoryRecoveryDao().getMutationReceipt("receipt-18")?.materialMutationCount)
                assertEquals("schema 18 chat", roomDatabase.chatRoomDao().getChatRooms().single().title)
                assertEquals("schema 18 message", roomDatabase.messageDao().loadMessages(180).single().content)
                assertEquals("provider-18", roomDatabase.platformDao().getPlatform(180)?.uid)
                assertEquals("model-18", roomDatabase.platformModelDao().getModel("provider-18", "model-18")?.modelId)
                assertEquals("medium", roomDatabase.chatPlatformModelDao().getByChatId(180).single().reasoningMode)
                assertEquals(
                    SCHEMA_18_RECALL_PROJECTION_HASH,
                    roomDatabase.memoryRecoveryDao().getCorpusState("chat_recall_long_term")?.recallProjectionHash
                )
                assertEquals(
                    "distillation-18",
                    roomDatabase.memoryRecoveryDao().getDistillationCheckpoint(
                        dailySourcePath = "memory/2026-07-18.md",
                        dailySourceHash = "daily-hash-18",
                        batchKey = "batch-18"
                    )?.checkpointId
                )
                assertEquals(280, roomDatabase.memoryTurnBatchDao().getCheckpoint(180)?.lastObservedUserMessageId)
                assertEquals("turn-18", roomDatabase.memoryTurnBatchDao().getPendingTurn(180, 280)?.turnKey)
                assertEquals("user.schema18.item", roomDatabase.stickerCatalogDao().getItem("user.schema18.item")?.stickerId)
                val legacyActivities = roomDatabase.memoryActivityLogDao().observeLatest(10).first()
                    .filter { activity -> activity.logId.startsWith("activity-18-") }
                assertEquals(3, legacyActivities.size)
                assertEquals(
                    setOf("model_call", "memory_generation", "memory_organization"),
                    legacyActivities.map { activity -> activity.category }.toSet()
                )
                assertTrue(legacyActivities.all { activity -> activity.jobId == null })
            }
        } finally {
            roomDatabase.close()
        }
    }

    @Test
    fun migration19To20_createsHistoryDerivedTablesAndFtsTriggers() {
        migrationHelper.createDatabase(TEST_DATABASE, 19).apply {
            execSQL(
                "INSERT INTO chats_v2 (chat_id, title, enabled_platform, created_at, updated_at) VALUES (7, 'existing chat', '', 1, 1)"
            )
            execSQL(
                """
                INSERT INTO messages_v2 (
                    message_id, chat_id, thoughts, content, attachments, revisions,
                    active_revision_index, source_metadata, token_usage, linked_message_id,
                    platform_type, created_at
                ) VALUES (11, 7, '', 'existing message', '', '[]', -1, '', NULL, 0, NULL, 2)
                """.trimIndent()
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            20,
            true,
            ChatDatabaseV2Migrations.MIGRATION_19_20
        ).use { database ->
            assertEquals(SCHEMA_20_TABLES, database.userTableNames())
            assertEquals(
                1L,
                database.singleLong(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'chat_history_projection_fts'"
                )
            )
            assertEquals(
                3L,
                database.singleLong(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'chat_history_projection_%'"
                )
            )
            assertEquals(20L, database.singleLong("PRAGMA user_version"))
            assertEquals(1L, database.singleLong("SELECT COUNT(*) FROM chats_v2"))
            assertEquals(1L, database.singleLong("SELECT COUNT(*) FROM messages_v2"))
            database.execSQL(
                """
                INSERT INTO chat_history_projection (
                    turn_key, chat_id, user_message_id, assistant_message_id, assistant_platform_uid,
                    title, user_content, assistant_content, search_terms, content_hash,
                    projection_version, eligibility_state, created_at, updated_at
                ) VALUES (
                    'chat:7:user:11', 7, 11, 12, 'provider', 'existing chat', '北京旅行',
                    '北京旅程 answer', '北京 京旅 北京旅 answer', 'hash-1', 1, 'eligible', 1, 1
                )
                """.trimIndent()
            )
            assertEquals(1L, database.singleLong("SELECT COUNT(*) FROM chat_history_projection_fts WHERE chat_history_projection_fts MATCH '北京'"))
            database.execSQL("UPDATE chat_history_projection SET assistant_content = 'updated answer', search_terms = 'updated' WHERE turn_key = 'chat:7:user:11'")
            assertEquals(1L, database.singleLong("SELECT COUNT(*) FROM chat_history_projection_fts WHERE chat_history_projection_fts MATCH 'updated'"))
            database.execSQL("DELETE FROM chat_history_projection WHERE turn_key = 'chat:7:user:11'")
            assertEquals(0L, database.singleLong("SELECT COUNT(*) FROM chat_history_projection_fts WHERE chat_history_projection_fts MATCH 'updated'"))
            database.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        }
    }

    @Test
    fun freshSchema20_opensAndReopensWithHistoryDerivedState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val freshDatabase = Room.databaseBuilder(context, ChatDatabaseV2::class.java, TEST_DATABASE)
            .addCallback(ChatHistoryDatabaseCallback())
            .build()
        try {
            val database = freshDatabase
            val sqliteDatabase = database.openHelper.writableDatabase
            assertEquals(SCHEMA_20_TABLES, sqliteDatabase.userTableNames())
            assertLegacySemanticTablesAbsent(sqliteDatabase)
            assertEquals(20L, sqliteDatabase.singleLong("PRAGMA user_version"))
            assertEquals(1L, sqliteDatabase.singleLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'chat_history_projection_fts'"))
            sqliteDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
            assertEquals("ok", sqliteDatabase.singleString("PRAGMA integrity_check"))
        } finally {
            freshDatabase.close()
        }

        val reopenedDatabase = Room.databaseBuilder(context, ChatDatabaseV2::class.java, TEST_DATABASE)
            .addCallback(ChatHistoryDatabaseCallback())
            .build()
        try {
            val database = reopenedDatabase
            val sqliteDatabase = database.openHelper.writableDatabase
            assertEquals(SCHEMA_20_TABLES, sqliteDatabase.userTableNames())
            assertLegacySemanticTablesAbsent(sqliteDatabase)
            assertEquals(20L, sqliteDatabase.singleLong("PRAGMA user_version"))
            assertEquals("ok", sqliteDatabase.singleString("PRAGMA integrity_check"))
            runBlocking {
                assertTrue(database.chatRoomDao().getChatRooms().isEmpty())
                assertTrue(database.messageDao().loadMessages(1).isEmpty())
                assertTrue(database.platformDao().getPlatforms().isEmpty())
                assertNull(database.memoryMaintenanceJobDao().getById("missing"))
                assertNull(database.memoryRecoveryDao().getCorpusState("chat_recall_long_term"))
                assertNull(database.memoryTurnBatchDao().getCheckpoint(1))
                assertTrue(database.memoryActivityLogDao().observeLatest(10).first().isEmpty())
                assertNull(database.memoryLongTermConsolidationDao().getById("missing"))
            }
        } finally {
            reopenedDatabase.close()
        }
    }

    @Test
    fun historyFtsTokenizerContract_supportsCjkNgramsAndEnglishWithoutRawMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, ChatDatabaseV2::class.java, TEST_DATABASE)
            .addCallback(ChatHistoryDatabaseCallback())
            .build()
        try {
            val sqliteDatabase = database.openHelper.writableDatabase
            sqliteDatabase.execSQL(
                "INSERT INTO chats_v2 (chat_id, title, enabled_platform, created_at, updated_at) VALUES (7, '旅行计划', '', 1, 1)"
            )
            sqliteDatabase.execSQL(
                """
                INSERT INTO chat_history_projection (
                    turn_key, chat_id, user_message_id, assistant_message_id, assistant_platform_uid,
                    title, user_content, assistant_content, search_terms, content_hash,
                    projection_version, eligibility_state, created_at, updated_at
                ) VALUES (
                    'chat:7:user:1', 7, 1, 2, 'provider', '旅行计划', '北京旅行 hello',
                    '北京旅程 answer', '旅行 旅行计 北京 京旅 北京旅 hello answer', 'hash', 1, 'eligible', 1, 1
                )
                """.trimIndent()
            )

            assertEquals(1L, ftsCount(sqliteDatabase, "北京"))
            assertEquals(1L, ftsCount(sqliteDatabase, "京旅"))
            assertEquals(1L, ftsCount(sqliteDatabase, "hello"))
            assertEquals(1L, ftsCount(sqliteDatabase, "\"北京旅\""))
            assertEquals(0L, ftsCount(sqliteDatabase, "😀"))
            assertEquals("ok", sqliteDatabase.singleString("PRAGMA integrity_check"))
        } finally {
            database.close()
        }
    }

    private fun ftsCount(database: SupportSQLiteDatabase, match: String): Long =
        database.singleLong(
            "SELECT COUNT(*) FROM chat_history_projection_fts WHERE chat_history_projection_fts MATCH '${match.replace("'", "''")}'"
        )

    private fun assertMigratedLegacyActivity(
        database: SupportSQLiteDatabase,
        logId: String,
        expectedBatchId: String,
        expectedCategory: String,
        expectedStatus: String,
        expectedDetail: String,
        expectedOperationCount: Int?,
        expectedCompletedAt: Long?,
        expectedUpdatedAt: Long
    ) {
        database.query(
            """
            SELECT batch_id, category, status, platform_name, model_name, attempt,
                turn_count, operation_count, detail, started_at, completed_at, updated_at,
                job_id, job_type, phase, trigger_reason, platform_uid, model_id,
                input_count, error_code, phase_summary_json, row_version, retry_cycle
            FROM memory_activity_log
            WHERE log_id = ?
            """.trimIndent(),
            arrayOf(logId)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedBatchId, cursor.getString(0))
            assertEquals(expectedCategory, cursor.getString(1))
            assertEquals(expectedStatus, cursor.getString(2))
            assertEquals("Provider 18", cursor.getString(3))
            assertEquals("model-18", cursor.getString(4))
            assertEquals(2, cursor.getInt(5))
            assertEquals(3, cursor.getInt(6))
            if (expectedOperationCount == null) {
                assertTrue(cursor.isNull(7))
            } else {
                assertEquals(expectedOperationCount, cursor.getInt(7))
            }
            assertEquals(expectedDetail, cursor.getString(8))
            assertEquals(300L, cursor.getLong(9))
            if (expectedCompletedAt == null) {
                assertTrue(cursor.isNull(10))
            } else {
                assertEquals(expectedCompletedAt, cursor.getLong(10))
            }
            assertEquals(expectedUpdatedAt, cursor.getLong(11))
            (12..20).forEach { columnIndex -> assertTrue(cursor.isNull(columnIndex)) }
            assertEquals(0L, cursor.getLong(21))
            assertEquals(0, cursor.getInt(22))
        }
    }

    private fun insertSchema18LongTermMigrationRows(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO chats_v2 (chat_id, title, enabled_platform, created_at, updated_at) VALUES (180, 'schema 18 chat', '[]', 280, 281)"
        )
        database.execSQL(
            """
            INSERT INTO messages_v2 (
                message_id, chat_id, thoughts, content, attachments, sticker_refs, revisions,
                active_revision_index, source_metadata, token_usage, linked_message_id,
                platform_type, created_at
            ) VALUES (
                280, 180, '', 'schema 18 message', '[]', '', '[]', -1, '[]', NULL,
                0, 'provider-18', 282
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO platform_v2 (
                platform_id, uid, name, compatible_type, enabled, api_url, token, model,
                temperature, top_p, system_prompt, stream, reasoning, timeout,
                model_refresh_status, model_refresh_error, model_refreshed_at
            ) VALUES (
                180, 'provider-18', 'Provider 18', 'OPENAI', 1, 'https://example.invalid', NULL,
                'model-18', NULL, NULL, NULL, 1, 1, 60, 'success', NULL, 283
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO platform_model_v2 (
                platform_uid, model_id, display_name, description, enabled, is_default, updated_at
            ) VALUES ('provider-18', 'model-18', 'Model 18', '', 1, 1, 284)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO chat_platform_model_v2 (
                chat_id, platform_uid, model, reasoning_mode, updated_at
            ) VALUES (180, 'provider-18', 'model-18', 'medium', 285)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_maintenance_job (
                job_id, type, status, idempotency_key, payload_json, attempts,
                last_error, created_at, started_at, updated_at, next_run_at,
                family, generation, row_version, lease_owner, lease_expires_at,
                retry_cycle, blocked_reason
            ) VALUES (
                'sync-vector-18', 'sync_vector_index', 'pending',
                'sync-vector-key-18', ?, 0, NULL,
                300, NULL, 302, 303, 'index', 18, 4, NULL, NULL, 0, NULL
            ), (
                'daily-18', 'distill_daily_notes', 'succeeded',
                'daily-key-18', '{"sentinel":"daily-18"}', 1, NULL,
                286, 286, 287, NULL, 'semantic', 18, 1, NULL, NULL, 0, NULL
            )
            """.trimIndent(),
            arrayOf<Any>(SCHEMA_18_SYNC_PAYLOAD)
        )
        database.execSQL(
            """
            INSERT INTO memory_mutation_group (
                group_id, generation, semantic_job_id, semantic_batch_id, state,
                idempotency_key, last_error, created_at, updated_at, completed_at,
                expected_receipt_count, row_version
            ) VALUES (
                'group-18', 18, 'daily-18', NULL, 'index_pending',
                'group-key-18', NULL, 300, 305, NULL, 1, 2
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_mutation_receipt (
                receipt_id, group_id, generation, source_path, base_source_hash,
                target_source_hash, staged_target_path, state, idempotency_key,
                target_index_fingerprint, attempts, last_error, created_at, updated_at,
                file_committed_at, indexed_at, row_version
            ) VALUES (
                'receipt-18', 'group-18', 18, 'MEMORY.md', ?, ?,
                '.staging/receipt-18.md', 'index_pending', 'receipt-key-18', ?,
                1, NULL, 300, 305, 305, NULL, 3
            )
            """.trimIndent(),
            arrayOf<Any>(
                SCHEMA_18_BASE_SOURCE_HASH,
                SCHEMA_18_TARGET_SOURCE_HASH,
                SCHEMA_18_TARGET_INDEX_FINGERPRINT
            )
        )
        database.execSQL(
            """
            INSERT INTO memory_corpus_state (
                corpus, source_path, source_hash, generation, target_index_fingerprint,
                index_status, indexed_generation, indexed_source_hash, indexed_fingerprint,
                latest_receipt_id, last_error, row_version, created_at, updated_at
            ) VALUES (
                'chat_recall_long_term', 'MEMORY.md', ?, 18, ?, 'pending',
                NULL, NULL, NULL, 'receipt-18', NULL, 4, 300, 305
            )
            """.trimIndent(),
            arrayOf<Any>(SCHEMA_18_RECALL_PROJECTION_HASH, SCHEMA_18_TARGET_INDEX_FINGERPRINT)
        )
        database.execSQL(
            """
            INSERT INTO memory_distillation_checkpoint (
                checkpoint_id, daily_source_path, daily_source_hash, batch_key, daily_date,
                semantic_job_id, target_source_path, target_base_hash, target_source_hash,
                mutation_group_id, status, created_at, updated_at, processed_at, row_version
            ) VALUES (
                'distillation-18', 'memory/2026-07-18.md', 'daily-hash-18', 'batch-18',
                '2026-07-18', 'daily-18', 'MEMORY.md', ?, ?, 'group-18',
                'completed', 286, 287, 287, 2
            )
            """.trimIndent(),
            arrayOf<Any>(SCHEMA_18_BASE_SOURCE_HASH, SCHEMA_18_TARGET_SOURCE_HASH)
        )
        database.execSQL(
            """
            INSERT INTO memory_chat_checkpoint (
                chat_id, last_processed_user_message_id, last_observed_user_message_id,
                pending_since, last_user_activity_at, idle_due_at, updated_at
            ) VALUES (180, 279, 280, 288, 289, 290, 291)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_pending_turn (
                turn_key, chat_id, user_message_id, payload_json, content_hash,
                completed_at, claimed_job_id, created_at, updated_at
            ) VALUES ('turn-18', 180, 280, '{"sentinel":"turn-18"}', 'turn-hash-18', 292, NULL, 292, 292)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_activity_log (
                log_id, batch_id, category, status, platform_name, model_name, attempt,
                turn_count, operation_count, detail, started_at, completed_at, updated_at
            ) VALUES (
                'activity-18-model-call', 'batch-18-model-call', 'model_call', 'running',
                'Provider 18', 'model-18', 2, 3, NULL, 'model call sentinel', 300, NULL, 306
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_activity_log (
                log_id, batch_id, category, status, platform_name, model_name, attempt,
                turn_count, operation_count, detail, started_at, completed_at, updated_at
            ) VALUES (
                'activity-18-generation', 'batch-18-generation', 'memory_generation', 'succeeded',
                'Provider 18', 'model-18', 2, 3, 2, 'generation sentinel', 300, 307, 307
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_activity_log (
                log_id, batch_id, category, status, platform_name, model_name, attempt,
                turn_count, operation_count, detail, started_at, completed_at, updated_at
            ) VALUES (
                'activity-18-organization', 'batch-18-organization', 'memory_organization', 'succeeded',
                'Provider 18', 'model-18', 2, 3, 4, 'organization sentinel', 300, 308, 308
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO sticker_packs (
                pack_id, display_name, is_builtin, created_at, updated_at
            ) VALUES ('user.schema18', 'Schema 18 stickers', 0, 293, 293)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO sticker_assets (
                asset_key, storage_kind, relative_path, media_kind, mime_type,
                poster_asset_key, duration_ms, loop_count, byte_size, width, height
            ) VALUES (
                ?, 'local_file', 'assets/schema18.png', 'static_raster', 'image/png',
                NULL, NULL, NULL, 18, 4, 4
            )
            """.trimIndent(),
            arrayOf<Any>(SCHEMA_18_STICKER_ASSET_KEY)
        )
        database.execSQL(
            """
            INSERT INTO sticker_items (
                sticker_id, pack_id, asset_key, title, alt_text, tags_json,
                aliases_json, enabled, is_builtin, created_at, updated_at
            ) VALUES (
                'user.schema18.item', 'user.schema18', ?, 'Schema 18 item',
                'Schema 18 sticker', '["schema18"]', '[]', 1, 0, 294, 294
            )
            """.trimIndent(),
            arrayOf<Any>(SCHEMA_18_STICKER_ASSET_KEY)
        )
    }

    private fun insertSchema15Rows(database: SupportSQLiteDatabase) {
        insertSchema16Rows(database)
        database.execSQL(
            """
            INSERT INTO memory_document (
                source_path, title, scope, content_hash, last_modified_at, indexed_at
            ) VALUES ('MEMORY.md', 'Memory', 'long_term', 'legacy-document-hash', 109, 110)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_chunk (
                chunk_id, source_path, chunk_index, heading, text, entry_id, type,
                sensitivity, source, chat_id, created_at, updated_at, indexed_at
            ) VALUES (
                'legacy-chunk-15', 'MEMORY.md', 0, 'Profile', 'legacy derived chunk',
                'entry-15', 'stable_profile', 'normal', 'explicit_user_statement',
                70, 109, 109, 110
            )
            """.trimIndent()
        )
    }

    private fun insertSchema16Rows(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO chats_v2 (chat_id, title, enabled_platform, created_at, updated_at) VALUES (70, 'schema 16 chat', '[]', 100, 101)"
        )
        database.execSQL(
            """
            INSERT INTO messages_v2 (
                message_id, chat_id, thoughts, content, attachments, revisions,
                active_revision_index, source_metadata, token_usage, linked_message_id,
                platform_type, created_at
            ) VALUES (110, 70, '', 'schema 16 message', '[]', '[]', -1, '[]', NULL, 0, NULL, 102)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO platform_v2 (
                platform_id, uid, name, compatible_type, enabled, api_url, token, model,
                temperature, top_p, system_prompt, stream, reasoning, timeout,
                model_refresh_status, model_refresh_error, model_refreshed_at
            ) VALUES (
                30, 'provider-16', 'Provider 16', 'OPENAI', 1, 'https://example.invalid', NULL,
                'model-16', NULL, NULL, NULL, 1, 1, 60, 'success', NULL, 103
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO platform_model_v2 (
                platform_uid, model_id, display_name, description, enabled, is_default, updated_at
            ) VALUES ('provider-16', 'model-16', 'Model 16', '', 1, 1, 104)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO chat_platform_model_v2 (
                chat_id, platform_uid, model, reasoning_mode, updated_at
            ) VALUES (70, 'provider-16', 'model-16', 'medium', 105)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO personal_memory (
                memory_id, summary, details, recall_text, type, scope, domains, entities,
                tags, applicable_modes, avoid_modes, importance, confidence, source,
                sensitivity, status, evidence, created_at, updated_at, last_accessed_at, expires_at
            ) VALUES (
                50, 'schema 16 personal memory', NULL, 'schema 16 recall', 'stable_profile',
                'personal', '[]', '[]', '[]', '[]', '[]', 0.8, 0.9,
                'explicit_user_statement', 'normal', 'active', NULL, 106, 107, NULL, NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO chat_classification (
                chat_id, mode, intent, memory_needs, domains, entities, emotional_tone,
                should_use_memories, should_learn_memories, sensitivity, confidence,
                updated_at, raw_model_json
            ) VALUES (70, 'chat', 'answer', '[]', '[]', '[]', NULL, 1, 1, 'normal', 0.9, 108, NULL)
            """.trimIndent()
        )
        listOf(
            Triple("legacy-pending", "rebuild_memory_index", "pending"),
            Triple("legacy-running", "repair_markdown_metadata", "running"),
            Triple("legacy-retryable", "rebuild_memory_index", "failed_retryable"),
            Triple("legacy-waiting", "repair_markdown_metadata", "waiting_repair"),
            Triple("legacy-blocked", "rebuild_memory_index", "blocked_dependency"),
            Triple("legacy-terminal", "repair_markdown_metadata", "failed_terminal"),
            Triple("legacy-succeeded", "rebuild_memory_index", "succeeded"),
            Triple("legacy-dismissed", "repair_markdown_metadata", "dismissed"),
            Triple("modern-sync", "sync_vector_index", "pending")
        ).forEach { (jobId, type, status) ->
            insertSchema15MaintenanceJob(database, jobId, type, status)
        }

        database.execSQL(
            """
            INSERT INTO memory_mutation_group (
                group_id, generation, semantic_job_id, semantic_batch_id, state,
                idempotency_key, last_error, created_at, updated_at, completed_at,
                expected_receipt_count, row_version
            ) VALUES (
                'group-15', 5, NULL, NULL, 'index_pending', 'group-key-15', NULL,
                111, 112, 112, 1, 2
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_mutation_receipt (
                receipt_id, group_id, generation, source_path, base_source_hash,
                target_source_hash, staged_target_path, state, idempotency_key,
                target_index_fingerprint, attempts, last_error, created_at, updated_at,
                file_committed_at, indexed_at, row_version
            ) VALUES (
                'receipt-15', 'group-15', 5, 'MEMORY.md', 'base-hash-15', 'target-hash-15',
                '.staging/receipt-15.md', 'index_pending', 'receipt-key-15',
                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                1, NULL, 111, 112, 112, NULL, 3
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_corpus_state (
                corpus, source_path, source_hash, generation, target_index_fingerprint,
                index_status, indexed_generation, indexed_source_hash, indexed_fingerprint,
                latest_receipt_id, last_error, row_version, created_at, updated_at
            ) VALUES (
                'chat_recall_long_term', 'MEMORY.md', 'target-hash-15', 5,
                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                'pending', NULL, NULL, NULL, 'receipt-15', NULL, 4, 111, 112
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_distillation_checkpoint (
                checkpoint_id, daily_source_path, daily_source_hash, batch_key, daily_date,
                semantic_job_id, target_source_path, target_base_hash, target_source_hash,
                mutation_group_id, status, created_at, updated_at, processed_at, row_version
            ) VALUES (
                'checkpoint-15', 'memory/2026-07-12.md', 'daily-hash-15', 'batch-15',
                '2026-07-12', 'modern-sync', 'MEMORY.md', 'base-hash-15', 'target-hash-15',
                'group-15', 'completed', 113, 114, 114, 2
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_chat_checkpoint (
                chat_id, last_processed_user_message_id, last_observed_user_message_id,
                pending_since, last_user_activity_at, idle_due_at, updated_at
            ) VALUES (70, 109, 110, 115, 116, 117, 118)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_pending_turn (
                turn_key, chat_id, user_message_id, payload_json, content_hash,
                completed_at, claimed_job_id, created_at, updated_at
            ) VALUES ('turn-15', 70, 110, '{}', 'turn-hash-15', 119, 'modern-sync', 119, 119)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_activity_log (
                log_id, batch_id, category, status, platform_name, model_name, attempt,
                turn_count, operation_count, detail, started_at, completed_at, updated_at
            ) VALUES (
                'activity-15', 'batch-activity-15', 'organization', 'succeeded',
                'Provider 16', 'model-16', 1, 1, 1, 'sentinel', 120, 121, 121
            )
            """.trimIndent()
        )
    }

    private fun insertSchema15MaintenanceJob(
        database: SupportSQLiteDatabase,
        jobId: String,
        type: String,
        status: String
    ) {
        val family = when (type) {
            "repair_markdown_metadata" -> "repair"
            else -> "index"
        }
        database.execSQL(
            """
            INSERT INTO memory_maintenance_job (
                job_id, type, status, idempotency_key, payload_json, attempts,
                last_error, created_at, started_at, updated_at, next_run_at,
                family, generation, row_version, lease_owner, lease_expires_at,
                retry_cycle, blocked_reason
            ) VALUES (?, ?, ?, ?, ?, 2, ?, 200, 210, 220, 230, ?, 5, 7, ?, 1, 3, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                jobId,
                type,
                status,
                "key-$jobId",
                "{\"sentinel\":\"$jobId\"}",
                "before-$jobId",
                family,
                "lease-$jobId",
                "blocked-$jobId"
            )
        )
    }

    private fun insertSchema14Rows(database: SupportSQLiteDatabase) {
        database.execSQL(
            "INSERT INTO chats_v2 (chat_id, title, enabled_platform, created_at, updated_at) VALUES (7, 'kept chat', '', 10, 11)"
        )
        database.execSQL(
            """
            INSERT INTO messages_v2 (
                message_id, chat_id, thoughts, content, attachments, revisions,
                active_revision_index, source_metadata, token_usage, linked_message_id,
                platform_type, created_at
            ) VALUES (11, 7, '', 'kept message', '', '[]', -1, '', NULL, 0, NULL, 12)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO platform_v2 (
                platform_id, uid, name, compatible_type, enabled, api_url, token, model,
                temperature, top_p, system_prompt, stream, reasoning, timeout,
                model_refresh_status, model_refresh_error, model_refreshed_at
            ) VALUES (
                3, 'provider-1', 'Provider', 'openai', 1, 'https://example.invalid', NULL,
                'model-1', NULL, NULL, NULL, 1, 1, 60, 'success', NULL, 19
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO platform_model_v2 (
                platform_uid, model_id, display_name, description, enabled, is_default, updated_at
            ) VALUES ('provider-1', 'model-1', 'Model One', '', 1, 1, 19)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO chat_platform_model_v2 (
                chat_id, platform_uid, model, reasoning_mode, updated_at
            ) VALUES (7, 'provider-1', 'model-1', 'medium', 19)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO personal_memory (
                memory_id, summary, details, recall_text, type, scope, domains, entities,
                tags, applicable_modes, avoid_modes, importance, confidence, source,
                sensitivity, status, evidence, created_at, updated_at, last_accessed_at, expires_at
            ) VALUES (
                5, 'kept personal memory', NULL, 'recall', 'preference', 'global', '', '',
                '', '', '', 0.8, 0.9, 'chat', 'private', 'active', NULL, 10, 11, NULL, NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO chat_classification (
                chat_id, mode, intent, memory_needs, domains, entities, emotional_tone,
                should_use_memories, should_learn_memories, sensitivity, confidence,
                updated_at, raw_model_json
            ) VALUES (7, 'chat', 'answer', '', '', '', NULL, 1, 1, 'private', 0.9, 11, NULL)
            """.trimIndent()
        )
        insertMaintenanceJob(
            database = database,
            jobId = "semantic-job",
            type = "consolidate_turn_batch",
            status = "running",
            attempts = 2
        )
        insertMaintenanceJob(
            database = database,
            jobId = "index-job",
            type = "rebuild_memory_index",
            status = "failed_terminal",
            attempts = 3
        )
        insertMaintenanceJob(
            database = database,
            jobId = "unknown-job",
            type = "legacy_unknown_job",
            status = "pending",
            attempts = 0
        )
        database.execSQL(
            """
            INSERT INTO memory_document (
                source_path, title, scope, content_hash, last_modified_at, indexed_at
            ) VALUES ('MEMORY.md', 'Memory', 'long_term', 'document-hash', 20, 21)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_chunk (
                chunk_id, source_path, chunk_index, heading, text, entry_id, type,
                sensitivity, source, chat_id, created_at, updated_at, indexed_at
            ) VALUES (
                'chunk-1', 'MEMORY.md', 0, 'Preferences', 'kept chunk', 'entry-1',
                'preference', 'private', 'chat', 7, 20, 20, 21
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_chat_checkpoint (
                chat_id, last_processed_user_message_id, last_observed_user_message_id,
                pending_since, last_user_activity_at, idle_due_at, updated_at
            ) VALUES (7, 10, 11, 12, 13, 14, 15)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_pending_turn (
                turn_key, chat_id, user_message_id, payload_json, content_hash,
                completed_at, claimed_job_id, created_at, updated_at
            ) VALUES ('7:11', 7, 11, '{}', 'turn-hash', 16, 'semantic-job', 16, 16)
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO memory_activity_log (
                log_id, batch_id, category, status, platform_name, model_name, attempt,
                turn_count, operation_count, detail, started_at, completed_at, updated_at
            ) VALUES ('log-1', 'batch-1', 'generation', 'succeeded', NULL, NULL, 1, 1, 1, NULL, 17, 18, 18)
            """.trimIndent()
        )
    }

    private fun insertMaintenanceJob(
        database: SupportSQLiteDatabase,
        jobId: String,
        type: String,
        status: String,
        attempts: Int
    ) {
        database.execSQL(
            """
            INSERT INTO memory_maintenance_job (
                job_id, type, status, idempotency_key, payload_json, attempts,
                last_error, created_at, started_at, updated_at, next_run_at
            ) VALUES (?, ?, ?, ?, '{}', ?, NULL, 20, 21, 22, NULL)
            """.trimIndent(),
            arrayOf<Any>(jobId, type, status, "key-$jobId", attempts)
        )
    }

    private fun insertDistillationCheckpoint(
        database: SupportSQLiteDatabase,
        checkpointId: String,
        batchKey: String
    ) {
        database.execSQL(
            """
            INSERT INTO memory_distillation_checkpoint (
                checkpoint_id, daily_source_path, daily_source_hash, batch_key, daily_date,
                semantic_job_id, target_source_path, target_base_hash, target_source_hash,
                mutation_group_id, status, created_at, updated_at, processed_at, row_version
            ) VALUES (
                ?, 'memory/2026-07-11.md', 'daily-hash', ?, '2026-07-11',
                ?, 'MEMORY.md', 'base-hash', 'target-hash', NULL, 'pending', 30, 30, NULL, 0
            )
            """.trimIndent(),
            arrayOf<Any>(checkpointId, batchKey, "job-$checkpointId")
        )
    }

    private fun assertSchema15JobUnchanged(
        database: SupportSQLiteDatabase,
        jobId: String,
        expectedStatus: String
    ) {
        database.query(
            """
            SELECT status, last_error, blocked_reason, next_run_at, started_at,
                lease_owner, lease_expires_at, row_version, attempts, payload_json,
                updated_at, retry_cycle
            FROM memory_maintenance_job
            WHERE job_id = ?
            """.trimIndent(),
            arrayOf(jobId)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedStatus, cursor.getString(0))
            assertEquals("before-$jobId", cursor.getString(1))
            assertEquals("blocked-$jobId", cursor.getString(2))
            assertEquals(230L, cursor.getLong(3))
            assertEquals(210L, cursor.getLong(4))
            assertEquals("lease-$jobId", cursor.getString(5))
            assertEquals(1L, cursor.getLong(6))
            assertEquals(7L, cursor.getLong(7))
            assertEquals(2, cursor.getInt(8))
            assertEquals("{\"sentinel\":\"$jobId\"}", cursor.getString(9))
            assertEquals(220L, cursor.getLong(10))
            assertEquals(3, cursor.getInt(11))
        }
    }

    private fun assertLegacySemanticTablesAbsent(database: SupportSQLiteDatabase) {
        LEGACY_SEMANTIC_TABLES.forEach { tableName ->
            assertEquals(
                "$tableName should be absent",
                0L,
                database.singleLong(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$tableName'"
                )
            )
        }
    }

    private fun SupportSQLiteDatabase.userTableNames(): Set<String> = query(
        """
        SELECT name
        FROM sqlite_master
        WHERE type = 'table'
            AND name NOT LIKE 'sqlite_%'
             AND name NOT IN ('android_metadata', 'room_master_table')
             AND name NOT LIKE 'chat_history_projection_fts%'
        ORDER BY name
        """.trimIndent()
    ).use { cursor ->
        buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun SupportSQLiteDatabase.snapshotRows(tableName: String): List<List<String>> = query(
        "SELECT * FROM `$tableName` ORDER BY rowid"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    (0 until cursor.columnCount).map { columnIndex ->
                        when (cursor.getType(columnIndex)) {
                            Cursor.FIELD_TYPE_NULL -> "null"
                            Cursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(columnIndex)}"
                            Cursor.FIELD_TYPE_FLOAT -> "float:${cursor.getDouble(columnIndex)}"
                            Cursor.FIELD_TYPE_STRING -> "string:${cursor.getString(columnIndex)}"
                            Cursor.FIELD_TYPE_BLOB -> "blob:${cursor.getBlob(columnIndex).joinToString(",")}"
                            else -> error("Unsupported SQLite field type")
                        }
                    }
                )
            }
        }
    }

    private fun SupportSQLiteDatabase.singleLong(sql: String): Long = query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "No result for query: $sql" }
        cursor.getLong(0)
    }

    private fun SupportSQLiteDatabase.singleString(sql: String): String = query(sql).use { cursor ->
        check(cursor.moveToFirst()) { "No result for query: $sql" }
        cursor.getString(0)
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        private const val TEST_DATABASE = "chat-v2-migration-test"
        private const val SCHEMA_18_BASE_SOURCE_HASH =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val SCHEMA_18_TARGET_SOURCE_HASH =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val SCHEMA_18_RECALL_PROJECTION_HASH =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        private const val SCHEMA_18_TARGET_INDEX_FINGERPRINT =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        private const val SCHEMA_18_STICKER_ASSET_KEY =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        private const val SCHEMA_18_SYNC_PAYLOAD =
            "{\"mutationGroupId\":\"group-18\",\"receiptId\":\"receipt-18\",\"generation\":18,\"sourcePath\":\"MEMORY.md\",\"sourceHash\":\"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd\",\"targetIndexFingerprint\":\"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee\"}"
        private const val SCHEMA_19_LONG_TERM_BASE_HASH =
            "1111111111111111111111111111111111111111111111111111111111111111"
        private const val SCHEMA_19_LONG_TERM_SNAPSHOT_HASH =
            "2222222222222222222222222222222222222222222222222222222222222222"
        private const val SCHEMA_19_LONG_TERM_PAYLOAD =
            "{\"checkpointId\":\"checkpoint-19\",\"baseSourceHash\":\"1111111111111111111111111111111111111111111111111111111111111111\",\"orderedSnapshotHash\":\"2222222222222222222222222222222222222222222222222222222222222222\"}"
        private const val SCHEMA_19_DUPLICATE_BASE_HASH =
            "3333333333333333333333333333333333333333333333333333333333333333"
        private const val SCHEMA_19_DUPLICATE_RECALL_HASH =
            "4444444444444444444444444444444444444444444444444444444444444444"
        private const val SCHEMA_19_DUPLICATE_SNAPSHOT_HASH =
            "5555555555555555555555555555555555555555555555555555555555555555"
        private val FIXED_CLOCK = Clock.fixed(Instant.ofEpochSecond(1_000L), ZoneOffset.UTC)
        private val LEGACY_ACTIVE_JOB_IDS = setOf(
            "legacy-pending",
            "legacy-running",
            "legacy-retryable",
            "legacy-waiting",
            "legacy-blocked",
            "legacy-terminal"
        )
        private val SCHEMA_17_TABLES = setOf(
            "chats_v2",
            "messages_v2",
            "platform_v2",
            "platform_model_v2",
            "chat_platform_model_v2",
            "memory_maintenance_job",
            "memory_mutation_group",
            "memory_mutation_receipt",
            "memory_corpus_state",
            "memory_distillation_checkpoint",
            "memory_chat_checkpoint",
            "memory_pending_turn",
            "memory_activity_log"
        )
        private val SCHEMA_18_TABLES = SCHEMA_17_TABLES + setOf(
            "sticker_packs",
            "sticker_assets",
            "sticker_items"
        )
        private val SCHEMA_19_TABLES = SCHEMA_18_TABLES + "memory_long_term_consolidation_checkpoint"
        private val SCHEMA_20_TABLES = SCHEMA_19_TABLES + setOf(
            "chat_history_projection",
            "chat_history_index_queue",
            "chat_history_backfill_checkpoint",
            "chat_history_index_state",
            "chat_history_embedding_cache"
        )
        private val UNCHANGED_SCHEMA_18_TO_19_TABLES = SCHEMA_18_TABLES - setOf(
            "memory_maintenance_job",
            "memory_mutation_receipt",
            "memory_activity_log"
        )
        private val LEGACY_SEMANTIC_TABLES = setOf("personal_memory", "chat_classification")
        private val SCHEMA_16_TABLES = SCHEMA_17_TABLES + LEGACY_SEMANTIC_TABLES
        private val UNCHANGED_SCHEMA_16_TABLES = SCHEMA_16_TABLES - "memory_maintenance_job"
    }

    private class RecordingWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
        val families = mutableListOf<String>()

        override fun enqueueWork(family: String, delaySeconds: Long) {
            families += family
        }
    }
}
