package cn.nabr.chatwithchat.data.dto.anthropic.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ImageSourceType {

    @SerialName("base64")
    BASE64,

    @SerialName("file")
    FILE
}
