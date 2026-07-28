package cn.nabr.chatwithchat.data.memory

import java.nio.charset.StandardCharsets

class MemoryCorpusSnapshotter(
    private val memoryFileStore: MemoryFileStore,
    private val memoryChunker: MemoryChunker
) : MemoryCorpusSnapshotSource {

    override suspend fun snapshots(corpus: MemoryCorpus): Result<List<MemoryCorpusSnapshot>> = runCatching {
        val read = memoryFileStore.readCorpusFiles(corpus).getOrThrow()
        read.files.map { file ->
            val markdown = String(file.bytes, StandardCharsets.UTF_8)
            val chunking = memoryChunker.chunksFor(
                sourcePath = file.sourcePath,
                markdown = markdown,
                projectionPolicy = corpus.projectionPolicy
            )
            MemoryCorpusSnapshot(
                corpus = corpus,
                sourcePath = file.sourcePath,
                canonicalSourceHash = file.bytes.sha256Hex(),
                recallProjectionHash = chunking.projectionHash,
                generation = read.revision,
                chunks = chunking.chunks,
                diagnostics = chunking.diagnostics
            )
        }
    }

    override suspend fun isCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> = runCatching {
        if (snapshots.isEmpty()) return@runCatching false
        val corpus = snapshots.first().corpus
        if (snapshots.any { snapshot -> snapshot.corpus != corpus }) return@runCatching false
        val expectedGeneration = snapshots.first().generation
        if (snapshots.any { snapshot -> snapshot.generation != expectedGeneration }) return@runCatching false

        val current = memoryFileStore.readCorpusFiles(corpus).getOrThrow()
        if (current.revision != expectedGeneration) return@runCatching false
        val expectedSources = snapshots.associate { snapshot -> snapshot.sourcePath to snapshot.canonicalSourceHash }
        val currentSources = current.files.associate { file -> file.sourcePath to file.bytes.sha256Hex() }
        expectedSources == currentSources
    }

    override suspend fun isProjectionCurrent(snapshots: List<MemoryCorpusSnapshot>): Result<Boolean> = runCatching {
        if (snapshots.isEmpty()) return@runCatching false
        val corpus = snapshots.first().corpus
        if (snapshots.any { snapshot -> snapshot.corpus != corpus }) return@runCatching false
        val current = snapshots(corpus).getOrThrow()
        val expectedSources = snapshots.associate { snapshot -> snapshot.sourcePath to snapshot.recallProjectionHash }
        val currentSources = current.associate { snapshot -> snapshot.sourcePath to snapshot.recallProjectionHash }
        expectedSources == currentSources
    }
}
