package cn.nabr.chatwithchat.data.dto.openai.response

import cn.nabr.chatwithchat.data.dto.ProviderUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Response body of the standalone `POST /v1/responses/compact` endpoint.
 *
 * Every output item is kept as raw JSON ([OutputItem] wraps a `JsonObject`) because the whole point
 * of this window is that it is opaque: the compaction item carries forward state and reasoning in a
 * form that is explicitly not meant to be interpreted, and the items after it must be replayed
 * byte-for-byte. Parsing, trimming or rendering any of this is a correctness bug, not a feature.
 */
@Serializable
data class ResponsesCompactResponse(
    @SerialName("id")
    val id: String? = null,

    @SerialName("status")
    val status: String? = null,

    @SerialName("error")
    val error: ResponseError? = null,

    @SerialName("output")
    val output: List<OutputItem> = emptyList(),

    @SerialName("usage")
    val usage: ProviderUsage? = null
)

/**
 * Result of a compact call as the rest of the app sees it.
 *
 * [outputWindowJson] is the serialized `output` array, ready to be spliced verbatim into the next
 * `/v1/responses` input array. It is never inspected.
 */
data class ResponsesCompactResult(
    val outputWindowJson: String,
    val outputItemCount: Int,
    val usage: ProviderUsage? = null
)
