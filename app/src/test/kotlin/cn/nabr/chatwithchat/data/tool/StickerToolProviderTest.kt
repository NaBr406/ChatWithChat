package cn.nabr.chatwithchat.data.tool

import android.net.Uri
import cn.nabr.chatwithchat.data.sticker.StickerCatalogItem
import cn.nabr.chatwithchat.data.sticker.StickerImportBatchResult
import cn.nabr.chatwithchat.data.sticker.StickerItemMetadata
import cn.nabr.chatwithchat.data.sticker.StickerPresentationArtifact
import cn.nabr.chatwithchat.data.sticker.StickerRepository
import cn.nabr.chatwithchat.data.sticker.StickerResolution
import cn.nabr.chatwithchat.data.sticker.StickerSearchCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class StickerToolProviderTest {
    @Test
    fun `search returns at most six bounded candidates with ids in fallback content`() = runBlocking {
        val candidates = (1..7).map { index ->
            StickerSearchCandidate(
                stickerId = "builtin.reactions.$index",
                title = "Reaction $index",
                altText = "Reaction alt text $index",
                tags = listOf("reaction", "tag$index")
            )
        }
        val repository = FakeStickerRepository(candidates = candidates)
        val provider = SearchStickersToolProvider(repository)

        val result = provider.execute(
            ToolCall("call_search", "search_stickers", """{"query":"reaction","limit":6}"""),
            ToolLoopConfig.Default
        )

        assertFalse(result.isError)
        assertEquals("reaction", repository.searchQuery)
        assertEquals(6, repository.searchLimit)
        assertEquals(1, repository.ensureInitializedCalls)
        assertTrue(result.content.contains("sticker_id=builtin.reactions.1"))
        assertTrue(result.content.contains("sticker_id=builtin.reactions.6"))
        assertFalse(result.content.contains("builtin.reactions.7"))
        val structuredCandidates = result.structuredContent
            ?.jsonObject
            ?.get("candidates")
            ?.jsonArray
            .orEmpty()
        assertEquals(6, structuredCandidates.size)
        assertEquals(
            "builtin.reactions.1",
            structuredCandidates.first().jsonObject.getValue("sticker_id").jsonPrimitive.contentOrNull
        )
    }

    @Test
    fun `successful send exposes one local artifact without serializing asset data`() = runBlocking {
        val artifact = StickerPresentationArtifact(
            instanceId = "call_send",
            stickerId = "builtin.reactions.crying_cat",
            assetKey = "a".repeat(64),
            altText = "Crying cat"
        )
        val repository = FakeStickerRepository(resolution = StickerResolution.Success(artifact))
        val provider = SendStickerToolProvider(repository)

        val result = provider.execute(
            ToolCall(
                "call_send",
                "send_sticker",
                """{"sticker_id":"builtin.reactions.crying_cat"}"""
            ),
            ToolLoopConfig.Default
        )
        val serialized = toolProtocolJson.encodeToString(result)

        assertFalse(result.isError)
        assertTrue(result.content.contains("builtin.reactions.crying_cat"))
        assertFalse(result.content.contains(artifact.assetKey))
        assertEquals(listOf(artifact), provider.presentationArtifacts(result))
        assertFalse(serialized.contains(artifact.assetKey))
        assertFalse(serialized.contains("presentationArtifacts"))
    }

    @Test
    fun `unavailable send produces bounded error without artifact`() = runBlocking {
        val repository = FakeStickerRepository(
            resolution = StickerResolution.Unavailable("sticker_not_found")
        )
        val provider = SendStickerToolProvider(repository)

        val result = provider.execute(
            ToolCall("call_missing", "send_sticker", """{"sticker_id":"missing"}"""),
            ToolLoopConfig.Default
        )

        assertTrue(result.isError)
        assertEquals("sticker_not_found", result.content)
        assertEquals("sticker_not_found", result.metadata["error_code"])
        assertTrue(provider.presentationArtifacts(result).isEmpty())
    }

    @Test
    fun `mismatched successful artifact is rejected without presentation`() = runBlocking {
        val repository = FakeStickerRepository(
            resolution = StickerResolution.Success(
                StickerPresentationArtifact(
                    instanceId = "different_call",
                    stickerId = "builtin.reactions.crying_cat",
                    assetKey = "a".repeat(64),
                    altText = "Crying cat"
                )
            )
        )
        val provider = SendStickerToolProvider(repository)

        val result = provider.execute(
            ToolCall(
                "call_send",
                "send_sticker",
                """{"sticker_id":"builtin.reactions.crying_cat"}"""
            ),
            ToolLoopConfig.Default
        )

        assertTrue(result.isError)
        assertEquals("sticker_unavailable", result.content)
        assertTrue(provider.presentationArtifacts(result).isEmpty())
    }

    @Test
    fun `send policy permits one artifact per assistant request`() = runBlocking {
        val repository = FakeStickerRepository(
            resolutionFactory = { stickerId, instanceId ->
                StickerResolution.Success(
                    StickerPresentationArtifact(
                        instanceId = instanceId,
                        stickerId = stickerId,
                        assetKey = "b".repeat(64),
                        altText = "Sticker $stickerId"
                    )
                )
            }
        )
        val provider = SendStickerToolProvider(repository)
        val orchestrator = ToolLoopOrchestrator(ToolExecutor(ToolRegistry(listOf(provider))))
        val session = orchestrator.createExecutionSession().also { executionSession ->
            executionSession.state.replaceValues(STICKER_CANDIDATE_IDS_SESSION_KEY, listOf("one", "two"))
        }

        val results = orchestrator.executeToolCalls(
            calls = listOf(
                ToolCall("call_one", "send_sticker", """{"sticker_id":"one"}"""),
                ToolCall("call_two", "send_sticker", """{"sticker_id":"two"}""")
            ),
            tools = listOf(provider.definition),
            executionSession = session
        )

        assertFalse(results.first().isError)
        assertTrue(results.last().isError)
        assertTrue(results.last().content.contains("max_sticker_sends_per_request"))
        assertEquals(listOf("call_one"), orchestrator.presentationArtifacts(results).map { artifact -> artifact.instanceId })
    }

    @Test
    fun `send policy rejects a second tool round in the same assistant request`() = runBlocking {
        val repository = FakeStickerRepository(
            resolutionFactory = { stickerId, instanceId ->
                StickerResolution.Success(
                    StickerPresentationArtifact(
                        instanceId = instanceId,
                        stickerId = stickerId,
                        assetKey = "c".repeat(64),
                        altText = "Sticker $stickerId"
                    )
                )
            }
        )
        val provider = SendStickerToolProvider(repository)
        val orchestrator = ToolLoopOrchestrator(ToolExecutor(ToolRegistry(listOf(provider))))
        val session = orchestrator.createExecutionSession().also { executionSession ->
            executionSession.state.replaceValues(STICKER_CANDIDATE_IDS_SESSION_KEY, listOf("one", "two"))
        }

        val firstRound = orchestrator.executeToolCalls(
            calls = listOf(ToolCall("call_one", "send_sticker", "{\"sticker_id\":\"one\"}")),
            tools = listOf(provider.definition),
            executionSession = session
        )
        val secondRound = orchestrator.executeToolCalls(
            calls = listOf(ToolCall("call_two", "send_sticker", "{\"sticker_id\":\"two\"}")),
            tools = listOf(provider.definition),
            executionSession = session
        )

        assertFalse(firstRound.single().isError)
        assertTrue(secondRound.single().isError)
        assertTrue(secondRound.single().content.contains("max_sticker_sends_per_request"))
        assertEquals(
            listOf("call_one"),
            orchestrator.presentationArtifacts(firstRound + secondRound).map { artifact -> artifact.instanceId }
        )
    }

    @Test
    fun `send requires a candidate returned by search in the same execution session`() = runBlocking {
        val candidateId = "builtin.reactions.crying_cat"
        val repository = FakeStickerRepository(
            candidates = listOf(
                StickerSearchCandidate(
                    stickerId = candidateId,
                    title = "Crying cat",
                    altText = "Crying cat",
                    tags = listOf("sad")
                )
            ),
            resolutionFactory = { stickerId, instanceId ->
                StickerResolution.Success(
                    StickerPresentationArtifact(
                        instanceId = instanceId,
                        stickerId = stickerId,
                        assetKey = "d".repeat(64),
                        altText = "Crying cat"
                    )
                )
            }
        )
        val searchProvider = SearchStickersToolProvider(repository)
        val sendProvider = SendStickerToolProvider(repository)
        val orchestrator = ToolLoopOrchestrator(
            ToolExecutor(ToolRegistry(listOf(searchProvider, sendProvider)))
        )
        val session = orchestrator.createExecutionSession()

        val searchResults = orchestrator.executeToolCalls(
            calls = listOf(ToolCall("call_search", "search_stickers", "{\"query\":\"sad\"}")),
            tools = listOf(searchProvider.definition, sendProvider.definition),
            executionSession = session
        )
        val sendResults = orchestrator.executeToolCalls(
            calls = listOf(ToolCall("call_send", "send_sticker", "{\"sticker_id\":\"$candidateId\"}")),
            tools = listOf(searchProvider.definition, sendProvider.definition),
            executionSession = session
        )
        val forgedResults = orchestrator.executeToolCalls(
            calls = listOf(ToolCall("call_forged", "send_sticker", "{\"sticker_id\":\"builtin.reactions.other\"}")),
            tools = listOf(searchProvider.definition, sendProvider.definition),
            executionSession = orchestrator.createExecutionSession()
        )

        assertFalse(searchResults.single().isError)
        assertFalse(sendResults.single().isError)
        assertTrue(forgedResults.single().isError)
        assertEquals("sticker_not_found", forgedResults.single().metadata["error_code"])
        assertTrue(orchestrator.presentationArtifacts(forgedResults).isEmpty())
    }

    @Test
    fun `json fallback carries returned candidate ids into the next tool round`() = runBlocking {
        val candidateId = "builtin.reactions.heartbroken"
        val repository = FakeStickerRepository(
            candidates = listOf(
                StickerSearchCandidate(
                    stickerId = candidateId,
                    title = "Heartbroken",
                    altText = "Heartbroken penguin",
                    tags = listOf("sad")
                )
            ),
            resolutionFactory = { stickerId, instanceId ->
                StickerResolution.Success(
                    StickerPresentationArtifact(
                        instanceId = instanceId,
                        stickerId = stickerId,
                        assetKey = "e".repeat(64),
                        altText = "Heartbroken penguin"
                    )
                )
            }
        )
        val searchProvider = SearchStickersToolProvider(repository)
        val sendProvider = SendStickerToolProvider(repository)
        val orchestrator = ToolLoopOrchestrator(
            ToolExecutor(ToolRegistry(listOf(searchProvider, sendProvider)))
        )
        val responses = mutableListOf(
            """{"type":"tool_calls","tool_calls":[{"id":"call_search","name":"search_stickers","arguments":{"query":"sad"}}]}""",
            """{"type":"tool_calls","tool_calls":[{"id":"call_send","name":"send_sticker","arguments":{"sticker_id":"$candidateId"}}]}""",
            """{"type":"final_answer","content":"I understand."}"""
        )

        val result = orchestrator.runLoop(
            tools = listOf(searchProvider.definition, sendProvider.definition)
        ) {
            Result.success(responses.removeAt(0))
        }

        assertTrue(result is ToolLoopResult.ToolResults)
        val toolResults = (result as ToolLoopResult.ToolResults).results
        assertFalse(toolResults.single { it.name == "search_stickers" }.isError)
        assertFalse(toolResults.single { it.name == "send_sticker" }.isError)
        assertEquals(
            listOf("call_send"),
            orchestrator.presentationArtifacts(toolResults).map { artifact -> artifact.instanceId }
        )
    }
}

