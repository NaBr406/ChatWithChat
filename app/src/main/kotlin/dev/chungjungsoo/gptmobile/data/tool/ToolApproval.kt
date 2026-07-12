package dev.chungjungsoo.gptmobile.data.tool

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

@ConsistentCopyVisibility
data class ToolApprovalBinding internal constructor(
    val toolNameHash: String,
    val argumentsHash: String,
    val callBindingHash: String,
    val operationHash: String
)

fun ToolCall.approvalBinding(): Result<ToolApprovalBinding> = runCatching {
    require(name.isNotBlank()) { "tool_name_required" }
    require(id.isNotBlank()) { "tool_call_id_required" }

    val argumentsObject = toolProtocolJson
        .parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
        ?: throw IllegalArgumentException("tool_arguments_object_expected")
    val argumentsHash = sha256Domain(ARGUMENTS_HASH_DOMAIN, listOf(argumentsObject.toCanonicalJson()))
    ToolApprovalBinding(
        toolNameHash = sha256Domain(TOOL_NAME_HASH_DOMAIN, listOf(name)),
        argumentsHash = argumentsHash,
        callBindingHash = sha256Domain(CALL_BINDING_HASH_DOMAIN, listOf(name, id, argumentsHash)),
        operationHash = sha256Domain(OPERATION_HASH_DOMAIN, listOf(name, argumentsHash))
    )
}

sealed interface ToolCallApproval {
    data object Missing : ToolCallApproval
    data object Denied : ToolCallApproval

    @ConsistentCopyVisibility
    data class Approved internal constructor(internal val token: ToolApprovalToken) : ToolCallApproval
}

@JvmInline
value class ToolApprovalToken internal constructor(internal val encoded: String)

enum class ToolApprovalStatus(val recoverableErrorCode: String?) {
    APPROVED(null),
    DENIED("tool_approval_denied"),
    MISSING("tool_approval_required"),
    INVALID("tool_approval_invalid")
}

data class ToolApprovalEvaluation(
    val status: ToolApprovalStatus,
    val binding: ToolApprovalBinding?
) {
    val isApproved: Boolean
        get() = status == ToolApprovalStatus.APPROVED
}

class ToolApprovalAuthority internal constructor(secretKey: ByteArray) {
    private val secretKey = secretKey.copyOf().also { key ->
        require(key.size >= MIN_SECRET_KEY_BYTES) { "tool_approval_secret_too_short" }
    }

    constructor() : this(generateSecretKey())

    fun approve(call: ToolCall): Result<ToolCallApproval.Approved> = call.approvalBinding().mapCatching { binding ->
        ToolCallApproval.Approved(ToolApprovalToken(sign(binding.callBindingHash)))
    }

    fun evaluate(
        call: ToolCall,
        approval: ToolCallApproval
    ): ToolApprovalEvaluation {
        val binding = call.approvalBinding().getOrNull()
        val status = when (approval) {
            ToolCallApproval.Missing -> ToolApprovalStatus.MISSING
            ToolCallApproval.Denied -> ToolApprovalStatus.DENIED
            is ToolCallApproval.Approved -> {
                if (binding != null && approval.token.matches(sign(binding.callBindingHash))) {
                    ToolApprovalStatus.APPROVED
                } else {
                    ToolApprovalStatus.INVALID
                }
            }
        }
        return ToolApprovalEvaluation(status = status, binding = binding)
    }

    private fun sign(callBindingHash: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secretKey, HMAC_ALGORITHM))
        return mac.doFinal(callBindingHash.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun ToolApprovalToken.matches(expected: String): Boolean = MessageDigest.isEqual(
        encoded.toByteArray(StandardCharsets.US_ASCII),
        expected.toByteArray(StandardCharsets.US_ASCII)
    )

    companion object {
        private const val MIN_SECRET_KEY_BYTES = 32
        private const val HMAC_ALGORITHM = "HmacSHA256"

        private fun generateSecretKey(): ByteArray = ByteArray(MIN_SECRET_KEY_BYTES).also { key ->
            SecureRandom().nextBytes(key)
        }
    }
}

internal fun sha256Domain(
    domain: String,
    parts: List<String>
): String = sha256(
    buildString {
        appendLengthPrefixed(domain)
        parts.forEach { part -> appendLengthPrefixed(part) }
    }
)

private fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.toByteArray(StandardCharsets.UTF_8).size)
    append(':')
    append(value)
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .toHex()

private fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        chars[index * 2] = HEX_CHARS[value ushr 4]
        chars[index * 2 + 1] = HEX_CHARS[value and 0x0f]
    }
    return String(chars)
}

private fun JsonElement.toCanonicalJson(): String = when (this) {
    is JsonObject ->
        entries
            .sortedBy { (key, _) -> key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                "${JsonPrimitive(key)}:${value.toCanonicalJson()}"
            }
    is JsonArray -> joinToString(prefix = "[", postfix = "]", separator = ",") { element ->
        element.toCanonicalJson()
    }
    JsonNull -> "null"
    is JsonPrimitive -> when {
        isString -> JsonPrimitive(content).toString()
        booleanOrNull != null -> booleanOrNull.toString()
        else -> content.toCanonicalNumber()
    }
}

private fun String.toCanonicalNumber(): String {
    val number = BigDecimal(this).stripTrailingZeros()
    return if (number.compareTo(BigDecimal.ZERO) == 0) "0" else number.toPlainString()
}

private const val TOOL_NAME_HASH_DOMAIN = "tool-name-v1"
private const val ARGUMENTS_HASH_DOMAIN = "tool-arguments-v1"
private const val CALL_BINDING_HASH_DOMAIN = "tool-approval-call-v1"
private const val OPERATION_HASH_DOMAIN = "tool-operation-v1"
private const val HEX_CHARS = "0123456789abcdef"
