package cn.nabr.chatwithchat.presentation.common

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 打开附件预览页的入口。
 *
 * 附件缩略图长在 composer 的草稿条和消息气泡里，离 NavHost 隔着七八层 composable，逐层传导航回调
 * 要改一路的签名（含 [cn.nabr.chatwithchat.presentation.ui.chat.ChatShellScreen] 这种带 slot 的），
 * 所以在 NavHost 外面统一提供一次。同一个 local 也覆盖了落地页（草稿）和聊天页（已发送）两处。
 *
 * 默认实现是空操作：`@Preview` 里没有 NavController，点一下不该崩。
 */
fun interface AttachmentPreviewLauncher {
    fun open(filePath: String)
}

val LocalAttachmentPreviewLauncher = staticCompositionLocalOf { AttachmentPreviewLauncher { } }
