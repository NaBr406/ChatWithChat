package cn.nabr.chatwithchat.util

import java.util.UUID

/**
 * 附件复制进 filesDir 时的文件名约定：`attachment_<sha256>_<uuid>_<原始名>.<ext>`。
 *
 * 前两段是去重与唯一性需要的（[cn.nabr.chatwithchat.presentation.ui.chat.attachmentIdentity] 靠
 * `attachment_` 后的第一段解 sha），第三段纯粹是为了让 chip、气泡和 `<attachment name>` 能显示
 * 用户认得出的文件名，而不是 `attachment_9f3a….docx`。
 */
object AttachmentNaming {
    internal const val PREFIX = "attachment_"
    private const val MAX_BASE_NAME_LENGTH = 48

    /** ext4/f2fs 的单个文件名上限是 255 字节，CJK 名字一个字符占 3 字节，必须按字节封顶。 */
    private const val MAX_FILE_NAME_BYTES = 200
    private val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
    private val UUID_PATTERN = Regex("^[0-9a-fA-F-]{36}$")

    fun buildAttachmentFileName(
        digestHex: String,
        baseName: String,
        extension: String,
        uniqueSuffix: String = UUID.randomUUID().toString()
    ): String {
        val head = "$PREFIX${digestHex}_$uniqueSuffix"
        val tail = if (extension.isEmpty()) "" else ".$extension"
        val baseBudget = MAX_FILE_NAME_BYTES - head.length - tail.length - 1
        val base = takeUtf8(baseName.trim().take(MAX_BASE_NAME_LENGTH), baseBudget)
        return if (base.isEmpty()) "$head$tail" else "${head}_$base$tail"
    }

    /**
     * 从内部文件名反解出用户可读的名字。不符合约定（比如旧版本存下来的文件）时原样返回，
     * 保证历史附件不会显示成空白。
     */
    fun attachmentDisplayName(fileName: String): String {
        if (!fileName.startsWith(PREFIX)) return fileName
        val extension = fileName.substringAfterLast('.', "")
        val withoutExtension = if (extension.isEmpty()) fileName else fileName.dropLast(extension.length + 1)
        val segments = withoutExtension.removePrefix(PREFIX).split('_', limit = 3)
        if (segments.size < 3) return fileName
        if (!DIGEST_PATTERN.matches(segments[0]) || !UUID_PATTERN.matches(segments[1])) return fileName
        val baseName = segments[2].takeIf { it.isNotBlank() } ?: return fileName
        return if (extension.isEmpty()) baseName else "$baseName.$extension"
    }

    private fun takeUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val builder = StringBuilder()
        var bytes = 0
        for (char in value) {
            val charBytes = when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isSurrogate() -> 2
                else -> 3
            }
            if (bytes + charBytes > maxBytes) break
            builder.append(char)
            bytes += charBytes
        }
        if (builder.isNotEmpty() && builder.last().isHighSurrogate()) {
            builder.deleteCharAt(builder.length - 1)
        }
        return builder.toString()
    }
}
