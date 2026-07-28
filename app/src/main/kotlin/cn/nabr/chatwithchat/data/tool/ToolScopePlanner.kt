package cn.nabr.chatwithchat.data.tool

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

fun interface ToolAdvertisementSizer {
    fun estimateChars(definitions: List<ToolDefinition>): Int

    companion object {
        val promptText = ToolAdvertisementSizer { definitions ->
            definitions.sumOf { definition -> definition.toPromptText().length }
        }
    }
}

/**
 * Keeps the model-visible tool set small without granting access to hidden tools.
 * A scope exists for one chat request only and is expanded by discover_tools.
 */
class ToolScopePlanner(
    private val maxAdvertisedTools: Int = DEFAULT_MAX_ADVERTISED_TOOLS,
    private val maxAdvertisedSchemaChars: Int = DEFAULT_MAX_ADVERTISED_SCHEMA_CHARS,
    private val maxInitialOnDemandTools: Int = DEFAULT_MAX_INITIAL_ON_DEMAND_TOOLS,
    private val maxDiscoveryResults: Int = DEFAULT_MAX_DISCOVERY_RESULTS,
    private val maxDiscoveryCalls: Int = DEFAULT_MAX_DISCOVERY_CALLS
) {
    fun createScope(
        entries: List<ToolCatalogEntry>,
        initialIntent: String = "",
        advertisementSizer: ToolAdvertisementSizer = ToolAdvertisementSizer.promptText
    ): ToolScope {
        val candidates = entries
            .asSequence()
            .map { entry -> ToolScopeCandidate(entry.definition, entry.discovery) }
            .filter { candidate -> candidate.definition.name != ToolDefinition.DiscoverTools.name }
            .distinctBy { candidate -> candidate.definition.name }
            .toList()
        return ToolScope(
            candidates = candidates,
            initialIntent = initialIntent,
            maxAdvertisedTools = maxAdvertisedTools,
            maxAdvertisedSchemaChars = maxAdvertisedSchemaChars,
            maxInitialOnDemandTools = maxInitialOnDemandTools,
            maxDiscoveryResults = maxDiscoveryResults,
            maxDiscoveryCalls = maxDiscoveryCalls,
            advertisementSizer = advertisementSizer
        )
    }

    private companion object {
        private const val DEFAULT_MAX_ADVERTISED_TOOLS = 6
        private const val DEFAULT_MAX_ADVERTISED_SCHEMA_CHARS = 4_800
        private const val DEFAULT_MAX_INITIAL_ON_DEMAND_TOOLS = 1
        private const val DEFAULT_MAX_DISCOVERY_RESULTS = 3
        private const val DEFAULT_MAX_DISCOVERY_CALLS = 2
    }
}

