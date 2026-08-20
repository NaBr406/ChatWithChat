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
        /**
         * Advertisement budget, sized against a measured real server.
         *
         * A production ordering server (29 tools, Chinese descriptions, nested basket schemas) costs 23,544
         * chars of prompt text — see `McpLargeServerScopeTest`. At the previous ceiling of 20 tools the
         * *count* was what truncated it, not the chars, and the tools it dropped were the ones a task needs:
         * a coherent server is only useful as a set, since the model cannot price a basket if it can see
         * `calculate-price` but not `query-meals`.
         *
         * These are ceilings, not costs — nothing is advertised that the user did not enable. Overflow
         * degrades by relevance now, and `shouldReserveDiscovery` keeps `discover_tools` reachable.
         */
        private const val DEFAULT_MAX_ADVERTISED_TOOLS = 48
        private const val DEFAULT_MAX_ADVERTISED_SCHEMA_CHARS = 48_000

        /**
         * How many on-demand built-ins are pre-advertised before `discover_tools` is needed.
         *
         * This was one, on the reasoning that pre-advertising more than the single best intent match spends
         * prompt budget on tools the request never asked for. The budget argument does not survive contact
         * with the numbers: the whole built-in set costs a few hundred chars against a 48,000-char ceiling,
         * and the tools that actually consume it are MCP servers, which are resident and never compete for
         * these slots. What the ceiling of one bought instead was a coordination tax — any request needing
         * two built-ins together had to spend a `discover_tools` round first, and whatever it discovered was
         * only callable from the round after that. Requests that gave up mid-chain answered from the one
         * tool they could see, which is the failure the user reported as "工具之间的协调差得离谱".
         */
        private const val DEFAULT_MAX_INITIAL_ON_DEMAND_TOOLS = 4
        private const val DEFAULT_MAX_DISCOVERY_RESULTS = 6
        private const val DEFAULT_MAX_DISCOVERY_CALLS = 3
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
            // Intent rank sits between priority and name so that an overflowing resident set is cut by
            // relevance. Alphabetical order made the cut arbitrary: a 29-tool ordering server lost
            // `query-meals` and `query-nearby-stores` purely because `q` sorts late, which left the model
            // able to price a basket it could never assemble. Order is irrelevant when everything fits.
            .sortedWith(
                compareByDescending<ToolScopeCandidate> { candidate -> candidate.metadata.priority }
                    .thenByDescending { candidate -> candidate.matchScore(initialIntent) }
                    .thenBy { candidate -> candidate.definition.name }
            )
        val reserveDiscovery = shouldReserveDiscovery(residentCandidates)
        if (candidates.any { candidate -> candidate.metadata.exposure == ToolExposure.OnDemand } || reserveDiscovery) {
            advertise(ToolScopeCandidate(ToolDefinition.DiscoverTools, ToolDiscoveryMetadata(ToolExposure.Resident)))
            if (reserveDiscovery && contains(ToolDefinition.DiscoverTools.name)) {
                reservedDiscoverySlots = discoveryHeadroom()
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
            // Only a genuine budget problem: candidates matched, but none of them would fit.
            matchingCandidates.isNotEmpty() ->
                call.errorResult("tool_scope_budget_exceeded", "tool_scope_budget_exceeded")
            // Nothing matched. Previously this also reported a budget failure, which sent the model off
            // retrying a query that was never the problem instead of rewording it.
            candidates.any(::isDiscoverable) -> ToolResult(
                callId = call.id,
                name = call.name,
                content = "没有工具匹配该描述；可以换用其他关键词再试一次，或在能直接回答时直接回答。"
            )
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

    /**
     * How many slots to hold back for the *results* of discovery.
     *
     * Reserving a single slot only paid for the `discover_tools` entry itself: residents then filled every
     * remaining slot, so a discovery call could surface at most one tool no matter what
     * [maxDiscoveryResults] said. An overflowing coherent server was therefore unusable rather than merely
     * degraded — the model could reach one missing tool per call when it needed four together.
     *
     * Capped at a quarter of the budget so holding room for discovery can never starve the resident set
     * that most requests actually use.
     */
    private fun discoveryHeadroom(): Int {
        val maxTools = maxAdvertisedTools.coerceAtLeast(0)
        val cap = (maxTools / DISCOVERY_HEADROOM_DIVISOR).coerceAtLeast(1)
        return maxDiscoveryResults.coerceAtLeast(1).coerceAtMost(cap)
    }

    private fun shouldReserveDiscovery(residentCandidates: List<ToolScopeCandidate>): Boolean {        if (residentCandidates.isEmpty()) return false
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
        private const val DISCOVERY_HEADROOM_DIVISOR = 4
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

/**
 * Splits text into matchable terms.
 *
 * Latin script splits on word boundaries. CJK has none, so a Chinese phrase would otherwise survive as one
 * long token and only ever match a query containing that exact phrase — which is why every Chinese query
 * used to score zero against a Chinese tool description. Each CJK run is additionally emitted as its
 * adjacent character bigrams, the standard way to make CJK substring-matchable without shipping a
 * segmenter: "查询附近门店" yields 查询/询附/附近/近门/门店, so the query "附近的门店" overlaps on 附近 and 门店.
 *
 * The whole run is kept too, so an exact phrase match still earns the higher exact-term score.
 */
private fun String.discoveryWords(): List<String> = Regex("[\\p{L}\\p{N}_-]+")
    .findAll(lowercase())
    .map { match -> match.value.replace('_', ' ').replace('-', ' ') }
    .flatMap { value -> value.split(' ').asSequence() }
    .filter { value -> value.isNotBlank() }
    .flatMap { value -> sequenceOf(value) + value.cjkBigrams() }
    .distinct()
    .toList()

/** Adjacent character bigrams of every CJK run in this token; empty for Latin-only text. */
private fun String.cjkBigrams(): Sequence<String> = CJK_RUN.findAll(this)
    .flatMap { match ->
        val run = match.value
        if (run.length < CJK_BIGRAM_CHARS) {
            emptySequence()
        } else {
            (0..run.length - CJK_BIGRAM_CHARS).asSequence()
                .map { start -> run.substring(start, start + CJK_BIGRAM_CHARS) }
        }
    }

private const val CJK_BIGRAM_CHARS = 2

/** Han, Hiragana/Katakana, and Hangul. Explicit ranges rather than `\p{IsHan}` for predictability. */
private val CJK_RUN = Regex("[\\u1100-\\u11ff\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af\\uf900-\\ufaff]+")

private const val MIN_DISCOVERY_TERM_CHARS = 2
private const val EXACT_TERM_SCORE = 100
private const val PARTIAL_TERM_SCORE = 10
private const val MAX_TERM_BONUS = 20
private const val MAX_DISCOVERY_SUMMARY_CHARS = 120
private val DISCOVERY_GENERIC_TERMS = setOf(
    "a", "an", "and", "at", "by", "current", "for", "from", "in", "is", "local", "of", "on", "or",
    "the", "this", "to", "tool", "use", "with", "your"
)
