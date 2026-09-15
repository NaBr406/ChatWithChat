package cn.nabr.chatwithchat.data.repository

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualAssetSupportTest {

    @Test
    fun officialDeepSeekFlashSupportsVisualInput() {
        val platform = customDeepSeekPlatform("deepseek-flash")

        assertTrue(platform.supportsVisualAssetInput())
    }

    @Test
    fun officialDeepSeekTextModelStaysUnsupported() {
        val platform = customDeepSeekPlatform("deepseek-chat")

        assertFalse(platform.supportsVisualAssetInput())
    }

    private fun customDeepSeekPlatform(model: String) = PlatformV2(
        uid = "deepseek",
        name = "DeepSeek",
        compatibleType = ClientType.CUSTOM,
        apiUrl = "https://api.deepseek.com",
        model = model
    )
}
