package cn.nabr.chatwithchat.util

/** 落单代理项的替身：模型看到的是"这里有个字坏了"，而不是整条消息发不出去。 */
private const val UNPAIRED_SURROGATE_REPLACEMENT = '\uFFFD'

/**
 * 把落单（未配对）的代理项换成 `U+FFFD`。
 *
 * PDFBox 按 ToUnicode CMap 还原正文时会产出单个 `U+D800..U+DFFF`。这种 char 在 Java 里完全合法，
 * 但没有任何 UTF-8 字节序列能表示它；偏偏 Ktor 把 String 请求体转字节走的是严格编码器
 * （`TextContent.bytes()` 调 `encodeToByteArray(throwOnInvalidSequence = true)`），一个落单代理项就
 * 足以让整次请求在本地抛 `MalformedInputException("Input length = 1")`：没有延迟、没有字节发出。
 *
 * 换掉的代价只是正文里多一个 `�`，比整条消息发不出去小得多。
 */
internal fun String.withoutUnpairedSurrogates(): String {
    if (isEmpty()) return this

    var index = 0
    var builder: StringBuilder? = null
    while (index < length) {
        val char = this[index]
        when {
            char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                builder?.append(char)
                builder?.append(this[index + 1])
                index += 2
            }

            char.isSurrogate() -> {
                // 第一个坏字符出现时才开缓冲区，干净文本原样返回、不多一次分配。
                val target = builder ?: StringBuilder(length).also { it.append(this, 0, index) }
                builder = target
                target.append(UNPAIRED_SURROGATE_REPLACEMENT)
                index += 1
            }

            else -> {
                builder?.append(char)
                index += 1
            }
        }
    }
    return builder?.toString() ?: this
}
