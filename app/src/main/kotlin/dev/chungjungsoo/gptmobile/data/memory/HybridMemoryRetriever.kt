package dev.chungjungsoo.gptmobile.data.memory

import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingAvailability
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingCapability
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexConfiguration
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorIndexIdentity
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorQuery
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorQueryResult
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorSnapshotExpectation
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorSnapshotVerification
import dev.chungjungsoo.gptmobile.data.memory.vector.MemoryVectorStore
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException

class HybridMemoryRetriever(
    private val snapshotSource: MemoryCorpusSnapshotSource,
    private val lexicalRetriever: MarkdownLexicalRetriever,
    private val vectorStore: MemoryVectorStore,
    private val embeddingCapability: MemoryEmbeddingCapability,
    private val vectorRecallStateSource: MemoryVectorRecallStateSource,
    private val repairTrigger: MemoryVectorRecallRepairTrigger
) : MemoryRetriever {
    override suspend fun retrieve(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>> = try {
        Result.success(retrieveCurrentSnapshot(request))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

    private suspend fun retrieveCurrentSnapshot(request: MemoryRetrievalRequest): List<MemoryRetrievalResult> {
        require(request.corpus == MemoryCorpus.CHAT_RECALL_LONG_TERM) {
            "Hybrid recall only supports ${MemoryCorpus.CHAT_RECALL_LONG_TERM}"
        }
        if (request.limit <= 0 || request.tokenBudget <= 0) return emptyList()
        val combinedQuery = request.combinedQuery()
        if (combinedQuery.isBlank()) return emptyList()

        repeat(MAX_SNAPSHOT_ATTEMPTS) {
            val snapshots = snapshotSource.snapshots(request.corpus).getOrThrow()
            val snapshot = snapshots.singleOrNull()
                ?.takeIf { current ->
                    current.corpus == MemoryCorpus.CHAT_RECALL_LONG_TERM &&
                        current.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME
                }
                ?: return emptyList()
            val lexicalCandidates = lexicalRetriever.rankCandidates(
                request = request.copy(strategy = MemoryRetrievalStrategy.LEXICAL),
                combinedQuery = combinedQuery,
                snapshots = listOf(snapshot)
            )
            val ranked = when (request.strategy) {
                MemoryRetrievalStrategy.LEXICAL -> lexicalCandidates
                MemoryRetrievalStrategy.VECTOR,
                MemoryRetrievalStrategy.HYBRID -> {
                    val vectorCandidates = retrieveVectorCandidates(request, combinedQuery, snapshot)
                    if (vectorCandidates.isNullOrEmpty()) {
                        diversifyLexical(lexicalCandidates, request.candidateLimit)
                    } else {
                        fuseAndDiversify(
                            lexicalCandidates = if (request.strategy == MemoryRetrievalStrategy.HYBRID) {
                                lexicalCandidates
                            } else {
                                emptyList()
                            },
                            vectorCandidates = vectorCandidates,
                            candidateLimit = request.candidateLimit
                        )
                    }
                }
            }
            val packed = ranked.packFor(request)
            if (snapshotSource.isCurrent(listOf(snapshot)).getOrThrow()) {
                return packed
            }
        }
        return emptyList()
    }

    private suspend fun retrieveVectorCandidates(
        request: MemoryRetrievalRequest,
        combinedQuery: String,
        snapshot: MemoryCorpusSnapshot
    ): List<VectorCandidate>? {
        return try {
            val capability = embeddingCapability as? MemoryEmbeddingCapability.Ready ?: return unavailableVectorBranch()
            val configuration = capability.configuration
            val expectedIdentity = vectorRecallStateSource.expectedIdentity(snapshot, configuration)
                ?: return unavailableVectorBranch()
            val expectation = expectedIdentity.toExpectation(snapshot.chunks)
            val verifiedManifest = when (val verification = vectorStore.verifySnapshot(expectation)) {
                is MemoryVectorSnapshotVerification.Ready -> verification.manifest
                is MemoryVectorSnapshotVerification.Stale,
                MemoryVectorSnapshotVerification.Missing,
                MemoryVectorSnapshotVerification.RecoveredCorruption -> return unavailableVectorBranch()
            }
            if (verifiedManifest.identity != expectedIdentity) return unavailableVectorBranch()
            if (capability.provider.availability() != MemoryEmbeddingAvailability.Available) {
                return unavailableVectorBranch()
            }

            val queryEmbedding = capability.provider.embedQuery(combinedQuery).getOrElse {
                return unavailableVectorBranch()
            }
            if (!queryEmbedding.isValidFor(configuration)) return unavailableVectorBranch()
            val queryResult = vectorStore.query(
                MemoryVectorQuery(
                    expectedIdentity = expectedIdentity,
                    embedding = queryEmbedding,
                    limit = request.candidateLimit.coerceIn(1, MAX_CANDIDATE_LIMIT)
                )
            ) as? MemoryVectorQueryResult.Ready ?: return unavailableVectorBranch()
            if (queryResult.manifest.identity != expectedIdentity) return unavailableVectorBranch()

            val currentChunks = snapshot.chunks.associateBy(MemoryCorpusChunk::chunkId)
            queryResult.matches
                .asSequence()
                .mapNotNull { match ->
                    val current = currentChunks[match.chunk.chunkId]
                        ?.takeIf { chunk -> chunk.contentHash == match.chunk.contentHash }
                        ?: return@mapNotNull null
                    CurrentVectorMatch(current, match.embedding, match.cosineDistance)
                }
                .filter { match ->
                    request.includePrivate ||
                        match.chunk.sensitivity == null ||
                        match.chunk.sensitivity !in setOf(MemorySensitivity.PRIVATE, MemorySensitivity.SENSITIVE)
                }
                .sortedWith(
                    compareBy<CurrentVectorMatch> { match ->
                        match.cosineDistance
                    }.thenBy { match -> match.chunk.chunkId }
                )
                .map { match ->
                    VectorCandidate(
                        result = MemoryRetrievalResult(
                            chunkId = match.chunk.chunkId,
                            entryId = match.chunk.entryId,
                            sourcePath = match.chunk.sourcePath,
                            text = match.chunk.text,
                            type = match.chunk.type,
                            sensitivity = match.chunk.sensitivity,
                            source = match.chunk.source,
                            contentHash = match.chunk.contentHash,
                            lexicalScore = null,
                            vectorScore = (1f - match.cosineDistance).coerceIn(-1f, 1f),
                            fusedScore = 0f,
                            updatedAt = match.chunk.updatedAt
                        ),
                        embedding = match.embedding
                    )
                }
                .distinctBy { candidate -> candidate.result.deduplicationKey() }
                .toList()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            unavailableVectorBranch()
        }
    }

    private fun unavailableVectorBranch(): List<VectorCandidate>? {
        runCatching { repairTrigger.requestRepair() }
        return null
    }

    private fun fuseAndDiversify(
        lexicalCandidates: List<MemoryRetrievalResult>,
        vectorCandidates: List<VectorCandidate>,
        candidateLimit: Int
    ): List<MemoryRetrievalResult> {
        val lexicalByKey = lexicalCandidates.associateBy { candidate -> candidate.deduplicationKey() }
        val vectorByKey = vectorCandidates.associateBy { candidate -> candidate.result.deduplicationKey() }
        val lexicalRanks = lexicalCandidates.withIndex().associate { indexed ->
            indexed.value.deduplicationKey() to indexed.index + 1
        }
        val vectorRanks = vectorCandidates.withIndex().associate { indexed ->
            indexed.value.result.deduplicationKey() to indexed.index + 1
        }
        val keys = (lexicalRanks.keys + vectorRanks.keys).toSet()
        val fused = keys.map { key ->
            val lexical = lexicalByKey[key]
            val vector = vectorByKey[key]
            val representative = lexical ?: checkNotNull(vector).result
            val score = listOfNotNull(
                lexicalRanks[key]?.let { rank -> reciprocalRank(rank) },
                vectorRanks[key]?.let { rank -> reciprocalRank(rank) }
            ).sum()
            DiversifiableCandidate(
                result = representative.copy(
                    lexicalScore = lexical?.lexicalScore,
                    vectorScore = vector?.result?.vectorScore,
                    fusedScore = score
                ),
                embedding = vector?.embedding?.takeIf { representative.chunkId == vector.result.chunkId }
            )
        }
        return diversify(fused, candidateLimit.coerceIn(1, MAX_CANDIDATE_LIMIT))
            .map(DiversifiableCandidate::result)
    }

    private fun diversifyLexical(
        candidates: List<MemoryRetrievalResult>,
        candidateLimit: Int
    ): List<MemoryRetrievalResult> = diversify(
        candidates = candidates.map { result -> DiversifiableCandidate(result, embedding = null) },
        limit = candidateLimit.coerceIn(1, MAX_CANDIDATE_LIMIT)
    ).map(DiversifiableCandidate::result)

    private fun diversify(
        candidates: List<DiversifiableCandidate>,
        limit: Int
    ): List<DiversifiableCandidate> {
        if (candidates.size <= 1) return candidates
        val maxFusedScore = candidates.maxOf { candidate -> candidate.result.fusedScore }.coerceAtLeast(1e-9f)
        val remaining = candidates.toMutableList()
        val selected = mutableListOf<DiversifiableCandidate>()
        while (remaining.isNotEmpty() && selected.size < limit) {
            val next = remaining.sortedWith(
                compareByDescending<DiversifiableCandidate> { candidate ->
                    val relevance = candidate.result.fusedScore / maxFusedScore
                    val redundancy = selected.maxOfOrNull { chosen -> candidate.similarityTo(chosen) } ?: 0f
                    MMR_RELEVANCE_WEIGHT * relevance - MMR_DIVERSITY_WEIGHT * redundancy
                }.thenByDescending { candidate -> candidate.result.fusedScore }
                    .thenByDescending { candidate -> candidate.result.updatedAt }
                    .thenBy { candidate -> candidate.result.chunkId }
            ).first()
            selected += next
            remaining -= next
        }
        return selected
    }

    private fun DiversifiableCandidate.similarityTo(other: DiversifiableCandidate): Float {
        val left = embedding
        val right = other.embedding
        if (left != null && right != null && left.size == right.size) {
            return left.indices.sumOf { index -> left[index].toDouble() * right[index].toDouble() }
                .toFloat()
                .coerceIn(0f, 1f)
        }
        val leftTokens = diversityTokens(result.text)
        val rightTokens = diversityTokens(other.result.text)
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0f
        return leftTokens.intersect(rightTokens).size.toFloat() / leftTokens.union(rightTokens).size
    }

    private fun diversityTokens(text: String): Set<String> = DIVERSITY_TOKEN_REGEX
        .findAll(text.lowercase())
        .map { match -> match.value }
        .toSet()

    private fun MemoryRetrievalResult.deduplicationKey(): String =
        entryId?.let { value -> "entry:$value" } ?: "hash:$contentHash"

    private fun reciprocalRank(rank: Int): Float = 1f / (RRF_K + rank)

    private fun MemoryVectorIndexIdentity.toExpectation(
        chunks: List<MemoryCorpusChunk>
    ): MemoryVectorSnapshotExpectation = MemoryVectorSnapshotExpectation(
        corpus = corpus,
        sourcePath = sourcePath,
        sourceHash = sourceHash,
        corpusGeneration = corpusGeneration,
        indexFingerprint = indexFingerprint,
        chunks = chunks
    )

    private fun FloatArray.isValidFor(configuration: MemoryVectorIndexConfiguration): Boolean {
        if (size != configuration.embeddingDescriptor.dimension || any { value -> !value.isFinite() }) {
            return false
        }
        if (!configuration.embeddingDescriptor.normalized) return true
        val norm = sqrt(sumOf { value -> value.toDouble() * value.toDouble() })
        return abs(norm - 1.0) <= NORMALIZED_VECTOR_TOLERANCE
    }

    private data class VectorCandidate(
        val result: MemoryRetrievalResult,
        val embedding: FloatArray
    )

    private data class CurrentVectorMatch(
        val chunk: MemoryCorpusChunk,
        val embedding: FloatArray,
        val cosineDistance: Float
    )

    private data class DiversifiableCandidate(
        val result: MemoryRetrievalResult,
        val embedding: FloatArray?
    )

    private companion object {
        private val DIVERSITY_TOKEN_REGEX = Regex("[a-z0-9_]+|[\\u3400-\\u9fff]")
        const val RRF_K = 60f
        const val MMR_RELEVANCE_WEIGHT = 0.75f
        const val MMR_DIVERSITY_WEIGHT = 0.25f
        const val MAX_CANDIDATE_LIMIT = 500
        const val MAX_SNAPSHOT_ATTEMPTS = 2
        const val NORMALIZED_VECTOR_TOLERANCE = 1e-3
    }
}
