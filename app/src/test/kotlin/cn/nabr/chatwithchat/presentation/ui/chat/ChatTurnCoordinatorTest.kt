package cn.nabr.chatwithchat.presentation.ui.chat

import android.content.ContextWrapper
import cn.nabr.chatwithchat.data.context.ContextCompactionModelClient
import cn.nabr.chatwithchat.data.context.ContextCompactionModelResponse
import cn.nabr.chatwithchat.data.database.entity.ChatRoom
import cn.nabr.chatwithchat.data.database.entity.ChatRoomV2
import cn.nabr.chatwithchat.data.database.entity.Message
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.dto.ApiState
import cn.nabr.chatwithchat.data.memory.MemoryCompletedTurnInput
import cn.nabr.chatwithchat.data.memory.MemoryTurnRecordingResult
import cn.nabr.chatwithchat.data.memory.PreparedMemoryContext
import cn.nabr.chatwithchat.data.model.ChatPlatformConfig
import cn.nabr.chatwithchat.data.model.ReasoningMode
import cn.nabr.chatwithchat.data.repository.ChatRepository
import cn.nabr.chatwithchat.data.repository.MemoryRepository
import cn.nabr.chatwithchat.data.textskill.ChatRequestContext
import cn.nabr.chatwithchat.data.title.ChatTitleGenerationService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guarantee this class exists for: **a turn's result is persisted whether or not a screen is still
 * watching.**
 *
 * Before the coordinator, the save hung off a `_loadingStates` collector inside `ChatViewModel`, so
 * leaving a chat destroyed the answer twice over — the stream was cancelled, and the thing that would
 * have written what survived died in the same breath. Every test here is about a way that can still go
 * wrong: nobody subscribed, two turns racing for one chat, a chat deleted underneath, a rename landing
 * mid-turn.
 */
class ChatTurnCoordinatorTest {

    @Test
    fun `a turn is persisted with no subscribers at all`() = runBlocking {
        val repository = RecordingChatRepository()
        val coordinator = coordinator(repository)

        val turn = coordinator.start(key = "chat:7", request = request()) { turn ->
            turn.messagesFlow.value = oneTurn("streamed answer")
        }
        turn.awaitFinished()

        assertEquals(1, repository.savedChats.size)
        assertEquals(
            listOf("ask", "streamed answer"),
            repository.savedChats.single().second.map { it.content }
        )
    }

    @Test
    fun `a second turn under the same key is refused`() = runBlocking {
        val repository = RecordingChatRepository()
        val coordinator = coordinator(repository)
        val firstReleased = CompletableDeferred<Unit>()

        val first = coordinator.start(key = "chat:7", request = request()) {
            firstReleased.await()
        }
        val second = coordinator.start(key = "chat:7", request = request()) {
            error("the second turn must never run")
        }

        assertNotNull(first)
        assertNull("two turns in one chat would each save the whole message list", second)
        firstReleased.complete(Unit)
        first.awaitFinished()
        assertEquals(1, repository.savedChats.size)
    }

    @Test
    fun `turns in different chats run independently`() = runBlocking {
        val repository = RecordingChatRepository()
        val coordinator = coordinator(repository)

        val seven = coordinator.start("chat:7", request()) { it.messagesFlow.value = oneTurn("a") }
        val nine = coordinator.start("chat:9", request(chatId = 9)) { it.messagesFlow.value = oneTurn("b") }
        seven.awaitFinished()
        nine.awaitFinished()

        assertEquals(2, repository.savedChats.size)
        assertEquals(setOf(7, 9), repository.savedChats.map { it.first.id }.toSet())
    }

    /**
     * Stopping keeps the partial answer, so it has to reach the database too — otherwise stop would be
     * the same silent loss that leaving the chat used to be.
     */
    @Test
    fun `a stopped turn still persists what it produced`() = runBlocking {
        val repository = RecordingChatRepository()
        val coordinator = coordinator(repository)
        val streamed = CompletableDeferred<Unit>()

        val turn = coordinator.start(key = "chat:7", request = request()) { turn ->
            turn.messagesFlow.value = oneTurn("half an answer")
            streamed.complete(Unit)
            awaitCancellation()
        }
        streamed.await()
        coordinator.stop("chat:7")
        turn.awaitFinished()

        assertEquals(1, repository.savedChats.size)
        assertTrue(repository.savedChats.single().second.any { it.content == "half an answer" })
    }

