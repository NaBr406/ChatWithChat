package cn.nabr.chatwithchat.data.websearch

enum class WebSearchMode(val storageValue: String) {
    Off("off"),
    Auto("auto");

    companion object {
        fun fromStorageValue(value: String?): WebSearchMode = when (value?.trim()?.lowercase()) {
            Auto.storageValue,
            LEGACY_ALWAYS_STORAGE_VALUE -> Auto
            else -> Off
        }

        private const val LEGACY_ALWAYS_STORAGE_VALUE = "always"
    }
}
