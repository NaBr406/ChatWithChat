package cn.nabr.chatwithchat.data.memory

sealed interface MemoryModelPreference {
    data object Auto : MemoryModelPreference

    data class Fixed(
        val platformUid: String,
        val modelId: String
    ) : MemoryModelPreference {
        init {
            require(platformUid.isNotBlank()) { "Memory model platform UID must not be blank" }
            require(modelId.isNotBlank()) { "Memory model ID must not be blank" }
        }
    }

    data class Invalid(
        val platformUid: String?,
        val modelId: String?,
        val reason: MemoryModelPreferenceInvalidReason
    ) : MemoryModelPreference

    companion object {
        fun fromStored(platformUid: String?, modelId: String?): MemoryModelPreference {
            if (platformUid == null && modelId == null) return Auto
            if (platformUid == null) {
                return Invalid(platformUid, modelId, MemoryModelPreferenceInvalidReason.MISSING_PLATFORM_UID)
            }
            if (modelId == null) {
                return Invalid(platformUid, modelId, MemoryModelPreferenceInvalidReason.MISSING_MODEL_ID)
            }

            val normalizedPlatformUid = platformUid.trim()
            val normalizedModelId = modelId.trim()
            return when {
                normalizedPlatformUid.isBlank() && normalizedModelId.isBlank() -> Invalid(
                    platformUid,
                    modelId,
                    MemoryModelPreferenceInvalidReason.BLANK_PAIR
                )
                normalizedPlatformUid.isBlank() -> Invalid(
                    platformUid,
                    modelId,
                    MemoryModelPreferenceInvalidReason.BLANK_PLATFORM_UID
                )
                normalizedModelId.isBlank() -> Invalid(
                    platformUid,
                    modelId,
                    MemoryModelPreferenceInvalidReason.BLANK_MODEL_ID
                )
                else -> Fixed(normalizedPlatformUid, normalizedModelId)
            }
        }
    }
}

enum class MemoryModelPreferenceInvalidReason {
    MISSING_PLATFORM_UID,
    MISSING_MODEL_ID,
    BLANK_PLATFORM_UID,
    BLANK_MODEL_ID,
    BLANK_PAIR
}
