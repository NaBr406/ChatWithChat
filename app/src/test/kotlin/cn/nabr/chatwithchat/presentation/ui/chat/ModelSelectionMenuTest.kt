package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.model.ReasoningCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionMenuTest {
    @Test
    fun `popup width stays inside compact and freeform viewports`() {
        assertEquals(216, modelSelectionPopupWidthDp(screenWidthDp = 240))
        assertEquals(296, modelSelectionPopupWidthDp(screenWidthDp = 320))
        assertEquals(336, modelSelectionPopupWidthDp(screenWidthDp = 360))
        assertEquals(400, modelSelectionPopupWidthDp(screenWidthDp = 600))
    }

    @Test
    fun `top bar hides secondary summary at accessibility font scales`() {
        assertTrue(modelTriggerShowsReasoningSummary(fontScale = 1.3f))
        assertFalse(modelTriggerShowsReasoningSummary(fontScale = 1.5f))
        assertFalse(modelTriggerShowsReasoningSummary(fontScale = 2f))
    }

    @Test
    fun `shared options select exact provider model pair and retain duplicate subtitles`() {
        val first = availableModel("provider-a", "Provider A", "shared-model")
        val second = availableModel("provider-b", "Provider B", "shared-model")

        val options = buildModelSelectionOptions(
            models = listOf(first, second),
            selectedPlatformUid = "provider-b",
            selectedModel = "shared-model"
        )

        assertFalse(options[0].selected)
        assertTrue(options[1].selected)
        assertEquals("Provider A", options[0].subtitle)
        assertEquals("Provider B", options[1].subtitle)
    }

    @Test
    fun `shared options expose default-only reasoning capability`() {
        val options = buildModelSelectionOptions(
            models = listOf(availableModel("provider-a", "Provider A", "deepseek-v4")),
            selectedPlatformUid = "provider-a",
            selectedModel = "deepseek-v4"
        )

        assertEquals(ReasoningCapability.DEFAULT_ONLY, options.single().reasoningCapability.capability)
        assertTrue(options.single().reasoningCapability.supportedModes.isEmpty())
    }

    private fun availableModel(
        platformUid: String,
        platformName: String,
        modelId: String
    ): AvailableChatModel {
        val platform = PlatformV2(
            uid = platformUid,
            name = platformName,
            compatibleType = ClientType.CUSTOM,
            apiUrl = "https://example.test",
            model = modelId
        )
        return AvailableChatModel(
            platform = platform,
            model = PlatformModelV2(
                platformUid = platformUid,
                modelId = modelId,
                displayName = modelId
            )
        )
    }
}
