package cn.nabr.chatwithchat.data.context

import cn.nabr.chatwithchat.data.model.ClientType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextWindowResolverTest {

    @Test
    fun `openai family prefix resolves from table`() {
        assertEquals(400_000, ModelContextWindowTable.lookup(ClientType.OPENAI, "gpt-5.2"))
    }

    @Test
    fun `longest family prefix wins over shorter one`() {
        // "gpt-5.4" must not be shadowed by the shorter "gpt-5" entry.
        assertEquals(1_000_000, ModelContextWindowTable.lookup(ClientType.OPENAI, "gpt-5.4-turbo"))
    }

    @Test
    fun `unknown openai family falls through to null`() {
        assertNull(ModelContextWindowTable.lookup(ClientType.OPENAI, "gpt-9-unreleased"))
    }

    @Test
    fun `table is not consulted for providers without curated families`() {
        assertNull(ModelContextWindowTable.lookup(ClientType.GROQ, "gpt-5.2"))
        assertNull(ModelContextWindowTable.lookup(ClientType.GOOGLE, "gpt-5.2"))
        assertNull(ModelContextWindowTable.lookup(ClientType.ANTHROPIC, "gpt-5.2"))
        assertNull(ModelContextWindowTable.lookup(ClientType.OPENROUTER, "gpt-5.2"))
    }

    @Test
    fun `custom gateway families resolve`() {
        assertEquals(65_536, ModelContextWindowTable.lookup(ClientType.CUSTOM, "deepseek-chat"))
    }

    @Test
    fun `deepseek v4 families resolve to documented one million token window`() {
        assertEquals(1_000_000, ModelContextWindowTable.lookup(ClientType.CUSTOM, "deepseek-v4-flash"))
        assertEquals(1_000_000, ModelContextWindowTable.lookup(ClientType.CUSTOM, "deepseek-v4-pro"))
    }

    @Test
    fun `custom family lookup ignores gateway path prefix`() {
        assertEquals(65_536, ModelContextWindowTable.lookup(ClientType.CUSTOM, "deepseek/deepseek-chat"))
    }

    @Test
    fun `window is never inferred from digits in the model name`() {
        // A name advertising a huge window proves nothing about what the endpoint accepts.
        assertNull(ModelContextWindowTable.lookup(ClientType.CUSTOM, "some-vendor-1m-ultra"))
        assertNull(ModelContextWindowTable.lookup(ClientType.CUSTOM, "mystery-128k"))
        assertNull(ModelContextWindowTable.lookup(ClientType.OPENAI, "totally-new-2m"))
    }

    @Test
    fun `ollama is never resolved from the table even for a known family name`() {
        assertNull(ModelContextWindowTable.lookup(ClientType.OLLAMA, "gpt-5.2"))
    }

    @Test
    fun `blank model resolves to null`() {
        assertNull(ModelContextWindowTable.lookup(ClientType.OPENAI, "   "))
    }

    @Test
    fun `every client type has a positive fallback window`() {
        ClientType.entries.forEach { clientType ->
            val policy = ProviderContextPolicy.forClientType(clientType)
            assert(policy.fallbackModelContextWindow > 0) {
                "$clientType has a non-positive fallbackModelContextWindow"
            }
        }
    }

    @Test
    fun `fallback window is distinct from the local construction budget for large providers`() {
        // Guards the most dangerous misreading of the plan: using maxContextTokens (a local
        // construction budget) as the model context window would make RAW_PREFERRED compact
        // immediately on providers with genuinely large windows.
        listOf(ClientType.OPENAI, ClientType.ANTHROPIC, ClientType.GOOGLE).forEach { clientType ->
            val policy = ProviderContextPolicy.forClientType(clientType)
            assert(policy.fallbackModelContextWindow > policy.maxContextTokens) {
                "$clientType fallback window should exceed the local construction budget"
            }
        }
    }
}
