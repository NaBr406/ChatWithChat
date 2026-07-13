package dev.chungjungsoo.gptmobile.data.database

import android.database.Cursor
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceJobFamily
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceRepairer
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceWorkEnqueuer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
    fun migration14To15To16_preservesBusinessDataAndCreatesRecoveryState() {
        migrationHelper.createDatabase(TEST_DATABASE, 14).apply {
            insertSchema14Rows(this)
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            16,
            true,
            ChatDatabaseV2Migrations.MIGRATION_14_15,
            ChatDatabaseV2Migrations.MIGRATION_15_16
        ).use { database ->
            assertEquals(1L, database.singleLong("SELECT COUNT(*) FROM chats_v2"))
            assertEquals("kept chat", database.singleString("SELECT title FROM chats_v2 WHERE chat_id = 7"))
            assertEquals("kept message", database.singleString("SELECT content FROM messages_v2 WHERE message_id = 11"))
            assertEquals("provider-1", database.singleString("SELECT uid FROM platform_v2 WHERE platform_id = 3"))
            assertEquals("model-1", database.singleString("SELECT model_id FROM platform_model_v2 WHERE platform_uid = 'provider-1'"))
            assertEquals("medium", database.singleString("SELECT reasoning_mode FROM chat_platform_model_v2 WHERE chat_id = 7"))
            assertEquals("kept personal memory", database.singleString("SELECT summary FROM personal_memory WHERE memory_id = 5"))
            assertEquals("chat", database.singleString("SELECT mode FROM chat_classification WHERE chat_id = 7"))
            assertEquals(0L, database.singleLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'memory_document'"))
            assertEquals(0L, database.singleLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'memory_chunk'"))
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
            assertEquals(16L, database.singleLong("PRAGMA user_version"))
            assertEquals(RETAINED_TABLES, database.userTableNames())
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDatabase = Room.databaseBuilder(context, ChatDatabaseV2::class.java, TEST_DATABASE)
            .addMigrations(
                ChatDatabaseV2Migrations.MIGRATION_14_15,
                ChatDatabaseV2Migrations.MIGRATION_15_16
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
            expectedCounts = RETAINED_TABLES.associateWith { tableName ->
                singleLong("SELECT COUNT(*) FROM `$tableName`")
            }
            expectedRows = UNCHANGED_RETAINED_TABLES.associateWith { tableName -> snapshotRows(tableName) }
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
            assertEquals(RETAINED_TABLES, database.userTableNames())
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
            .addMigrations(ChatDatabaseV2Migrations.MIGRATION_15_16)
            .build()
        try {
            runBlocking {
                assertEquals("schema 16 chat", roomDatabase.chatRoomDao().getChatRooms().single().title)
                assertEquals("schema 16 message", roomDatabase.messageDao().loadMessages(70).single().content)
                assertEquals("provider-16", roomDatabase.platformDao().getPlatform(30)?.uid)
                assertEquals("model-16", roomDatabase.platformModelDao().getModel("provider-16", "model-16")?.modelId)
                assertEquals("medium", roomDatabase.chatPlatformModelDao().getByChatId(70).single().reasoningMode)
                assertEquals("schema 16 personal memory", roomDatabase.personalMemoryDao().getAll().single().summary)
                assertEquals("chat", roomDatabase.chatClassificationDao().getByChatId(70)?.mode)
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
                assertEquals("target-hash-15", corpusState?.sourceHash)
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
            assertEquals(RETAINED_TABLES, roomDatabase.openHelper.writableDatabase.userTableNames())
        } finally {
            roomDatabase.close()
        }
    }

    @Test
    fun freshSchema16_opensAndReopensWithExactlyRetainedTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val freshDatabase = Room.databaseBuilder(context, ChatDatabaseV2::class.java, TEST_DATABASE).build()
        try {
            val database = freshDatabase
            assertEquals(RETAINED_TABLES, database.openHelper.writableDatabase.userTableNames())
            assertEquals(16L, database.openHelper.writableDatabase.singleLong("PRAGMA user_version"))
            assertEquals("ok", database.openHelper.writableDatabase.singleString("PRAGMA integrity_check"))
        } finally {
            freshDatabase.close()
        }

        val reopenedDatabase = Room.databaseBuilder(context, ChatDatabaseV2::class.java, TEST_DATABASE).build()
        try {
            val database = reopenedDatabase
            assertEquals(RETAINED_TABLES, database.openHelper.writableDatabase.userTableNames())
            runBlocking {
                assertTrue(database.chatRoomDao().getChatRooms().isEmpty())
                assertTrue(database.messageDao().loadMessages(1).isEmpty())
                assertTrue(database.platformDao().getPlatforms().isEmpty())
                assertTrue(database.personalMemoryDao().getAll().isEmpty())
                assertNull(database.memoryMaintenanceJobDao().getById("missing"))
                assertNull(database.memoryRecoveryDao().getCorpusState("chat_recall_long_term"))
                assertNull(database.memoryTurnBatchDao().getCheckpoint(1))
                assertTrue(database.memoryActivityLogDao().observeLatest(10).first().isEmpty())
            }
        } finally {
            reopenedDatabase.close()
        }
    }

    private fun insertSchema15Rows(database: SupportSQLiteDatabase) {
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

    private fun SupportSQLiteDatabase.userTableNames(): Set<String> = query(
        """
        SELECT name
        FROM sqlite_master
        WHERE type = 'table'
            AND name NOT LIKE 'sqlite_%'
            AND name NOT IN ('android_metadata', 'room_master_table')
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

    companion object {
        private const val TEST_DATABASE = "chat-v2-migration-test"
        private val FIXED_CLOCK = Clock.fixed(Instant.ofEpochSecond(1_000L), ZoneOffset.UTC)
        private val LEGACY_ACTIVE_JOB_IDS = setOf(
            "legacy-pending",
            "legacy-running",
            "legacy-retryable",
            "legacy-waiting",
            "legacy-blocked",
            "legacy-terminal"
        )
        private val RETAINED_TABLES = setOf(
            "chats_v2",
            "messages_v2",
            "platform_v2",
            "platform_model_v2",
            "chat_platform_model_v2",
            "personal_memory",
            "chat_classification",
            "memory_maintenance_job",
            "memory_mutation_group",
            "memory_mutation_receipt",
            "memory_corpus_state",
            "memory_distillation_checkpoint",
            "memory_chat_checkpoint",
            "memory_pending_turn",
            "memory_activity_log"
        )
        private val UNCHANGED_RETAINED_TABLES = RETAINED_TABLES - "memory_maintenance_job"
    }

    private class RecordingWorkEnqueuer : MemoryMaintenanceWorkEnqueuer {
        val families = mutableListOf<String>()

        override fun enqueueWork(family: String, delaySeconds: Long) {
            families += family
        }
    }
}
