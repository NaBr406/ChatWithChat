# ChatWithChat OpenClaw-Style Vector Memory And Maintenance Prompt

> **For the implementation agent:** Read `AGENTS.md` and this document completely before editing. This is an execution prompt, not a historical design note. Start from a dedicated branch/worktree, preserve unrelated user changes, implement in the listed order, and make small verified commits. Do not modify the chat product surface except where the memory contract requires it.

> **Plan snapshot:** This document was written against `main` at `bdbadc8a6821fb4fa47839a2d63bd1ffb08040ea` on 2026-07-12. The worktree already contained unrelated chat UI, history drawer, string, `ChatViewModel.kt`, `ChatLaunchState.kt`, and `ChatLaunchStateTest.kt` changes. Recheck the live checkout before implementation and do not absorb, discard, reformat, or commit those changes as part of memory work.

> **Scope decision:** The earlier Markdown-first and five-turn batching plans remain authoritative for product semantics. This plan supersedes only their deferred vector-backend decision and the current coupling between canonical Markdown writes and the Room-derived index. The first shippable implementation stops at Room schema 15 with the old Room index retained but unused by production recall. Schema `15 -> 16` cleanup is specified here for a later release/independent implementation run after schema 15 shadow/cutover evidence exists; it is not part of the first delivery.

## Goal

Evolve the current memory subsystem into an Android-native version of the useful OpenClaw memory shape:

- `MEMORY.md` remains the user-visible, canonical long-term memory.
- `memory/YYYY-MM-DD.md` remains an internal working corpus for daily observations and consolidation.
- normal chat recall reads only current `MEMORY.md` content;
- lexical and semantic retrieval run locally and require zero dedicated recall LLM calls;
- ObjectBox provides a separate, disposable, rebuildable HNSW vector index;
- the existing business Room database remains in place for chats, messages, platforms, pending turns, checkpoints, maintenance jobs, activity logs, migration state, and durable recovery receipts;
- Markdown commits are durable even when embedding or vector indexing fails;
- maintenance is split by responsibility so local repair/index work does not require a network;
- daily notes are actually distilled into `MEMORY.md` through a bounded, idempotent maintenance path;
- stale or mismatched vectors are never used to inject memory into a prompt.

The user-visible symptom this plan must eliminate is precise: a preference absent from the displayed `MEMORY.md` must not repeatedly appear in ordinary conversations merely because it remains in a hidden daily file or stale derived index.

## Product Invariants

These are hard acceptance rules, not suggestions.

1. **Canonical ownership**
   - `MEMORY.md` is the only user-visible long-term memory source of truth.
   - `memory/*.md` files are canonical internal working notes, but are not ordinary chat-recall sources.
   - Room index rows and ObjectBox rows are derived data. Both may be deleted and rebuilt.

2. **Business Room is retained**
   - Do not delete the Room database.
   - Preserve chat rooms, messages, provider/platform data, pending turns, chat checkpoints, maintenance jobs, activity logs, and legacy migration compatibility.
   - Removing `memory_chunk` and `memory_document` later does not mean removing Room.

3. **Visible-recall parity**
   - If text is not present in the current committed `MEMORY.md`, ordinary chat recall must not inject it.
   - Hidden daily notes may affect only explicit maintenance/consolidation operations until promoted into `MEMORY.md`.
   - A deleted or replaced long-term entry must stop being recallable after the Markdown commit, even if the vector index is stale or unavailable.

4. **Local recall**
   - Query embedding, lexical retrieval, vector retrieval, fusion, deduplication, and token-budget packing are local operations.
   - Pre-answer recall performs zero dedicated memory LLM/API calls.
   - A normal answer model may remain the final relevance gate after memories are injected.

5. **Fail closed for derived state**
   - A vector index whose source hash or fingerprint differs from the current Markdown snapshot is ineligible for retrieval.
   - Failure falls back to lexical search over the current Markdown snapshot, then to no memory if that also fails.
   - Memory retrieval failure must never fail normal chat completion.

6. **Canonical writes win**
   - Once controlled Markdown content has been atomically committed, a later embedding/index failure must not roll the Markdown back.
   - Recovery must move derived state forward from Markdown, never move Markdown backward to match an old index.

7. **No hidden cloud embedding**
   - The default embedding implementation is on-device behind `MemoryEmbeddingProvider`.
   - Do not silently call OpenAI, Gemini, another chat provider, or a new remote endpoint for embeddings.
   - Any future remote embedding option requires a separate explicit product decision, settings/privacy copy, and offline fallback.

8. **No regression in batching semantics**
   - Preserve the existing five-completed-turn threshold, 30-minute idle fallback, compaction convergence, immutable claimed batches, at-most-three semantic attempts, and one semantic memory request at a time.
   - Chat UI must not wait for consolidation, distillation, model loading, or vector indexing.

9. **No per-entry approval workflow**
   - Keep the Memory screen as a read-only `MEMORY.md` surface with existing export behavior.
   - Do not add pending-confirmation, archive, resolve, or per-entry management UI.

## Verified Current-State Snapshot

Reverify these facts before editing because this section is a snapshot, not a substitute for reading the live code.

### Persistence And Build

- `ChatDatabaseV2` is Room schema version `14`.
- `MIGRATION_10_11` introduced `memory_document` and `memory_chunk`.
- `MIGRATION_11_12` introduced `memory_maintenance_job`.
- `MIGRATION_12_13` introduced `memory_chat_checkpoint` and `memory_pending_turn`.
- `MIGRATION_13_14` introduced `memory_activity_log`.
- `DatabaseModule` registers migrations through `MIGRATION_13_14` without destructive fallback.
- Schema `14.json` is exported under `app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/`.
- The project currently uses AGP `9.1.1`, Kotlin `2.3.20`, KSP `2.3.4`, Room `2.8.4`, WorkManager `2.11.0`, Java 17, and `minSdk 31`.
- No ObjectBox, embedding runtime, or embedding model is currently present.
- Release builds use R8, so generated ObjectBox code and native packaging must be release-tested.

### Current Recall Path

```text
ChatViewModel.prepareMemoryPrompt(...)
  -> MemoryRepositoryImpl.prepareMemoryContext(...)
  -> MemoryRetriever (currently MemoryIndexRepository)
  -> MemoryPromptBuilder.buildRetrieved(...)
  -> ChatRepositoryImpl.mergePromptSections(...)
```

Important live behavior:

- `MemoryRetrievalRequest.sourcePath` defaults to `null`.
- ordinary chat recall does not set `sourcePath`;
- `MemoryIndexRepository.rebuildAll()` indexes `MEMORY.md` plus every daily Markdown file;
- `MemoryIndexDao.getSearchCandidates(sourcePath = null, ...)` therefore returns long-term and daily chunks;
- the long-term score bonus changes ordering but does not exclude daily chunks.

This is the direct cause of the visible-file/index mismatch reported by the user.

### Current Index Coupling

`MemoryIndexRepository` currently owns too many responsibilities:

- enumerating Markdown files;
- parsing/chunking Markdown;
- converting chunks directly into Room `MemoryChunk` entities;
- writing Room document/chunk rows;
- lexical tokenization/scoring;
- retrieval, deduplication, token budgeting, and rebuild reporting.

Additional risks:

- `MemoryChunker` returns the Room entity rather than a storage-neutral chunk model.
- `rebuildAll()` uses `mapNotNull { buildIndexedFile(...).getOrNull() }`; a bad file can be silently skipped before `replaceAll()` removes the previous index.
- `changedChunkIds` is not a reliable content diff; embeddings must compare persisted content hashes.
- retrieval `contentHash` is computed at query time and does not identify the source revision, chunker, model, dimension, or index schema.

### Current Write And Maintenance Path

```text
saved completed turn
  -> MemoryTurnBatchCoordinator
  -> MemoryPendingTurn / MemoryChatCheckpoint
  -> CONSOLIDATE_TURN_BATCH
  -> MemoryMaintenanceWorker (network required for every job)
  -> MemoryBatchConsolidationService
  -> controlled Markdown replacement(s)
  -> synchronous Room index rebuild
  -> claimed batch completion / job success
```

