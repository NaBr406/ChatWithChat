package cn.nabr.chatwithchat.data.textskill

import kotlinx.serialization.Serializable

@Serializable
data class InstalledSkillPackage(
    val packageId: String,
    val sourceName: String,
    val variants: List<RegisteredTextSkillVariant>,
    val hasScripts: Boolean,
    val requestedCapabilities: List<SkillCapabilityRequest> = emptyList(),
    val packageHash: String,
    val installedAt: Long = System.currentTimeMillis()
)

@Serializable
data class RegisteredTextSkillVariant(
    val skillId: String,
    val variantKey: String,
    val packageRelativeDir: String,
    val displayName: String,
    val entryPath: String,
    val name: String,
    val description: String,
    val frontmatter: SkillFrontmatter,
    val standardStatus: SkillStandardStatus,
    val textCompatibility: TextSkillCompatibility,
    val requestedCapabilities: List<SkillCapabilityRequest> = emptyList(),
    val directReferencePaths: List<String> = emptyList(),
    val directReferenceContentHashes: Map<String, String> = emptyMap(),
    val contentHash: String
)

@Serializable
data class SkillFrontmatter(
    val name: String? = null,
    val description: String? = null,
    val license: String? = null,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val allowedTools: List<String> = emptyList(),
    val userInvocable: Boolean? = null
)

@Serializable
enum class SkillStandardStatus {
    STANDARD,
    CLAUDE_COMPATIBLE,
    ADAPTER_EXTENSION,
    NON_STANDARD_FRONTMATTER
}

@Serializable
enum class TextSkillCompatibility {
    TEXT_ONLY,
    TEXT_ONLY_WITH_UNAVAILABLE_CAPABILITIES
}

@Serializable
enum class SkillCapability {
    TEXT_REFERENCE_READ,
    HOST_TOOL_CATALOG,
    MCP_TOOL_CALL,
    MCP_RESOURCE_READ,
    MCP_PROMPT_READ
}

@Serializable
data class SkillCapabilityRequest(
    val capability: SkillCapability,
    val source: String,
    val detail: String? = null
)

data class SkillInvocationContext(
    val chatId: Int,
    val userMessageId: Int? = null
)

sealed interface SkillCapabilityDecision {
    data object Allowed : SkillCapabilityDecision
    data class Denied(val reason: String) : SkillCapabilityDecision
}

interface SkillCapabilityGate {
    suspend fun resolve(request: SkillCapabilityRequest, context: SkillInvocationContext): SkillCapabilityDecision
}

class TextOnlySkillCapabilityGate : SkillCapabilityGate {
    override suspend fun resolve(
        request: SkillCapabilityRequest,
        context: SkillInvocationContext
    ): SkillCapabilityDecision = if (request.capability == SkillCapability.TEXT_REFERENCE_READ) {
        SkillCapabilityDecision.Allowed
    } else {
        SkillCapabilityDecision.Denied("Text-only Skill mode does not grant ${request.capability.name}.")
    }
}

@Serializable
enum class SkillInvocationSource {
    SESSION,
    ONE_SHOT
}

@Serializable
data class InvokedTextSkill(
    val packageId: String,
    val skillId: String,
    val name: String,
    val variantKey: String,
    val entryPath: String,
    val skillBodyHash: String,
    val referencePaths: List<String>,
    val referenceContentHashes: Map<String, String>,
    val source: SkillInvocationSource
)

@Serializable
data class TextSkillInvocationSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val skills: List<InvokedTextSkill>,
    val prompt: String,
    val promptHash: String,
    val estimatedTokens: Int,
    val limitsVersion: Int = TextSkillLimits.VERSION
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

data class ChatRequestContext(
    val memoryPrompt: String? = null,
    val textSkillInvocation: TextSkillInvocationSnapshot? = null
)

@Serializable
data class SelectedTextSkill(
    val packageId: String,
    val skillId: String,
    val variantKey: String,
    val displayName: String
)

@Serializable
enum class TextSkillSelectionScope {
    ONE_SHOT,
    SESSION
}

/**
 * A Skill the user picked before the chat row exists.
 *
 * Used both as the navigation payload from the landing page into a brand new chat and as the
 * in-memory holding area for session-scope picks made while [SkillInvocationContext.chatId] is still
 * 0, because session bindings are keyed by a persisted chat id.
 */
@Serializable
data class PendingTextSkillSelection(
    val skill: SelectedTextSkill,
    val scope: TextSkillSelectionScope,
    val isEnabled: Boolean = true
)

/** Stable identity of a picked Skill variant, used for de-duplication across scopes. */
fun SelectedTextSkill.selectionKey(): String = "$packageId:$skillId:$variantKey"

/** Every installed Skill variant, flattened into the shape the invocation picker shows. */
fun List<InstalledSkillPackage>.toSelectableTextSkills(): List<SelectedTextSkill> = flatMap { packageItem ->
    packageItem.variants.map { variant ->
        SelectedTextSkill(
            packageId = packageItem.packageId,
            skillId = variant.skillId,
            variantKey = variant.variantKey,
            displayName = variant.displayName
        )
    }
}

data class TextSkillVariantKey(
    val packageId: String,
    val skillId: String,
    val variantKey: String
)

fun RegisteredTextSkillVariant.key(packageId: String): TextSkillVariantKey =
    TextSkillVariantKey(packageId, skillId, variantKey)
