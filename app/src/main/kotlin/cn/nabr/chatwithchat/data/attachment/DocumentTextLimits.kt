package cn.nabr.chatwithchat.data.attachment

object DocumentTextLimits {
    /** 单个文档抽取后保留的最大字符数，超出即截断并标注。 */
    const val MAX_DOCUMENT_CHARS = 40_000

    /** 一条消息里所有文档正文合计的上限，防止一次塞爆上下文窗口。 */
    const val MAX_TOTAL_DOCUMENT_CHARS = 80_000

    /** 纯文本文件一次性读入内存的上限。 */
    const val MAX_PLAIN_TEXT_BYTES = 8 * 1024 * 1024L

    /** docx 里 word/document.xml 解压后的字节上限，防 zip bomb。 */
    const val MAX_DOCX_XML_BYTES = 16 * 1024 * 1024L

    /** docx 容器允许的条目数上限。 */
    const val MAX_DOCX_ZIP_ENTRIES = 512

    /** PDF 文件本身的字节上限。 */
    const val MAX_PDF_BYTES = 32 * 1024 * 1024L

    /** 最多抽取多少页 PDF，超出的页数记为截断。 */
    const val MAX_PDF_PAGES = 200

    /** 判定二进制文件时嗅探的前导字节数。 */
    const val BINARY_SNIFF_BYTES = 8 * 1024
}
