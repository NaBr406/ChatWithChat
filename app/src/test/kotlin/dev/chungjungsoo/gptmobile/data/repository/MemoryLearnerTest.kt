package dev.chungjungsoo.gptmobile.data.repository

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.memory.FakeMemoryIntelligence
import dev.chungjungsoo.gptmobile.data.memory.InMemoryChatClassificationDao
import dev.chungjungsoo.gptmobile.data.memory.InMemoryPersonalMemoryDao
import dev.chungjungsoo.gptmobile.data.memory.MemoryAction
import dev.chungjungsoo.gptmobile.data.memory.MemoryLearningResult
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        assertEquals(MemoryLearningResult.STATUS_APPLIED_DIRECT_FALLBACK, result.status)
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

    private fun createRepository(
        personalMemoryDao: InMemoryPersonalMemoryDao,
        intelligence: FakeMemoryIntelligence
    ): MemoryRepositoryImpl = MemoryRepositoryImpl(
        personalMemoryDao = personalMemoryDao,
        chatClassificationDao = InMemoryChatClassificationDao(),
        memoryIntelligence = intelligence,
        memoryPromptBuilder = MemoryPromptBuilder(),
        memoryMarkdownCodec = MemoryMarkdownCodec()
    )

    private fun chatRoom() = ChatRoomV2(id = 1, title = "Chat", enabledPlatform = listOf("platform"))

    private fun userMessages(content: String) = listOf(MessageV2(chatId = 1, content = content, platformType = null))
}
