package cn.nabr.chatwithchat.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTitleNormalizerTest {
    @Test
    fun normalizeChatTitle_trimsAndCollapsesWhitespace() {
        assertEquals(
            "安卓 后期规划",
            normalizeChatTitle("  安卓  \n 后期规划  ")
        )
    }

    @Test
    fun normalizeChatTitle_plainTitle_keepsContent() {
        assertEquals("会话标题", normalizeChatTitle("会话标题"))
    }
}
