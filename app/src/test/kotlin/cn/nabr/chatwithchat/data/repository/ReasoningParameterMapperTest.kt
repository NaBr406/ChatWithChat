package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.model.ReasoningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningParameterMapperTest {
    @Test
    fun `openai responses maps explicit strength to effort`() {
        val params = mapReasoningMode(
            platform = platform(ClientType.OPENAI, "gpt-5.4-mini"),
            requestedMode = ReasoningMode.HIGH
        )

        assertEquals(ReasoningMode.HIGH, params.mode)
        assertEquals("high", params.openAIEffort)
        assertTrue(params.hasExplicitReasoning)
    }

    @Test
    fun `unsupported openai model falls back to auto without parameters`() {
        val params = mapReasoningMode(
            platform = platform(ClientType.OPENAI, "gpt-4o-mini"),
            requestedMode = ReasoningMode.HIGH
        )

        assertEquals(ReasoningMode.AUTO, params.mode)
        assertNull(params.openAIEffort)
    }

    @Test
    fun `anthropic max maps to thinking budget and response headroom`() {
        val params = mapReasoningMode(
            platform = platform(ClientType.ANTHROPIC, "claude-opus-4-20250514"),
            requestedMode = ReasoningMode.MAX
        )

        assertEquals(24_000, params.anthropicBudgetTokens)
        assertEquals(28_096, params.anthropicMaxTokens)
    }

    @Test
    fun `google off maps to zero thinking budget without thoughts`() {
        val params = mapReasoningMode(
            platform = platform(ClientType.GOOGLE, "gemini-2.5-pro"),
            requestedMode = ReasoningMode.OFF
        )

        assertEquals(ReasoningMode.OFF, params.mode)
        assertEquals(0, params.googleThinkingBudget)
        assertNull(params.googleIncludeThoughts)
    }

    @Test
    fun `groq gpt oss uses effort and include reasoning`() {
        val params = mapReasoningMode(
            platform = platform(ClientType.GROQ, "openai/gpt-oss-20b"),
            requestedMode = ReasoningMode.LOW
        )

        assertEquals("low", params.groqReasoningEffort)
        assertEquals(true, params.groqIncludeReasoning)
        assertNull(params.groqReasoningFormat)
    }

    @Test
    fun `groq parsed reasoning model uses parsed format`() {
        val params = mapReasoningMode(
            platform = platform(ClientType.GROQ, "qwen/qwen3-32b"),
            requestedMode = ReasoningMode.MEDIUM
        )

        assertEquals("parsed", params.groqReasoningFormat)
        assertNull(params.groqReasoningEffort)
        assertNull(params.groqIncludeReasoning)
    }

    @Test
    fun `custom ordinary model does not receive compatible reasoning effort`() {
        val params = mapReasoningMode(
            platform = platform(ClientType.CUSTOM, "llama-3.1-8b"),
            requestedMode = ReasoningMode.HIGH
        )

        assertEquals(ReasoningMode.AUTO, params.mode)
        assertNull(params.openAICompatibleReasoningEffort)
    }

    private fun platform(
        clientType: ClientType,
        model: String,
        reasoning: Boolean = false
    ) = PlatformV2(
        name = clientType.name,
        compatibleType = clientType,
        apiUrl = "https://example.test",
        model = model,
        reasoning = reasoning
    )
}
