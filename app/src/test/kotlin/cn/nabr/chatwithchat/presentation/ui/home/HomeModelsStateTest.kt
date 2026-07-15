package cn.nabr.chatwithchat.presentation.ui.home

import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.model.ClientType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelsStateTest {
    @Test
    fun loading_doesNotShowAddProviderFallback() {
        assertFalse(HomeModelsState.Loading.shouldShowAddProvider())
    }

    @Test
    fun readyWithoutModels_showsAddProviderFallback() {
        assertTrue(HomeModelsState.Ready(emptyList()).shouldShowAddProvider())
    }

    @Test
    fun savedPlatformWithModelRefreshFailure_doesNotShowAddProviderFallback() {
        val state = HomeModelsState.Ready(
            models = emptyList(),
            hasConfiguredPlatforms = true,
            loadFailed = true
        )

        assertFalse(state.shouldShowAddProvider())
    }

    @Test
    fun loading_cannotStartChat() {
        assertFalse(HomeModelsState.Loading.canStartChat())
    }

    @Test
    fun readyWithModel_canStartChatWithoutFallback() {
        val platform = PlatformV2(
            uid = "platform",
            name = "Provider",
            compatibleType = ClientType.OPENAI,
            enabled = true,
            apiUrl = "https://example.com",
            model = "model"
        )
        val model = AvailableChatModel(
            platform = platform,
            model = PlatformModelV2(
                platformUid = platform.uid,
                modelId = "model",
                displayName = "Model"
            )
        )
        val state = HomeModelsState.Ready(listOf(model))

        assertTrue(state.canStartChat())
        assertFalse(state.shouldShowAddProvider())
    }
}
