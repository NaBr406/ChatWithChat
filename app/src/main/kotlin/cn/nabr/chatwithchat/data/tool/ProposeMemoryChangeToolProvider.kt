package cn.nabr.chatwithchat.data.tool

import cn.nabr.chatwithchat.data.memory.InMemoryMemoryProposalStore
import cn.nabr.chatwithchat.data.memory.MemoryEvidenceStrength
import cn.nabr.chatwithchat.data.memory.MemoryProposalInput
import cn.nabr.chatwithchat.data.memory.MemoryProposalStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ProposeMemoryChangeToolProvider(
    private val proposalStore: MemoryProposalStore = InMemoryMemoryProposalStore(),
    private val now: () -> Long = { System.currentTimeMillis() }
) : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition.ProposeMemoryChange

    override val settingsMetadata = ToolSettingsMetadata(
        defaultEnabled = true,
        isSensitive = true,
        presentationKey = "propose_memory_change"
    )

    override val securityPolicy = ToolSecurityPolicy(
        effect = ToolEffect.LOCAL_WRITE,
        approvalPolicy = ToolApprovalPolicy.REQUIRE_EACH_CALL
    )

    override val policy = ToolPolicy(maxResultChars = 1_200)

    override fun approvalArgumentSummary(call: ToolCall): String {
        val proposal = parse(call).getOrNull()
        return when {
            proposal == null -> "记忆提案（参数无效）"
            proposal.text.isNotBlank() -> "保存记忆：${proposal.text}"
            proposal.targetRef != null -> "删除记忆：${proposal.targetRef}"
            else -> "记忆提案"
        }.take(MAX_APPROVAL_SUMMARY_CHARS)
    }

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
        call.errorResult("memory_proposal_approval_required", "memory_proposal_approval_required")

    override suspend fun execute(
        call: ToolCall,
        config: ToolLoopConfig,
        executionContext: ToolExecutionContext
    ): ToolResult {
        if (executionContext.approval !is ToolCallApproval.Approved) {
            return call.errorResult("memory_proposal_approval_required", "memory_proposal_approval_required")
        }
        val proposal = parse(call).getOrElse { error ->
            return call.errorResult(
                "memory_proposal_invalid:${error.message ?: "invalid_arguments"}",
                "memory_proposal_invalid"
            )
        }
        val timestamp = now()
        val idempotencyKey = sha256Domain(
            "memory-proposal-v1",
            listOf(proposal.intent, proposal.targetRef.orEmpty(), normalizeForHash(proposal.text), normalizeForHash(proposal.reason))
        )
        val stored = proposalStore.createOrGet(
            MemoryProposalInput(
                proposalId = "proposal_${idempotencyKey.take(24)}",
                chatId = executionContext.chatId.takeIf { it > 0 },
                turnKey = executionContext.turnKey,
                intent = proposal.intent,
                targetRef = proposal.targetRef,
                text = proposal.text,
                normalizedText = normalizeForHash(proposal.text),
                reason = proposal.reason,
                requestedEvidenceStrength = proposal.evidenceStrength,
                idempotencyKey = idempotencyKey,
                createdAt = timestamp
            )
        )
        return ToolResult(
            callId = call.id,
            name = call.name,
            content = buildJsonObject {
                put("proposalId", stored.proposalId)
                put("state", stored.state)
                put("intent", stored.intent)
                stored.targetRef?.let { put("targetRef", it) }
                if (stored.text.isNotBlank()) put("text", stored.text)
                put("evidenceStrength", stored.effectiveEvidenceStrength)
                put("message", "提案已保存，等待绑定到已完成回合")
            }.toString(),
            structuredContent = buildJsonObject {
                put("proposalId", stored.proposalId)
                put("state", stored.state)
                put("idempotencyKey", stored.idempotencyKey)
            }
        )
    }

    private fun parse(call: ToolCall): Result<ParsedProposal> = runCatching {
        val json = call.argumentsObject().getOrThrow()
        val intent = json.requiredString("intent")
        require(intent in VALID_INTENTS) { "intent_invalid" }
        val targetRef = json.optionalString("targetRef")
        when (intent) {
            INTENT_REMEMBER -> require(targetRef == null) { "remember_target_ref_forbidden" }
            INTENT_UPDATE,
            INTENT_FORGET -> require(targetRef?.matches(REF_PATTERN) == true) { "target_ref_required" }
        }
        val text = json.optionalString("text").orEmpty().trim()
        if (intent == INTENT_FORGET) {
            require(text.isBlank()) { "forget_text_forbidden" }
        } else {
            require(text.isNotBlank()) { "text_required" }
        }
        val reason = json.requiredString("reason").trim()
        require(reason.isNotBlank()) { "reason_required" }
        val evidenceStrength = json.requiredString("evidenceStrength")
        require(evidenceStrength in VALID_EVIDENCE_STRENGTHS) { "evidence_strength_invalid" }
        validateText("text", text, MAX_TEXT_CHARS)
        validateText("reason", reason, MAX_REASON_CHARS)
        targetRef?.let { validateText("targetRef", it, MAX_TARGET_REF_CHARS) }
        ParsedProposal(intent, targetRef, text, reason, evidenceStrength)
    }

    private fun validateText(field: String, value: String, maxChars: Int) {
        require(value.length <= maxChars) { "${field}_too_long" }
        require(value.none(Char::isISOControl)) { "${field}_control_character" }
        require(!UNSAFE_MARKERS.containsMatchIn(value)) { "${field}_internal_marker" }
        require(!PROMPT_ROLE_MARKERS.containsMatchIn(value)) { "${field}_prompt_marker" }
    }

    private fun JsonObject.requiredString(name: String): String =
        get(name)?.jsonPrimitive?.contentOrNull?.trim().orEmpty().also { value ->
            require(value.isNotBlank()) { "${name}_required" }
        }

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    private data class ParsedProposal(
        val intent: String,
        val targetRef: String?,
        val text: String,
        val reason: String,
        val evidenceStrength: String
    )

    private companion object {
        const val INTENT_REMEMBER = "remember"
        const val INTENT_UPDATE = "update"
        const val INTENT_FORGET = "forget"
        const val MAX_TEXT_CHARS = 600
        const val MAX_REASON_CHARS = 240
        const val MAX_TARGET_REF_CHARS = 3
        const val MAX_APPROVAL_SUMMARY_CHARS = 700
        val VALID_INTENTS = setOf(INTENT_REMEMBER, INTENT_UPDATE, INTENT_FORGET)
        val VALID_EVIDENCE_STRENGTHS = setOf(
            MemoryEvidenceStrength.USER_EXPLICIT,
            MemoryEvidenceStrength.USER_CONFIRMED,
            MemoryEvidenceStrength.AGENT_INFERRED
        )
        val REF_PATTERN = Regex("R\\d{2}")
        val UNSAFE_MARKERS = Regex(
            "(?i)(memory\\.md|canonical[_ ]?key|target(memory)?(id|ref)|entry[_ ]?id|source[ _]?path|supersededby|" +
                "(?:type|scope|sensitivity|source|recallstate)\\s*:|<\\|[^|]+\\|>)"
        )
        val PROMPT_ROLE_MARKERS = Regex("(?im)^\\s*(system|assistant|user|tool)\\s*:")

        fun normalizeForHash(value: String): String = value.trim().replace(Regex("\\s+"), " ")
    }
}