class ToolScope internal constructor(
    private val candidates: List<ToolScopeCandidate>,
    initialIntent: String,
    private val maxAdvertisedTools: Int,
    private val maxAdvertisedSchemaChars: Int,
    private val maxInitialOnDemandTools: Int,
    private val maxDiscoveryResults: Int,
    private val maxDiscoveryCalls: Int,
    private val advertisementSizer: ToolAdvertisementSizer
) {
    private val candidatesByName = candidates.associateBy { candidate -> candidate.definition.name }
    private val advertisedNames = linkedSetOf<String>()
    private var discoveryCalls = 0
    private var reservedDiscoverySlots = 0

    val definitions: List<ToolDefinition>
        get() = advertisedNames.mapNotNull { name ->
            if (name == ToolDefinition.DiscoverTools.name) {
                ToolDefinition.DiscoverTools
            } else {
                candidatesByName[name]?.definition
            }
        }

    val advertisedToolNames: Set<String>
        get() = advertisedNames.toSet()

    internal val maxDiscoveryRounds: Int
        get() = maxDiscoveryCalls.coerceAtLeast(0)

    init {
        val residentCandidates = candidates
            .filter { candidate ->
                candidate.metadata.exposure == ToolExposure.Resident ||
                    candidate.definition.name in AUTOMATIC_STICKER_TOOL_NAMES
            }
            .sortedWith(
                compareByDescending<ToolScopeCandidate> { candidate -> candidate.metadata.priority }
                    .thenBy { candidate -> candidate.definition.name }
            )
        val reserveDiscovery = shouldReserveDiscovery(residentCandidates)
        if (candidates.any { candidate -> candidate.metadata.exposure == ToolExposure.OnDemand } || reserveDiscovery) {
            advertise(ToolScopeCandidate(ToolDefinition.DiscoverTools, ToolDiscoveryMetadata(ToolExposure.Resident)))
            if (reserveDiscovery && contains(ToolDefinition.DiscoverTools.name)) {
                reservedDiscoverySlots = 1
            }
        }

        residentCandidates.forEach(::advertise)

        selectCandidates(initialIntent, maxInitialOnDemandTools).forEach(::advertise)
    }

    fun contains(toolName: String): Boolean = toolName in advertisedNames

    fun discover(call: ToolCall): ToolResult {
        val query = call.discoveryQuery().getOrElse { throwable ->
            return call.errorResult(
                message = "tool_arguments_invalid:${throwable.message ?: "discover_query_invalid"}",
                errorCode = "tool_arguments_invalid"
            )
        }
        if (!contains(ToolDefinition.DiscoverTools.name)) {
            return call.errorResult("tool_unavailable:${call.name}", "tool_unavailable")
        }
        if (discoveryCalls >= maxDiscoveryCalls.coerceAtLeast(0)) {
            return call.errorResult("tool_discovery_budget_exceeded", "tool_discovery_budget_exceeded")
        }
        discoveryCalls += 1
        reservedDiscoverySlots = 0

        val matchingCandidates = selectCandidates(query, maxDiscoveryResults)
        if (maxAdvertisedTools.coerceAtLeast(0) <= 1 && matchingCandidates.isNotEmpty()) {
            advertisedNames.remove(ToolDefinition.DiscoverTools.name)
        }
        val selected = matchingCandidates
            .filter(::advertise)
        return when {
            selected.isNotEmpty() -> ToolResult(
                callId = call.id,
                name = call.name,
                content = selected.joinToString(
                    prefix = "以下工具从下一次响应开始可用：\n",
                    separator = "\n"
                ) { candidate ->
                    "- ${candidate.definition.name}: ${candidate.summary()}"
                },
                metadata = mapOf(
                    "discovered_tool_names" to selected.joinToString(separator = ",") { candidate ->
                        candidate.definition.name
                    }
                )
            )
            candidates.any(::isDiscoverable) ->
                call.errorResult("tool_scope_budget_exceeded", "tool_scope_budget_exceeded")
            else -> ToolResult(
                callId = call.id,
                name = call.name,
                content = "没有其他已启用工具符合所需能力；可以直接回答时请直接回答。"
            )
        }
    }

    private fun advertise(candidate: ToolScopeCandidate): Boolean {
        if (candidate.definition.name in advertisedNames) return false
        val closure = companionClosure(candidate) ?: return false
        val newCandidates = closure.filter { companion -> companion.definition.name !in advertisedNames }
        if (newCandidates.isEmpty()) return false

        val isDiscoveryControl = candidate.definition.name == ToolDefinition.DiscoverTools.name
        val availableSlots = maxAdvertisedTools.coerceAtLeast(0) - advertisedNames.size -
            if (isDiscoveryControl) 0 else reservedDiscoverySlots
        if (newCandidates.size > availableSlots) return false
        val prospectiveDefinitions = definitions + newCandidates.map(ToolScopeCandidate::definition)
        val prospectiveCost = runCatching {
            advertisementSizer.estimateChars(prospectiveDefinitions)
        }.getOrElse {
            Int.MAX_VALUE
        }
        if (prospectiveCost > maxAdvertisedSchemaChars.coerceAtLeast(0)) return false
        advertisedNames += newCandidates.map { companion -> companion.definition.name }
        return true
    }

    private fun shouldReserveDiscovery(residentCandidates: List<ToolScopeCandidate>): Boolean {
        if (residentCandidates.isEmpty()) return false
        val maxTools = maxAdvertisedTools.coerceAtLeast(0)
        val hasOnDemandCandidates = candidates.any { candidate ->
            candidate.metadata.exposure == ToolExposure.OnDemand
        }
        val residentSlotLimit = if (hasOnDemandCandidates) {
            maxTools - 2
        } else {
            maxTools
        }
        if (residentCandidates.size > residentSlotLimit) return true
        val residentCost = runCatching {
            advertisementSizer.estimateChars(residentCandidates.map(ToolScopeCandidate::definition))
        }.getOrElse {
            Int.MAX_VALUE
        }
        if (residentCost > maxAdvertisedSchemaChars.coerceAtLeast(0)) return true
        if (!hasOnDemandCandidates) return false

        val discoveryAndResidentCost = runCatching {
            advertisementSizer.estimateChars(
                listOf(ToolDefinition.DiscoverTools) + residentCandidates.map(ToolScopeCandidate::definition)
            )
        }.getOrElse {
            Int.MAX_VALUE
        }
        return discoveryAndResidentCost > maxAdvertisedSchemaChars.coerceAtLeast(0)
    }

    private fun isDiscoverable(candidate: ToolScopeCandidate): Boolean =
        candidate.definition.name !in advertisedNames && companionClosure(candidate) != null

    private fun companionClosure(candidate: ToolScopeCandidate): List<ToolScopeCandidate>? {
        val closure = mutableListOf<ToolScopeCandidate>()
        val visitedNames = mutableSetOf<String>()

        fun collect(current: ToolScopeCandidate): Boolean {
            if (!visitedNames.add(current.definition.name)) return true
            closure += current
            for (companionName in current.metadata.requiredCompanionToolNames.sorted()) {
                val companion = candidatesByName[companionName] ?: return false
                if (!collect(companion)) return false
            }
            return true
        }

        return closure.takeIf { collect(candidate) }
    }

    private fun selectCandidates(query: String, limit: Int): List<ToolScopeCandidate> = candidates
        .asSequence()
        .filter(::isDiscoverable)
        .map { candidate -> candidate to candidate.matchScore(query) }
        .filter { (_, score) -> score > 0 }
        .sortedWith(
            compareByDescending<Pair<ToolScopeCandidate, Int>> { (_, score) -> score }
                .thenByDescending { (candidate, _) -> candidate.metadata.priority }
                .thenBy { (candidate, _) -> candidate.definition.name }
        )
        .map { (candidate, _) -> candidate }
        .take(limit.coerceAtLeast(0))
        .toList()

    private fun ToolCall.discoveryQuery(): Result<String> = runCatching {
        val value = argumentsObject().getOrThrow()["query"] as? JsonPrimitive
            ?: throw IllegalArgumentException("query_required")
        val query = value.contentOrNull?.trim().orEmpty()
        require(query.isNotBlank()) { "query_required" }
        require(query.length <= MAX_DISCOVERY_QUERY_CHARS) { "query_too_long" }
        query
    }

    private companion object {
        private const val MAX_DISCOVERY_QUERY_CHARS = 160
        private val AUTOMATIC_STICKER_TOOL_NAMES = setOf(
            ToolDefinition.SearchStickers.name,
            ToolDefinition.SendSticker.name
        )
    }
}

