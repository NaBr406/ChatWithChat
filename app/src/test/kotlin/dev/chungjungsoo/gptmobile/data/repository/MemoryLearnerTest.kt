package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.dao.MemoryMaintenanceJobDao
import dev.chungjungsoo.gptmobile.data.database.entity.MemoryMaintenanceJob
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.memory.FakeMemoryIntelligence
import dev.chungjungsoo.gptmobile.data.memory.InMemoryChatClassificationDao
import dev.chungjungsoo.gptmobile.data.memory.InMemoryPersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryCodec
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryEntry
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryLearningNote
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryLearningProposal
import dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryLearningService
import dev.chungjungsoo.gptmobile.data.memory.MemoryAction
import dev.chungjungsoo.gptmobile.data.memory.MemoryFilePaths
import dev.chungjungsoo.gptmobile.data.memory.MemoryFileStore
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexRebuildResult
import dev.chungjungsoo.gptmobile.data.memory.MemoryIndexRebuilder
import dev.chungjungsoo.gptmobile.data.memory.MemoryLearningResult
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceJobStatus
import dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceScheduler
import dev.chungjungsoo.gptmobile.data.memory.MemoryMarkdownCodec
import dev.chungjungsoo.gptmobile.data.memory.MemoryCandidate
import dev.chungjungsoo.gptmobile.data.memory.MemoryPromptBuilder
import dev.chungjungsoo.gptmobile.data.memory.MemorySensitivity
import dev.chungjungsoo.gptmobile.data.memory.MemorySource
import dev.chungjungsoo.gptmobile.data.memory.MemoryStatus
import dev.chungjungsoo.gptmobile.data.memory.MemoryUpdateOperation
import dev.chungjungsoo.gptmobile.data.memory.MemoryUpdatePlan
import dev.chungjungsoo.gptmobile.data.memory.testCandidate
import dev.chungjungsoo.gptmobile.data.memory.testClassification
import dev.chungjungsoo.gptmobile.data.memory.testMemory
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLearnerTest {

    @Test
    fun `learner creates pending boundary memory from fake extractor and planner`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val candidate = testCandidate(
            summary = "以后别太说教",
            recallText = "The user prefers replies that are not preachy.",
            type = "boundary",
            requiresConfirmation = true
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = FakeMemoryIntelligence(
                classification = testClassification(shouldLearnMemories = true),
                candidates = listOf(candidate),
                updatePlan = MemoryUpdatePlan(
                    operations = listOf(
                        MemoryUpdateOperation(
                            action = MemoryAction.CREATE,
                            candidateIndex = 0,
                            result = candidate,
                            reason = "Explicit preference"
                        )
                    )
                )
            )
        )

        val result = repository.learnFromChat(chatRoom(), userMessages("以后别太说教"), listOf(emptyList()))

        assertEquals(1, personalMemoryDao.memories.size)
        assertEquals("boundary", personalMemoryDao.memories.first().type)
        assertEquals(MemoryStatus.PENDING_CONFIRMATION, personalMemoryDao.memories.first().status)
        assertEquals(MemoryLearningResult.STATUS_APPLIED, result.status)
    }

    @Test
    fun `explicit non-sensitive user memory can become active immediately`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val candidate = testCandidate(
            summary = "The user prefers concise answers.",
            recallText = "The user prefers concise answers.",
            type = "communication_style",
            requiresConfirmation = false
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = FakeMemoryIntelligence(
                classification = testClassification(shouldLearnMemories = true),
                candidates = listOf(candidate),
                updatePlan = MemoryUpdatePlan(
                    operations = listOf(
                        MemoryUpdateOperation(
                            action = MemoryAction.CREATE,
                            candidateIndex = 0,
                            result = candidate,
                            reason = "Explicit preference"
                        )
                    )
                )
            )
        )

        val result = repository.learnFromChat(chatRoom(), userMessages("Remember that I prefer concise answers."), listOf(emptyList()))

        assertEquals(1, personalMemoryDao.memories.size)
        assertEquals(MemoryStatus.ACTIVE, personalMemoryDao.memories.first().status)
        assertEquals(MemoryLearningResult.STATUS_APPLIED, result.status)
    }

    @Test
    fun `explicit direct memory is created when planner returns no operations`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val candidate = testCandidate(
            summary = "The user values direct, concrete answers.",
            recallText = "The user values direct, concrete answers.",
            type = "communication_style",
            requiresConfirmation = false
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = FakeMemoryIntelligence(
                classification = testClassification(shouldLearnMemories = true),
                candidates = listOf(candidate),
                updatePlan = MemoryUpdatePlan()
            )
        )

        val result = repository.learnFromChat(chatRoom(), userMessages("请记住我喜欢直接、具体的回答。"), listOf(emptyList()))

        assertEquals(1, personalMemoryDao.memories.size)
        assertEquals(MemoryStatus.ACTIVE, personalMemoryDao.memories.first().status)
        assertEquals(MemoryLearningResult.STATUS_APPLIED_DIRECT_FALLBACK, result.status)
    }

    @Test
    fun `learner still extracts when classifier says not to learn`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val candidate = testCandidate(
            summary = "The user wants durable personal preferences remembered.",
            recallText = "The user wants durable personal preferences remembered.",
            type = "communication_style",
            requiresConfirmation = false
        )
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(shouldLearnMemories = false),
            candidates = listOf(candidate),
            updatePlan = MemoryUpdatePlan()
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = intelligence
        )

        val result = repository.learnFromChat(chatRoom(), userMessages("请记住这些偏好。"), listOf(emptyList()))

        assertEquals(1, intelligence.extractCalls)
        assertEquals(1, personalMemoryDao.memories.size)
        assertEquals(MemoryStatus.ACTIVE, personalMemoryDao.memories.first().status)
        assertEquals(MemoryLearningResult.STATUS_APPLIED_DIRECT_FALLBACK, result.status)
    }

    @Test
    fun `learner normalizes common model candidate field variants`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val candidate = MemoryCandidate(
            summary = "The user prefers concrete implementation.",
            recallText = "",
            type = "preference",
            source = "confirmed",
            sensitivity = "non-sensitive",
            suggestedStatus = "remember",
            scope = "user",
            requiresConfirmation = true,
            reason = "Explicit preference"
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = FakeMemoryIntelligence(
                classification = testClassification(shouldLearnMemories = false),
                candidates = listOf(candidate),
                updatePlan = MemoryUpdatePlan()
            )
        )

        val result = repository.learnFromChat(chatRoom(), userMessages("我喜欢你直接给具体实现。"), listOf(emptyList()))

        val memory = personalMemoryDao.memories.first()
        assertEquals("communication_style", memory.type)
        assertEquals(MemorySource.USER_CONFIRMED, memory.source)
        assertEquals(MemoryStatus.ACTIVE, memory.status)
        assertEquals("The user prefers concrete implementation.", memory.recallText)
        assertEquals(MemoryLearningResult.STATUS_APPLIED_DIRECT_FALLBACK, result.status)
    }

    @Test
    fun `explicit direct memory is created when planner only ignores it`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val candidate = testCandidate(
            summary = "The user prefers end-to-end implementation over advice.",
            recallText = "The user prefers end-to-end implementation over advice.",
            type = "communication_style",
            requiresConfirmation = false
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = FakeMemoryIntelligence(
                classification = testClassification(shouldLearnMemories = true),
                candidates = listOf(candidate),
                updatePlan = MemoryUpdatePlan(
                    operations = listOf(
                        MemoryUpdateOperation(
                            action = MemoryAction.IGNORE,
                            candidateIndex = 0,
                            reason = "Planner was too conservative"
                        )
                    )
                )
            )
        )

        val result = repository.learnFromChat(chatRoom(), userMessages("我更喜欢直接实现，不要只给建议。"), listOf(emptyList()))

        assertEquals(1, personalMemoryDao.memories.size)
        assertEquals(MemoryStatus.ACTIVE, personalMemoryDao.memories.first().status)
        assertEquals(MemoryLearningResult.STATUS_APPLIED_DIRECT_FALLBACK, result.status)
    }

    @Test
    fun `planner can mark existing memory resolved`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao(
            listOf(testMemory(1, "The user has an important exam soon.", type = "important_event"))
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = FakeMemoryIntelligence(
                classification = testClassification(shouldLearnMemories = true),
                candidates = listOf(testCandidate("考试结束了", type = "important_event")),
                updatePlan = MemoryUpdatePlan(
                    operations = listOf(
                        MemoryUpdateOperation(
                            action = MemoryAction.MARK_RESOLVED,
                            targetMemoryIds = listOf(1),
                            reason = "The event ended"
                        )
                    )
                )
            )
        )

        val result = repository.learnFromChat(chatRoom(), userMessages("考试结束了"), listOf(emptyList()))

        assertEquals(MemoryStatus.RESOLVED, personalMemoryDao.memories.first().status)
        assertEquals(MemoryLearningResult.STATUS_APPLIED, result.status)
    }

    @Test
    fun `null classifier still extracts explicit memories`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val candidate = testCandidate(
            summary = "The user wants memory extraction to be semantic.",
            recallText = "The user wants memory extraction to be semantic.",
            requiresConfirmation = false
        )
        val intelligence = FakeMemoryIntelligence(
            classification = null,
            candidates = listOf(candidate),
            updatePlan = MemoryUpdatePlan()
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = intelligence
        )

        val result = repository.learnFromChat(chatRoom(), userMessages("remember something"), listOf(emptyList()))

        assertEquals(1, intelligence.extractCalls)
        assertEquals(1, personalMemoryDao.memories.size)
        assertEquals(MemoryLearningResult.STATUS_APPLIED_DIRECT_FALLBACK, result.status)
    }

    @Test
    fun `high confidence classifier creates fallback memory when extraction is unavailable`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = FakeMemoryIntelligence(
                classification = testClassification(
                    shouldLearnMemories = true,
                    sensitivity = MemorySensitivity.PRIVATE,
                    confidence = 0.98f
                ),
                candidates = emptyList(),
                updatePlan = null
            )
        )

        val result = repository.learnFromChat(
            chatRoom(),
            userMessages("我是一个偏效率主义的人，做事目标明确，不喜欢冗余流程。"),
            listOf(emptyList())
        )

        val memory = personalMemoryDao.memories.first()
        assertEquals(1, personalMemoryDao.memories.size)
        assertEquals(MemorySource.USER_CONFIRMED, memory.source)
        assertEquals(MemorySensitivity.PRIVATE, memory.sensitivity)
        assertEquals(MemoryStatus.ACTIVE, memory.status)
        assertTrue(memory.summary.contains("偏效率主义"))
        assertFalse(memory.recallText.contains("The user said:"))
        assertEquals(MemoryLearningResult.STATUS_APPLIED_DIRECT_FALLBACK, result.status)
    }

    @Test
    fun `classifier fallback skips long raw user statements when extraction is unavailable`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = FakeMemoryIntelligence(
                classification = testClassification(
                    shouldLearnMemories = true,
                    sensitivity = MemorySensitivity.PRIVATE,
                    confidence = 0.98f
                ),
                candidates = emptyList(),
                updatePlan = null
            )
        )

        val result = repository.learnFromChat(
            chatRoom(),
            userMessages("我是一个偏效率主义的人，做事目标明确，不喜欢冗余流程，也不喜欢 AI 那种客套、机械、空泛的表达。".repeat(8)),
            listOf(emptyList())
        )

        assertEquals(0, personalMemoryDao.memories.size)
        assertEquals(MemoryLearningResult.STATUS_FAILED_EXTRACTION_UNAVAILABLE, result.status)
    }

    @Test
    fun `learner sends compact latest user message to extractor`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val longLatestMessage = "I prefer direct concrete answers. ".repeat(100)
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(shouldLearnMemories = true),
            candidates = listOf(
                testCandidate(
                    summary = "The user likes direct concrete answers.",
                    recallText = "The user likes direct concrete answers."
                )
            ),
            updatePlan = MemoryUpdatePlan()
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = intelligence
        )

        repository.learnFromChat(
            chatRoom(),
            listOf(
                MessageV2(chatId = 1, content = "older user message", platformType = null),
                MessageV2(chatId = 1, content = longLatestMessage, platformType = null)
            ),
            listOf(
                listOf(MessageV2(chatId = 1, content = "older assistant message", platformType = "platform")),
                listOf(MessageV2(chatId = 1, content = "assistant response", platformType = "platform"))
            )
        )

        val extractionMessages = intelligence.lastExtractionRequest?.recentMessages.orEmpty()
        assertEquals(1, extractionMessages.size)
        assertEquals("user", extractionMessages.first().role)
        assertEquals(1200, extractionMessages.first().content.length)
        assertTrue(extractionMessages.first().content.startsWith("I prefer direct concrete answers."))
    }

    @Test
    fun `learner appends controlled markdown notes and rebuilds affected indexes`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val markdown = createMarkdownHarness()
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(shouldLearnMemories = true),
            candidates = listOf(testCandidate("The user prefers direct answers.", requiresConfirmation = false)),
            updatePlan = MemoryUpdatePlan(),
            markdownProposal = MarkdownMemoryLearningProposal(
                dailyNotes = listOf(
                    MarkdownMemoryLearningNote(
                        text = "ChatWithChat should keep Markdown memory writes durable.",
                        type = "project_context",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT
                    )
                ),
                longTermUpdates = listOf(
                    MarkdownMemoryLearningNote(
                        text = "The user is comfortable with private local memory metadata.",
                        type = "stable_profile",
                        sensitivity = MemorySensitivity.PRIVATE,
                        source = MemorySource.ASSISTANT_INFERRED
                    )
                )
            )
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = intelligence,
            markdownMemoryLearningService = markdown.service
        )

        val result = repository.learnFromChat(
            chatRoom(),
            userMessages("Remember that I prefer direct answers."),
            listOf(emptyList()),
            memoryPlatform = null,
            learningIdempotencyKey = "chat-1-turn-1"
        )

        val dailyMarkdown = markdown.fileStore.readDailyMemory().getOrThrow()
        val longTermMarkdown = markdown.fileStore.readLongTermMemory().getOrThrow()
        assertEquals(MemoryLearningResult.STATUS_APPLIED_DIRECT_FALLBACK, result.status)
        assertEquals(1, personalMemoryDao.memories.size)
        assertTrue(dailyMarkdown.contains("ChatWithChat should keep Markdown memory writes durable."))
        assertTrue(dailyMarkdown.contains("type=project_context sensitivity=normal source=explicit_user_statement"))
        assertTrue(longTermMarkdown.contains("The user is comfortable with private local memory metadata."))
        assertTrue(longTermMarkdown.contains("type=stable_profile sensitivity=private source=assistant_inferred"))
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, markdown.jobDao.jobs.single().status)
        assertEquals(setOf("2026-07-09.md", "MEMORY.md"), markdown.indexRebuilder.rebuiltFiles.map { it.name }.toSet())
    }

    @Test
    fun `markdown learning strips raw user statement prefix before writing notes`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val markdown = createMarkdownHarness()
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(shouldLearnMemories = true),
            candidates = listOf(testCandidate("The user prefers semantic memory notes.", requiresConfirmation = false)),
            updatePlan = MemoryUpdatePlan(),
            markdownProposal = MarkdownMemoryLearningProposal(
                dailyNotes = listOf(
                    MarkdownMemoryLearningNote(
                        text = "The user said: The user asked memory notes to be semantic rather than copied.",
                        type = "communication_style",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT
                    )
                ),
                longTermUpdates = listOf(
                    MarkdownMemoryLearningNote(
                        text = "The user said: The user wants MEMORY.md to contain concise semantic memories.",
                        type = "communication_style",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT
                    )
                )
            )
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = intelligence,
            markdownMemoryLearningService = markdown.service
        )

        repository.learnFromChat(
            chatRoom(),
            userMessages("整理的记忆只是单纯的复制用户的话吗？"),
            listOf(emptyList()),
            memoryPlatform = null,
            learningIdempotencyKey = "strip-raw-prefix"
        )

        val dailyMarkdown = markdown.fileStore.readDailyMemory().getOrThrow()
        val longTermMarkdown = markdown.fileStore.readLongTermMemory().getOrThrow()
        assertFalse(dailyMarkdown.contains("The user said:"))
        assertFalse(longTermMarkdown.contains("The user said:"))
        assertTrue(dailyMarkdown.contains("The user asked memory notes to be semantic rather than copied."))
        assertTrue(longTermMarkdown.contains("The user wants MEMORY.md to contain concise semantic memories."))
    }

    @Test
    fun `learner does not append duplicate markdown notes for the same learning key`() = runBlocking {
        val markdown = createMarkdownHarness()
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(shouldLearnMemories = true),
            candidates = listOf(testCandidate("The user prefers concise replies.", requiresConfirmation = false)),
            updatePlan = MemoryUpdatePlan(),
            markdownProposal = MarkdownMemoryLearningProposal(
                dailyNotes = listOf(
                    MarkdownMemoryLearningNote(
                        text = "The user wants concise replies saved as Markdown.",
                        type = "communication_style",
                        sensitivity = MemorySensitivity.NORMAL,
                        source = MemorySource.EXPLICIT_USER_STATEMENT
                    )
                )
            )
        )
        val repository = createRepository(
            personalMemoryDao = InMemoryPersonalMemoryDao(),
            intelligence = intelligence,
            markdownMemoryLearningService = markdown.service
        )

        repository.learnFromChat(
            chatRoom(),
            userMessages("Keep replies concise."),
            listOf(emptyList()),
            memoryPlatform = null,
            learningIdempotencyKey = "same-turn"
        )
        repository.learnFromChat(
            chatRoom(),
            userMessages("Keep replies concise."),
            listOf(emptyList()),
            memoryPlatform = null,
            learningIdempotencyKey = "same-turn"
        )

        val dailyMarkdown = markdown.fileStore.readDailyMemory().getOrThrow()
        assertEquals(1, dailyMarkdown.countOccurrences("The user wants concise replies saved as Markdown."))
        assertEquals(1, intelligence.markdownProposalCalls)
        assertEquals(MemoryMaintenanceJobStatus.SUCCEEDED, markdown.jobDao.jobs.single().status)
    }

    @Test
    fun `markdown learning updates targeted long term memory instead of appending progress duplicate`() = runBlocking {
        val markdown = createMarkdownHarness()
        val codec = MarkdownMemoryCodec()
        markdown.fileStore.replaceLongTermMemory(
            codec.renderLongTerm(
                listOf(
                    MarkdownMemoryEntry(
                        id = "mem_learning_progress",
                        text = "The user is learning Kotlin coroutines and has completed Flow basics.",
                        type = "project_context",
                        sensitivity = MemorySensitivity.PRIVATE,
                        source = MemorySource.ASSISTANT_INFERRED,
                        createdAt = 100,
                        updatedAt = 100
                    )
                )
            )
        ).getOrThrow()
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(
                memoryNeeds = listOf("project_context"),
                shouldLearnMemories = true,
                sensitivity = MemorySensitivity.PRIVATE
            ),
            candidates = listOf(
                testCandidate(
                    "The user is learning Kotlin coroutines and has completed Flow basics plus cancellation handling.",
                    type = "project_context",
                    sensitivity = MemorySensitivity.PRIVATE,
                    requiresConfirmation = true
                )
            ),
            updatePlan = MemoryUpdatePlan(),
            markdownProposal = MarkdownMemoryLearningProposal(
                longTermUpdates = listOf(
                    MarkdownMemoryLearningNote(
                        text = "The user is learning Kotlin coroutines and has completed Flow basics plus cancellation handling.",
                        type = "project_context",
                        sensitivity = MemorySensitivity.PRIVATE,
                        source = MemorySource.ASSISTANT_INFERRED,
                        targetMemoryId = "mem_learning_progress"
                    )
                )
            )
        )
        val repository = createRepository(
            personalMemoryDao = InMemoryPersonalMemoryDao(),
            intelligence = intelligence,
            markdownMemoryLearningService = markdown.service
        )

        repository.learnFromChat(
            chatRoom(),
            userMessages("我已经同步到 cancellation handling 了"),
            listOf(emptyList()),
            memoryPlatform = null,
            learningIdempotencyKey = "learning-progress-update"
        )

        val longTermMarkdown = markdown.fileStore.readLongTermMemory().getOrThrow()
        val parsedEntries = codec.parse(longTermMarkdown).entries
        val existingMemory = intelligence.lastMarkdownLearningRequest?.existingMarkdownMemories?.single()
        assertEquals("mem_learning_progress", existingMemory?.id)
        assertEquals(1, parsedEntries.size)
        assertEquals("mem_learning_progress", parsedEntries.single().id)
        assertEquals(100, parsedEntries.single().createdAt)
        assertEquals(1783592430L, parsedEntries.single().updatedAt)
        assertTrue(longTermMarkdown.contains("Flow basics plus cancellation handling."))
        assertFalse(longTermMarkdown.contains("has completed Flow basics."))
        assertEquals(1, longTermMarkdown.countOccurrences("memory:id=mem_learning_progress"))
        assertEquals(setOf("MEMORY.md"), markdown.indexRebuilder.rebuiltFiles.map { it.name }.toSet())
    }

    @Test
    fun `markdown proposal failure keeps room learning result and records retryable job`() = runBlocking {
        val personalMemoryDao = InMemoryPersonalMemoryDao()
        val markdown = createMarkdownHarness()
        val intelligence = FakeMemoryIntelligence(
            classification = testClassification(shouldLearnMemories = true),
            candidates = listOf(testCandidate("The user prefers concrete implementation.", requiresConfirmation = false)),
            updatePlan = MemoryUpdatePlan(),
            markdownProposal = null
        )
        val repository = createRepository(
            personalMemoryDao = personalMemoryDao,
            intelligence = intelligence,
            markdownMemoryLearningService = markdown.service
        )

        val result = repository.learnFromChat(
            chatRoom(),
            userMessages("I prefer concrete implementation."),
            listOf(emptyList()),
            memoryPlatform = null,
            learningIdempotencyKey = "markdown-fails"
        )

        val dailyMarkdown = markdown.fileStore.readDailyMemory().getOrThrow()
        assertEquals(MemoryLearningResult.STATUS_APPLIED_DIRECT_FALLBACK, result.status)
        assertEquals(1, personalMemoryDao.memories.size)
        assertTrue(!dailyMarkdown.contains("concrete implementation saved as Markdown"))
        assertEquals(MemoryMaintenanceJobStatus.FAILED_RETRYABLE, markdown.jobDao.jobs.single().status)
        assertEquals(1, intelligence.markdownProposalCalls)
    }

    private fun createRepository(
        personalMemoryDao: InMemoryPersonalMemoryDao,
        intelligence: FakeMemoryIntelligence,
        markdownMemoryLearningService: MarkdownMemoryLearningService? = null
    ): MemoryRepositoryImpl = MemoryRepositoryImpl(
        personalMemoryDao = personalMemoryDao,
        chatClassificationDao = InMemoryChatClassificationDao(),
        memoryIntelligence = intelligence,
        memoryPromptBuilder = MemoryPromptBuilder(),
        memoryMarkdownCodec = MemoryMarkdownCodec(),
        markdownMemoryLearningService = markdownMemoryLearningService
    )

    private fun chatRoom() = ChatRoomV2(id = 1, title = "Chat", enabledPlatform = listOf("platform"))

    private fun userMessages(content: String) = listOf(MessageV2(chatId = 1, content = content, platformType = null))

    private fun createMarkdownHarness(): MarkdownHarness {
        val clock = Clock.fixed(Instant.parse("2026-07-09T10:20:30Z"), ZoneOffset.UTC)
        val fileStore = MemoryFileStore(
            paths = MemoryFilePaths(Files.createTempDirectory("memory-learner-markdown-test").toFile()),
            clock = clock
        )
        val jobDao = TestMemoryMaintenanceJobDao()
        val indexRebuilder = RecordingMemoryIndexRebuilder()
        val service = MarkdownMemoryLearningService(
            memoryFileStore = fileStore,
            markdownMemoryCodec = MarkdownMemoryCodec(),
            maintenanceScheduler = MemoryMaintenanceScheduler(jobDao, clock),
            memoryIndexRebuilder = indexRebuilder,
            clock = clock
        )
        return MarkdownHarness(
            service = service,
            fileStore = fileStore,
            jobDao = jobDao,
            indexRebuilder = indexRebuilder
        )
    }

    private fun String.countOccurrences(value: String): Int =
        Regex(Regex.escape(value)).findAll(this).count()
}

