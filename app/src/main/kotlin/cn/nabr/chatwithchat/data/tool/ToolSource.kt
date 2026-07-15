package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.database.entity.AppSourceNavigationTarget
import cn.nabr.chatwithchat.data.database.entity.MessageSourceMetadata
import cn.nabr.chatwithchat.data.database.entity.MessageSourceType
import cn.nabr.chatwithchat.data.database.entity.isSafeLocalEntityId
import cn.nabr.chatwithchat.data.database.entity.safeHttpUrlOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class ToolSource {
    abstract val title: String
    abstract val snippet: String

    @Serializable
    @SerialName("public_url")
    data class PublicUrl(
        override val title: String,
        val url: String,
        override val snippet: String = ""
    ) : ToolSource()

    @Serializable
    @SerialName("local_app")
    data class LocalApp(
        override val title: String,
        val localEntityId: String,
        val navigationTarget: AppSourceNavigationTarget,
        override val snippet: String = ""
    ) : ToolSource()
}

fun ToolSource.toMessageSourceMetadataOrNull(sourceToolName: String): MessageSourceMetadata? = when (this) {
    is ToolSource.PublicUrl -> url.safeHttpUrlOrNull()?.let { safeUrl ->
        MessageSourceMetadata(
            title = title,
            url = safeUrl,
            snippet = snippet,
            sourceToolName = sourceToolName,
            sourceType = MessageSourceType.PUBLIC_URL
        )
    }
    is ToolSource.LocalApp ->
        localEntityId
            .trim()
            .takeIf { value -> value.isSafeLocalEntityId() }
            ?.let { safeEntityId ->
                MessageSourceMetadata(
                    title = title,
                    snippet = snippet,
                    sourceToolName = sourceToolName,
                    sourceType = MessageSourceType.LOCAL_APP,
                    localEntityId = safeEntityId,
                    appNavigationTarget = navigationTarget
                )
            }
}

fun List<ToolSource>.toMessageSourceMetadata(sourceToolName: String): List<MessageSourceMetadata> =
    mapNotNull { source -> source.toMessageSourceMetadataOrNull(sourceToolName) }
