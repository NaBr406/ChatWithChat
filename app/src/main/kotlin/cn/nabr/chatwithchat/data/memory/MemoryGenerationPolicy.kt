package cn.nabr.chatwithchat.data.memory

internal object MemoryClass {
    const val DURABLE_FACT = "durable_fact"
    const val ACTIVE_PROJECT = "active_project"
    const val TEMPORARY_OBSERVATION = "temporary_observation"

    val VALUES = setOf(DURABLE_FACT, ACTIVE_PROJECT, TEMPORARY_OBSERVATION)
}

internal object MemoryGenerationPolicy {
    fun normalize(memoryClass: String?, type: String? = null, scope: String? = null): String =
        memoryClass ?: if (type == "project_context" && scope?.startsWith("project:") == true) {
            MemoryClass.ACTIVE_PROJECT
        } else {
            MemoryClass.DURABLE_FACT
        }

    fun validate(
        memoryClass: String?,
        type: String,
        scope: String?,
        source: String,
        recallState: String?,
        destination: String? = null,
        longTermWrite: Boolean = destination == MemoryBatchDestination.LONG_TERM
    ): String {
        val normalized = normalize(memoryClass, type, scope)
        require(normalized in MemoryClass.VALUES) { "memory_class_invalid" }
        if (normalized == MemoryClass.TEMPORARY_OBSERVATION) {
            require(!longTermWrite) { "temporary_observation_long_term_forbidden" }
            require(recallState != MemoryRecallState.CORE) { "temporary_observation_core_forbidden" }
        }
        if (normalized == MemoryClass.ACTIVE_PROJECT) {
            require(type == "project_context") { "active_project_type_invalid" }
            require(scope?.startsWith("project:") == true) { "active_project_scope_required" }
            require(recallState == MemoryRecallState.QUERY) { "active_project_query_required" }
            val profile = MemoryFreshnessPolicy.resolve(
                MarkdownMemoryEntry(
                    id = "generation",
                    text = "project",
                    type = type,
                    sensitivity = MemorySensitivity.NORMAL,
                    source = source,
                    createdAt = 0L,
                    updatedAt = 0L,
                    scope = scope
                )
            )
            require(profile.freshness == MemoryFreshness.REFRESHABLE) {
                "active_project_freshness_invalid"
            }
        }
        if (source == MemorySource.ASSISTANT_INFERRED && recallState == MemoryRecallState.CORE) {
            require(normalized != MemoryClass.TEMPORARY_OBSERVATION) {
                "assistant_inferred_core_forbidden"
            }
            require(type !in setOf("stable_profile", "interest")) {
                "assistant_inferred_core_forbidden"
            }
        }
        return normalized
    }
}
