package cn.nabr.chatwithchat.data.model

import cn.nabr.chatwithchat.data.database.entity.PlatformModelV2
import cn.nabr.chatwithchat.data.database.entity.PlatformV2

data class AvailableChatModel(
    val platform: PlatformV2,
    val model: PlatformModelV2
) {
    val platformUid: String = platform.uid
    val modelId: String = model.modelId
    val displayName: String = model.displayName.ifBlank { model.modelId }
    val platformName: String = platform.name
}

data class ModelRefreshResult(
    val platform: PlatformV2,
    val models: List<PlatformModelV2>,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean = errorMessage == null
}
