package cn.nabr.chatwithchat.data.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryRecallCoreInstrumentedTest {

    @Test
    fun isolatedMemoryFile_projectsUserAddressAndAssistantNameIntoCore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "memory_recall_core_test/run-${System.nanoTime()}")
        val fileStore = MemoryFileStore(MemoryFilePaths(root))
        try {
            fileStore.ensureStore().getOrThrow()
            val markdown = MarkdownMemoryCodec().renderLongTerm(
                listOf(
                    MarkdownMemoryEntry(
                        id = "core-preferred-address",
                        text = "用户希望被称为小纳。",
                        type = "stable_profile",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        canonicalKey = "identity.preferred_address",
                        recallState = MemoryRecallState.CORE
                    ),
                    MarkdownMemoryEntry(
                        id = "core-assistant-name",
                        text = "助手名称是 ChatWithChat。",
                        type = "stable_profile",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT,
                        canonicalKey = "identity.assistant_name",
                        recallState = MemoryRecallState.CORE
                    )
                )
            )
            fileStore.replaceLongTermMemory(markdown).getOrThrow()

            val snapshot = MemoryCorpusSnapshotter(fileStore, MemoryChunker())
                .snapshots(MemoryCorpus.CHAT_RECALL_LONG_TERM)
                .getOrThrow()
                .single()
            val coreIdentityKeys = snapshot
                .selectCoreResults(includePrivate = true)
                .mapNotNull(MemoryRetrievalResult::canonicalKey)
                .filter { key -> key in CORE_IDENTITY_KEYS }

            assertEquals(
                listOf(
                    "identity.assistant_name",
                    "identity.preferred_address"
                ),
                coreIdentityKeys
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        val CORE_IDENTITY_KEYS = setOf(
            "identity.preferred_address",
            "identity.assistant_name"
        )
    }
}
