package cn.nabr.chatwithchat.util

object AttachmentPayloadCache {
    internal const val MAX_ENTRIES: Int = 32

    private val payloads = object : LinkedHashMap<String, FileUtils.EncodedImage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FileUtils.EncodedImage>?): Boolean = size > MAX_ENTRIES
    }

    fun get(filePath: String): FileUtils.EncodedImage? = synchronized(this) {
        payloads[filePath]
    }

    fun put(filePath: String, payload: FileUtils.EncodedImage) {
        synchronized(this) {
            payloads[filePath] = payload
        }
    }

    fun remove(filePath: String) {
        synchronized(this) {
            payloads.remove(filePath)
        }
    }

    internal fun clear() {
        synchronized(this) {
            payloads.clear()
        }
    }
}
