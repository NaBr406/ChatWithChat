package cn.nabr.chatwithchat.data.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryRecallCoreInstrumentedTest {

    @Test
    fun installedMemoryFile_projectsUserAddressAndAssistantNameIntoCore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fileStore = MemoryFileStore(MemoryFilePaths.fromContext(context))
        val markdown = fileStore.readLongTermMemory().getOrThrow()

        assertTrue(markdown.contains("canonical_key=identity.preferred_address"))
        assertTrue(markdown.contains("canonical_key=identity.assistant_name"))

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
                "identity.preferred_address",
                "identity.assistant_name"
            ),
            coreIdentityKeys
        )
    }

    private companion object {
        val CORE_IDENTITY_KEYS = setOf(
            "identity.preferred_address",
            "identity.assistant_name"
        )
    }
}
