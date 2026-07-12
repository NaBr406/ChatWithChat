package dev.chungjungsoo.gptmobile.data.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

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
    fun migration14To15_preservesPopulatedBusinessAndMemoryState() {
        migrationHelper.createDatabase(TEST_DATABASE, 14).apply {
            insertSchema14Rows(this)
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            15,
            true,
            ChatDatabaseV2Migrations.MIGRATION_14_15
        ).use { database ->
            assertEquals(1L, database.singleLong("SELECT COUNT(*) FROM chats_v2"))
            assertEquals("kept chat", database.singleString("SELECT title FROM chats_v2 WHERE chat_id = 7"))
            assertEquals("kept message", database.singleString("SELECT content FROM messages_v2 WHERE message_id = 11"))
            assertEquals("provider-1", database.singleString("SELECT uid FROM platform_v2 WHERE platform_id = 3"))
            assertEquals("model-1", database.singleString("SELECT model_id FROM platform_model_v2 WHERE platform_uid = 'provider-1'"))
            assertEquals("medium", database.singleString("SELECT reasoning_mode FROM chat_platform_model_v2 WHERE chat_id = 7"))
            assertEquals("kept personal memory", database.singleString("SELECT summary FROM personal_memory WHERE memory_id = 5"))
            assertEquals("chat", database.singleString("SELECT mode FROM chat_classification WHERE chat_id = 7"))
            assertEquals("document-hash", database.singleString("SELECT content_hash FROM memory_document WHERE source_path = 'MEMORY.md'"))
            assertEquals("kept chunk", database.singleString("SELECT text FROM memory_chunk WHERE chunk_id = 'chunk-1'"))
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
                "failed_terminal",
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
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDatabase = Room.databaseBuilder(context, ChatDatabaseV2::class.java, TEST_DATABASE)
            .addMigrations(ChatDatabaseV2Migrations.MIGRATION_14_15)
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
            }
        } finally {
            roomDatabase.close()
        }
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
    }
}