private data class MarkdownHarness(
    val service: MarkdownMemoryLearningService,
    val fileStore: MemoryFileStore,
    val jobDao: TestMemoryMaintenanceJobDao,
    val indexRebuilder: RecordingMemoryIndexRebuilder
)

private class RecordingMemoryIndexRebuilder : MemoryIndexRebuilder {
    val rebuiltFiles = mutableListOf<File>()

    override suspend fun rebuildFile(file: File): Result<MemoryIndexRebuildResult> {
        rebuiltFiles += file
        return Result.success(MemoryIndexRebuildResult(indexedDocuments = 1, indexedChunks = 1))
    }
}

private class TestMemoryMaintenanceJobDao : MemoryMaintenanceJobDao {
    val jobs = mutableListOf<MemoryMaintenanceJob>()

    override suspend fun getById(jobId: String): MemoryMaintenanceJob? =
        jobs.firstOrNull { it.jobId == jobId }

    override suspend fun getByIdempotencyKey(idempotencyKey: String): MemoryMaintenanceJob? =
        jobs.firstOrNull { it.idempotencyKey == idempotencyKey }

    override suspend fun getVisibleJobs(limit: Int): List<MemoryMaintenanceJob> =
        jobs.sortedByDescending { it.updatedAt }.take(limit)

