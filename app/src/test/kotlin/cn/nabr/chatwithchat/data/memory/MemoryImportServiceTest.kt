package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.InMemoryMemoryRecoveryDao
import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.repository.SettingRepository
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryImportServiceTest {

    @Test
    fun `app memory import requires managed format and skips duplicate text`() = runBlocking {
        val fixture = Fixture()
        val codec = MarkdownMemoryCodec()
        val current = codec.renderLongTerm(
            listOf(
                entry("mem_existing", "Keep the existing preference.")
            )
        )
        fixture.fileStore.replaceLongTermMemory(current).getOrThrow()
        val imported = codec.renderLongTerm(
            listOf(
                entry("mem_existing", "A different imported fact."),
                entry("mem_new", "A new imported fact."),
                entry("mem_duplicate", "Keep the existing preference.")
            )
        )

        val result = fixture.service.importAppMemory(imported) as MemoryImportOutcome.Imported
        val parsed = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow())

        assertEquals(2, result.importedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(3, parsed.entries.size)
        assertTrue(parsed.entries.any { it.text == "A new imported fact." })
        assertTrue(parsed.entries.any { it.text == "A different imported fact." })
        assertTrue(parsed.entries.any { it.id != "mem_existing" && it.text == "A different imported fact." })
    }

    @Test
    fun `app memory import remaps supersession references when ids collide`() = runBlocking {
        val fixture = Fixture()
        val codec = MarkdownMemoryCodec()
        fixture.fileStore.replaceLongTermMemory(
            codec.renderLongTerm(listOf(entry("mem_current", "Existing fact.")))
        ).getOrThrow()
        val importedActive = entry("mem_current", "Imported active fact.").copy(
            canonicalKey = "profile.imported_fact",
            scope = MemoryScope.GENERAL
        )
        val importedHistory = importedActive.copy(
            id = "mem_history",
            text = "Imported historical fact.",
            updatedAt = 2L,
            validity = MemoryValidity.OBSOLETE,
            supersededBy = importedActive.id,
            recallState = MemoryRecallState.MAINTENANCE_ONLY
        )

        val result = fixture.service.importAppMemory(codec.renderLongTerm(listOf(importedActive, importedHistory)))
        val parsed = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow())
        val importedActiveId = parsed.entries.first { it.text == importedActive.text }.id
        val importedHistoryEntry = parsed.entries.first { it.text == importedHistory.text }

        assertEquals(2, (result as MemoryImportOutcome.Imported).importedCount)
        assertEquals(importedActiveId, importedHistoryEntry.supersededBy)
        assertTrue(parsed.skippedEntries.isEmpty())
    }

    @Test
    fun `app memory import combines preferred address and assistant name immediately`() = runBlocking {
        val fixture = Fixture()
        val codec = MarkdownMemoryCodec()
        fixture.fileStore.replaceLongTermMemory(codec.renderLongTerm(emptyList())).getOrThrow()
        val preferred = entry("mem_preferred", "Address the user as Captain.").copy(
            type = "communication_style",
            canonicalKey = MemoryCanonicalIdentityPolicy.PREFERRED_ADDRESS_KEY,
            scope = MemoryScope.GENERAL,
            recallState = MemoryRecallState.CORE
        )
        val assistant = entry("mem_assistant", "The assistant name is Small C.").copy(
            type = "communication_style",
            canonicalKey = MemoryCanonicalIdentityPolicy.ASSISTANT_NAME_KEY,
            scope = MemoryScope.GENERAL,
            recallState = MemoryRecallState.CORE
        )

        val result = fixture.service.importAppMemory(codec.renderLongTerm(listOf(preferred, assistant)))
        val parsed = codec.parse(fixture.fileStore.readLongTermMemory().getOrThrow())
        val current = parsed.entries.filter { it.validity == MemoryValidity.CURRENT }

        assertEquals(2, (result as MemoryImportOutcome.Imported).importedCount)
        assertEquals(1, current.size)
        assertEquals(MemoryCanonicalIdentityPolicy.PREFERRED_ADDRESS_KEY, current.single().canonicalKey)
        assertTrue(current.single().text.contains(preferred.text))
        assertTrue(current.single().text.contains(assistant.text))
    }

    @Test
    fun `external memory import sends text through model and stores canonical rewrite`() = runBlocking {
        val fixture = Fixture(
            intelligence = FakeMemoryIntelligence(
                importProposal = MemoryImportProposal(
                    operations = listOf(
                        MemoryImportOperation(
                            action = MemoryImportAction.CREATE,
                            text = "The user prefers short answers.",
                            type = "communication_style",
                            canonicalKey = "communication.response_style",
                            scope = MemoryScope.GENERAL,
                            recallState = MemoryRecallState.QUERY
                        )
                    )
                )
            )
        )
        fixture.fileStore.replaceLongTermMemory(MarkdownMemoryCodec().renderLongTerm(emptyList())).getOrThrow()

        val result = fixture.service.importExternalMemory("A long external memory note.") as MemoryImportOutcome.Imported
        val parsed = MarkdownMemoryCodec().parse(fixture.fileStore.readLongTermMemory().getOrThrow())

        assertEquals(1, result.importedCount)
        assertTrue(result.rewrittenByModel)
        assertEquals("A long external memory note.", fixture.intelligence.lastImportRequest?.importedText)
        assertEquals("The user prefers short answers.", parsed.entries.single().text)
        assertEquals("communication.response_style", parsed.entries.single().canonicalKey)
    }

    @Test
    fun `app memory import rejects ordinary markdown`() = runBlocking {
        val fixture = Fixture()

        val exception = runCatching {
            fixture.service.importAppMemory("# Notes\n\nThis is not an app memory file.")
        }.exceptionOrNull()

        assertTrue(exception is MemoryImportException)
        assertEquals(
            MemoryImportException.Reason.INVALID_APP_FORMAT,
            (exception as MemoryImportException).reason
        )
    }

    @Test
    fun `app memory import rejects files larger than the service limit`() = runBlocking {
        val fixture = Fixture()

        val exception = runCatching {
            fixture.service.importAppMemory("x".repeat(256 * 1024 + 1))
        }.exceptionOrNull()

        assertTrue(exception is MemoryImportException)
        assertEquals(
            MemoryImportException.Reason.INPUT_TOO_LARGE,
            (exception as MemoryImportException).reason
        )
    }

    @Test
    fun `external memory import rejects a create operation with a target id`() = runBlocking {
        val fixture = Fixture(
            intelligence = FakeMemoryIntelligence(
                importProposal = MemoryImportProposal(
                    operations = listOf(
                        MemoryImportOperation(
                            action = MemoryImportAction.CREATE,
                            targetMemoryId = "mem_existing",
                            text = "A new fact.",
                            type = "project_context",
                            canonicalKey = "project.imported_fact",
                            scope = "project:import",
                            recallState = MemoryRecallState.QUERY
                        )
                    )
                )
            )
        )
        fixture.fileStore.replaceLongTermMemory(
            MarkdownMemoryCodec().renderLongTerm(listOf(entry("mem_existing", "Existing fact.")))
        ).getOrThrow()

        val exception = runCatching {
            fixture.service.importExternalMemory("A long external memory note.")
        }.exceptionOrNull()

        assertTrue(exception is MemoryImportException)
        assertEquals(
            MemoryImportException.Reason.MODEL_REWRITE_FAILED,
            (exception as MemoryImportException).reason
        )
        assertTrue(
            MarkdownMemoryCodec().parse(fixture.fileStore.readLongTermMemory().getOrThrow())
                .entries.none { it.text == "A new fact." }
        )
    }

    private class Fixture(
        val intelligence: FakeMemoryIntelligence = FakeMemoryIntelligence()
    ) {
        private val root = Files.createTempDirectory("memory-import-service").toFile()
        private val clock = Clock.fixed(Instant.parse("2026-08-06T04:00:00Z"), ZoneOffset.UTC)
        val fileStore = MemoryFileStore(MemoryFilePaths(root), clock)
        private val recoveryDao = InMemoryMemoryRecoveryDao()
        private val jobDao = InMemoryMaintenanceJobDao()
        private val workEnqueuer = RecordingWorkEnqueuer()
        private val settingRepository = settingRepository()
        private val resolver = MemoryModelResolver(settingRepository)
        private val coordinator = MemoryMutationCoordinator(
            recoveryDao = recoveryDao,
            memoryFileStore = fileStore,
            maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, clock),
            workEnqueuer = workEnqueuer,
            clock = clock
        )
        val service = MemoryImportService(
            memoryFileStore = fileStore,
            memoryMutationCoordinator = coordinator,
            memoryIntelligence = intelligence,
            memoryModelResolver = resolver,
            settingRepository = settingRepository
        )

        init {
            fileStore.ensureStore().getOrThrow()
        }

        private fun settingRepository(): SettingRepository {
            val platform = PlatformV2(
                uid = "import-platform",
                name = "Import platform",
                compatibleType = ClientType.OPENROUTER,
                enabled = true,
                apiUrl = "https://example.test/v1",
                token = "token",
                model = "import-model"
            )
            val model = PlatformModelV2(
                platformUid = platform.uid,
                modelId = platform.model,
                displayName = platform.model,
                enabled = true
            )
            val handler = java.lang.reflect.InvocationHandler { _, method, arguments ->
                when (method.name) {
                    "fetchMemoryModelPreference" -> MemoryModelPreference.Auto
                    "fetchPlatformV2s" -> listOf(platform)
                    "fetchPlatformModels" -> if (method.parameterCount == 1) {
                        listOf(model)
                    } else {
                        listOf(model)
                    }
                    else -> error("Unexpected SettingRepository call: ${method.name}")
                }
            }
            return Proxy.newProxyInstance(
                SettingRepository::class.java.classLoader,
                arrayOf(SettingRepository::class.java),
                handler
            ) as SettingRepository
        }
    }

    private companion object {
        fun entry(id: String, text: String): MarkdownMemoryEntry = MarkdownMemoryEntry(
            id = id,
            text = text,
            type = "project_context",
            sensitivity = MemorySensitivity.NORMAL,
            source = MemorySource.EXPLICIT_USER_STATEMENT,
            createdAt = 1L,
            updatedAt = 1L
        )
    }
}
