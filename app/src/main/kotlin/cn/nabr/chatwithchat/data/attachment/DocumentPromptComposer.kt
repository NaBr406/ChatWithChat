package cn.nabr.chatwithchat.data.attachment

/**
 * 把抽取出来的文档正文拼进消息文本。
 *
 * 为什么走内联文本而不是 provider 的文件接口：没有任何 provider 原生吃 docx，而 Groq / Ollama /
 * OpenRouter / 自定义端点连 vision 都不一定有。内联文本是唯一在全部平台都成立的投递方式。
 */
internal object DocumentPromptComposer {
    data class DocumentSection(
        val name: String,
        val mimeType: String,
        val text: String,
        val truncated: Boolean
    )

    fun compose(
        baseText: String,
        documents: List<DocumentSection>,
        maxTotalChars: Int = DocumentTextLimits.MAX_TOTAL_DOCUMENT_CHARS
    ): String {
        if (documents.isEmpty()) return baseText

        val blocks = StringBuilder()
        var remaining = maxTotalChars
        for (document in documents) {
            if (remaining <= 0) break
            val clipped = clip(document.text, remaining)
            if (clipped.isBlank()) continue
            remaining -= clipped.length
            if (blocks.isNotEmpty()) blocks.append('\n')
            appendBlock(
                target = blocks,
                document = document,
                body = clipped,
                truncated = document.truncated || clipped.length < document.text.length
            )
        }

        if (blocks.isEmpty()) return baseText
        return if (baseText.isBlank()) blocks.toString() else "$baseText\n\n$blocks"
    }

    private fun appendBlock(target: StringBuilder, document: DocumentSection, body: String, truncated: Boolean) {
        target.append("<attachment name=\"").append(escapeXmlAttribute(document.name))
            .append("\" type=\"").append(escapeXmlAttribute(document.mimeType))
            .append("\" chars=\"").append(body.length)
            .append("\" truncated=\"").append(truncated)
            .append("\">\n")
            .append(defuseClosingTag(body))
            .append("\n</attachment>")
    }

    private fun clip(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val end = if (maxChars > 0 && text[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
        return text.substring(0, end)
    }

    private fun escapeXmlAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\n", " ")
        .replace("\r", " ")

    /** 文档正文里出现字面量结束标签就能提前闭合我们的块，是最直白的提示注入面，打散掉。 */
    private fun defuseClosingTag(body: String): String = body.replace("</attachment", "<\\/attachment", ignoreCase = true)
}
