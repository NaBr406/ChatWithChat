package cn.nabr.chatwithchat.data.richcontent

import cn.nabr.chatwithchat.data.richcontent.openui.OpenUiEnvelope

/** Presentation programs never become assistant facts or conversation context. */
object AssistantContentProjection {
    private val parser = RichContentParser()

    fun toMarkdown(content: String): String {
        val markdown = OpenUiEnvelope.toMarkdown(content)
        return if (markdown.contains("<ui-")) {
            RichContentProjection.toMarkdown(parser.parse(markdown)).trim()
        } else {
            markdown
        }
    }
}
