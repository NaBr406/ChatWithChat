package dev.chungjungsoo.gptmobile.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevision
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantRevisionListConverter
import dev.chungjungsoo.gptmobile.data.database.entity.ChatAttachmentListConverter
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.model.ReasoningMode
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
}