Current problems to remove:

- `MemoryBatchConsolidationService.applyOperations()` rolls Markdown back if the derived index rebuild fails.
- completing the claimed batch can also trigger a Markdown rollback.
- local rebuild/repair and semantic LLM work share one connected-network Worker.
- `MemoryMaintenanceWorker` turns any uncaught failure into a WorkManager retry even though retry truth is already persisted in Room.
- `DISTILL_DAILY_NOTES` and `PROMOTE_LONG_TERM_CANDIDATE` are classified as semantic jobs but have no implementation; they end in an unsupported/pending failure path.
- startup/boot repair resets jobs and repairs turn batches, but does not reconcile Markdown hashes with index state.

### Existing Assets To Preserve

- `MemoryFilePaths`, `MemoryFileStore`, UTF-8 reads/writes, backups, and atomic replace behavior.
- `MarkdownMemoryCodec`, stable Markdown entry IDs, replacement/removal by ID, and controlled LLM operation validation.
- `MemoryRetriever`, `MemoryRetrievalRequest`, `MemoryRetrievalResult`, `LEXICAL`/`VECTOR`/`HYBRID`, and score provenance as the caller-facing retrieval boundary.
- `MemoryPromptBuilder` and the current prompt-injection path.
- `MemoryTurnBatchCoordinator`, `MemoryTurnBatchScheduler`, pending-turn/checkpoint Room tables, and immutable batch payloads.
- `memory_maintenance_job`, activity logging, notifications, manual retry, `GPTMobileApp` launch repair, `BootCompletedReceiver`, and separate immediate/delayed unique work names.
- migration-only `PersonalMemory` and `ChatClassification` compatibility until a separate removal decision.

## OpenClaw Reference And Android Deviations

The reference was last checked against OpenClaw `main` commit `761839343aefaced873b2d90b6897ef0c4be5446`, version `2026.7.2`. At implementation time, briefly recheck the official sources, but do not let upstream drift override the product invariants above:

- https://docs.openclaw.ai/concepts/memory
- https://docs.openclaw.ai/concepts/memory-builtin
- https://docs.openclaw.ai/concepts/memory-search
- https://docs.openclaw.ai/concepts/active-memory
- https://docs.openclaw.ai/concepts/compaction
- https://github.com/openclaw/openclaw

Adopt these principles:

- Markdown is inspectable source material, not an export of an opaque database.
- search indexes are disposable derivatives;
- lexical and vector retrieval complement each other;
- embedding/index state is versioned and can be rebuilt;
- active/daily notes are distilled into a curated long-term surface;
- maintenance and recovery are explicit lifecycle concerns.

Do not copy these server/desktop assumptions into Android:

- no QMD or Node/Bun sidecar;
- no Python service, Milvus, Qdrant, or external daemon;
- no arbitrary workspace exposure;
- no dependence on SQLite extension loading;
- no large reranker or dedicated recall LLM call;
- no assumption that background processes run continuously.

Use app-private storage, WorkManager, launch/boot reconciliation, a bounded on-device embedding model, and an embedded Android-supported vector store.

## Target Ownership And Architecture

```text
                         CANONICAL CONTENT
                 filesDir/memory_store/MEMORY.md
                    /                         \
           current snapshot              controlled writes
                 |                              |
      +----------+----------+          mutation receipt/outbox
      |                     |                    |
lexical retriever      index synchronizer <-----+
(current Markdown)     chunk -> embed -> verify
      |                     |
      |             noBackupFilesDir/
      |             memory_vector_index/
      |               ObjectBox HNSW
      |                     |
      +------> hybrid retriever <-------+
                    |                   |
             RRF + dedupe/MMR      fingerprint/hash gate
                    |
              MemoryPromptBuilder
                    |
             normal answer prompt

  filesDir/memory_store/memory/YYYY-MM-DD.md
                    |
        maintenance-only corpus reader
                    |
      consolidation / daily distillation
                    |
             controlled MEMORY.md write

  Room chat_v2 (retained)
    chats/messages/platforms
    pending turns/checkpoints
    maintenance jobs/activity logs
    mutation receipts/index state/distillation checkpoint
```

The ownership boundaries must be visible in class names and dependencies. `MemoryRetriever` must not depend on `MemoryIndexDao` after final cleanup. ObjectBox must not be reachable from chat, message, or provider repositories.

## Backend Decision: ObjectBox

Use ObjectBox `5.4.2` as the first concrete local vector database, subject to the Task 0/2 build canary.

Why:

- official Android/Kotlin support and API 21+ compatibility;
- persistent native HNSW with cosine distance and `FloatArray` vectors;
- transactional entity replacement and automatic HNSW updates;
- substantially lower Android integration risk than maintaining a custom SQLite extension/JNI layer.

Required boundaries:

- create a dedicated `BoxStore` for memory vectors only;
- store it below `context.noBackupFilesDir`, for example `memory_vector_index/`;
- never add business entities to that `BoxStore`;
- deleting or corrupting that directory must be recoverable from Markdown;
- index only the long-term chat-recall corpus in the first production HNSW store, avoiding reliance on HNSW metadata prefiltering to exclude daily notes;
- keep the ObjectBox native runtime's binary license visible in dependency/license review;
- record debug/release APK size and ABI changes;
- verify AGP 9.1.1, Kotlin 2.3.20, existing KSP processors, ObjectBox code generation, R8, and 16 KB page compatibility before committing to the integration.

Do not substitute these without an explicit scope change:

| Option | Decision |
| --- | --- |
| `sqlite-vec` | Not now. It has no official Android AAR and requires maintaining native SQLite/JNI integration for the supported ABIs. |
| USearch | Not now. It is an index library rather than the selected transactional store, and Java filtering/update support is a weaker fit. |
| Kotlin brute-force cosine | Allowed only as a test oracle or emergency lexical-only comparison, not as the requested vector database. |
| LanceDB / Milvus / Qdrant | Excluded from the APK-native design. |
| Cloud vector database | Excluded. |

If the ObjectBox canary fails for a reproducible toolchain, ABI, licensing, or release-packaging reason, stop before backend substitution. Document the evidence and request a product decision rather than silently changing the architecture.

## Embedding Decision And Gate

Introduce a storage-neutral interface first:

```kotlin
interface MemoryEmbeddingProvider {
    val descriptor: MemoryEmbeddingDescriptor

    suspend fun availability(): MemoryEmbeddingAvailability
    suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>>
    suspend fun embedQuery(text: String): Result<FloatArray>
}

data class MemoryEmbeddingDescriptor(
    val providerId: String,
    val modelId: String,
    val modelVersion: String,
    val dimension: Int,
    val normalized: Boolean,
    val tokenizerVersion: String
)
```

The production provider must be a real on-device multilingual embedding implementation, not a hash/random placeholder. A deterministic fake is required for unit tests but must never be bound in release DI.

### Candidate Default For The Device Spike

Start the concrete feasibility spike with this pinned candidate rather than surveying the ecosystem again:

```text
Runtime: com.microsoft.onnxruntime:onnxruntime-android:1.27.0
Model: BAAI/bge-small-zh-v1.5, reproducibly exported INT8 ONNX
Dimension: 512
Maximum input: 256 WordPiece tokens for the first version
Pooling: last_hidden_state[:, 0] (CLS)
Normalization: L2
Distance: cosine
Query prefix: 为这个句子生成表示以用于检索相关文章：
Document prefix: none
```

Rationale: `bge-small-zh-v1.5` is a roughly 24M-parameter, four-layer Chinese retrieval model with an MIT license, a simple BERT WordPiece vocabulary, and a commonly available INT8 ONNX artifact around 24 MB. It is materially more suitable for the current Chinese-first single-user Android app than a roughly 100+ MB quantized multilingual model. ONNX Runtime has an official Android AAR, though its runtime/ABI size must be measured.

