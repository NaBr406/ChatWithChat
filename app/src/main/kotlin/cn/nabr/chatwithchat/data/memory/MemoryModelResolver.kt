package cn.nabr.chatwithchat.data.memory

import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.AvailableChatModel
import cn.nabr.chatwithchat.data.model.ClientType
import cn.nabr.chatwithchat.data.repository.SettingRepository

class MemoryModelResolver(
    private val settingRepository: SettingRepository
) {
    suspend fun resolvePreference(preference: MemoryModelPreference): MemoryModelResolution = when (preference) {
        MemoryModelPreference.Auto -> resolveAuto()
        is MemoryModelPreference.Fixed -> resolveFrozen(preference.platformUid, preference.modelId)
        is MemoryModelPreference.Invalid -> MemoryModelResolution.Unavailable(
            MemoryModelUnavailableReason.INVALID_PREFERENCE
        )
    }

    suspend fun resolveAuto(): MemoryModelResolution {
        val enabledModelIdsByPlatform = settingRepository.fetchPlatformModels()
            .filter { model -> model.enabled }
            .groupBy({ model -> model.platformUid }, { model -> model.modelId })
        val resolved = settingRepository.fetchPlatformV2s().firstNotNullOfOrNull { platform ->
            platform
                .takeIf { candidate -> candidate.model in enabledModelIdsByPlatform[candidate.uid].orEmpty() }
                ?.takeIf { candidate -> candidate.isEligibleMemoryModel() }
        }
        return resolved?.let(MemoryModelResolution::Resolved)
            ?: MemoryModelResolution.Unavailable(MemoryModelUnavailableReason.NO_ELIGIBLE_MODEL)
    }

    suspend fun resolveFrozen(platformUid: String, modelId: String): MemoryModelResolution {
        if (platformUid.isBlank() || modelId.isBlank()) {
            return MemoryModelResolution.Unavailable(MemoryModelUnavailableReason.INVALID_FROZEN_IDENTITY)
        }
        val platform = settingRepository.fetchPlatformV2s()
            .firstOrNull { candidate -> candidate.uid == platformUid }
            ?.copy(model = modelId)
            ?: return MemoryModelResolution.Unavailable(MemoryModelUnavailableReason.FROZEN_MODEL_UNAVAILABLE)
        val modelAvailable = settingRepository.fetchPlatformModels(platformUid).any { model ->
            model.modelId == modelId && model.enabled
        }
        if (!modelAvailable) {
            return MemoryModelResolution.Unavailable(MemoryModelUnavailableReason.FROZEN_MODEL_UNAVAILABLE)
        }
        return if (platform.isEligibleMemoryModel()) {
            MemoryModelResolution.Resolved(platform)
        } else {
            MemoryModelResolution.Unavailable(MemoryModelUnavailableReason.FROZEN_MODEL_UNAVAILABLE)
        }
    }

    fun isEligible(option: AvailableChatModel): Boolean =
        option.platform.copy(model = option.modelId).isEligibleMemoryModel()

    private fun PlatformV2.isEligibleMemoryModel(): Boolean = enabled &&
        uid.isNotBlank() &&
        model.isNotBlank() &&
        apiUrl.isNotBlank() &&
        compatibleType in SUPPORTED_CLIENT_TYPES &&
        (compatibleType == ClientType.OLLAMA || !token.isNullOrBlank())

    private companion object {
        val SUPPORTED_CLIENT_TYPES = setOf(
            ClientType.OPENAI,
            ClientType.ANTHROPIC,
            ClientType.GOOGLE,
            ClientType.GROQ,
            ClientType.OPENROUTER,
            ClientType.OLLAMA,
            ClientType.CUSTOM
        )
    }
}

sealed interface MemoryModelResolution {
    data class Resolved(val platform: PlatformV2) : MemoryModelResolution
    data class Unavailable(val reason: MemoryModelUnavailableReason) : MemoryModelResolution
}

enum class MemoryModelUnavailableReason(val code: String) {
    NO_ELIGIBLE_MODEL("no_eligible_memory_model"),
    INVALID_PREFERENCE("invalid_memory_model_preference"),
    INVALID_FROZEN_IDENTITY("invalid_frozen_memory_model_identity"),
    FROZEN_MODEL_UNAVAILABLE("frozen_memory_model_unavailable")
}
