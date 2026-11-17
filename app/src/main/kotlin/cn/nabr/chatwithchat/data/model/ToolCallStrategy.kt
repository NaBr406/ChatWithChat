package cn.nabr.chatwithchat.data.model

/** Transport for enabled tools; the global ToolCallingMode still controls tool availability. */
enum class ToolCallStrategy {
    AUTO,
    NATIVE,
    JSON
}