Treat this as a candidate default that must pass the spike, not an unconditional production mandate. Do not download a mutable Hugging Face `main` artifact at runtime. Prefer a reproducible export from the official BAAI revision; otherwise pin the exact artifact revision, SHA-256, export configuration, tokenizer files, and license. Keep model binaries and benchmark output out of Git unless the repository's artifact policy is explicitly changed.

Task 0 must produce a reproducible model artifact contract before release DI or a production 512-dimension HNSW store is enabled. The contract must record:

- exact upstream repository and immutable revision;
- export/quantization script plus pinned tool/dependency versions;
- model, vocabulary, tokenizer-config, and license SHA-256 values;
- expected tensor names, shapes, dtypes, pooling, normalization, prefixes, and maximum tokens;
- exactly one delivery choice: bundled/asset-pack artifact, explicit user-consented checksum-verified download, or CI/build-provisioned checksum-verified artifact;
- the CI/release command that obtains or verifies the artifact without relying on a mutable URL;
- the app path, deletion/reprovisioning behavior, and release-build failure behavior when the artifact is missing.

For the single-user side-loaded project, a checksum-verified build-provisioned or bundled artifact is preferable when its size is acceptable. A downloaded model is acceptable only with explicit provisioning and lexical behavior before completion. If no reproducible acquisition/distribution choice is approved, land only the provider interfaces, ObjectBox build/native canary, and lexical production path. Do not publish a READY production manifest, bind hybrid DI, or claim that the fixed 512-dimension production index exists.

Tokenizer equivalence is a release gate. Generate at least 50 golden token-ID/attention-mask fixtures with a trusted Transformers implementation, covering Chinese, English, mixed Chinese/English, code, emoji, punctuation, and long/truncated text. Android tokenization must match exactly. Do not ship an improvised tokenizer that only passes a few hand-written examples.

The candidate must also pass:

- INT8 versus trusted FP32 golden-corpus `Recall@5 >= 0.90`;
- real memory-recall questions, not only pairwise sentence-similarity examples;
- API 31+ ARM64 physical-device performance/OEM tests for session creation, cancellation, concurrency, background execution, close/reopen, and process restart; this is independent of the 16 KB emulator compatibility gate;
- suggested target-device budgets of hot-query p95 at or below 250 ms, cold initialization at or below 2 seconds, and incremental peak RSS at or below 200 MB, with actual measurements reported even when a budget is missed.

If this candidate fails a gate, preserve the provider/store boundaries and lexical production fallback, document the exact failure, and stop before selecting another production model/runtime without user direction.

Before hybrid cutover, pin and document one actual model/runtime after validating it on the target Android environment. Required gate:

- Chinese and English query/document support;
- fixed dimension known before defining the HNSW property;
- cosine-compatible query/document normalization and any required prefixes are explicit;
- model license permits app distribution or the model uses an explicit user-initiated download;
- model checksum, tokenizer/runtime version, install location, deletion behavior, and warm-up behavior are defined;
- no silent network fallback when the model is absent;
- one real-device semantic fixture proves a paraphrase can be found when lexical tokens do not overlap before production hybrid cutover; this is not a page-size compatibility requirement;
- query embedding and peak-memory measurements are reported rather than guessed;
- the app remains usable with lexical recall while the model is absent, loading, incompatible, or deleted.

Target engineering budgets to evaluate, not claims to fake:

- vector dimension: 512 for the candidate above and at most 768 for any separately approved replacement;
- exclude model files from the base APK when they would add more than roughly 100 MB compressed;
- batch document embedding in bounded chunks and avoid holding the full corpus plus duplicate model outputs in memory;
- keep the non-model ObjectBox/runtime APK increase measured and justified;
- do not enable hybrid production DI until one physical ARM64 device passes the semantic and performance/OEM checks; this production gate does not block or reopen a passing 16 KB emulator compatibility gate.

If no model/runtime satisfies this gate during the implementation run, it is acceptable to land the ObjectBox shadow store, manifest/outbox, provider interface, and lexical production fallback. It is not acceptable to label vector recall complete or switch `HYBRID` on with fake embeddings.

## Required Interfaces And Models

Use names consistent with the repository, but preserve these responsibility boundaries.

### Storage-Neutral Corpus Model

Replace the Room entity return type from `MemoryChunker` with a pure model such as:

```kotlin
data class MemoryCorpusChunk(
    val chunkId: String,
    val entryId: String?,
    val sourcePath: String,
    val chunkIndex: Int,
    val heading: String?,
    val text: String,
    val type: String?,
    val sensitivity: String?,
    val source: String?,
    val chatId: Int?,
    val createdAt: Long,
    val updatedAt: Long,
    val contentHash: String
)

data class MemoryCorpusSnapshot(
    val corpus: MemoryCorpus,
    val sourcePath: String,
    val sourceHash: String,
    val generation: Long,
    val chunks: List<MemoryCorpusChunk>
)
```

`contentHash` must be deterministic over retrieval-relevant normalized content and metadata. `sourceHash` must be SHA-256 over the exact committed UTF-8 file bytes. Do not use timestamps as freshness proof.

### Explicit Corpus Scope

Remove nullable/all-files semantics from production call sites.

```kotlin
enum class MemoryCorpus {
    CHAT_RECALL_LONG_TERM,
    MAINTENANCE_WORKING_SET
}
```

`MemoryRetrievalRequest` must require an explicit corpus. Ordinary chat always uses `CHAT_RECALL_LONG_TERM`. Maintenance code must use a separate explicit working-set reader or `MAINTENANCE_WORKING_SET`; it must never flow into `MemoryPromptBuilder` accidentally.

### Retrieval Components

Keep `MemoryRetriever` as the caller boundary and split implementations below it:

- `MemoryCorpusSnapshotter`: atomically reads/parses the requested canonical Markdown snapshot.
- `MarkdownLexicalRetriever`: searches the supplied current snapshot without Room.
- `MemoryVectorStore`: ObjectBox CRUD/query/manifest boundary.
- `MemoryIndexSynchronizer`: chunks, embeds, verifies, and atomically replaces a vector snapshot.
- `HybridMemoryRetriever`: applies freshness gates, queries lexical/vector branches, fuses results, deduplicates, diversifies, and packs the token budget.
- `MemoryMaintenanceCorpusReader`: explicitly reads long-term plus selected daily files for consolidation/distillation only.

Do not let `ChatViewModel`, `ChatRepositoryImpl`, `MemoryPromptBuilder`, or provider clients know which vector backend or embedding runtime is used.

### ObjectBox Entity

The first entity must contain at least:

```text
objectBoxId
chunkId (unique and deterministic)
entryId
sourcePath
chunkIndex
heading
text
type
sensitivity
source
createdAt
updatedAt
contentHash
sourceHash
corpusGeneration
indexFingerprint
embeddingModelId
embeddingModelVersion
embeddingDimension
chunkerVersion
indexSchemaVersion
embedding: FloatArray with HNSW cosine index
```

Also persist an ObjectBox-side manifest in the same transaction or store generation. It must include the source hash, generation, fingerprint, expected chunk count, and completed-at timestamp. A Room-side status row is useful for scheduling/diagnostics, but Room alone cannot prove an ObjectBox commit completed.

ObjectBox HNSW dimensions are schema-bound. The first implementation must fix the selected dimension as a constant. A dimension change requires an explicit index schema/store migration or a new versioned store directory, never writing wrong-length arrays into the old entity.

## Index Fingerprint

Define one deterministic fingerprint over all inputs that can change retrieval meaning:

```text
index schema version
chunker algorithm/version and size/overlap settings
Markdown codec version relevant to chunks
embedding provider ID
model ID and exact model version/checksum
tokenizer/runtime version
embedding dimension
query/document normalization and prefixes
distance metric
corpus scope
```

