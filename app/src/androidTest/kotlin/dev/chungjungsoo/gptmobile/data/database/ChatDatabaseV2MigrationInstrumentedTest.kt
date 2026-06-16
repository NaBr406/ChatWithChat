package dev.chungjungsoo.gptmobile.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDatabaseV2MigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChatDatabaseV2::class.java,
        listOf(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migration4To5CreatesMemoryTables() {
        helper.createDatabase(TEST_DB, 4).apply {
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            ChatDatabaseV2Migrations.MIGRATION_4_5
        ).apply {
            query("SELECT COUNT(*) FROM personal_memory").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            query("SELECT COUNT(*) FROM chat_classification").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            close()
        }
    }

    companion object {
        private const val TEST_DB = "chat-v2-migration-test"
    }
}
