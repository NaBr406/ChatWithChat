package cn.nabr.chatwithchat.data.model

import cn.nabr.chatwithchat.data.context.NativeCompactionModelGate
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.network.providerEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiProtocolTest {
    private fun platform(type: ClientType) = PlatformV2(name = "Saved", compatibleType = type, apiUrl = "https://api.openai.com/", model = "gpt-5.4")

    @Test
    fun `legacy protocols retain their wire format without changing identity`() {
        ClientType.entries.forEach { type ->
            val saved = platform(type)
            assertEquals(type, saved.requestClientType)
            assertEquals(saved.uid, saved.copy(apiProtocol = ApiProtocol.ANTHROPIC_MESSAGES).uid)
        }
        assertEquals(ApiProtocol.OPENAI_RESPONSES, platform(ClientType.OPENAI).resolvedApiProtocol)
        assertEquals(ApiProtocol.GOOGLE_GENERATIVE_AI, platform(ClientType.GOOGLE).resolvedApiProtocol)
        assertEquals(3, ApiProtocol.selectable.size)
        assertFalse(ApiProtocol.GOOGLE_GENERATIVE_AI in ApiProtocol.selectable)
    }

    @Test
    fun `explicit protocol overrides saved brand in every request route`() {
        val saved = platform(ClientType.OPENAI)
        assertEquals(ClientType.CUSTOM, saved.copy(apiProtocol = ApiProtocol.OPENAI_CHAT_COMPLETIONS).requestClientType)
        assertEquals(ClientType.ANTHROPIC, saved.copy(apiProtocol = ApiProtocol.ANTHROPIC_MESSAGES).requestClientType)
        assertFalse(NativeCompactionModelGate.supportsServerSideCompaction(saved.copy(apiProtocol = ApiProtocol.OPENAI_CHAT_COMPLETIONS)))
        assertFalse(NativeCompactionModelGate.supportsServerSideCompaction(saved.copy(apiUrl = "https://relay.example/v1")))
        assertTrue(NativeCompactionModelGate.supportsServerSideCompaction(saved))
    }

    @Test
    fun `custom chat protocol preserves glm path and optional local credentials`() {
        val glm = platform(ClientType.CUSTOM).copy(apiUrl = "https://open.bigmodel.cn/api/paas/v4/", apiProtocol = ApiProtocol.OPENAI_CHAT_COMPLETIONS)
        assertEquals(ClientType.GLM, glm.requestClientType)
        assertEquals("", glm.providerEndpoint().apiPathPrefix)
        assertTrue(platform(ClientType.CUSTOM).acceptsOptionalApiKey)
    }
}
