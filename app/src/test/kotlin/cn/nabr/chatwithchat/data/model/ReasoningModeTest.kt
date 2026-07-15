package cn.nabr.chatwithchat.data.model

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningModeTest {
    @Test
    fun `legacy reasoning flag maps to stable defaults`() {
        assertEquals(ReasoningMode.MEDIUM, ReasoningMode.fromLegacyReasoning(true))
        assertEquals(ReasoningMode.OFF, ReasoningMode.fromLegacyReasoning(false))
    }

    @Test
    fun `platform default reasoning mode is controlled by chat selection`() {
        val platform = platform(ClientType.OPENAI, "gpt-5.4-mini", reasoning = true)

        assertEquals(ReasoningMode.AUTO, platform.defaultReasoningMode())
    }

    @Test
    fun `explicitly unsupported model has no reasoning modes and coerces to auto`() {
        val platform = platform(ClientType.CUSTOM, "gpt-4o-mini")

        assertEquals(ReasoningCapability.UNSUPPORTED, platform.reasoningCapabilityForModel().capability)
        assertTrue(platform.reasoningModesForModel().isEmpty())
        assertEquals(ReasoningMode.AUTO, platform.coerceReasoningModeForModel(ReasoningMode.HIGH))
    }

    @Test
    fun `unknown custom alias stays unknown instead of being reported unsupported`() {
        val platform = platform(ClientType.CUSTOM, "acme-ultra-v7")

        assertEquals(ReasoningCapability.UNKNOWN, platform.reasoningCapabilityForModel().capability)
        assertTrue(platform.reasoningModesForModel().isEmpty())
        assertEquals(ReasoningMode.AUTO, platform.coerceReasoningModeForModel(ReasoningMode.HIGH))
    }

    @Test
    fun `deepseek v4 uses model default reasoning without fabricated effort levels`() {
        val platform = platform(ClientType.CUSTOM, "deepseek-v4")

        assertEquals(ReasoningCapability.DEFAULT_ONLY, platform.reasoningCapabilityForModel().capability)
        assertTrue(platform.reasoningModesForModel().isEmpty())
        assertEquals(ReasoningMode.AUTO, platform.coerceReasoningModeForModel(ReasoningMode.HIGH))
    }

    @Test
    fun `compatible provider model name alone does not imply adjustable effort`() {
        listOf(ClientType.OPENROUTER, ClientType.CUSTOM).forEach { clientType ->
            val platform = platform(clientType, "gpt-5.4-mini")

            assertEquals(ReasoningCapability.DEFAULT_ONLY, platform.reasoningCapabilityForModel().capability)
            assertTrue(platform.reasoningModesForModel().isEmpty())
        }
    }

    @Test
    fun `openai reasoning model exposes user modes without max`() {
        val platform = platform(ClientType.OPENAI, "gpt-5.4-mini")

        assertEquals(ReasoningCapability.EFFORT, platform.reasoningCapabilityForModel().capability)
        assertEquals(
            listOf(
                ReasoningMode.AUTO,
                ReasoningMode.LOW,
                ReasoningMode.MEDIUM,
                ReasoningMode.HIGH
            ),
            platform.reasoningModesForModel()
        )
        assertFalse(ReasoningMode.MAX in platform.reasoningModesForModel())
    }

    @Test
    fun `anthropic thinking model exposes max mode`() {
        val platform = platform(ClientType.ANTHROPIC, "claude-sonnet-4-20250514")

        assertEquals(ReasoningCapability.EFFORT, platform.reasoningCapabilityForModel().capability)
        assertTrue(ReasoningMode.MAX in platform.reasoningModesForModel())
        assertTrue(ReasoningMode.OFF in platform.reasoningModesForModel())
    }

    @Test
    fun `groq parsed reasoning model exposes toggle semantics`() {
        val platform = platform(ClientType.GROQ, "qwen/qwen3-32b")

        assertEquals(ReasoningCapability.TOGGLE, platform.reasoningCapabilityForModel().capability)
        assertEquals(
            listOf(ReasoningMode.AUTO, ReasoningMode.OFF, ReasoningMode.MEDIUM),
            platform.reasoningModesForModel()
        )
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
