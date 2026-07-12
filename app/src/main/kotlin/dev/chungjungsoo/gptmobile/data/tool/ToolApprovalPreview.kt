package dev.chungjungsoo.gptmobile.data.tool

@ConsistentCopyVisibility
data class ToolApprovalPreview private constructor(
    val toolName: String,
    val presentationKey: String?,
    val fallbackDisplayName: String,
    val argumentSummary: String,
    internal val callBindingHash: String
) {
    internal fun matches(call: ToolCall): Boolean = call.approvalBinding()
        .getOrNull()
        ?.callBindingHash == callBindingHash

    companion object {
        const val MAX_ARGUMENT_SUMMARY_CHARS = 240

        internal fun create(
            call: ToolCall,
            presentationKey: String?,
            fallbackDisplayName: String,
            humanReadableArgumentSummary: String
        ): Result<ToolApprovalPreview> = call.approvalBinding().mapCatching { binding ->
            val normalizedToolName = call.name.trim()
            require(normalizedToolName.isNotBlank()) { "tool_name_required" }

            val normalizedSummary = humanReadableArgumentSummary
                .trim()
                .replace(WHITESPACE, " ")
            require(normalizedSummary.isNotBlank()) { "tool_approval_summary_required" }
            require(!normalizedSummary.looksLikeRawJson()) { "tool_approval_raw_json_summary_rejected" }

            ToolApprovalPreview(
                toolName = normalizedToolName,
                presentationKey = presentationKey?.trim()?.takeIf { key -> key.isNotBlank() },
                fallbackDisplayName = fallbackDisplayName.trim().ifBlank { normalizedToolName.toDisplayName() },
                argumentSummary = normalizedSummary.boundedSummary(),
                callBindingHash = binding.callBindingHash
            )
        }
    }
}

private fun String.boundedSummary(): String {
    if (length <= ToolApprovalPreview.MAX_ARGUMENT_SUMMARY_CHARS) return this
    return take(ToolApprovalPreview.MAX_ARGUMENT_SUMMARY_CHARS - ELLIPSIS.length)
        .trimEnd() + ELLIPSIS
}

private fun String.looksLikeRawJson(): Boolean {
    if (!startsWith('{') && !startsWith('[')) return false
    return runCatching { toolProtocolJson.parseToJsonElement(this) }.isSuccess
}

private fun String.toDisplayName(): String = split('_', '-', ' ')
    .filter { part -> part.isNotBlank() }
    .joinToString(" ") { part -> part.replaceFirstChar { char -> char.titlecase() } }
    .ifBlank { this }

private val WHITESPACE = Regex("\\s+")
private const val ELLIPSIS = "..."
