package dev.chungjungsoo.gptmobile.data.repository

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import dev.chungjungsoo.gptmobile.data.ModelConstants
import dev.chungjungsoo.gptmobile.data.dto.APIModel
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class ModelDiscoveryRepositoryImpl @Inject constructor(
    private val networkClient: NetworkClient
) : ModelDiscoveryRepository {

    override suspend fun fetchModels(
        clientType: ClientType,
        apiUrl: String,
        token: String?
    ): List<APIModel> = withContext(Dispatchers.IO) {
        val resolvedApiUrl = resolveApiUrl(clientType, apiUrl)
        require(resolvedApiUrl.isNotBlank()) { "api_url_required" }

        try {
            fetchModelsForClient(clientType, resolvedApiUrl, token)
                .distinctBy { it.aliasValue }
                .filter { it.aliasValue.isNotBlank() }
        } catch (e: Exception) {
            throw IllegalStateException(e.toModelFetchMessage(), e)
        }
    }

    private suspend fun fetchModelsForClient(
        clientType: ClientType,
        apiUrl: String,
        token: String?
    ): List<APIModel> = when (clientType) {
        ClientType.OPENAI, ClientType.GROQ, ClientType.OPENROUTER, ClientType.CUSTOM -> fetchOpenAICompatibleModels(apiUrl, token)
        ClientType.OLLAMA -> fetchOllamaModels(apiUrl, token)
        ClientType.GOOGLE -> fetchGoogleModels(apiUrl, token)
        ClientType.ANTHROPIC -> fetchAnthropicModels(apiUrl, token)
    }

    private suspend fun fetchOpenAICompatibleModels(apiUrl: String, token: String?): List<APIModel> {
        val responseBody = networkClient().prepareGet(joinEndpoint(apiUrl, "v1/models")) {
            token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
            parameter("limit", MODEL_LIMIT)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw httpException(response.status.value, response.body<String>())
            }
            response.body<String>()
        }

        return NetworkClient.json.decodeFromString<OpenAIModelListResponse>(responseBody).data.map { model ->
            APIModel(
                name = model.name?.takeIf { it.isNotBlank() } ?: model.id,
                description = model.description?.takeIf { it.isNotBlank() } ?: model.ownedBy.orEmpty(),
                aliasValue = model.id
            )
        }
    }

    private suspend fun fetchOllamaModels(apiUrl: String, token: String?): List<APIModel> {
        val tagsModels = runCatching { fetchOllamaTags(apiUrl, token) }.getOrDefault(emptyList())
        if (tagsModels.isNotEmpty()) return tagsModels

        return fetchOpenAICompatibleModels(apiUrl, token)
    }

    private suspend fun fetchOllamaTags(apiUrl: String, token: String?): List<APIModel> {
        val responseBody = networkClient().prepareGet(joinEndpoint(apiUrl, "api/tags")) {
            token?.takeIf { it.isNotBlank() }?.let { bearerAuth(it) }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw httpException(response.status.value, response.body<String>())
            }
            response.body<String>()
        }

        return NetworkClient.json.decodeFromString<OllamaTagsResponse>(responseBody).models.map { model ->
            APIModel(
                name = model.name,
                description = model.details?.parameterSize?.takeIf { it.isNotBlank() }.orEmpty(),
                aliasValue = model.name
            )
        }
    }

    private suspend fun fetchGoogleModels(apiUrl: String, token: String?): List<APIModel> {
        val responseBody = networkClient().prepareGet(joinEndpoint(apiUrl, "v1beta/models")) {
            token?.takeIf { it.isNotBlank() }?.let { parameter("key", it) }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw httpException(response.status.value, response.body<String>())
            }
            response.body<String>()
        }

        return NetworkClient.json.decodeFromString<GoogleModelListResponse>(responseBody).models
            .filter { model -> model.supportedGenerationMethods.isEmpty() || model.supportedGenerationMethods.any { it == "generateContent" || it == "streamGenerateContent" } }
            .map { model ->
                val id = model.name.removePrefix("models/")
                APIModel(
                    name = model.displayName?.takeIf { it.isNotBlank() } ?: id,
                    description = model.description.orEmpty(),
                    aliasValue = id
                )
            }
    }

    private suspend fun fetchAnthropicModels(apiUrl: String, token: String?): List<APIModel> {
        val responseBody = networkClient().prepareGet(joinEndpoint(apiUrl, "v1/models")) {
            header(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION)
            token?.takeIf { it.isNotBlank() }?.let { header(ANTHROPIC_API_KEY_HEADER, it) }
            parameter("limit", MODEL_LIMIT)
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw httpException(response.status.value, response.body<String>())
            }
            response.body<String>()
        }

        return NetworkClient.json.decodeFromString<AnthropicModelListResponse>(responseBody).data.map { model ->
            APIModel(
                name = model.displayName?.takeIf { it.isNotBlank() } ?: model.id,
                description = model.type.orEmpty(),
                aliasValue = model.id
            )
        }
    }

    private fun resolveApiUrl(clientType: ClientType, apiUrl: String): String = apiUrl.trim().ifBlank {
        when (clientType) {
            ClientType.OPENAI -> ModelConstants.OPENAI_API_URL
            ClientType.ANTHROPIC -> ModelConstants.ANTHROPIC_API_URL
            ClientType.GOOGLE -> ModelConstants.GOOGLE_API_URL
            ClientType.GROQ -> ModelConstants.GROQ_API_URL
            ClientType.OLLAMA -> ModelConstants.OLLAMA_API_URL
            ClientType.OPENROUTER -> ModelConstants.OPENROUTER_API_URL
            ClientType.CUSTOM -> ""
        }
    }

    private fun joinEndpoint(apiUrl: String, path: String): String {
        val base = apiUrl.trim().trimEnd('/')
        val cleanPath = path.trimStart('/')
        val normalizedPath = when {
            base.endsWith("/v1") && cleanPath.startsWith("v1/") -> cleanPath.removePrefix("v1/")
            base.endsWith("/v1beta") && cleanPath.startsWith("v1beta/") -> cleanPath.removePrefix("v1beta/")
            else -> cleanPath
        }
        return "$base/$normalizedPath"
    }

    private fun httpException(statusCode: Int, body: String): IllegalStateException =
        IllegalStateException("http_$statusCode:${body.take(MAX_ERROR_BODY_LENGTH)}")

    private fun Exception.toModelFetchMessage(): String = when (this) {
        is UnknownHostException -> "unknown_host"
        is java.nio.channels.UnresolvedAddressException -> "unresolved_address"
        is ConnectException -> "connection_failed"
        is SocketTimeoutException -> "request_timeout"
        is javax.net.ssl.SSLException -> "ssl_failed"
        else -> message ?: "model_fetch_failed"
    }

    companion object {
        private const val MODEL_LIMIT = 100
        private const val MAX_ERROR_BODY_LENGTH = 500
        private const val ANTHROPIC_API_KEY_HEADER = "x-api-key"
        private const val ANTHROPIC_VERSION_HEADER = "anthropic-version"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}

@Serializable
private data class OpenAIModelListResponse(
    val data: List<OpenAIModelResponse> = emptyList()
)

@Serializable
private data class OpenAIModelResponse(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    @SerialName("owned_by")
    val ownedBy: String? = null
)

@Serializable
private data class OllamaTagsResponse(
    val models: List<OllamaModelResponse> = emptyList()
)

@Serializable
private data class OllamaModelResponse(
    val name: String,
    val details: OllamaModelDetails? = null
)

@Serializable
private data class OllamaModelDetails(
    @SerialName("parameter_size")
    val parameterSize: String? = null
)

@Serializable
private data class GoogleModelListResponse(
    val models: List<GoogleModelResponse> = emptyList()
)

@Serializable
private data class GoogleModelResponse(
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val supportedGenerationMethods: List<String> = emptyList()
)

@Serializable
private data class AnthropicModelListResponse(
    val data: List<AnthropicModelResponse> = emptyList()
)

@Serializable
private data class AnthropicModelResponse(
    val id: String,
    @SerialName("display_name")
    val displayName: String? = null,
    val type: String? = null
)