    /** A chat the user removed must not come back: `saveChat` upserts the row it is given. */
    @Test
    fun `a turn whose chat was deleted is dropped without saving`() = runBlocking {
        val repository = RecordingChatRepository()
        val coordinator = coordinator(repository)
        val streamed = CompletableDeferred<Unit>()

        val turn = coordinator.start(key = "chat:7", request = request()) { turn ->
            turn.messagesFlow.value = oneTurn("answer nobody wants")
            streamed.complete(Unit)
            awaitCancellation()
        }
        streamed.await()
        coordinator.cancelForChats(setOf(7))
        turn.awaitFinished()

        assertEquals(emptyList<Any>(), repository.savedChats)
        assertFalse(coordinator.isLive("chat:7"))
    }

    /**
     * The other half of the same hazard: the row is gone by the time the turn finishes on its own, so
     * there is no cancellation to notice it and the save has to check.
     */
    @Test
    fun `a chat deleted while the turn ran is not recreated by the save`() = runBlocking {
        val repository = RecordingChatRepository(existingChatIds = emptySet())
        val coordinator = coordinator(repository)

        val turn = coordinator.start(key = "chat:7", request = request()) { turn ->
            turn.messagesFlow.value = oneTurn("answer")
        }
        turn.awaitFinished()

        assertEquals(emptyList<Any>(), repository.savedChats)
    }

    /**
     * `saveChat` writes the whole chat row, so a title captured when the turn started would quietly undo
     * a rename made while it ran. This is why the request carries flows rather than values.
     */
    @Test
    fun `a rename during the turn is carried into the save`() = runBlocking {
        val repository = RecordingChatRepository()
        val coordinator = coordinator(repository)
        val chatRoom = MutableStateFlow(ChatRoomV2(id = 7, title = "before", enabledPlatform = listOf("p")))
        val streamed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val turn = coordinator.start(key = "chat:7", request = request(chatRoom = chatRoom)) { turn ->
            turn.messagesFlow.value = oneTurn("answer")
            streamed.complete(Unit)
            release.await()
        }
        streamed.await()
        chatRoom.value = chatRoom.value.copy(title = "renamed midway")
        release.complete(Unit)
        turn.awaitFinished()

        assertEquals("renamed midway", repository.savedChats.single().first.title)
    }

    @Test
    fun `a finished turn stops being live and leaves the running set`() = runBlocking {
        val repository = RecordingChatRepository()
        val coordinator = coordinator(repository)
        val release = CompletableDeferred<Unit>()

        val turn = coordinator.start(key = "chat:7", request = request()) { turn ->
            turn.messagesFlow.value = oneTurn("answer")
            release.await()
        }
        assertTrue(coordinator.isLive("chat:7"))
        assertEquals(setOf(7), coordinator.runningChatIds.value)

        release.complete(Unit)
        turn.awaitFinished()

        assertFalse(coordinator.isLive("chat:7"))
        assertEquals(emptySet<Int>(), coordinator.runningChatIds.value)
    }

    /** A draft chat has no row in the list, so it must not show an indicator against a bogus id. */
    @Test
    fun `a draft turn never appears in the running chat ids`() = runBlocking {
        val coordinator = coordinator(RecordingChatRepository())
        val release = CompletableDeferred<Unit>()

        val turn = coordinator.start(key = "draft:abc", request = request(chatId = 0)) { release.await() }

        assertTrue(coordinator.isLive("draft:abc"))
        assertEquals(emptySet<Int>(), coordinator.runningChatIds.value)
        release.complete(Unit)
        turn.awaitFinished()
    }

    /**
     * A half-assembled turn — a user message without its assistant row — cannot be grouped back into
     * turns by the reader, so it must not be stored.
     */
    @Test
    fun `a mismatched message list is not persisted`() = runBlocking {
        val repository = RecordingChatRepository()
        val coordinator = coordinator(repository)

        val turn = coordinator.start(key = "chat:7", request = request()) { turn ->
            turn.messagesFlow.value = ChatViewModel.GroupedMessages(
                userMessages = listOf(MessageV2(content = "ask", platformType = null)),
                assistantMessages = emptyList()
            )
        }
        turn.awaitFinished()

        assertEquals(emptyList<Any>(), repository.savedChats)
    }

