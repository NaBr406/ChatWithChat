package cn.nabr.chatwithchat.presentation.ui.chat

import cn.nabr.chatwithchat.data.context.stripInternalStickerMarkers
import cn.nabr.chatwithchat.data.database.entity.MessageV2
import cn.nabr.chatwithchat.data.database.entity.effectiveContent
import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiEnvelope

/** WebViews cannot safely participate in the chat's recorded backdrop layer on all devices. */
internal class ChatGlassPolicy(
    private val containsOpenUi: (String) -> Boolean = OpenUiEnvelope::containsOpenUi
) {
    private val checkedContentByTurn = mutableMapOf<Int, String>()
    private var disabled = false

    fun isEnabled(assistantMessages: List<List<MessageV2>>, selectedPlatformIndices: List<Int>): Boolean {
        if (disabled) return false
        assistantMessages.forEachIndexed { turnIndex, messages ->
            val message = messages.getOrNull(selectedPlatformIndices.getOrElse(turnIndex) { 0 })
            val content = message?.effectiveContent().orEmpty()
            if (checkedContentByTurn[turnIndex] != content) {
                if (containsOpenUi(content.stripInternalStickerMarkers())) {
                    // Keep capture disabled through rollback, revision/platform switches and
                    // removal: a retiring WebView may still be attached during composition.
                    disabled = true
                    checkedContentByTurn.clear()
                    return false
                }
                checkedContentByTurn[turnIndex] = content
            }
        }
        checkedContentByTurn.keys.removeAll { it >= assistantMessages.size }
        return true
    }
}
