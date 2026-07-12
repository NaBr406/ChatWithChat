package dev.chungjungsoo.gptmobile.data.tool

enum class ToolCallingMode(val storageValue: String) {
    Off("off"),
    Auto("auto");

    companion object {
        fun fromStorageValue(value: String?): ToolCallingMode = entries.firstOrNull { mode ->
            mode.storageValue == value?.trim()?.lowercase()
        } ?: Auto
    }
}
