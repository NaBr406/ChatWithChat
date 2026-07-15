package cn.nabr.chatwithchat.data.dto.anthropic.common

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ImageSource(
    @SerialName("type")
    val type: ImageSourceType,

    @SerialName("media_type")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val mediaType: MediaType? = null,

    @SerialName("data")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val data: String? = null,

    @SerialName("file_id")
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val fileId: String? = null
) {
    companion object {
        fun base64(mediaType: MediaType, data: String) = ImageSource(
            type = ImageSourceType.BASE64,
            mediaType = mediaType,
            data = data
        )

        fun file(fileId: String) = ImageSource(
            type = ImageSourceType.FILE,
            fileId = fileId
        )
    }
}