Hash the canonical serialized descriptor with SHA-256. Do not use ad hoc string order or Android app version as the only invalidation key.

Any mismatch means the vector snapshot is stale. Same-dimension model changes require full re-embedding. Dimension changes require a new compatible ObjectBox schema/store. A file sequence `A -> B -> A` must still advance a monotonic corpus generation so an old succeeded job cannot suppress the new mutation.

## Corpus Scope Rules

### Ordinary Chat Recall

- Read exactly `MEMORY.md` through a current snapshot.
- Never search `memory/*.md`, backup files, staging files, legacy `PersonalMemory`, `ChatClassification`, old Room chunks, or an ObjectBox generation that does not match the snapshot.
- Preserve the current local query construction, result/token caps, private metadata behavior, and no-memory degradation unless tests justify a focused change.
- Before returning results, verify that the committed source revision did not change during retrieval. Coordinate app writes/reads with a process-local revision gate and rerun or discard results when the revision changes.

### Consolidation Working Set

- Five-turn consolidation may inspect current `MEMORY.md` and a bounded set of relevant daily entries for duplicate/update decisions.
- Use an explicit maintenance corpus boundary. Do not reuse the ordinary chat request with a nullable source path.
- Maintenance search over daily files may remain deterministic Markdown lexical search; daily notes do not need to be inserted into the first HNSW store.

### Daily Distillation

- Closed daily files are internal evidence for promotion/update decisions.
- A stable preference becomes ordinary-recall eligible only after a controlled operation commits it to `MEMORY.md`.
- Distillation must update matching stable IDs/entries rather than appending progress duplicates.
- Daily files remain inspectable internal source material after processing; a checkpoint records what revision was distilled.

## Hybrid Retrieval Behavior

1. Build one consistent `MEMORY.md` snapshot and combined query from the current user message plus bounded recent context.
2. Run lexical search over that exact snapshot.
3. Read the ObjectBox manifest and enable the vector branch only when all of these match the snapshot/request:
   - source path;
   - source SHA-256;
   - corpus generation;
   - index fingerprint;
   - model ID/version;
   - dimension;
   - chunker/index schema version;
   - READY/completed manifest state.
4. Embed the query locally and request bounded HNSW candidates.
5. Fuse lexical and vector ranked lists with reciprocal-rank fusion (RRF), not direct addition of incomparable raw scores. Use a documented stable constant such as `k = 60`.
6. Deduplicate first by stable non-null `entryId`, then by `contentHash` for fallback chunks.
7. Apply deterministic diversity/MMR so near-duplicate chunks do not consume the budget. Vector similarity may drive MMR when available; use a deterministic lexical similarity fallback otherwise.
8. Apply the existing result count and token budget after fusion/deduplication.
9. Recheck the current source revision before returning. If it changed, retry once from a fresh snapshot or return lexical/no-memory safely.
10. Keep lexical/vector/fused score provenance for tests and diagnostics, but do not expose internal scores on the normal Memory screen.

When vectors are stale, missing, corrupt, still building, or the embedding provider is unavailable, do not query the old vector store. Schedule/revive local sync and return current lexical results.

## Markdown Commit And Recovery Protocol

Filesystem, Room, and ObjectBox cannot share one transaction. Implement an explicit recoverable state machine instead of pretending they can.

### Mutation Receipt

Room `14 -> 15` must add durable sync/outbox state. Exact names may follow repository style, but the data model must represent:

- mutation/group ID and monotonic generation;
- semantic source job/batch ID;
- source path;
- base source hash;
- target source hash;
- staged target path or another durable way to resume the exact already-approved target without a second LLM call;
- state such as `PREPARED`, `FILE_COMMITTED`, `INDEX_PENDING`, `INDEXED`, `CONFLICT`, `FAILED`;
- target index fingerprint;
- attempts/error/timestamps;
- idempotency key that includes generation, not only a reusable file hash.

For a semantic operation that changes multiple Markdown files, stage every exact target first and create one group plus per-file receipts. There is no multi-file atomic rename; recovery must finish an interrupted group idempotently rather than calling the LLM again or reverting already committed files because indexing failed.

### Commit Sequence

```text
1. Validate controlled operations and render exact target Markdown.
2. Persist PREPARED receipt(s), base/target hashes, generation, and durable staged target(s).
3. Atomically replace canonical Markdown file(s) using MemoryFileStore.
4. Verify exact target SHA-256 and persist FILE_COMMITTED/INDEX_PENDING.
5. Complete the claimed semantic batch and mark semantic job success once the
   canonical file group is durably committed/recoverable.
6. Enqueue a local index-sync job for the latest generation.
7. Parse the current committed file, embed chunks, and recheck source hash.
8. Replace ObjectBox vectors plus its manifest in one ObjectBox transaction.
9. Verify ObjectBox manifest/count/hash, then mark the Room receipt INDEXED.
```

Steps 6-9 may fail and retry without undoing steps 1-5.

### Startup/Boot Reconciliation

For every incomplete receipt:

- current file hash equals target hash: advance/resume from `FILE_COMMITTED` without another semantic call;
- current file hash equals base hash and a valid staged target exists: resume the atomic file commit;
- ObjectBox manifest already equals the receipt target: advance Room state without rebuilding;
- current file hash matches neither base nor target: mark conflict, preserve files/backups, log/notify, and do not overwrite newer canonical content;
- ObjectBox is corrupt or absent: close/delete only the vector store and rebuild from current Markdown;
- stale Room `INDEXED` state with mismatched ObjectBox manifest: downgrade to pending and rebuild.

Never restore a backup merely because embedding or ObjectBox failed. Backup restore remains for a failed/invalid canonical file operation before a durable target commit or an explicit user repair action.

### Synchronizer Race Rules

- Capture expected source hash/generation before chunking.
- Verify it immediately before ObjectBox commit.
- If Markdown changed during embedding, discard generated vectors and enqueue the latest generation.
- Compare per-chunk `contentHash`; never trust the current `changedChunkIds` output as the sole diff.
- Prefer correctness-first full replacement for the small first corpus. Incremental updates are allowed only after equivalence tests prove deletes/updates and manifest behavior.

## Maintenance And Worker Redesign

Keep the durable Room scheduler and split execution by actual constraints.

### Atomic Family Claim And Lease

Splitting one processor into multiple Workers makes the current `runnableJobs()` followed by `markRunning()` sequence unsafe. Unique WorkManager names and an in-process mutex are not a database claim and do not prevent immediate, delayed, boot, or restarted workers from selecting the same job.

Add a transactional/CAS DAO boundary such as `claimNextRunnable(allowedTypes, leaseOwner, now)`:

1. filter eligible jobs to the caller's explicit type family;
2. select the next eligible row in deterministic order;
3. conditionally update it from its expected status/version to `RUNNING`, increment attempts, and set a lease owner/expiry;
4. return the job only when exactly one row was updated;
5. for semantic types, atomically refuse a claim while any non-stale semantic lease is active globally;
6. reclaim only expired leases through repair.

Workers must process only successfully claimed rows. Add concurrent DAO/processor tests in which semantic, index, immediate, delayed, and boot-triggered workers race; exactly one claim and one semantic LLM call may occur. Keep content/idempotency checks as a second defense, not as a replacement for atomic claiming.

### Semantic Worker (Network Required)

Own only jobs that call the configured memory LLM:

- `CONSOLIDATE_TURN_BATCH`;
- implemented `DISTILL_DAILY_NOTES`;
- temporary adapters for legacy `APPEND_DAILY_NOTE` and `COMPACTION_FLUSH` payloads.

Rules:

- require `NetworkType.CONNECTED`;
- preserve global semantic serialization;
- preserve three automatic attempts per immutable semantic input;
- invalid model output is retryable/terminal according to the existing policy;
- memory disabled performs no semantic call and does not loop;
- a committed mutation target is replayed locally after process death instead of asking the LLM again.

### Local Index Worker (No Network Constraint)

Own jobs such as:

- `SYNC_VECTOR_INDEX` for a committed generation;
- `REBUILD_VECTOR_INDEX` for corruption/fingerprint/model changes;
- optional model warm-up only when the model is already installed.

Rules:

- no connected-network constraint;
- coalesce by corpus while always processing the latest generation;
- an old job must not mark a newer generation ready;
- failures use bounded per-run backoff, but must remain repairable after the semantic three-attempt limit;
- model-not-installed is a defined unavailable state, not a cloud fallback or hot retry loop;
- corruption recovery deletes only ObjectBox derived data.

If model download is required, use a separate explicit provisioning flow/job with network and user consent. Do not make a local index job secretly download a model.

### Local Repair Worker (No Network Constraint)

Own jobs such as:

- mutation/outbox reconciliation;
- Markdown metadata/hash verification;
- ObjectBox manifest verification;
- stale-running local job reset;
- scheduling the appropriate semantic or index worker after classification.

`BootCompletedReceiver` should enqueue lightweight repair only. `GPTMobileApp` launch remains the reliable catch-up path. Repair must be idempotent because OEM boot delivery and WorkManager timing are not guaranteed.

### WorkManager Semantics

- Use separate unique work names for semantic, index, and repair families.
- Keep immediate and delayed work separate so delayed `KEEP`/`REPLACE` policy cannot block urgent repair.
- Do not set `NetworkType.CONNECTED` on the local families.
- Do not use WorkManager's blind top-level retry as a second, conflicting source of truth. Map failures into persisted job state, schedule the calculated next wake, and return an appropriate terminal Worker result for that invocation.
- Preserve notification decisions based on persisted job-state transitions.

### Retry And Worker Result Contract

Use an explicit family policy rather than applying the current global three-attempt limit to every task:

| Family/outcome | Persisted policy | WorkManager result for this invocation |
| --- | --- | --- |
| Semantic transient failure | At most 3 attempts for the immutable semantic input; persist `FAILED_RETRYABLE` plus `nextRunAt`, then terminal after attempt 3 | `success()` after state/next wake is durably persisted |
| Semantic invalid/permanent failure | Persist terminal state and notification/manual-retry eligibility | `success()` |
| Index transient failure | Up to 5 attempts per corpus generation with bounded exponential delays (for example 30 s, 2 min, 10 min, 1 h, 6 h), then `BLOCKED`/`WAITING_REPAIR`; a later model/fingerprint/provision/manual/startup event may open a new repair cycle | `success()` after state/next wake is durably persisted |
| Model not installed/unavailable | Persist `BLOCKED_DEPENDENCY` with no hot-loop deadline; revive only on provisioning/model-version/manual/startup availability change | `success()` |
| ObjectBox corruption | Close/delete derived store and schedule one rebuild cycle; repeated failure becomes blocked diagnostics while lexical remains active | `success()` after persistence |
| Repair completed or follow-up scheduled | Persist all discovered transitions/jobs | `success()` |
| Room cannot be opened or failure occurs before any result/state can be persisted | No trustworthy local source of retry truth was updated | `retry()` with WorkManager backoff |
| Invalid Worker input/programming contract | Log content-free diagnostics; use `failure()` only when retry cannot make the invocation valid | `failure()` |

Add the required blocked/lease constants and fields in schema 15 rather than overloading `FAILED_TERMINAL`. `attempts` and retry cycles must be defined per family/generation. A Worker must not both persist a `nextRunAt` retry and return `retry()`, because that creates two competing schedules.

### Daily Distillation

Implement the currently missing maintenance behavior.

- Fold `PROMOTE_LONG_TERM_CANDIDATE` semantics into `DISTILL_DAILY_NOTES`; do not maintain two competing promotion pipelines.
- Migrate, translate, or explicitly dismiss persisted legacy promotion jobs so none become unknown infinite retries.
- Persist a distillation checkpoint/cursor in Room, including processed daily path/hash/date, target `MEMORY.md` base/target hashes, and semantic job ID.
- Schedule once per local day for closed daily files, with launch-time catch-up. Do no LLM work when no daily revision is pending.
- Bound input by date/file count/chars/tokens and split deterministic immutable batches when needed.
- Provide the current curated `MEMORY.md` entries so the LLM can create, replace, merge, or ignore without duplicate preferences.
- Reuse the strict controlled-operation validation and stable entry-ID behavior.
- One immutable distillation batch uses at most one semantic request per attempt; it never calls a separate selector/reranker.
- Success means the controlled Markdown target is committed/recoverable. Vector sync follows independently.
- Do not delete daily files as part of first implementation. Advance the checkpoint only for exact processed hashes.

## Room And ObjectBox Migration Plan

Use a staged migration. Do not jump directly from schema 14 to dropped Room index tables in one release.

### Stage A: Room `14 -> 15`, Old Index Retained

- Add mutation receipt/index sync/corpus generation state and the distillation checkpoint required above.
- Add the lease/version/blocked state needed for atomic family claims and dependency-aware retries.
- Keep `memory_document`, `memory_chunk`, and `MemoryIndexDao` temporarily for rollback/shadow comparison.
- Register `MIGRATION_14_15` in `DatabaseModule` and export schema `15.json`.
- Preserve every business and operational row.
- Add real Room migration instrumentation tests using `MigrationTestHelper`; keep the existing SQL unit tests as fast checks.
- Define handling for all persisted legacy memory job types.

### Stage B: Shadow ObjectBox Build

- Build ObjectBox only from current `MEMORY.md`, never from old Room chunks.
- Compare snapshot chunk IDs/hashes and lexical/hybrid results in tests and optional content-free diagnostics.
- Keep production recall lexical/current-Markdown until fingerprint, real embedding, device, and process-death gates pass.
- Rebuild automatically after app data restore because ObjectBox is intentionally under `noBackupFilesDir`.

### Stage C: Hybrid Cutover

- Bind `HybridMemoryRetriever` behind the existing `MemoryRetriever` boundary.
- Ordinary chat remains explicitly long-term-only.
- Keep lexical fallback enabled permanently.
- Do not expose an end-user switch unless needed for diagnosis; an internal rollout/config gate is sufficient for the single-user project.

### Stage D (Later Release): Room `15 -> 16`, Drop Derived Index Tables

Do not perform this stage in the first schema 15 implementation or release. If the final APK immediately declares schema 16, an existing schema 14 installation will run `14 -> 15 -> 16` back-to-back and there is no deployed schema 15 shadow/cutover period. Open a later implementation run only after the schema 15 build has been installed, exercised, and accepted with ObjectBox/hybrid recovery evidence.

In that later release:

- remove production dependencies on `MemoryIndexDao`, `MemoryDocument`, and Room `MemoryChunk`;
- drop `memory_chunk` first, then `memory_document` because of the foreign key;
- remove their entities/DAO provider from `ChatDatabaseV2`/`DatabaseModule`;
- register `MIGRATION_15_16` and export schema `16.json`;
- preserve all business/operational tables and data;
- do not migrate old Room chunks into ObjectBox; rebuild from current Markdown;
- retain legacy `PersonalMemory`/`ChatClassification` unless separately authorized.

There must be no destructive migration fallback.

## Implementation Tasks

Complete Tasks 0-7 in order for the first schema 15 delivery. Task 8 is a documented later-release cleanup and must remain unchecked unless the user separately authorizes it after schema 15 has been installed and observed. After each active task, run its focused tests, `:app:testDebugUnitTest --tests "*Memory*"`, `:app:compileDebugKotlin`, and `git diff --check` before committing. If a phase cannot pass, stop that phase and report evidence; do not stack later phases on a broken base.

### Task 0: Branch, Baseline, And Dependency Gate