private class FakeStickerRepository(
    private val candidates: List<StickerSearchCandidate> = emptyList(),
    private val resolution: StickerResolution = StickerResolution.Unavailable("sticker_unavailable"),
    private val resolutionFactory: ((String, String) -> StickerResolution)? = null
) : StickerRepository {
    var ensureInitializedCalls: Int = 0
    var searchQuery: String? = null
    var searchLimit: Int? = null

    override fun observeCatalog(): Flow<List<StickerCatalogItem>> = flowOf(emptyList())

    override suspend fun ensureInitialized() {
        ensureInitializedCalls += 1
    }

    override suspend fun importStaticImages(uris: List<Uri>): StickerImportBatchResult =
        StickerImportBatchResult(emptyList(), emptyList())

    override suspend fun updateCustomItem(stickerId: String, metadata: StickerItemMetadata): Boolean = false

    override suspend fun setCustomItemEnabled(stickerId: String, enabled: Boolean): Boolean = false

    override suspend fun deleteCustomItem(stickerId: String): Boolean = false

    override suspend fun searchEnabledStatic(query: String, limit: Int): List<StickerSearchCandidate> {
        searchQuery = query
        searchLimit = limit
        return candidates
    }

    override suspend fun resolveEnabledStatic(stickerId: String, instanceId: String): StickerResolution =
        resolutionFactory?.invoke(stickerId, instanceId) ?: resolution

    override suspend fun openAsset(assetKey: String): InputStream? = null
}