    override suspend fun getRunnableJobs(
        statuses: List<String>,
        now: Long,
        limit: Int
    ): List<MemoryMaintenanceJob> = jobs
        .filter { job -> job.status in statuses && now >= (job.nextRunAt ?: 0L) }
        .sortedBy { it.createdAt }
        .take(limit)

    override suspend fun getEarliestFutureRunAt(now: Long): Long? =
        jobs
            .filter { job -> job.status in listOf(MemoryMaintenanceJobStatus.PENDING, MemoryMaintenanceJobStatus.FAILED_RETRYABLE) }
            .mapNotNull { job -> job.nextRunAt }
            .filter { nextRunAt -> nextRunAt > now }
            .minOrNull()

    override suspend fun insertIgnore(job: MemoryMaintenanceJob): Long {
        if (jobs.any { it.idempotencyKey == job.idempotencyKey }) return -1L
        jobs += job
        return jobs.size.toLong()
    }

    override suspend fun update(job: MemoryMaintenanceJob) {
        val index = jobs.indexOfFirst { it.jobId == job.jobId }
        if (index != -1) {
            jobs[index] = job
        }
    }

    override suspend fun moveStaleJobs(
        fromStatus: String,
        status: String,
        before: Long,
        lastError: String?,
        updatedAt: Long,
        nextRunAt: Long?
    ): Int {
        var changedCount = 0
        jobs.replaceAll { job ->
            if (job.status == fromStatus && job.updatedAt < before) {
                changedCount += 1
                job.copy(
                    status = status,
                    lastError = lastError,
                    updatedAt = updatedAt,
                    nextRunAt = nextRunAt
                )
            } else {
                job
            }
        }
        return changedCount
    }
}