- [ ] Read `AGENTS.md`, this prompt, the two preceding memory plans, and `docs/architecture/on-device-vector-memory-readiness.md`.
- [ ] Run `git status --short --branch`, record `HEAD`, and preserve all unrelated user changes.
- [ ] Create a dedicated branch/worktree before editing; suggested branch: `feature/openclaw-vector-memory-maintenance`.
- [ ] Trace the current recall, consolidation, Markdown write/rollback, worker, launch/boot repair, notification, and migration paths from live code.
- [ ] Run the current memory/repository baseline tests and Kotlin compilation.
- [ ] Record debug and release APK SHA-256, APK size, ABI contents, and whether an Android device/emulator is connected.
- [ ] Recheck ObjectBox 5.4.2 official Android/Kotlin setup and native runtime license.
- [ ] Prove a minimal ObjectBox entity can generate/build alongside current KSP, AGP, and Kotlin configuration before broad refactoring.
- [ ] Spike ONNX Runtime Android `1.27.0` plus reproducibly pinned `bge-small-zh-v1.5` INT8 (512 dimensions) against the embedding/tokenizer/device gate; promote it only if it passes, otherwise explicitly hold production at lexical while completing shadow infrastructure.
- [ ] Produce the immutable model artifact/distribution contract (revision, exporter, hashes, license, delivery mode, CI/release provisioning, missing-artifact behavior) before enabling release embedding DI or a production READY HNSW manifest.

Baseline commands:

```powershell
git status --short --branch
git rev-parse HEAD
adb devices
./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.MemoryRepositoryTest"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImplTest"
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:assembleDebug
git diff --check
```

### Task 1: Fix Corpus Scope And Extract Pure Chunking

- [ ] Make corpus scope explicit and required in `MemoryRetrievalRequest` or an equivalent typed request.
- [ ] Change ordinary `MemoryRepositoryImpl.prepareMemoryContext()` to `CHAT_RECALL_LONG_TERM`.
- [ ] Ensure ordinary chat cannot retrieve daily Markdown, Room daily chunks, backup/staging files, or legacy rows.
- [ ] Add a current-Markdown snapshotter and storage-neutral `MemoryCorpusChunk`.
- [ ] Refactor `MemoryChunker` away from Room entities while preserving stable IDs and surrogate-safe splitting.
- [ ] Implement/test current-Markdown lexical retrieval, including Chinese two/three-character n-grams.
- [ ] Keep a temporary adapter to old Room index types only as needed for Stage A shadow compatibility.
- [ ] Add immediate regression tests proving a hidden daily preference cannot enter ordinary prompt memory.

Acceptance:

- [ ] changing/deleting `MEMORY.md` removes old text from lexical recall immediately;
- [ ] the same text present only in a daily file is absent from ordinary recall;
- [ ] consolidation can still request an explicit maintenance working set;
- [ ] upper chat/provider APIs remain unchanged.

### Task 2: Add ObjectBox And Embedding Boundaries In Shadow Mode

- [ ] Add pinned ObjectBox version/plugin/runtime dependencies and license metadata.
- [ ] Create a dedicated `BoxStore` below `noBackupFilesDir`.
- [ ] Add the vector entity and ObjectBox manifest with fixed HNSW cosine dimension.
- [ ] Implement `MemoryVectorStore` CRUD, transactional snapshot replace, query, manifest, close/delete, and corruption recovery boundaries.
- [ ] Add `MemoryEmbeddingProvider`, descriptor, availability states, deterministic fake, and release-DI guard against the fake.
- [ ] Integrate the selected real on-device model only if Task 0 gate passed.
- [ ] Keep ObjectBox shadow-only; do not bind hybrid production recall yet.
- [ ] Add Android instrumentation tests for native ObjectBox behavior.

Acceptance:

- [ ] insert/query/update/delete/reopen work on device;
- [ ] wrong vector dimension fails explicitly;
- [ ] a transaction never publishes a partial READY manifest;
- [ ] deleting the vector directory leaves Markdown and business Room intact;
- [ ] release build/R8 can open the generated `BoxStore`;
- [ ] fake embeddings cannot be selected by release DI.

### Task 3: Add Room `14 -> 15` Recovery State And Split Workers

- [ ] Add mutation receipt/group, corpus/index state, monotonic generation, and distillation checkpoint entities/DAOs.
- [ ] Implement and register non-destructive `MIGRATION_14_15`; export schema `15.json`.
- [ ] Preserve the old Room index tables for shadow mode.
- [ ] Split semantic, local index, and local repair WorkManager entry points and unique work names.
- [ ] Replace list-then-mark job selection with a Room transactional/CAS family claim, lease owner/expiry, and a database-enforced global semantic lease.
- [ ] Apply network constraints only to semantic work and explicit model provisioning.
- [ ] Make persisted scheduler/job state the retry source of truth.
- [ ] Implement the documented per-family attempts/backoff/blocked states and Worker result mapping; do not reuse semantic's three-attempt terminal rule for index/repair.
- [ ] Translate/drain/dismiss every legacy persisted job type deterministically.
- [ ] Extend launch and boot repair to reconcile receipts/manifests and schedule the correct family.

Acceptance:

- [ ] real migration tests preserve populated chats, messages, platforms, pending turns, checkpoints, jobs, and activity logs;
- [ ] local index/repair work runs offline;
- [ ] semantic work remains connected-only and globally serialized;
- [ ] concurrent immediate/delayed/boot Workers can claim a semantic job only once;
- [ ] immediate local repair is not blocked by delayed semantic/index work;
- [ ] an `A -> B -> A` file sequence creates a new generation and is not suppressed by an old succeeded job.

### Task 4: Implement Commit Receipts And Vector Synchronization

- [ ] Refactor `MemoryBatchConsolidationService` to stage exact targets and persist PREPARED receipts before file replacement.
- [ ] Mark canonical mutation success independently from derived indexing.
- [ ] Remove rollback-on-index-failure behavior and update tests that currently encode it.
- [ ] Ensure process death after file replacement does not cause a second LLM call.
- [ ] Implement latest-generation ObjectBox synchronization with before/after hash checks and fingerprint enforcement.
- [ ] Publish vectors and ObjectBox manifest atomically.
- [ ] Reconcile the crash window after ObjectBox commit but before Room job completion.
- [ ] Coalesce superseded index jobs and prevent an old job from marking a newer generation ready.
- [ ] Treat ObjectBox corruption as delete/rebuild of derived state only.

Acceptance:

- [ ] Markdown commit survives embedding/ObjectBox failure;
- [ ] vector failure produces lexical recall from the new Markdown revision;
- [ ] stale vectors are never queried;
- [ ] retrying the same receipt creates no duplicate rows or entries;
- [ ] changing model, dimension, tokenizer, chunker, or source hash forces the correct rebuild behavior.

### Task 5: Implement And Cut Over Hybrid Recall

- [ ] Implement bounded ObjectBox vector retrieval for the current long-term snapshot.
- [ ] Implement deterministic RRF, deduplication, MMR/diversity, and final token packing.
- [ ] Keep lexical and vector score provenance.
- [ ] Bind `HybridMemoryRetriever` behind `MemoryRetriever` only after real embedding/device gates pass.
- [ ] Retain lexical fallback permanently.
- [ ] Ensure the normal Memory screen still reads only `MEMORY.md` and exposes no index internals.
- [ ] Add Chinese/English lexical, semantic paraphrase, stale-index, deletion, and token-budget tests.

Acceptance:

- [ ] a Chinese paraphrase with little/no lexical overlap retrieves the intended visible long-term memory on a real device;
- [ ] a hidden daily preference never appears in ordinary results;
- [ ] deleting visible text prevents recall even while vector sync is pending;
- [ ] vector/model absence does not block chat and produces current lexical results;
- [ ] pre-answer recall performs zero network/LLM calls.

### Task 6: Implement Daily Distillation

