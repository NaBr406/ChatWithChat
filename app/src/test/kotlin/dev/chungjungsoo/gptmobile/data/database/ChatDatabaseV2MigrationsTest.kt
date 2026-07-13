package dev.chungjungsoo.gptmobile.data.database

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevisionListConverter
import dev.chungjungsoo.gptmobile.data.database.entity.ChatAttachmentListConverter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDatabaseV2MigrationsTest {
    @Test
    fun `legacy file list migrates to attachment json`() {
        val json = ChatDatabaseV2Migrations.legacyFilesToAttachmentsJson("/tmp/first.png,/tmp/second.webp")

        val attachments = ChatAttachmentListConverter().fromString(json)

        assertEquals(2, attachments.size)
        assertEquals("/tmp/first.png", attachments[0].localFilePath)
        assertEquals("/tmp/first.png", attachments[0].preparedFilePath)
        assertEquals("first.png", attachments[0].resolvedDisplayName)
        assertTrue(attachments[0].providerRefs.isEmpty())
        assertEquals("/tmp/second.webp", attachments[1].localFilePath)
    }

    @Test
    fun `legacy revision list migrates to structured revisions`() {
        val json = ChatDatabaseV2Migrations.legacyRevisionsToStructuredJson(
            revisionsValue = "first revision,second revision",
            createdAt = 1234L
        )

        val revisions = AssistantRevisionListConverter().fromString(json)

        assertEquals(2, revisions.size)
        assertEquals("first revision", revisions[0].content)
        assertEquals("", revisions[0].thoughts)
        assertEquals(1234L, revisions[0].createdAt)
        assertEquals("second revision", revisions[1].content)
        assertEquals(1234L, revisions[1].createdAt)
    }

    @Test
    fun `blank legacy revision list migrates to empty structured revisions`() {
        val json = ChatDatabaseV2Migrations.legacyRevisionsToStructuredJson(
            revisionsValue = "",
            createdAt = 1234L
        )

        val revisions = AssistantRevisionListConverter().fromString(json)

        assertTrue(revisions.isEmpty())
    }

    @Test
    fun `legacy revision migration filters blank segments and applies timestamp`() {
        val json = ChatDatabaseV2Migrations.legacyRevisionsToStructuredJson(
            revisionsValue = "a, ,b",
            createdAt = 1234L
        )

        val revisions = AssistantRevisionListConverter().fromString(json)

        assertEquals(2, revisions.size)
        assertEquals("a", revisions[0].content)
        assertEquals(1234L, revisions[0].createdAt)
        assertEquals("b", revisions[1].content)
        assertEquals(1234L, revisions[1].createdAt)
    }

    @Test
    fun `corrupt assistant revision json decodes to empty list`() {
        val revisions = AssistantRevisionListConverter().fromString("[")

        assertTrue(revisions.isEmpty())
    }

    @Test
    fun `migration 4 to 5 creates memory tables`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_4_5.migrate(db)

        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `personal_memory`") })
        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `chat_classification`") })
    }

    @Test
    fun `migration 5 to 6 preserves memory tables for development v5 databases`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_5_6.migrate(db)

        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `personal_memory`") })
        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `chat_classification`") })
    }

    @Test
    fun `migration 7 to 8 rebuilds chat model table with reasoning modes`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_7_8.migrate(db)

        val migrationSql = executedSql.joinToString(separator = "\n")
        assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS `chat_platform_model_v2_new`"))
        assertTrue(migrationSql.contains("WHEN platform.`reasoning` = 1 THEN 'medium'"))
        assertTrue(migrationSql.contains("ELSE 'off'"))
        assertTrue(executedSql.any { it == "DROP TABLE `chat_platform_model_v2`" })
        assertTrue(executedSql.any { it == "ALTER TABLE `chat_platform_model_v2_new` RENAME TO `chat_platform_model_v2`" })
    }

    @Test
    fun `migration 8 to 9 adds source metadata to messages`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_8_9.migrate(db)

        assertTrue(executedSql.any { it == "ALTER TABLE messages_v2 ADD COLUMN source_metadata TEXT NOT NULL DEFAULT ''" })
    }

    @Test
    fun `migration 9 to 10 adds token usage to messages`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_9_10.migrate(db)

        assertTrue(executedSql.any { it == "ALTER TABLE messages_v2 ADD COLUMN token_usage TEXT" })
    }

    @Test
    fun `migration 10 to 11 creates memory index tables`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_10_11.migrate(db)

        val migrationSql = executedSql.joinToString(separator = "\n")
        assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS `memory_document`"))
        assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS `memory_chunk`"))
        assertTrue(migrationSql.contains("FOREIGN KEY(`source_path`) REFERENCES `memory_document`(`source_path`)"))
        assertTrue(executedSql.any { it == "CREATE INDEX IF NOT EXISTS `index_memory_document_scope` ON `memory_document` (`scope`)" })
        assertTrue(executedSql.any { it == "CREATE INDEX IF NOT EXISTS `index_memory_chunk_sensitivity` ON `memory_chunk` (`sensitivity`)" })
    }

    @Test
    fun `migration 11 to 12 creates memory maintenance job table`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_11_12.migrate(db)

        val migrationSql = executedSql.joinToString(separator = "\n")
        assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS `memory_maintenance_job`"))
        assertTrue(migrationSql.contains("`idempotency_key` TEXT NOT NULL"))
        assertTrue(executedSql.any { it == "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_maintenance_job_idempotency_key` ON `memory_maintenance_job` (`idempotency_key`)" })
        assertTrue(executedSql.any { it == "CREATE INDEX IF NOT EXISTS `index_memory_maintenance_job_status` ON `memory_maintenance_job` (`status`)" })
        assertTrue(executedSql.any { it == "CREATE INDEX IF NOT EXISTS `index_memory_maintenance_job_next_run_at` ON `memory_maintenance_job` (`next_run_at`)" })
    }

    @Test
    fun `migration 12 to 13 creates pending turn tables for an empty database`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_12_13.migrate(db)

        val migrationSql = executedSql.joinToString(separator = "\n")
        assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS `memory_chat_checkpoint`"))
        assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS `memory_pending_turn`"))
        assertTrue(migrationSql.contains("FOREIGN KEY(`chat_id`) REFERENCES `chats_v2`(`chat_id`) ON UPDATE NO ACTION ON DELETE CASCADE"))
        assertTrue(executedSql.any { it == "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_pending_turn_chat_id_user_message_id` ON `memory_pending_turn` (`chat_id`, `user_message_id`)" })
        assertTrue(executedSql.any { it == "CREATE INDEX IF NOT EXISTS `index_memory_chat_checkpoint_idle_due_at` ON `memory_chat_checkpoint` (`idle_due_at`)" })
    }

    @Test
    fun `migration 12 to 13 preserves populated database tables and rows`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_12_13.migrate(db)

        assertTrue(executedSql.none { sql ->
            val normalized = sql.trimStart().uppercase()
            normalized.startsWith("DROP ") ||
                normalized.startsWith("DELETE ") ||
                normalized.startsWith("UPDATE ") ||
                normalized.startsWith("INSERT ") ||
                normalized.startsWith("ALTER ")
        })
        assertTrue(executedSql.all { it.contains("IF NOT EXISTS") })
    }

    @Test
    fun `migration 13 to 14 creates persistent memory activity log`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_13_14.migrate(db)

        val migrationSql = executedSql.joinToString(separator = "\n")
        assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS `memory_activity_log`"))
        assertTrue(migrationSql.contains("`category` TEXT NOT NULL"))
        assertTrue(migrationSql.contains("`completed_at` INTEGER"))
        assertTrue(executedSql.any { it == "CREATE INDEX IF NOT EXISTS `index_memory_activity_log_started_at` ON `memory_activity_log` (`started_at`)" })
        assertTrue(executedSql.all { it.contains("IF NOT EXISTS") })
    }

    @Test
    fun `migration 14 to 15 adds recovery state without dropping schema 14 tables`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_14_15.migrate(db)

        val migrationSql = executedSql.joinToString(separator = "\n")
        assertTrue(migrationSql.contains("ADD COLUMN `family` TEXT NOT NULL DEFAULT 'semantic'"))
        assertTrue(migrationSql.contains("ADD COLUMN `generation` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(migrationSql.contains("ADD COLUMN `row_version` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(migrationSql.contains("ADD COLUMN `lease_owner` TEXT"))
        assertTrue(migrationSql.contains("ADD COLUMN `lease_expires_at` INTEGER"))
        assertTrue(migrationSql.contains("ADD COLUMN `retry_cycle` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(migrationSql.contains("ADD COLUMN `blocked_reason` TEXT"))
        assertTrue(migrationSql.contains("SET `family` = 'index' WHERE `type` = 'rebuild_memory_index'"))
        assertTrue(migrationSql.contains("SET `family` = 'repair' WHERE `type` = 'repair_markdown_metadata'"))
        assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS `memory_mutation_group`"))
        assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS `memory_mutation_receipt`"))
        assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS `memory_corpus_state`"))
        assertTrue(migrationSql.contains("CREATE TABLE IF NOT EXISTS `memory_distillation_checkpoint`"))
        assertTrue(migrationSql.contains("`batch_key` TEXT NOT NULL"))
        assertTrue(migrationSql.contains("(`daily_source_path`, `daily_source_hash`, `batch_key`)"))
        assertTrue(migrationSql.contains("`expected_receipt_count` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(migrationSql.contains("`row_version` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(migrationSql.contains("FOREIGN KEY(`group_id`) REFERENCES `memory_mutation_group`(`group_id`)"))
        assertTrue(executedSql.none { it.trimStart().startsWith("DROP ", ignoreCase = true) })
        assertTrue(executedSql.none { it.contains("SET `status` =", ignoreCase = true) })
    }

    @Test
    fun `migration 15 to 16 dismisses legacy index jobs before dropping index tables`() {
        val executedSql = mutableListOf<String>()
        val db = recordingDatabase(executedSql)

        ChatDatabaseV2Migrations.MIGRATION_15_16.migrate(db)

        assertEquals(3, executedSql.size)
        assertTrue(executedSql[0].trimStart().startsWith("UPDATE `memory_maintenance_job`"))
        assertEquals("DROP TABLE IF EXISTS `memory_chunk`", executedSql[1])
        assertEquals("DROP TABLE IF EXISTS `memory_document`", executedSql[2])

        val updateSql = executedSql[0]
        assertTrue(updateSql.contains("SET `status` = 'dismissed'"))
        assertTrue(updateSql.contains("`last_error` = 'schema16_legacy_room_index_removed'"))
        assertTrue(updateSql.contains("`blocked_reason` = NULL"))
        assertTrue(updateSql.contains("`next_run_at` = NULL"))
        assertTrue(updateSql.contains("`started_at` = NULL"))
        assertTrue(updateSql.contains("`lease_owner` = NULL"))
        assertTrue(updateSql.contains("`lease_expires_at` = NULL"))
        assertTrue(updateSql.contains("`row_version` = `row_version` + 1"))
        assertTrue(updateSql.contains("WHERE `type` IN ('rebuild_memory_index', 'repair_markdown_metadata')"))
        assertTrue(updateSql.contains("AND `status` NOT IN ('succeeded', 'dismissed')"))
    }

    private fun recordingDatabase(executedSql: MutableList<String>): SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
        InvocationHandler { _, method, args ->
            if (method.name == "execSQL" && args?.firstOrNull() is String) {
                executedSql += args.first() as String
            }
            null
        }
    ) as SupportSQLiteDatabase
}