    @Test
    fun `memory is recorded only when the turn ran with it enabled`() = runBlocking {
        val memory = RecordingMemoryRepository()
        val coordinator = coordinator(RecordingChatRepository(), memory)

        coordinator.start(key = "chat:7", request = request(memoryActivityAt = 100L)) { turn ->
            turn.memoryEnabled = false
            turn.messagesFlow.value = oneTurn("answer")
        }.awaitFinished()
        assertEquals(0, memory.recordedTurns)

        coordinator.start(key = "chat:9", request = request(chatId = 9, memoryActivityAt = 100L)) { turn ->
            turn.memoryEnabled = true
            turn.messagesFlow.value = oneTurn("answer")
        }.awaitFinished()
        assertEquals(1, memory.recordedTurns)
    }

    /**
     * The collision this guards against is reachable by hand: send in a new chat, go back before the
     * save, start another new chat. Both have id `0`, so an id-only key would hand the second chat the
     * first one's running turn.
     */
    @Test
    fun `an unsaved chat keys on its own nonce, a saved one on its id`() {
        assertEquals("chat:7", turnKeyFor(chatRoomId = 7, draftNonce = "nonce-a"))
        assertEquals("draft:nonce-a", turnKeyFor(chatRoomId = 0, draftNonce = "nonce-a"))
        assertNotEquals(
            turnKeyFor(chatRoomId = 0, draftNonce = "nonce-a"),
            turnKeyFor(chatRoomId = 0, draftNonce = "nonce-b")
        )
    }

    /** The same chat keeps one key across turns, which is what makes one-turn-per-chat enforceable. */
    @Test
    fun `the same saved chat always keys the same`() {
        assertEquals(
            turnKeyFor(chatRoomId = 7, draftNonce = "nonce-a"),
            turnKeyFor(chatRoomId = 7, draftNonce = "nonce-b")
        )
    }

    @Test
    fun `only saved chats surface as running chat ids`() {
        assertEquals(
            setOf(7, 9),
            runningChatIdsFrom(listOf("chat:7", "draft:abc", "chat:9", "chat:not-a-number"))
        )
        assertEquals(emptySet<Int>(), runningChatIdsFrom(emptyList()))
    }

    @Test
    fun `entering a chat yields the database read only when a turn is live`() {
        assertTrue(shouldYieldRoomLoad(hasLiveTurn = true))
        assertFalse(shouldYieldRoomLoad(hasLiveTurn = false))
    }

    /**
     * The sending screen subscribes before the turn exists.
     *
     * A new chat is saved first and only then starts its turn, so the screen has already begun watching
     * `chat:<id>` by the time anything is registered under it. If the flow only reported turns that
     * existed at subscription time, that screen would sit on the user's message while the answer streamed
     * into a flow nobody was reading.
     */
    @Test
    fun `a key can be watched before its turn exists`() = runBlocking {
        val coordinator = coordinator(RecordingChatRepository())
        val seen = mutableListOf<String?>()
        val watching = launch {
            coordinator.liveTurn("chat:7").collect { turn -> seen += turn?.key }
        }
        while (seen.isEmpty()) yield()
        assertEquals(listOf<String?>(null), seen)

        val release = CompletableDeferred<Unit>()
        val turn = coordinator.start(key = "chat:7", request = request()) { release.await() }
        while (seen.size < 2) yield()
        assertEquals("the turn must reach a subscriber that arrived first", "chat:7", seen[1])

        release.complete(Unit)
        turn.awaitFinished()
        while (seen.size < 3) yield()
        assertEquals("and it must be withdrawn again once finished", listOf(null, "chat:7", null), seen)
        watching.cancel()
    }

    /**
     * Waits for a turn to finish, including its save.
     *
     * Joins the turn's own job rather than advancing a virtual clock: this project has no
     * `kotlinx-coroutines-test` dependency, and joining is the stricter check anyway — it waits for real
     * completion, save included, not for a scheduler to look idle.
     */
    private suspend fun ChatTurnCoordinator.LiveTurn?.awaitFinished() {
        assertNotNull("the turn should have started", this)
        this?.job?.join()
    }

