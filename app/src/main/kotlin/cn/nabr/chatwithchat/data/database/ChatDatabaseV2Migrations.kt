package cn.nabr.chatwithchat.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.nabr.chatwithchat.data.database.entity.ACTIVE_REVISION_LATEST
import cn.nabr.chatwithchat.data.database.entity.AssistantRevision
import cn.nabr.chatwithchat.data.database.entity.AssistantRevisionListConverter
import cn.nabr.chatwithchat.data.database.entity.ChatAttachmentListConverter
import cn.nabr.chatwithchat.data.model.ChatAttachment
import cn.nabr.chatwithchat.data.model.ReasoningMode
import java.io.File

object ChatDatabaseV2Migrations {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `chat_platform_model_v2` (
                    `chat_id` INTEGER NOT NULL,
                    `platform_uid` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`chat_id`, `platform_uid`),
                    FOREIGN KEY(`chat_id`) REFERENCES `chats_v2`(`chat_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )

            val platformModelMap = mutableMapOf<String, String>()
            db.query("SELECT uid, model FROM platform_v2").use { platformCursor ->
                val uidIndex = platformCursor.getColumnIndexOrThrow("uid")
                val modelIndex = platformCursor.getColumnIndexOrThrow("model")
                while (platformCursor.moveToNext()) {
                    val uid = platformCursor.getString(uidIndex)
                    val model = platformCursor.getString(modelIndex) ?: ""
                    platformModelMap[uid] = model
                }
            }

            val currentTimestamp = System.currentTimeMillis() / 1000
            db.query("SELECT chat_id, enabled_platform FROM chats_v2").use { chatCursor ->
                val chatIdIndex = chatCursor.getColumnIndexOrThrow("chat_id")
                val enabledPlatformIndex = chatCursor.getColumnIndexOrThrow("enabled_platform")
                while (chatCursor.moveToNext()) {
                    val chatId = chatCursor.getInt(chatIdIndex)
                    val enabledPlatform = chatCursor.getString(enabledPlatformIndex) ?: ""
                    if (enabledPlatform.isBlank()) continue

                    enabledPlatform
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .forEach { platformUid ->
                            val model = platformModelMap[platformUid] ?: ""
                            db.execSQL(
                                "INSERT OR REPLACE INTO chat_platform_model_v2 (chat_id, platform_uid, model, updated_at) VALUES (?, ?, ?, ?)",
                                arrayOf<Any>(chatId, platformUid, model, currentTimestamp)
                            )
                        }
                }
            }
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `messages_v2_new` (
                    `message_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `chat_id` INTEGER NOT NULL,
                    `thoughts` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `attachments` TEXT NOT NULL,
                    `revisions` TEXT NOT NULL,
                    `linked_message_id` INTEGER NOT NULL,
                    `platform_type` TEXT,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`chat_id`) REFERENCES `chats_v2`(`chat_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                INSERT INTO `messages_v2_new` (
                    `message_id`,
                    `chat_id`,
                    `thoughts`,
                    `content`,
                    `attachments`,
                    `revisions`,
                    `linked_message_id`,
                    `platform_type`,
                    `created_at`
                )
                SELECT
                    `message_id`,
                    `chat_id`,
                    `thoughts`,
                    `content`,
                    '' as `attachments`,
                    `revisions`,
                    `linked_message_id`,
                    `platform_type`,
                    `created_at`
                FROM `messages_v2`
                """.trimIndent()
            )

            db.query("SELECT message_id, files FROM messages_v2").use { messageCursor ->
                val messageIdIndex = messageCursor.getColumnIndexOrThrow("message_id")
                val filesIndex = messageCursor.getColumnIndexOrThrow("files")
                while (messageCursor.moveToNext()) {
                    val messageId = messageCursor.getInt(messageIdIndex)
                    val filesValue = messageCursor.getString(filesIndex).orEmpty()
                    db.execSQL(
                        "UPDATE messages_v2_new SET attachments = ? WHERE message_id = ?",
                        arrayOf<Any>(legacyFilesToAttachmentsJson(filesValue), messageId)
                    )
                }
            }

            db.execSQL("DROP TABLE `messages_v2`")
            db.execSQL("ALTER TABLE `messages_v2_new` RENAME TO `messages_v2`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_v2_chat_id` ON `messages_v2` (`chat_id`)")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `messages_v2_new` (
                    `message_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `chat_id` INTEGER NOT NULL,
                    `thoughts` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `attachments` TEXT NOT NULL,
                    `revisions` TEXT NOT NULL,
                    `active_revision_index` INTEGER NOT NULL,
                    `linked_message_id` INTEGER NOT NULL,
                    `platform_type` TEXT,
                    `created_at` INTEGER NOT NULL,
                    FOREIGN KEY(`chat_id`) REFERENCES `chats_v2`(`chat_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )

            db.query(
                """
                SELECT
                    `message_id`,
                    `chat_id`,
                    `thoughts`,
                    `content`,
                    `attachments`,
                    `revisions`,
                    `linked_message_id`,
                    `platform_type`,
                    `created_at`
                FROM `messages_v2`
                """.trimIndent()
            ).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("message_id")
                val chatIdIndex = cursor.getColumnIndexOrThrow("chat_id")
                val thoughtsIndex = cursor.getColumnIndexOrThrow("thoughts")
                val contentIndex = cursor.getColumnIndexOrThrow("content")
                val attachmentsIndex = cursor.getColumnIndexOrThrow("attachments")
                val revisionsIndex = cursor.getColumnIndexOrThrow("revisions")
                val linkedMessageIdIndex = cursor.getColumnIndexOrThrow("linked_message_id")
                val platformTypeIndex = cursor.getColumnIndexOrThrow("platform_type")
                val createdAtIndex = cursor.getColumnIndexOrThrow("created_at")

                while (cursor.moveToNext()) {
                    db.execSQL(
                        """
                        INSERT INTO `messages_v2_new` (
                            `message_id`,
                            `chat_id`,
                            `thoughts`,
                            `content`,
                            `attachments`,
                            `revisions`,
                            `active_revision_index`,
                            `linked_message_id`,
                            `platform_type`,
                            `created_at`
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            cursor.getInt(idIndex),
                            cursor.getInt(chatIdIndex),
                            cursor.getString(thoughtsIndex) ?: "",
                            cursor.getString(contentIndex) ?: "",
                            cursor.getString(attachmentsIndex) ?: "",
                            legacyRevisionsToStructuredJson(
                                revisionsValue = cursor.getString(revisionsIndex).orEmpty(),
                                createdAt = cursor.getLong(createdAtIndex)
                            ),
                            ACTIVE_REVISION_LATEST,
                            cursor.getInt(linkedMessageIdIndex),
                            cursor.getString(platformTypeIndex),
                            cursor.getLong(createdAtIndex)
                        )
                    )
                }
            }

            db.execSQL("DROP TABLE `messages_v2`")
            db.execSQL("ALTER TABLE `messages_v2_new` RENAME TO `messages_v2`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_v2_chat_id` ON `messages_v2` (`chat_id`)")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMemoryTables(db)
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMemoryTables(db)
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMemoryTables(db)
            ensurePlatformModelTables(db)
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `chat_platform_model_v2_new` (
                    `chat_id` INTEGER NOT NULL,
                    `platform_uid` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `reasoning_mode` TEXT NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`chat_id`, `platform_uid`),
                    FOREIGN KEY(`chat_id`) REFERENCES `chats_v2`(`chat_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO `chat_platform_model_v2_new` (
                    `chat_id`,
                    `platform_uid`,
                    `model`,
                    `reasoning_mode`,
                    `updated_at`
                )
                SELECT
                    chat_model.`chat_id`,
                    chat_model.`platform_uid`,
                    chat_model.`model`,
                    CASE
                        WHEN platform.`reasoning` = 1 THEN '${ReasoningMode.MEDIUM.storageValue}'
                        ELSE '${ReasoningMode.OFF.storageValue}'
                    END,
                    chat_model.`updated_at`
                FROM `chat_platform_model_v2` AS chat_model
                LEFT JOIN `platform_v2` AS platform ON platform.`uid` = chat_model.`platform_uid`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `chat_platform_model_v2`")
            db.execSQL("ALTER TABLE `chat_platform_model_v2_new` RENAME TO `chat_platform_model_v2`")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages_v2 ADD COLUMN source_metadata TEXT NOT NULL DEFAULT ''")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages_v2 ADD COLUMN token_usage TEXT")
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMemoryIndexTables(db)
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMemoryMaintenanceJobTable(db)
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMemoryTurnBatchTables(db)
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ensureMemoryActivityLogTable(db)
        }
    }

    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            extendMemoryMaintenanceJobTable(db)
            ensureMemoryRecoveryTables(db)
        }
    }

    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                UPDATE `memory_maintenance_job`
                SET `status` = 'dismissed',
                    `last_error` = 'schema16_legacy_room_index_removed',
                    `blocked_reason` = NULL,
                    `next_run_at` = NULL,
                    `started_at` = NULL,
                    `lease_owner` = NULL,
                    `lease_expires_at` = NULL,
                    `row_version` = `row_version` + 1
                WHERE `type` IN ('rebuild_memory_index', 'repair_markdown_metadata')
                    AND `status` NOT IN ('succeeded', 'dismissed')
                """.trimIndent()
            )
            db.execSQL("DROP TABLE IF EXISTS `memory_chunk`")
            db.execSQL("DROP TABLE IF EXISTS `memory_document`")
        }
    }

    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `chat_classification`")
            db.execSQL("DROP TABLE IF EXISTS `personal_memory`")
        }
    }

    internal fun legacyFilesToAttachmentsJson(filesValue: String): String {
        val attachments = filesValue
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { filePath ->
                ChatAttachment(
                    localFilePath = filePath,
                    preparedFilePath = filePath,
                    displayName = File(filePath).name,
                    mimeType = "",
                    sizeBytes = 0L
                )
            }

        return ChatAttachmentListConverter().fromList(attachments)
    }

    internal fun legacyRevisionsToStructuredJson(
        revisionsValue: String,
        createdAt: Long
    ): String {
        val revisions = revisionsValue
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { AssistantRevision(content = it, thoughts = "", createdAt = createdAt) }

        return AssistantRevisionListConverter().fromList(revisions)
    }

    private fun ensureMemoryTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `personal_memory` (
                `memory_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `summary` TEXT NOT NULL,
                `details` TEXT,
                `recall_text` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `scope` TEXT NOT NULL,
                `domains` TEXT NOT NULL,
                `entities` TEXT NOT NULL,
                `tags` TEXT NOT NULL,
                `applicable_modes` TEXT NOT NULL,
                `avoid_modes` TEXT NOT NULL,
                `importance` REAL NOT NULL,
                `confidence` REAL NOT NULL,
                `source` TEXT NOT NULL,
                `sensitivity` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `evidence` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `last_accessed_at` INTEGER,
                `expires_at` INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_classification` (
                `chat_id` INTEGER NOT NULL,
                `mode` TEXT NOT NULL,
                `intent` TEXT NOT NULL,
                `memory_needs` TEXT NOT NULL,
                `domains` TEXT NOT NULL,
                `entities` TEXT NOT NULL,
                `emotional_tone` TEXT,
                `should_use_memories` INTEGER NOT NULL,
                `should_learn_memories` INTEGER NOT NULL,
                `sensitivity` TEXT NOT NULL,
                `confidence` REAL NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `raw_model_json` TEXT,
                PRIMARY KEY(`chat_id`)
            )
            """.trimIndent()
        )
    }

    private fun ensurePlatformModelTables(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE platform_v2 ADD COLUMN model_refresh_status TEXT NOT NULL DEFAULT 'not_loaded'")
        db.execSQL("ALTER TABLE platform_v2 ADD COLUMN model_refresh_error TEXT")
        db.execSQL("ALTER TABLE platform_v2 ADD COLUMN model_refreshed_at INTEGER")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_platform_v2_uid` ON `platform_v2` (`uid`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `platform_model_v2` (
                `platform_uid` TEXT NOT NULL,
                `model_id` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `description` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL,
                `is_default` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`platform_uid`, `model_id`),
                FOREIGN KEY(`platform_uid`) REFERENCES `platform_v2`(`uid`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_platform_model_v2_platform_uid` ON `platform_model_v2` (`platform_uid`)")

        val currentTimestamp = System.currentTimeMillis() / 1000
        db.query("SELECT uid, model FROM platform_v2 WHERE model IS NOT NULL AND TRIM(model) != ''").use { cursor ->
            val uidIndex = cursor.getColumnIndexOrThrow("uid")
            val modelIndex = cursor.getColumnIndexOrThrow("model")
            while (cursor.moveToNext()) {
                val uid = cursor.getString(uidIndex).orEmpty()
                val model = cursor.getString(modelIndex).orEmpty().trim()
                if (uid.isBlank() || model.isBlank()) continue

                db.execSQL(
                    "INSERT OR IGNORE INTO platform_model_v2 (platform_uid, model_id, display_name, description, enabled, is_default, updated_at) VALUES (?, ?, ?, '', 1, 1, ?)",
                    arrayOf<Any>(uid, model, model, currentTimestamp)
                )
                db.execSQL(
                    "UPDATE platform_v2 SET model_refresh_status = 'success', model_refreshed_at = ? WHERE uid = ?",
                    arrayOf<Any>(currentTimestamp, uid)
                )
            }
        }
    }

    private fun ensureMemoryIndexTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_document` (
                `source_path` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `scope` TEXT NOT NULL,
                `content_hash` TEXT NOT NULL,
                `last_modified_at` INTEGER NOT NULL,
                `indexed_at` INTEGER NOT NULL,
                PRIMARY KEY(`source_path`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_document_scope` ON `memory_document` (`scope`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_document_indexed_at` ON `memory_document` (`indexed_at`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_chunk` (
                `chunk_id` TEXT NOT NULL,
                `source_path` TEXT NOT NULL,
                `chunk_index` INTEGER NOT NULL,
                `heading` TEXT,
                `text` TEXT NOT NULL,
                `entry_id` TEXT,
                `type` TEXT,
                `sensitivity` TEXT,
                `source` TEXT,
                `chat_id` INTEGER,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `indexed_at` INTEGER NOT NULL,
                PRIMARY KEY(`chunk_id`),
                FOREIGN KEY(`source_path`) REFERENCES `memory_document`(`source_path`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_chunk_source_path` ON `memory_chunk` (`source_path`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_chunk_entry_id` ON `memory_chunk` (`entry_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_chunk_type` ON `memory_chunk` (`type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_chunk_sensitivity` ON `memory_chunk` (`sensitivity`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_chunk_indexed_at` ON `memory_chunk` (`indexed_at`)")
    }

    private fun ensureMemoryMaintenanceJobTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_maintenance_job` (
                `job_id` TEXT NOT NULL,
                `type` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `idempotency_key` TEXT NOT NULL,
                `payload_json` TEXT NOT NULL,
                `attempts` INTEGER NOT NULL,
                `last_error` TEXT,
                `created_at` INTEGER NOT NULL,
                `started_at` INTEGER,
                `updated_at` INTEGER NOT NULL,
                `next_run_at` INTEGER,
                PRIMARY KEY(`job_id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_maintenance_job_idempotency_key` ON `memory_maintenance_job` (`idempotency_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_maintenance_job_status` ON `memory_maintenance_job` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_maintenance_job_type` ON `memory_maintenance_job` (`type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_maintenance_job_next_run_at` ON `memory_maintenance_job` (`next_run_at`)")
    }

    private fun ensureMemoryTurnBatchTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_chat_checkpoint` (
                `chat_id` INTEGER NOT NULL,
                `last_processed_user_message_id` INTEGER NOT NULL,
                `last_observed_user_message_id` INTEGER NOT NULL,
                `pending_since` INTEGER,
                `last_user_activity_at` INTEGER,
                `idle_due_at` INTEGER,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`chat_id`),
                FOREIGN KEY(`chat_id`) REFERENCES `chats_v2`(`chat_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_chat_checkpoint_idle_due_at` ON `memory_chat_checkpoint` (`idle_due_at`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_pending_turn` (
                `turn_key` TEXT NOT NULL,
                `chat_id` INTEGER NOT NULL,
                `user_message_id` INTEGER NOT NULL,
                `payload_json` TEXT NOT NULL,
                `content_hash` TEXT NOT NULL,
                `completed_at` INTEGER NOT NULL,
                `claimed_job_id` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`turn_key`),
                FOREIGN KEY(`chat_id`) REFERENCES `chats_v2`(`chat_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_pending_turn_chat_id_user_message_id` ON `memory_pending_turn` (`chat_id`, `user_message_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_pending_turn_chat_id_claimed_job_id` ON `memory_pending_turn` (`chat_id`, `claimed_job_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_pending_turn_claimed_job_id` ON `memory_pending_turn` (`claimed_job_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_pending_turn_completed_at` ON `memory_pending_turn` (`completed_at`)")
    }

    private fun ensureMemoryActivityLogTable(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_activity_log` (
                `log_id` TEXT NOT NULL,
                `batch_id` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `platform_name` TEXT,
                `model_name` TEXT,
                `attempt` INTEGER,
                `turn_count` INTEGER,
                `operation_count` INTEGER,
                `detail` TEXT,
                `started_at` INTEGER NOT NULL,
                `completed_at` INTEGER,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`log_id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_activity_log_batch_id` ON `memory_activity_log` (`batch_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_activity_log_category` ON `memory_activity_log` (`category`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_activity_log_status` ON `memory_activity_log` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_activity_log_started_at` ON `memory_activity_log` (`started_at`)")
    }

    private fun extendMemoryMaintenanceJobTable(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memory_maintenance_job` ADD COLUMN `family` TEXT NOT NULL DEFAULT 'semantic'")
        db.execSQL("ALTER TABLE `memory_maintenance_job` ADD COLUMN `generation` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memory_maintenance_job` ADD COLUMN `row_version` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memory_maintenance_job` ADD COLUMN `lease_owner` TEXT")
        db.execSQL("ALTER TABLE `memory_maintenance_job` ADD COLUMN `lease_expires_at` INTEGER")
        db.execSQL("ALTER TABLE `memory_maintenance_job` ADD COLUMN `retry_cycle` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memory_maintenance_job` ADD COLUMN `blocked_reason` TEXT")
        db.execSQL(
            "UPDATE `memory_maintenance_job` SET `family` = 'index' WHERE `type` = 'rebuild_memory_index'"
        )
        db.execSQL(
            "UPDATE `memory_maintenance_job` SET `family` = 'repair' WHERE `type` = 'repair_markdown_metadata'"
        )
        db.execSQL(
            """
            UPDATE `memory_maintenance_job`
            SET `family` = 'repair'
            WHERE `type` NOT IN (
                'append_daily_note',
                'rebuild_memory_index',
                'distill_daily_notes',
                'promote_long_term_candidate',
                'repair_markdown_metadata',
                'compaction_flush',
                'consolidate_turn_batch'
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_maintenance_job_family_status_next_run_at_created_at` ON `memory_maintenance_job` (`family`, `status`, `next_run_at`, `created_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_maintenance_job_family_status_lease_expires_at` ON `memory_maintenance_job` (`family`, `status`, `lease_expires_at`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_maintenance_job_generation_status` ON `memory_maintenance_job` (`generation`, `status`)"
        )
    }

    private fun ensureMemoryRecoveryTables(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_mutation_group` (
                `group_id` TEXT NOT NULL,
                `generation` INTEGER NOT NULL,
                `semantic_job_id` TEXT,
                `semantic_batch_id` TEXT,
                `state` TEXT NOT NULL,
                `idempotency_key` TEXT NOT NULL,
                `last_error` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `completed_at` INTEGER,
                `expected_receipt_count` INTEGER NOT NULL DEFAULT 0,
                `row_version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`group_id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_mutation_group_idempotency_key` ON `memory_mutation_group` (`idempotency_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_mutation_group_semantic_job_id` ON `memory_mutation_group` (`semantic_job_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_mutation_group_semantic_batch_id` ON `memory_mutation_group` (`semantic_batch_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_mutation_group_generation` ON `memory_mutation_group` (`generation`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_mutation_group_state` ON `memory_mutation_group` (`state`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_mutation_receipt` (
                `receipt_id` TEXT NOT NULL,
                `group_id` TEXT NOT NULL,
                `generation` INTEGER NOT NULL,
                `source_path` TEXT NOT NULL,
                `base_source_hash` TEXT NOT NULL,
                `target_source_hash` TEXT NOT NULL,
                `staged_target_path` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `idempotency_key` TEXT NOT NULL,
                `target_index_fingerprint` TEXT,
                `attempts` INTEGER NOT NULL,
                `last_error` TEXT,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `file_committed_at` INTEGER,
                `indexed_at` INTEGER,
                `row_version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`receipt_id`),
                FOREIGN KEY(`group_id`) REFERENCES `memory_mutation_group`(`group_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_mutation_receipt_idempotency_key` ON `memory_mutation_receipt` (`idempotency_key`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_mutation_receipt_group_id_source_path` ON `memory_mutation_receipt` (`group_id`, `source_path`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_mutation_receipt_generation` ON `memory_mutation_receipt` (`generation`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_mutation_receipt_state` ON `memory_mutation_receipt` (`state`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_mutation_receipt_source_path` ON `memory_mutation_receipt` (`source_path`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_corpus_state` (
                `corpus` TEXT NOT NULL,
                `source_path` TEXT NOT NULL,
                `source_hash` TEXT NOT NULL,
                `generation` INTEGER NOT NULL,
                `target_index_fingerprint` TEXT,
                `index_status` TEXT NOT NULL,
                `indexed_generation` INTEGER,
                `indexed_source_hash` TEXT,
                `indexed_fingerprint` TEXT,
                `latest_receipt_id` TEXT,
                `last_error` TEXT,
                `row_version` INTEGER NOT NULL DEFAULT 0,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                PRIMARY KEY(`corpus`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_corpus_state_source_path` ON `memory_corpus_state` (`source_path`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_corpus_state_generation` ON `memory_corpus_state` (`generation`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_corpus_state_index_status` ON `memory_corpus_state` (`index_status`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `memory_distillation_checkpoint` (
                `checkpoint_id` TEXT NOT NULL,
                `daily_source_path` TEXT NOT NULL,
                `daily_source_hash` TEXT NOT NULL,
                `batch_key` TEXT NOT NULL,
                `daily_date` TEXT NOT NULL,
                `semantic_job_id` TEXT NOT NULL,
                `target_source_path` TEXT NOT NULL,
                `target_base_hash` TEXT NOT NULL,
                `target_source_hash` TEXT NOT NULL,
                `mutation_group_id` TEXT,
                `status` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                `updated_at` INTEGER NOT NULL,
                `processed_at` INTEGER,
                `row_version` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`checkpoint_id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_distillation_checkpoint_source_batch` ON `memory_distillation_checkpoint` (`daily_source_path`, `daily_source_hash`, `batch_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_distillation_checkpoint_daily_date` ON `memory_distillation_checkpoint` (`daily_date`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_distillation_checkpoint_semantic_job_id` ON `memory_distillation_checkpoint` (`semantic_job_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_distillation_checkpoint_mutation_group_id` ON `memory_distillation_checkpoint` (`mutation_group_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_distillation_checkpoint_status` ON `memory_distillation_checkpoint` (`status`)")
    }
}
