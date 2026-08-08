package cn.nabr.chatwithchat.data.network

/**
 * Raised when the official OpenAI compaction path fails, either on `/v1/responses/compact` or when a
 * `/v1/responses` request rejects `context_management`.
 *
 * Per the failure contract, a failure here never falls back to an app-side LLM summary and never
 * overwrites existing native state — except that [isUnsupportedParameter] is a durable signal: the
 * model is marked as not supporting native compaction so the app stops retrying it every turn and
 * degrades to the app-side checkpoint path.
 */
class OpenAICompactionException(
    message: String,
    val code: String? = null
) : IllegalStateException(message) {

    val isUnsupportedParameter: Boolean
        get() = code == UNSUPPORTED_PARAMETER || code == UNSUPPORTED_VALUE

    /** The range could not be submitted in one piece and must be initialised in smaller windows. */
    val isInputTooLarge: Boolean
        get() = code == CONTEXT_LENGTH_EXCEEDED

    companion object {
        const val UNSUPPORTED_PARAMETER = "unsupported_parameter"
        const val UNSUPPORTED_VALUE = "unsupported_value"
        const val CONTEXT_LENGTH_EXCEEDED = "context_length_exceeded"
    }
}