    /**
     * Mirrors the production `@ApplicationScope`: `SupervisorJob() + Dispatchers.Default`.
     *
     * Deliberately not the test's own `runBlocking` scope. `runningChatIds` is eagerly shared, so its
     * collector never completes, and `runBlocking` waits for every child of its scope before returning —
     * handing it this scope hangs the test rather than failing it. Turns are awaited through their own
     * job instead, which is what [awaitFinished] does.
     */
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        coordinatorScope.cancel()
    }

    private fun coordinator(
        repository: ChatRepository,
        memory: MemoryRepository = RecordingMemoryRepository()
    ) = ChatTurnCoordinator(
        context = ContextWrapper(null),
        chatRepository = repository,
        memoryRepository = memory,
        chatTitleGenerationService = ChatTitleGenerationService(
            ContextCompactionModelClient { _, _, _, _, _ -> Result.success(ContextCompactionModelResponse("")) }
        ),
        applicationScope = coordinatorScope
    )

    private fun request(
        chatId: Int = 7,
        chatRoom: MutableStateFlow<ChatRoomV2>? = null,
        memoryActivityAt: Long? = null
    ) = ChatTurnCoordinator.TurnRequest(
        chatRoom = chatRoom
            ?: MutableStateFlow(ChatRoomV2(id = chatId, title = "chat", enabledPlatform = listOf("p"))),
        chatPlatformModels = MutableStateFlow(emptyMap()),
        seedMessages = oneTurn(""),
        seedLoadingStates = listOf(ChatViewModel.LoadingState.Loading),
        turnIndex = 0,
        platformIndex = 0,
        memoryActivityAt = memoryActivityAt,
        preferredMemoryPlatform = null
    )

    private fun oneTurn(answer: String) = ChatViewModel.GroupedMessages(
        userMessages = listOf(MessageV2(content = "ask", platformType = null, createdAt = 1)),
        assistantMessages = listOf(listOf(MessageV2(content = answer, platformType = "p", createdAt = 2)))
    )

    private class RecordingChatRepository(
        private val existingChatIds: Set<Int> = setOf(7, 9)
    ) : ChatRepository {
        val savedChats = mutableListOf<Pair<ChatRoomV2, List<MessageV2>>>()

        override suspend fun saveChat(
            chatRoom: ChatRoomV2,
            messages: List<MessageV2>,
            chatPlatformModels: Map<String, ChatPlatformConfig>
        ): ChatRoomV2 {
            savedChats += chatRoom to messages
            return chatRoom.copy(id = if (chatRoom.id == 0) 42 else chatRoom.id, updatedAt = 999)
        }

        override suspend fun fetchChatListV2(): List<ChatRoomV2> = existingChatIds.map { id ->
            ChatRoomV2(id = id, title = "chat", enabledPlatform = listOf("p"))
        }

        override suspend fun fetchMessagesV2(chatId: Int): List<MessageV2> =
            savedChats.lastOrNull()?.second.orEmpty()

        override fun generateDefaultChatTitle(messages: List<MessageV2>): String? = null

        override suspend fun completeChat(
            userMessages: List<MessageV2>,
            assistantMessages: List<List<MessageV2>>,
            platform: PlatformV2,
            requestContext: ChatRequestContext,
            reasoningMode: ReasoningMode
        ): Flow<ApiState> = emptyFlow()

        override suspend fun fetchChatList(): List<ChatRoom> = unused()
        override suspend fun searchChatsV2(query: String): List<ChatRoomV2> = unused()
        override suspend fun fetchMessages(chatId: Int): List<Message> = unused()
        override suspend fun fetchChatPlatformModels(chatId: Int): Map<String, ChatPlatformConfig> = unused()
        override suspend fun saveChatPlatformModels(chatId: Int, models: Map<String, ChatPlatformConfig>) = Unit
        override suspend fun migrateToChatRoomV2MessageV2() = unused()
        override suspend fun updateChatTitle(chatRoom: ChatRoomV2, title: String) = unused()
        override suspend fun duplicateChatV2(chatRoom: ChatRoomV2): ChatRoomV2 = unused()
        override suspend fun deleteChats(chatRooms: List<ChatRoom>) = unused()
        override suspend fun deleteChatsV2(chatRooms: List<ChatRoomV2>) = unused()

        private fun unused(): Nothing = throw AssertionError("finishing a turn must not reach this")
    }

    private class RecordingMemoryRepository : MemoryRepository {
        var recordedTurns = 0
            private set

        override suspend fun recordUserActivity(chatId: Int, activityAt: Long) = Unit

        override suspend fun recordCompletedTurn(input: MemoryCompletedTurnInput): MemoryTurnRecordingResult {
            recordedTurns += 1
            return MemoryTurnRecordingResult(recorded = true)
        }

        override suspend fun onMemoryEnabledChanged(enabled: Boolean) = Unit

        override suspend fun prepareMemoryContext(
            chatRoom: ChatRoomV2,
            userMessages: List<MessageV2>,
            assistantMessages: List<List<MessageV2>>,
            memoryPlatform: PlatformV2?
        ): PreparedMemoryContext = PreparedMemoryContext()

        override suspend fun getLongTermMarkdown(): String = ""
        override fun observeLongTermMarkdown(): Flow<String> = emptyFlow()
    }
}
