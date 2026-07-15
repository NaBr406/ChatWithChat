package cn.nabr.chatwithchat.data.tool

enum class ToolEffect {
    READ_ONLY_PUBLIC,
    READ_ONLY_PRIVATE,
    LOCAL_WRITE,
    EXTERNAL_WRITE,
    IRREVERSIBLE;

    val isWriteCapable: Boolean
        get() = when (this) {
            READ_ONLY_PUBLIC,
            READ_ONLY_PRIVATE -> false
            LOCAL_WRITE,
            EXTERNAL_WRITE,
            IRREVERSIBLE -> true
        }
}

enum class ToolApprovalPolicy {
    NOT_REQUIRED,
    REQUIRE_EACH_CALL
}

data class ToolSecurityPolicy(
    val effect: ToolEffect,
    val approvalPolicy: ToolApprovalPolicy
) {
    init {
        require(!effect.isWriteCapable || approvalPolicy == ToolApprovalPolicy.REQUIRE_EACH_CALL) {
            "write_tool_requires_per_call_approval"
        }
    }

    companion object {
        val ReadOnlyPublic = ToolSecurityPolicy(ToolEffect.READ_ONLY_PUBLIC, ToolApprovalPolicy.NOT_REQUIRED)
        val ReadOnlyPrivate = ToolSecurityPolicy(ToolEffect.READ_ONLY_PRIVATE, ToolApprovalPolicy.NOT_REQUIRED)
        val FailClosed = ToolSecurityPolicy(ToolEffect.IRREVERSIBLE, ToolApprovalPolicy.REQUIRE_EACH_CALL)
    }
}
