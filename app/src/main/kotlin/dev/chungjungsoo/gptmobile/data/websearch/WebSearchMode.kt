package dev.chungjungsoo.gptmobile.data.websearch

enum class WebSearchMode(val storageValue: String) {
    Off("off"),
    Auto("auto"),
    Always("always");

    companion object {
        fun fromStorageValue(value: String?): WebSearchMode = entries.firstOrNull { mode ->
            mode.storageValue == value?.trim()?.lowercase()
        } ?: Off
    }
}