- [ ] Implement `DISTILL_DAILY_NOTES` with a persisted closed-daily-file checkpoint.
- [ ] Fold `PROMOTE_LONG_TERM_CANDIDATE` into that pipeline and handle old jobs.
- [ ] Schedule daily with launch-time catch-up and no-op when hashes are unchanged.
- [ ] Bound and freeze each semantic input batch.
- [ ] Reuse current strict operation validation and stable entry replacement/merge behavior.
- [ ] Commit distillation targets through the same mutation receipt protocol.
- [ ] Enqueue local vector sync without waiting for it.
- [ ] Preserve daily files and advance checkpoints only for exact processed hashes.

Acceptance:

- [ ] a daily-only stable preference is unavailable to normal chat before distillation;
- [ ] after successful promotion into `MEMORY.md`, it becomes recallable;
- [ ] replay/process death produces no duplicate long-term entry or second semantic call for an already-rendered target;
- [ ] unchanged daily files produce zero semantic calls;
- [ ] memory disabled produces zero calls and no retry loop.

### Task 7: Process-Death, Packaging, And Runtime Verification

- [ ] Add deterministic failpoints or a debug-only harness for each crash window in the required matrix.
- [ ] Verify startup recovery after killing the process at each point.
- [ ] Verify offline lexical recall, offline local index repair with an installed model, and no cloud fallback.
- [ ] Verify app-data backup/restore behavior: Markdown/Room may restore, ObjectBox under `noBackupFilesDir` rebuilds.
- [x] Run release R8 packaging and ABI inspection. Check both APK ZIP alignment and every packaged native library under arm64-v8a and x86_64 with the Android NDK `check_elf_alignment.sh`, `llvm-readelf`, or equivalent LOAD-segment evidence.
- [x] Run the release lifecycle on an Android 15+ Experimental 16 KB emulator, preferring ARM64 and allowing x86_64 fallback when the current host cannot run ARM64. Require `adb shell getconf PAGE_SIZE` to return exactly `16384`, then initialize, write, query, close/reopen, kill, restart, and reopen ObjectBox and ONNX Runtime.
- [ ] Run final physical ARM64 performance and OEM-environment validation. This is a separate production gate and does not block or reopen 16 KB page-size compatibility after the emulator gate passes.
- [ ] Record before/after APK size, model size/location, query latency, indexing time, and peak memory.
- [ ] Run the full focused suite and inspect content-free logs for duplicate calls/jobs.

### Task 8 (Later Release Only): Remove The Old Room Index With `15 -> 16`

Do not implement this task in the first delivery merely because Tasks 1-7 pass in one development worktree. Start it in a later release/independent prompt only after a schema 15 build has been installed and has produced accepted shadow/cutover/recovery evidence.

- [ ] Prove no production code reads/writes `MemoryIndexDao`, `MemoryDocument`, or Room `MemoryChunk`.
- [ ] Add/register `MIGRATION_15_16` dropping `memory_chunk` before `memory_document`.
- [ ] Remove old entities/DAO/provider and Room index adapters.
- [ ] Export schema `16.json`.
- [ ] Rebuild ObjectBox from current Markdown after upgrade; do not copy Room chunks.
- [ ] Add real populated `15 -> 16` and direct supported upgrade-path tests.
- [ ] Verify all business and operational Room data remains.
- [ ] Update `docs/architecture/on-device-vector-memory-readiness.md` with the final backend/model/fingerprint/recovery decision.
- [ ] Run final tests, debug/release builds, runtime smoke test, `git diff --check`, and `git status --short`.

## Required Failure And Process-Death Matrix

Automate at unit/instrumentation level where possible. Use a debug-only failpoint/device script for real process death; do not ship failpoints in release.

| Scenario | Required result |
| --- | --- |
| Daily file contains preference absent from `MEMORY.md` | Ordinary chat does not retrieve it |
| Long-term entry deleted, old vector still exists | Old vector is rejected; current lexical result contains no deleted text |
| `MEMORY.md` changes while embedding | Generated snapshot is discarded; latest generation is scheduled |
| Embedding provider unavailable | Current lexical recall; no cloud call; index remains pending/unavailable |
| ObjectBox missing/corrupt | Only derived store is recreated; canonical Markdown and business Room survive |
| Fingerprint/model/tokenizer/chunker mismatch | Vector branch fails closed and full rebuild is scheduled |
| Dimension mismatch | Explicit incompatible-store path; no malformed vector write/query |
| Process dies after PREPARED receipt | Repair commits staged target or safely leaves base content |
| Process dies after Markdown rename, before Room update | Target hash is recognized; no second LLM call; sync resumes |
| Process dies during embedding | Partial vectors are not READY; lexical uses current Markdown |
| Process dies after ObjectBox transaction, before Room update | Manifest advances Room receipt without duplicate rebuild/rows |
| Process dies after semantic file commit, before claimed-batch completion | Repair completes from receipt; no duplicate Markdown or semantic call |
| Two rapid Markdown mutations | Older sync cannot publish READY for newer generation |
| File sequence `A -> B -> A` | New monotonic generation is processed despite repeated hash |
| Multi-file semantic mutation interrupted midway | Recovery completes exact staged group or reports conflict; no index-driven rollback |
| Current file matches neither receipt base nor target | Conflict preserved/logged; newer content is not overwritten |
| WorkManager wakes early | No ineligible semantic/index call; next due work scheduled |
| Immediate/delayed/boot Workers race | Room lease/CAS allows one family claim; one semantic call |
| Semantic task fails three attempts | Terminal notification/manual retry path; no infinite loop |
| Local index task fails repeatedly | No hot loop; remains recoverable by launch/manual rebuild |
| Production model artifact is missing/unverified | Release hybrid/READY index is disabled or build fails by the chosen contract; fake/remote fallback is impossible |
| Memory disabled | No consolidation/distillation call; chat behavior remains valid |
| App restore excludes ObjectBox | Manifest mismatch triggers rebuild from restored `MEMORY.md` |
| Room `14 -> 15` populated upgrade | All business/operational rows preserved; new recovery state valid |
| Later-release Room `15 -> 16` populated upgrade | Only old derived index tables removed; business Room retained |
| Legacy persisted maintenance job | Deterministically translated, drained, or dismissed; never unknown-looping |
| Release/R8/native packaging | BoxStore opens and queries on supported target ABI/16 KB environment |

## Test Placement And Commands

Use JVM unit tests for pure chunking, hash/fingerprint, scope, RRF/MMR, job policy, receipt state machines, and fakes. Use Android instrumentation tests for real Room migrations, ObjectBox native persistence/HNSW, process restart, and packaged runtime behavior.

Add `androidx.room:room-testing` and `androidx.work:work-testing` where needed. Keep the current `ChatDatabaseV2MigrationsTest` SQL checks, but do not mistake them for real migration validation.

Suggested new/expanded tests:

- `MemoryCorpusSnapshotterTest`
- `MarkdownLexicalRetrieverTest`
- `MemoryIndexFingerprintTest`
- `HybridMemoryRetrieverTest`
- `MemoryIndexSynchronizerTest`
- `MemoryMutationRecoveryTest`
- `MemoryDistillationServiceTest`
- `MemoryIndexWorkSchedulerTest`
- `ObjectBoxMemoryVectorStoreInstrumentedTest`
- `ChatDatabaseV2MigrationInstrumentedTest`
- extensions to `MemoryIndexRepositoryTest`, `MemoryBatchConsolidationServiceTest`, `MemoryMaintenanceProcessorTest`, and `MemoryRepositoryTest`