internal data class ToolScopeCandidate(
    val definition: ToolDefinition,
    val metadata: ToolDiscoveryMetadata
) {
    fun summary(): String = definition.description
        .substringBeforeSentenceEnd()
        .trim()
        .take(MAX_DISCOVERY_SUMMARY_CHARS)
        .ifBlank { definition.name.replace('_', ' ') }

    fun matchScore(query: String): Int {
        val normalizedQuery = query.lowercase()
        if (normalizedQuery.isBlank()) return 0
        val terms = buildSet {
            addAll(metadata.intentTags)
            add(definition.name.replace('_', ' '))
            addAll(definition.name.split('_', '-').filterNot(DISCOVERY_GENERIC_TERMS::contains))
            if (metadata.intentTags.isEmpty()) {
                addAll(definition.description.discoveryTerms())
            }
        }
        return terms
            .filterNot { term -> term.lowercase().trim() in DISCOVERY_GENERIC_TERMS }
            .maxOfOrNull { term ->
                val normalizedTerm = term.lowercase().trim()
                when {
                    normalizedTerm.length < MIN_DISCOVERY_TERM_CHARS -> 0
                    normalizedQuery.contains(normalizedTerm) ->
                        EXACT_TERM_SCORE + normalizedTerm.length.coerceAtMost(MAX_TERM_BONUS)
                    else -> normalizedTerm.discoveryWords()
                        .filterNot(DISCOVERY_GENERIC_TERMS::contains)
                        .count { word -> word in normalizedQuery }
                        .takeIf { matches -> matches > 0 }
                        ?.let { matches -> matches * PARTIAL_TERM_SCORE }
                        ?: 0
                }
            }
            ?: 0
    }
}

internal fun Collection<ToolResult>.hasSuccessfulToolDiscovery(): Boolean = any { result ->
    result.name == ToolDefinition.DiscoverTools.name &&
        !result.isError &&
        result.metadata["discovered_tool_names"]
            ?.split(',')
            ?.any { name -> name.isNotBlank() } == true
}

private fun String.discoveryTerms(): Set<String> = discoveryWords()
    .filter { word -> word.length >= MIN_DISCOVERY_TERM_CHARS && word !in DISCOVERY_GENERIC_TERMS }
    .toSet()

private fun String.discoveryWords(): List<String> = Regex("[\\p{L}\\p{N}_-]+")
    .findAll(lowercase())
    .map { match -> match.value.replace('_', ' ').replace('-', ' ') }
    .flatMap { value -> value.split(' ').asSequence() }
    .filter { value -> value.isNotBlank() }
    .toList()

private const val MIN_DISCOVERY_TERM_CHARS = 2
private const val EXACT_TERM_SCORE = 100
private const val PARTIAL_TERM_SCORE = 10
private const val MAX_TERM_BONUS = 20
private const val MAX_DISCOVERY_SUMMARY_CHARS = 120
private val DISCOVERY_GENERIC_TERMS = setOf(
    "a", "an", "and", "at", "by", "current", "for", "from", "in", "is", "local", "of", "on", "or",
    "the", "this", "to", "tool", "use", "with", "your"
)
