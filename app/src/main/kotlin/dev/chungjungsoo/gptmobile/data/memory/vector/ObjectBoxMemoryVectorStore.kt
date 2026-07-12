package dev.chungjungsoo.gptmobile.data.memory.vector

import android.content.Context
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.exception.DbSchemaException
import io.objectbox.exception.FileCorruptException
import io.objectbox.query.QueryCondition
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpus
import dev.chungjungsoo.gptmobile.data.memory.MemoryCorpusChunk
import dev.chungjungsoo.gptmobile.data.memory.MemoryFilePaths
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingDescriptor
import dev.chungjungsoo.gptmobile.data.memory.embedding.MemoryEmbeddingPooling
import java.util.concurrent.Callable
import kotlin.math.abs
import kotlin.math.sqrt

internal class ObjectBoxMemoryVectorStore(
    private val context: Context,
    private val directory: MemoryVectorStoreDirectory,
    private val beforeManifestPublished: () -> Unit = {}
) : MemoryVectorStore {
    private val lock = Any()
    private var store: BoxStore? = null
    private var isClosed = false

    override fun readManifest(): MemoryVectorManifest? = withStore { currentStore ->
        currentStore.callInReadTx(
            Callable {
                currentStore.manifestBox().findCurrentManifest()?.toDomain()
            }
        )
    }

    override fun countChunks(): Long = withStore { currentStore ->
        currentStore.callInReadTx(Callable { currentStore.chunkBox().count() })
    }

    override fun verifySnapshot(
        expectation: MemoryVectorSnapshotExpectation
    ): MemoryVectorSnapshotVerification = synchronized(lock) {
        check(!isClosed) { "Memory vector store is closed" }
        try {
            val currentStore = openStoreLocked()
            currentStore.callInReadTx(
                Callable { verifyCurrentSnapshot(currentStore, expectation) }
            )
        } catch (throwable: Throwable) {
            if (!throwable.isRecoverableVectorStoreCorruption()) throw throwable
            closeStoreLocked()
            directory.deleteAll()
            MemoryVectorSnapshotVerification.RecoveredCorruption
        }
    }

    override fun replaceSnapshot(snapshot: MemoryVectorSnapshot): MemoryVectorPublishResult {
        validateSnapshot(snapshot)
        val chunkEntities = snapshot.chunks.map { embeddedChunk ->
            embeddedChunk.toEntity(snapshot.manifest.identity)
        }
        val manifestEntity = snapshot.manifest.toEntity()

        return withStore { currentStore ->
            val chunkBox = currentStore.chunkBox()
            val manifestBox = currentStore.manifestBox()
            currentStore.callInTx(
                Callable {
                    val currentManifest = manifestBox.findCurrentManifest()?.toDomain()
                    publicationResultOrNull(
                        currentStore = currentStore,
                        currentManifest = currentManifest,
                        targetManifest = snapshot.manifest
                    )?.let { result -> return@Callable result }

                    manifestBox.removeAll()
                    chunkBox.removeAll()
                    if (chunkEntities.isNotEmpty()) {
                        chunkBox.put(chunkEntities)
                    }
                    beforeManifestPublished()
                    manifestBox.put(manifestEntity)
                    MemoryVectorPublishResult.PUBLISHED
                }
            )
        }
    }

    override fun query(request: MemoryVectorQuery): MemoryVectorQueryResult {
        validateIdentity(request.expectedIdentity)
        validateEmbedding(request.embedding, "query embedding")
        require(request.limit in 1..MAX_MEMORY_VECTOR_QUERY_LIMIT) {
            "limit must be between 1 and $MAX_MEMORY_VECTOR_QUERY_LIMIT"
        }

        return withStore { currentStore ->
            currentStore.callInReadTx(
                Callable {
                    val manifest = currentStore.manifestBox().findCurrentManifest()?.toDomain()
                        ?: return@Callable MemoryVectorQueryResult.Unavailable(
                            MemoryVectorUnavailableReason.MISSING_MANIFEST
                        )
                    if (manifest.state != MemoryVectorManifestState.READY) {
                        return@Callable MemoryVectorQueryResult.Unavailable(
                            MemoryVectorUnavailableReason.MANIFEST_NOT_READY
                        )
                    }
                    if (manifest.identity != request.expectedIdentity) {
                        return@Callable MemoryVectorQueryResult.Unavailable(
                            MemoryVectorUnavailableReason.STALE_MANIFEST
                        )
                    }

                    val chunkBox = currentStore.chunkBox()
                    val matchingChunkCount = chunkBox
                        .query(chunkIdentityCondition(manifest.identity))
                        .build()
                        .use { query -> query.count() }
                    if (matchingChunkCount != manifest.expectedChunkCount) {
                        return@Callable MemoryVectorQueryResult.Unavailable(
                            MemoryVectorUnavailableReason.CHUNK_COUNT_MISMATCH
                        )
                    }
                    if (manifest.expectedChunkCount == 0L) {
                        return@Callable MemoryVectorQueryResult.Ready(manifest, emptyList())
                    }

                    val matches = chunkBox
                        .query(
                            chunkIdentityCondition(manifest.identity).and(
                                MemoryVectorChunkEntity_.embedding.nearestNeighbors(request.embedding, request.limit)
                            )
                        )
                        .build()
                        .use { query ->
                            query.findWithScores().map { scored ->
                                scored.get().toMatch(manifest.identity, scored.score.toFloat())
                            }
                        }
                    MemoryVectorQueryResult.Ready(manifest, matches)
                }
            )
        }
    }

    override fun clearSnapshot() {
        withStore { currentStore ->
            val chunkBox = currentStore.chunkBox()
            val manifestBox = currentStore.manifestBox()
            currentStore.runInTx {
                manifestBox.removeAll()
                chunkBox.removeAll()
            }
        }
    }

    override fun deleteDerivedStore() {
        synchronized(lock) {
            check(!isClosed) { "Memory vector store is closed" }
            closeStoreLocked()
            directory.deleteAll()
        }
    }

    override fun recoverFromCorruption(cause: Throwable): Boolean {
        if (!cause.isRecoverableVectorStoreCorruption()) return false
        deleteDerivedStore()
        return true
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            closeStoreLocked()
            isClosed = true
        }
    }

    private fun <T> withStore(block: (BoxStore) -> T): T = synchronized(lock) {
        check(!isClosed) { "Memory vector store is closed" }
        block(openStoreLocked())
    }

    private fun openStoreLocked(): BoxStore = store ?: MyObjectBox.builder()
        .androidContext(context)
        .directory(directory.file)
        .build()
        .also { openedStore -> store = openedStore }

    private fun closeStoreLocked() {
        store?.close()
        store = null
    }

    private fun validateSnapshot(snapshot: MemoryVectorSnapshot) {
        val manifest = snapshot.manifest
        validateIdentity(manifest.identity)
        require(manifest.state == MemoryVectorManifestState.READY) {
            "Only a complete READY vector snapshot can be published"
        }
        require(manifest.completedAt > 0) { "completedAt must be positive" }
        require(manifest.expectedChunkCount == snapshot.chunks.size.toLong()) {
            "expectedChunkCount must match the supplied chunks"
        }

        val chunkIds = HashSet<String>(snapshot.chunks.size)
        snapshot.chunks.forEach { embeddedChunk ->
            val chunk = embeddedChunk.chunk
            require(chunk.chunkId.isNotBlank()) { "chunkId must not be blank" }
            require(chunkIds.add(chunk.chunkId)) { "chunkId values must be unique" }
            require(chunk.sourcePath == manifest.identity.sourcePath) {
                "Every chunk must belong to the manifest sourcePath"
            }
            require(chunk.contentHash.matches(SHA_256_REGEX)) {
                "contentHash must be a lowercase SHA-256 value"
            }
            validateEmbedding(embeddedChunk.embedding, "chunk ${chunk.chunkId} embedding")
        }
    }

    private fun publicationResultOrNull(
        currentStore: BoxStore,
        currentManifest: MemoryVectorManifest?,
        targetManifest: MemoryVectorManifest
    ): MemoryVectorPublishResult? {
        currentManifest ?: return null
        val currentGeneration = currentManifest.identity.corpusGeneration
        val targetGeneration = targetManifest.identity.corpusGeneration
        if (currentGeneration > targetGeneration) {
            return MemoryVectorPublishResult.SUPERSEDED
        }
        if (currentGeneration < targetGeneration) {
            return null
        }
        if (currentManifest.identity != targetManifest.identity) {
            throw MemoryVectorStoreConflictException(
                "Vector manifests have the same generation but different identities"
            )
        }
        if (currentManifest.state != MemoryVectorManifestState.READY) {
            return null
        }
        if (currentManifest.expectedChunkCount != targetManifest.expectedChunkCount) {
            throw MemoryVectorStoreConflictException(
                "Vector manifests have the same identity but different chunk counts"
            )
        }

        validatePublishedSnapshot(currentStore, currentManifest)
        return MemoryVectorPublishResult.ALREADY_READY
    }

    private fun verifyCurrentSnapshot(
        currentStore: BoxStore,
        expectation: MemoryVectorSnapshotExpectation
    ): MemoryVectorSnapshotVerification {
        validateExpectation(expectation)
        val manifest = currentStore.manifestBox().findCurrentManifest()?.toDomain()
            ?: return MemoryVectorSnapshotVerification.Missing
        if (manifest.state != MemoryVectorManifestState.READY) {
            throw MemoryVectorStoreCorruptionException("Published vector manifest is not READY")
        }
        if (!manifest.identity.matches(expectation)) {
            return MemoryVectorSnapshotVerification.Stale(manifest)
        }
        validatePublishedSnapshot(currentStore, manifest, expectation.chunks)
        return MemoryVectorSnapshotVerification.Ready(manifest)
    }

    private fun validatePublishedSnapshot(
        currentStore: BoxStore,
        manifest: MemoryVectorManifest,
        expectedChunks: List<MemoryCorpusChunk>? = null
    ) {
        val chunkBox = currentStore.chunkBox()
        val chunks = chunkBox
            .query(chunkIdentityCondition(manifest.identity))
            .build()
            .use { query -> query.find() }
        val totalChunkCount = chunkBox.count()
        if (
            chunks.size.toLong() != manifest.expectedChunkCount ||
            totalChunkCount != manifest.expectedChunkCount
        ) {
            throw MemoryVectorStoreCorruptionException(
                "Published vector snapshot does not match its manifest"
            )
        }
        chunks.forEach { chunk -> chunk.validateAgainst(manifest.identity) }
        expectedChunks?.let { expected ->
            val expectedById = expected.associateBy(MemoryCorpusChunk::chunkId)
            if (
                expectedById.size != expected.size ||
                chunks.size != expected.size ||
                chunks.any { chunk ->
                    expectedById[chunk.chunkId]?.let { expectedChunk ->
                        chunk.matches(expectedChunk)
                    } != true
                }
            ) {
                throw MemoryVectorStoreCorruptionException(
                    "Published vector chunks do not match the current Markdown snapshot"
                )
            }
        }
    }

    private fun validateExpectation(expectation: MemoryVectorSnapshotExpectation) {
        require(expectation.corpus == MemoryCorpus.CHAT_RECALL_LONG_TERM) {
            "The first vector store only accepts CHAT_RECALL_LONG_TERM"
        }
        require(expectation.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) {
            "The first vector store only accepts MEMORY.md"
        }
        require(expectation.sourceHash.matches(SHA_256_REGEX)) {
            "sourceHash must be a lowercase SHA-256 value"
        }
        require(expectation.corpusGeneration >= 0) { "corpusGeneration must not be negative" }
        require(expectation.indexFingerprint.matches(SHA_256_REGEX)) {
            "indexFingerprint must be a lowercase SHA-256 value"
        }
        require(expectation.chunks.map(MemoryCorpusChunk::chunkId).distinct().size == expectation.chunks.size) {
            "Expected chunk IDs must be unique"
        }
        expectation.chunks.forEach { chunk ->
            require(chunk.sourcePath == expectation.sourcePath) {
                "Expected chunks must belong to the expected source path"
            }
            require(chunk.contentHash.matches(SHA_256_REGEX)) {
                "Expected chunk contentHash must be a lowercase SHA-256 value"
            }
        }
    }

    private fun MemoryVectorIndexIdentity.matches(expectation: MemoryVectorSnapshotExpectation): Boolean =
        corpus == expectation.corpus &&
            sourcePath == expectation.sourcePath &&
            sourceHash == expectation.sourceHash &&
            corpusGeneration == expectation.corpusGeneration &&
            indexFingerprint == expectation.indexFingerprint

    private fun MemoryVectorChunkEntity.matches(chunk: MemoryCorpusChunk): Boolean =
        chunkId == chunk.chunkId &&
            entryId == chunk.entryId &&
            sourcePath == chunk.sourcePath &&
            chunkIndex == chunk.chunkIndex &&
            heading == chunk.heading &&
            text == chunk.text &&
            type == chunk.type &&
            sensitivity == chunk.sensitivity &&
            source == chunk.source &&
            chatId == chunk.chatId &&
            createdAt == chunk.createdAt &&
            updatedAt == chunk.updatedAt &&
            contentHash == chunk.contentHash

    private fun validateIdentity(identity: MemoryVectorIndexIdentity) {
        require(identity.corpus == MemoryCorpus.CHAT_RECALL_LONG_TERM) {
            "The first vector store only accepts CHAT_RECALL_LONG_TERM"
        }
        require(identity.sourcePath == MemoryFilePaths.LONG_TERM_MEMORY_FILE_NAME) {
            "The first vector store only accepts MEMORY.md"
        }
        require(identity.sourceHash.matches(SHA_256_REGEX)) {
            "sourceHash must be a lowercase SHA-256 value"
        }
        require(identity.corpusGeneration >= 0) { "corpusGeneration must not be negative" }
        require(identity.indexFingerprint.matches(SHA_256_REGEX)) {
            "indexFingerprint must be a lowercase SHA-256 value"
        }
        require(identity.embeddingDescriptor.dimension == MEMORY_VECTOR_DIMENSION) {
            "Embedding descriptor dimension must be $MEMORY_VECTOR_DIMENSION"
        }
        require(identity.embeddingDescriptor.normalized) {
            "The cosine vector store requires normalized embeddings"
        }
        require(identity.chunkerVersion.isNotBlank()) { "chunkerVersion must not be blank" }
        require(identity.indexSchemaVersion == MEMORY_VECTOR_INDEX_SCHEMA_VERSION) {
            "indexSchemaVersion must be $MEMORY_VECTOR_INDEX_SCHEMA_VERSION"
        }
    }

    private fun validateEmbedding(
        embedding: FloatArray,
        label: String
    ) {
        require(embedding.size == MEMORY_VECTOR_DIMENSION) {
            "$label must contain exactly $MEMORY_VECTOR_DIMENSION values"
        }
        require(embedding.all(Float::isFinite)) { "$label must contain only finite values" }
        val norm = sqrt(embedding.sumOf { value -> value.toDouble() * value.toDouble() })
        require(abs(norm - 1.0) <= NORMALIZED_VECTOR_TOLERANCE) {
            "$label must be L2-normalized"
        }
    }

    private fun BoxStore.chunkBox(): Box<MemoryVectorChunkEntity> =
        boxFor(MemoryVectorChunkEntity::class.java)

    private fun BoxStore.manifestBox(): Box<MemoryVectorManifestEntity> =
        boxFor(MemoryVectorManifestEntity::class.java)

    private fun Box<MemoryVectorManifestEntity>.findCurrentManifest(): MemoryVectorManifestEntity? =
        query(MemoryVectorManifestEntity_.manifestKey.equal(MEMORY_VECTOR_MANIFEST_KEY))
            .build()
            .use { query -> query.findUnique() }

    private fun chunkIdentityCondition(
        identity: MemoryVectorIndexIdentity
    ): QueryCondition<MemoryVectorChunkEntity> =
        MemoryVectorChunkEntity_.sourcePath.equal(identity.sourcePath)
            .and(MemoryVectorChunkEntity_.sourceHash.equal(identity.sourceHash))
            .and(MemoryVectorChunkEntity_.corpusGeneration.equal(identity.corpusGeneration))
            .and(MemoryVectorChunkEntity_.indexFingerprint.equal(identity.indexFingerprint))
            .and(MemoryVectorChunkEntity_.embeddingModelId.equal(identity.embeddingDescriptor.modelId))
            .and(MemoryVectorChunkEntity_.embeddingModelVersion.equal(identity.embeddingDescriptor.modelVersion))
            .and(MemoryVectorChunkEntity_.embeddingDimension.equal(identity.embeddingDescriptor.dimension.toLong()))
            .and(MemoryVectorChunkEntity_.chunkerVersion.equal(identity.chunkerVersion))
            .and(MemoryVectorChunkEntity_.indexSchemaVersion.equal(identity.indexSchemaVersion.toLong()))

    private fun MemoryEmbeddedChunk.toEntity(identity: MemoryVectorIndexIdentity): MemoryVectorChunkEntity =
        MemoryVectorChunkEntity(
            chunkId = chunk.chunkId,
            entryId = chunk.entryId,
            sourcePath = chunk.sourcePath,
            chunkIndex = chunk.chunkIndex,
            heading = chunk.heading,
            text = chunk.text,
            type = chunk.type,
            sensitivity = chunk.sensitivity,
            source = chunk.source,
            chatId = chunk.chatId,
            createdAt = chunk.createdAt,
            updatedAt = chunk.updatedAt,
            contentHash = chunk.contentHash,
            sourceHash = identity.sourceHash,
            corpusGeneration = identity.corpusGeneration,
            indexFingerprint = identity.indexFingerprint,
            embeddingModelId = identity.embeddingDescriptor.modelId,
            embeddingModelVersion = identity.embeddingDescriptor.modelVersion,
            embeddingDimension = identity.embeddingDescriptor.dimension,
            chunkerVersion = identity.chunkerVersion,
            indexSchemaVersion = identity.indexSchemaVersion,
            embedding = embedding.copyOf()
        )

    private fun MemoryVectorManifest.toEntity(): MemoryVectorManifestEntity {
        val descriptor = identity.embeddingDescriptor
        return MemoryVectorManifestEntity(
            state = state.name,
            corpus = identity.corpus.name,
            sourcePath = identity.sourcePath,
            sourceHash = identity.sourceHash,
            corpusGeneration = identity.corpusGeneration,
            indexFingerprint = identity.indexFingerprint,
            expectedChunkCount = expectedChunkCount,
            completedAt = completedAt,
            embeddingProviderId = descriptor.providerId,
            embeddingRuntimeVersion = descriptor.runtimeVersion,
            embeddingModelId = descriptor.modelId,
            embeddingModelVersion = descriptor.modelVersion,
            embeddingModelSha256 = descriptor.modelSha256,
            embeddingDimension = descriptor.dimension,
            embeddingNormalized = descriptor.normalized,
            tokenizerVersion = descriptor.tokenizerVersion,
            tokenizerFingerprint = descriptor.tokenizerFingerprint,
            embeddingMaxInputTokens = descriptor.maxInputTokens,
            embeddingPooling = descriptor.pooling.name,
            queryPrefix = descriptor.queryPrefix,
            documentPrefix = descriptor.documentPrefix,
            chunkerVersion = identity.chunkerVersion,
            indexSchemaVersion = identity.indexSchemaVersion
        )
    }

    private fun MemoryVectorManifestEntity.toDomain(): MemoryVectorManifest = try {
        MemoryVectorManifest(
            identity = MemoryVectorIndexIdentity(
                corpus = MemoryCorpus.valueOf(corpus),
                sourcePath = sourcePath,
                sourceHash = sourceHash,
                corpusGeneration = corpusGeneration,
                indexFingerprint = indexFingerprint,
                embeddingDescriptor = MemoryEmbeddingDescriptor(
                    providerId = embeddingProviderId,
                    runtimeVersion = embeddingRuntimeVersion,
                    modelId = embeddingModelId,
                    modelVersion = embeddingModelVersion,
                    modelSha256 = embeddingModelSha256,
                    dimension = embeddingDimension,
                    normalized = embeddingNormalized,
                    tokenizerVersion = tokenizerVersion,
                    tokenizerFingerprint = tokenizerFingerprint,
                    maxInputTokens = embeddingMaxInputTokens,
                    pooling = MemoryEmbeddingPooling.valueOf(embeddingPooling),
                    queryPrefix = queryPrefix,
                    documentPrefix = documentPrefix
                ),
                chunkerVersion = chunkerVersion,
                indexSchemaVersion = indexSchemaVersion
            ),
            expectedChunkCount = expectedChunkCount,
            completedAt = completedAt,
            state = MemoryVectorManifestState.valueOf(state)
        ).also { manifest ->
            validateIdentity(manifest.identity)
            require(manifest.expectedChunkCount >= 0) { "expectedChunkCount must not be negative" }
            require(manifest.state != MemoryVectorManifestState.READY || manifest.completedAt > 0) {
                "A READY manifest must have a positive completedAt"
            }
        }
    } catch (exception: IllegalArgumentException) {
        throw MemoryVectorStoreCorruptionException("Invalid memory vector manifest", exception)
    }

    private fun MemoryVectorChunkEntity.toMatch(
        identity: MemoryVectorIndexIdentity,
        cosineDistance: Float
    ): MemoryVectorMatch {
        validateAgainst(identity)
        val storedEmbedding = checkNotNull(embedding)
        return MemoryVectorMatch(
            chunk = MemoryCorpusChunk(
                chunkId = chunkId,
                entryId = entryId,
                sourcePath = sourcePath,
                chunkIndex = chunkIndex,
                heading = heading,
                text = text,
                type = type,
                sensitivity = sensitivity,
                source = source,
                chatId = chatId,
                createdAt = createdAt,
                updatedAt = updatedAt,
                contentHash = contentHash
            ),
            embedding = storedEmbedding.copyOf(),
            cosineDistance = cosineDistance
        )
    }

    private fun MemoryVectorChunkEntity.validateAgainst(identity: MemoryVectorIndexIdentity) {
        if (
            sourcePath != identity.sourcePath ||
            sourceHash != identity.sourceHash ||
            corpusGeneration != identity.corpusGeneration ||
            indexFingerprint != identity.indexFingerprint ||
            embeddingModelId != identity.embeddingDescriptor.modelId ||
            embeddingModelVersion != identity.embeddingDescriptor.modelVersion ||
            embeddingDimension != identity.embeddingDescriptor.dimension ||
            chunkerVersion != identity.chunkerVersion ||
            indexSchemaVersion != identity.indexSchemaVersion
        ) {
            throw MemoryVectorStoreCorruptionException("Vector chunk does not match its manifest")
        }
        if (!contentHash.matches(SHA_256_REGEX)) {
            throw MemoryVectorStoreCorruptionException("Vector chunk has an invalid contentHash")
        }
        val storedEmbedding = embedding
            ?: throw MemoryVectorStoreCorruptionException("Vector chunk has no embedding")
        try {
            validateEmbedding(storedEmbedding, "stored chunk embedding")
        } catch (exception: IllegalArgumentException) {
            throw MemoryVectorStoreCorruptionException("Vector chunk has an invalid embedding", exception)
        }
    }

    private fun Throwable.isRecoverableVectorStoreCorruption(): Boolean =
        generateSequence(this) { throwable -> throwable.cause }
            .any { throwable ->
                throwable is FileCorruptException ||
                    throwable is DbSchemaException ||
                    throwable is MemoryVectorStoreCorruptionException
            }

    private companion object {
        const val MAX_MEMORY_VECTOR_QUERY_LIMIT = 500
        const val NORMALIZED_VECTOR_TOLERANCE = 1e-3
        val SHA_256_REGEX = Regex("[0-9a-f]{64}")
    }
}
