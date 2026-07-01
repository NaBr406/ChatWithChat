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