Focused/final commands:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.MemoryRepositoryTest"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImplTest"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.database.MemoryTurnBatchDaoTest"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2MigrationsTest"
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.data.memory.ObjectBoxMemoryVectorStoreInstrumentedTest
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2MigrationInstrumentedTest
./gradlew.bat :app:assembleRelease
./build-apk.ps1 -Release
git diff --check
git status --short --branch
```

When Android build tools are available, also capture:

```powershell
apkanalyzer files list <apk> | Select-String "/lib/|libobjectbox"
zipalign -c -P 16 -v 4 <apk>
<android-ndk>/build/tools/check_elf_alignment.sh <apk>
adb shell getprop ro.product.cpu.abilist
adb shell getconf PAGE_SIZE
Get-FileHash <apk> -Algorithm SHA256
tools/memory-vector/run-16kb-release-compatibility.ps1 -Serial <16-kb-emulator-serial>
```

`zipalign -P 16` checks APK ZIP placement for uncompressed native libraries; it does not prove ELF LOAD segments are 16 KB compatible. Preserve four independent evidence classes: ZIP alignment, every arm64-v8a/x86_64 ELF LOAD alignment, the Android 15+ 16 KB emulator release lifecycle with `PAGE_SIZE=16384`, and final physical ARM64 performance/OEM measurements. The last gate does not block or reopen the page-size compatibility gate.

Do not claim device/process-death/native behavior was verified if no device was connected. Report that gap explicitly.

## Commit Discipline

- Work on the dedicated branch/worktree from Task 0.
- Preserve the dirty user changes listed in the snapshot; never reset or checkout them away.
- Commit one coherent verified slice at a time, suggested order:
  1. scope/snapshot/pure chunking and tests;
  2. ObjectBox/embedding boundaries and native shadow tests;
  3. Room `14 -> 15`, receipts, and worker split;
  4. commit protocol/synchronizer/recovery;
  5. hybrid cutover;
  6. daily distillation;
  7. process-death/release evidence;
  8. in a later separately authorized release, Room `15 -> 16` cleanup/docs.
- Do not mix generated build output, local models, benchmark artifacts, or unrelated UI changes into commits.
- After interface changes, immediately update all test fakes before moving to the next slice.
- Do not merge back to `main` unless the user explicitly asks.

## Non-Goals

- Do not delete or replace the business Room database.
- Do not make ObjectBox the source of truth.
- Do not put daily files into ordinary chat recall or the first HNSW corpus.
- Do not add QMD, Node/Bun, Python, Milvus, Qdrant, LanceDB, a sidecar, or a cloud vector database.
- Do not use a remote embedding API or silently reuse chat-provider credentials for embeddings.
- Do not introduce LangChain4j, Koog, or a full agent framework.
- Do not add a dedicated recall selector/reranker LLM call.
- Do not put a fake/hash/random embedding provider into production.
- Do not expose ObjectBox, scores, daily notes, jobs, or maintenance internals on the normal Memory screen.
- Do not add per-entry approval/archive/resolve/delete workflows.
- Do not change attachments, editing, retry/revision, export, multi-provider flows, reasoning modes, tools/search, token accounting, or chat UI beyond the typed memory boundary.
- Do not remove `PersonalMemory` or `ChatClassification` in this plan.
- Do not delete daily Markdown after distillation in the first implementation.
- Do not use timestamps as index freshness proof.
- Do not use destructive Room migration or copy old Room chunks into ObjectBox.
- Do not drop `memory_chunk`/`memory_document` or declare schema 16 in the first schema 15 delivery; preserve a real installed shadow/cutover period.
- Do not roll canonical Markdown back because a derived index failed.
- Do not report vector recall complete without a real on-device embedding and device evidence.

## Completion Report Template

The final implementation handoff must report:

```text
Branch/worktree and baseline:
- branch:
- baseline commit:
- unrelated user changes preserved:

Ownership after implementation:
- canonical long-term path:
- internal daily path/scope:
- retained Room responsibilities:
- ObjectBox path and deletion/rebuild behavior:

Backend/model:
- ObjectBox version/license verification:
- embedding runtime/model/version/checksum/license:
- dimension/normalization/prefixes:
- bundled or downloaded model lifecycle:
- index fingerprint fields:

Recall correctness:
- proof ordinary chat searches only current MEMORY.md:
- proof hidden daily text is excluded:
- lexical/vector/RRF/MMR settings:
- stale-vector fail-closed behavior:
- dedicated recall LLM/network calls (must be zero):

Maintenance/recovery:
- semantic/index/repair worker constraints:
- mutation state machine and generation behavior:
- index failure behavior after Markdown commit:
- daily distillation cadence/checkpoint/call count:
- legacy job handling:
- startup/boot reconciliation:

Migrations:
- 14 -> 15 preservation evidence:
- shadow/cutover evidence:
- schema reached by this delivery (must be 15 initially):
- later 15 -> 16 cleanup readiness/evidence still required:
- exported schema files:

Verification:
- tests/commands and results:
- process-death cases verified:
- device/ABI/16 KB/R8 evidence:
- APK SHA-256 and before/after size:
- model/index latency and peak-memory measurements:
- intentionally unverified items:

Commits:
- hash and responsibility for each slice:

Remaining risks or deferred decisions:
```

Do not report completion while any of these remain true:

- ordinary recall can search a daily file;
- a stale vector can inject deleted/non-visible text;
- index failure rolls committed Markdown back;
- local repair/index work requires network connectivity;
- distillation remains an unimplemented job type;
- real vector production DI uses fake embeddings;
- Room index tables are dropped before shadow/cutover/recovery gates pass;
- business Room data is removed or destructively migrated.

## Copy-Paste Prompt For A Fresh Implementation Conversation

```text
Work in E:\code\ChatWithChat.

Read E:\code\ChatWithChat\AGENTS.md and then read this implementation prompt completely:
E:\code\ChatWithChat\docs\superpowers\plans\2026-07-12-openclaw-style-vector-memory-maintenance-prompt.md

Implement unchecked Tasks 0-7 in order. Task 8 is deliberately deferred to a later schema 16 release. This is an implementation request, not another planning request.

Start by auditing the live repo and creating a dedicated branch/worktree. The source worktree may contain unrelated user changes in chat UI, ChatViewModel, history drawer, strings, ChatLaunchState, and tests; preserve them exactly and do not include them in memory commits.

Hard requirements:
- MEMORY.md is the only ordinary chat-recall corpus.
- daily Markdown is maintenance-only until distilled into MEMORY.md.
- retain the business Room database and its chat/operational data.
- use ObjectBox 5.4.2 as a separate noBackupFilesDir HNSW derived index, subject to the documented canary gate.
- start the real-device embedding spike with ONNX Runtime Android 1.27.0 plus pinned bge-small-zh-v1.5 INT8 (512 dimensions), and promote it only after the documented tokenizer, Recall@5, performance, license, and packaging gates pass.
- require an immutable model revision/export/hash/distribution/CI artifact contract; without it, keep production lexical and do not publish a READY 512-dimension index.
- keep every embedding behind MemoryEmbeddingProvider; never silently use cloud embeddings and never ship fake embeddings in production.
- keep current-Markdown lexical fallback and reject every stale/fingerprint-mismatched vector snapshot.
- canonical Markdown commits must never be rolled back because derived indexing fails.
- split semantic, local index, and local repair workers by network/retry responsibility.
- use Room transactional/CAS family claims with leases and the documented per-family retry/Worker-result contract so concurrent Workers cannot duplicate semantic calls.
- implement daily distillation and fold PROMOTE_LONG_TERM_CANDIDATE into it.
- migrate Room 14 -> 15 now for durable receipt/sync state and retain the old derived index tables, unused by production recall, for a real installed shadow/cutover period.
- do not declare schema 16 or drop memory_chunk/memory_document in this first delivery; leave Task 8 for a later separately authorized release after schema 15 evidence exists.
- rebuild ObjectBox from Markdown, never from old Room chunks.
- preserve zero dedicated recall LLM calls and current five-turn/idle batching semantics.

Use small verified commits. Run the focused tests, *Memory* tests, compileDebugKotlin, and git diff --check after every slice. Run real Room/ObjectBox instrumentation, process-death, release/R8, ABI, and 16 KB packaging checks before cleanup/cutover. Do not claim checks you could not run.

Continue through Tasks 0-7, verification, and the completion-report template unless a documented hard gate fails. Leave Task 8 unchecked for the later schema 16 cleanup release. If ObjectBox or a real on-device model fails its gate, stop before substituting another backend/model and report exact evidence for a product decision.
```
