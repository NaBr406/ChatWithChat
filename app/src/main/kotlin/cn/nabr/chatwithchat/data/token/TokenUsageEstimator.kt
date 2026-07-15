package cn.nabr.chatwithchat.data.token

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingType
import cn.nabr.chatwithchat.data.database.entity.PlatformV2
import cn.nabr.chatwithchat.data.model.ClientType
import kotlin.math.ceil

object TokenUsageEstimator {
    private val registry by lazy { Encodings.newLazyEncodingRegistry() }

    fun estimateText(
        text: String,
        platform: PlatformV2
    ): Int = estimateText(text, platform.model, platform.compatibleType)

    fun estimateText(
        text: String,
        model: String,
        clientType: ClientType
    ): Int {
        if (text.isBlank()) return 0
        val encoding = encodingForModel(model) ?: fallbackEncoding(clientType)
        return runCatching { encoding?.countTokensOrdinary(text) }
            .getOrNull()
            ?: estimateTextHeuristically(text)
    }

    fun estimateRequestUsage(
        requestText: String,
        outputText: String,
        platform: PlatformV2,
        label: String,
        isToolRelated: Boolean
    ): TokenUsageRecord {
        val inputTokens = estimateText(requestText, platform)
        val outputTokens = estimateText(outputText, platform)
        val detail = TokenUsageDetail(
            label = label,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = inputTokens + outputTokens,
            isEstimated = true,
            isToolRelated = isToolRelated
        )

        return TokenUsageRecord(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = inputTokens + outputTokens,
            toolInputTokens = if (isToolRelated) inputTokens else 0,
            toolOutputTokens = if (isToolRelated) outputTokens else 0,
            toolTotalTokens = if (isToolRelated) inputTokens + outputTokens else 0,
            isEstimated = true,
            provider = platform.name,
            platformUid = platform.uid,
            model = platform.model,
            details = listOf(detail)
        )
    }

    private fun encodingForModel(model: String): Encoding? = runCatching {
        val encoding = registry.getEncodingForModel(model)
        if (encoding.isPresent) encoding.get() else null
    }.getOrNull()

    private fun fallbackEncoding(clientType: ClientType): Encoding? {
        val encodingType = when (clientType) {
            ClientType.OPENAI -> EncodingType.O200K_BASE
            else -> EncodingType.CL100K_BASE
        }
        return runCatching { registry.getEncoding(encodingType) }.getOrNull()
    }

    private fun estimateTextHeuristically(text: String): Int {
        var asciiRunLength = 0
        var tokens = 0

        fun flushAsciiRun() {
            if (asciiRunLength > 0) {
                tokens += ceil(asciiRunLength / 4.0).toInt().coerceAtLeast(1)
                asciiRunLength = 0
            }
        }

        text.forEach { char ->
            when {
                char.isWhitespace() -> flushAsciiRun()
                char.code in 0x21..0x7E -> asciiRunLength += 1
                else -> {
                    flushAsciiRun()
                    tokens += 1
                }
            }
        }
        flushAsciiRun()
        return tokens.coerceAtLeast(1)
    }
}
