# ChatWithChat Five-Turn Batched Memory And On-Device Vector Readiness Prompt

> **For the implementation agent:** Work in `E:\code\ChatWithChat`. This is an implementation handoff, not a request for another architecture proposal. Read `AGENTS.md`, inspect the current branch and dirty worktree, then implement the tasks in order. Preserve unrelated user changes. Do not invent repository APIs, do not stop after writing a plan, and run the smallest relevant tests after every phase.

## Goal

Refactor ChatWithChat's cross-conversation personalized memory system so that memory processing no longer sends several extra LLM requests after every completed answer.

The target behavior is:

- Normal chat completion must perform **zero dedicated memory LLM calls on the foreground path**.
- Memory recall before a provider request must perform **zero dedicated memory LLM calls**.
- Each chat accumulates completed turns independently.
- Every five completed turns are consolidated by **one structured memory LLM request**.
- If a chat has one to four pending completed turns and receives no new user prompt for 30 minutes, those remaining turns are consolidated by **one structured memory LLM request**.
- If unprocessed turns are about to be omitted by context compaction, use the same batch-consolidation path instead of a separate compaction LLM path.
- The current `classify -> extract -> plan -> markdown_write` duplication must be removed from the active runtime path.
- Markdown remains the source of truth. Room remains operational state and a rebuildable search index.
- The retrieval boundary must be ready for a future on-device vector index without adding a cloud embedding API or committing to one vector library in this task.

The normal memory API budget must be:

| Scenario | Dedicated memory LLM requests |
|---|---:|
| Memory disabled | 0 |
| One to four active turns, before idle deadline | 0 |
| Five completed turns in one chat | 1 |
| One to four completed turns idle for 30 minutes | 1 |
| Recall before each normal answer | 0 |
| Local index rebuild | 0 |
| Failed batch | At most 3 total automatic attempts for the same immutable batch |

Do not count the user's normal chat completion or tool loop as a memory LLM request.

## Product Semantics

This is a chat-centered, cross-conversation personalized memory feature. It is not a work-agent task log.

The memory layer should preserve durable information such as:

- stable preferences and communication style;
- important people, relationships, events, interests, boundaries, and life context;
- recurring themes and ongoing personal or project context;
- user corrections that replace an older memory;
- progress updates that should update an existing memory rather than create a neighboring duplicate.

The LLM owns semantic decisions. Code owns scheduling, storage, validation, idempotency, indexing, retry limits, and safe application of operations. Do not replace the consolidated LLM decision with keyword rules or regular-expression classification.

There is no user confirmation queue. Valid local memory writes are autonomous. The user-facing Memory screen remains a read-only view of `MEMORY.md`; do not add per-entry confirmation, archive, resolve, or delete workflows.

## Current State That Must Be Re-Audited

The following statements are true on `main` at the time this prompt was written, but line numbers and schema versions may move. Confirm them with `rg` before editing:

- `ChatViewModel.prepareMemoryPrompt(...)` calls `MemoryRepository.prepareMemoryContext(...)` before the provider request.
- `ChatViewModel.observeStateChanges()` saves a completed chat and calls `learnFromSavedChat(...)` after provider loading states become idle.
- `MemoryRepositoryImpl.prepareMemoryContext(...)` currently calls `classifyConversation(...)` and may call `selectMemories(...)` before local recall.
- `MemoryRepositoryImpl.learnFromChat(...)` currently calls `classifyConversation(...)`, `extractMemoryCandidates(...)`, and conditionally `planMemoryUpdates(...)`.
- `finishLearningResult(...)` then invokes `MarkdownMemoryLearningService`, which calls `proposeMarkdownMemoryWrites(...)`.
- `ChatRepositoryImpl.scheduleCompactionFlushIfNeeded(...)` can enqueue a separate `COMPACTION_FLUSH` job.
- `MemoryFileStore`, `MarkdownMemoryCodec`, `MemoryIndexRepository`, `MemoryMaintenanceScheduler`, `MemoryMaintenanceWorker`, `MemoryMaintenanceRepairer`, delayed WorkManager scheduling, boot repair, and maintenance notifications already exist.
- `MemoryIndexRepository` currently performs local lexical matching and returns stable chunk metadata, but its tokenization is weak for unsegmented Chinese text.
- `ChatDatabaseV2` was at version 12 when this document was written. Use the next valid schema version after inspecting the actual branch; do not blindly force version 13 if it has already moved.

Start with:

```powershell
git status --short --branch
Get-Content -LiteralPath AGENTS.md -Encoding UTF8
rg -n "prepareMemoryPrompt|learnFromSavedChat|prepareMemoryContext|learnFromChat|classifyConversation|extractMemoryCandidates|planMemoryUpdates|proposeMarkdownMemoryWrites|scheduleCompactionFlushIfNeeded" app/src/main/kotlin
rg -n "MemoryMaintenanceJobType|MemoryMaintenanceScheduler|MemoryMaintenanceWorker|MemoryIndexSearcher|MemoryIndexSearchRequest" app/src/main/kotlin
```

## Definitions

### Completed Turn

A completed turn is one user message plus at least one successfully persisted, nonblank assistant answer.

Rules:

- A multi-provider response counts as one completed turn, not one turn per provider.
- Wait until all selected provider streams have reached a terminal state and the chat has been saved before recording the turn.
- At least one assistant `effectiveContent()` must be nonblank. A turn where every provider failed or was cancelled does not count.
- Tool-loop intermediate requests do not add turns.
- Retrying an assistant answer or selecting a historical revision does not create another memory turn.
- Reprocessing the same user message ID must update an existing pending snapshot when appropriate, not increment the pending count.
- A newly sent user message is a new turn even when it is textually identical to an older one.
- Attachments should be represented only by existing safe textual metadata. Do not send local file bytes to the memory LLM.

### Canonical Assistant Context

The memory batch must not include every multi-provider answer because that duplicates tokens and can cause conflicting inferred facts.

Choose one canonical successful assistant answer deterministically:

1. Prefer the answer from the chat's selected/default memory platform when it succeeded.
2. Otherwise choose the first successful enabled provider in stable platform order.
3. Use assistant content only to resolve conversational references. The consolidation prompt must prohibit treating unsupported assistant claims as user facts.
4. Apply a per-message character/token cap using existing project token utilities where practical.

### Pending Batch

A pending batch contains one to five immutable completed-turn snapshots from exactly one chat. Never mix turns from different chats in one LLM request.

## Target Flow

```text
User sends a prompt
  -> record last user activity locally
  -> local memory retrieval only
  -> merge bounded memory candidates into the normal provider prompt
  -> provider/tool response completes
  -> save chat and messages
  -> persist/update one pending completed-turn snapshot
      -> pending count >= 5: enqueue one immediate consolidation batch
      -> pending count 1..4: set idle due time to last activity + 30 minutes
      -> context will omit pending turns: enqueue the same consolidation batch immediately

MemoryMaintenanceWorker
  -> claims one immutable batch globally
  -> one LLM call: consolidateMemoryBatch(...)
  -> validate structured operations
  -> atomically update daily Markdown and/or MEMORY.md
  -> rebuild affected lexical index chunks
  -> mark batch succeeded and advance checkpoint
  -> schedule the next oldest due batch
```

The foreground chat path may enqueue local database work, but it must never wait for memory consolidation or make a memory network request.

## Required Persistence Model

Do not rely only on an in-memory counter or `ViewModel` state. The counter and snapshots must survive navigation, process death, reboot, and WorkManager delay.

Introduce repository-appropriate entities similar to the following. Exact names can follow current conventions.

### `MemoryChatCheckpoint`

Suggested fields:

```kotlin
data class MemoryChatCheckpoint(
    val chatId: Int,
    val lastProcessedUserMessageId: Int,
    val lastObservedUserMessageId: Int,
    val pendingSince: Long?,
    val lastUserActivityAt: Long?,
    val idleDueAt: Long?,
    val updatedAt: Long
)
```

### `MemoryPendingTurn`

Suggested fields:

```kotlin
data class MemoryPendingTurn(
    val turnKey: String,
    val chatId: Int,
    val userMessageId: Int,
    val payloadJson: String,
    val contentHash: String,
    val completedAt: Long,
    val claimedJobId: String?,
    val createdAt: Long,
    val updatedAt: Long
)
```

Requirements:

- Add a unique constraint for `(chatId, userMessageId)` or an equivalent stable turn key.
- Use a foreign key with cascade cleanup when compatible with the existing chat schema.
- Persist an immutable snapshot in the maintenance job before starting LLM work.
- A claimed pending turn must not be claimed by another job.
- Only one active consolidation job may exist per chat.
- Batch idempotency should include the chat, first and last turn keys, and a content hash, for example:

```text
memory_batch:<chatId>:<firstTurnKey>:<lastTurnKey>:<contentHash>
```

- On success, delete or mark the claimed pending rows processed and advance the checkpoint in a transaction.
- On retryable failure, retain the same claim and immutable job payload.
- Never advance the checkpoint after invalid JSON, network failure, or partial file-write failure.
- If the app is opened after missed background work, repair logic must discover both runnable jobs and due unclaimed pending turns.

If a simpler schema can prove all of the same crash, retry, and deduplication guarantees with tests, it is acceptable. Do not simplify by returning to an in-memory five-turn counter.

## Trigger Rules

### Threshold Trigger

- After a completed turn is persisted, count unclaimed pending turns for that chat.
- If at least five exist, claim the oldest five and enqueue an immediate consolidation job.
- If ten or more exist after process recovery, process them as sequential batches of five, never one unbounded request.
- Do not execute multiple memory LLM jobs concurrently. All automatic memory LLM work must pass through one globally serialized worker path.

### Idle Trigger

- Default idle duration: 30 minutes since the most recent user prompt in that chat.
- One to four pending turns are eligible after the deadline.
- A new user prompt updates `lastUserActivityAt` and pushes `idleDueAt` forward.
- A delayed worker must re-read the database before making an LLM request. If activity moved the deadline, it must reschedule without calling the LLM.
- WorkManager timing is approximate; correctness must be based on the persisted deadline, not an assumption that the worker wakes exactly on time.
- Use the existing global maintenance queue. When scheduling delayed work, always schedule against the earliest database `nextRunAt`; do not let a later chat replace and postpone an earlier due job.

### Context-Compaction Trigger

- `ChatRepositoryImpl.scheduleCompactionFlushIfNeeded(...)` must not create a second semantic write path.
- When context construction discovers omitted turns, check whether any omitted completed turn is still pending.
- If so, mark the same pending batch as immediately due or enqueue the same consolidation job type.
- If all omitted turns were already processed, do nothing.
- Threshold, idle, and compaction triggers for the same turn range must converge on one idempotent job.

### Memory Toggle

- Memory disabled means no recall, no pending-turn recording, no new jobs, and no automatic retry calls.
- When memory is disabled, cancel or dismiss unstarted consolidation jobs and establish a new baseline so those conversations are not learned retroactively after re-enabling.
- Do not repeatedly mark disabled work as retryable; that creates an endless maintenance loop without useful work.
- When memory is re-enabled, begin tracking from the current latest completed turn unless an explicit existing migration behavior requires otherwise.

## One Consolidated LLM Contract

Replace the active runtime use of:

- `classifyConversation(...)`;
- `selectMemories(...)` for pre-answer recall;
- `extractMemoryCandidates(...)`;
- `planMemoryUpdates(...)`;
- `proposeMarkdownMemoryWrites(...)` as a second learning pass.

Expose one semantic method for batch learning, for example:

```kotlin
suspend fun consolidateMemoryBatch(
    request: MemoryBatchConsolidationRequest,
    preferredPlatform: PlatformV2?
): MemoryBatchConsolidationProposal?
```

The request should contain:

- chat ID and title;
- trigger reason: `threshold`, `idle`, `context_compaction`, `manual_retry`;
- one to five completed-turn snapshots;
- locally retrieved existing Markdown memories that may need an update;
- stable entry IDs and metadata for those existing memories;
- no full `MEMORY.md` dump when bounded local retrieval is sufficient;
- no legacy Room memory duplicates after migration is complete.

Use a fail-closed structured response such as:

```json
{
  "operations": [
    {
      "destination": "daily|long_term",
      "action": "create|replace|remove|ignore",
      "targetMemoryId": "existing Markdown memory id or null",
      "text": "complete semantic memory text or empty for ignore/remove",
      "type": "stable_profile|communication_style|project_context|interest|important_event|important_person|emotional_pattern|boundary|life_context|recurring_theme|light_productivity_preference",
      "sensitivity": "normal|private|sensitive",
      "source": "explicit_user_statement|assistant_inferred|user_confirmed",
      "evidenceTurnKeys": ["stable turn key"],
      "reason": "short reason"
    }
  ]
}
```

Validation rules:

- `replace` and `remove` may target only IDs supplied in the request.
- Do not allow invented paths, sections, IDs, or action names.
- `ignore` performs no write.
- Empty operations are a successful no-memory result and must advance the batch checkpoint.
- A fact should not be copied into daily and long-term destinations in the same batch unless the schema and tests establish a deliberate source-log relationship. Prefer one semantic operation per fact.
- Long-term updates replace the complete existing entry text rather than appending a near-duplicate.
- Never store raw transcripts or prefix text with `The user said:`.
- Assistant statements are not user facts unless confirmed by the user's messages.
- Preserve sensitivity metadata, but do not introduce an approval gate.
- Validate size limits before writing.
- Apply all Markdown changes atomically or restore from the existing backup mechanism.
- Rebuild only affected index files/chunks after a successful commit.

The memory provider request must use the existing provider routing and low-cost reasoning/output caps. Do not add another provider SDK.

## Recall Without A Dedicated LLM Call

`prepareMemoryContext(...)` must become local-only.

Required behavior:

1. Build a bounded retrieval query from the latest user prompt and compact recent chat context.
2. Search Markdown-derived local indexes.
3. Apply local scope and sensitivity metadata filtering.
4. Deduplicate results by stable memory entry ID/content hash.
5. Pack the highest-ranked candidates into a fixed token budget using the existing token counter.
6. Inject the result through the existing `memoryPrompt` and `mergePromptSections(...)` path.
7. Tell the normal answer model to use only memories genuinely relevant to the current request and never force an explicit mention.
8. Search failure must degrade to no memory and must not fail chat completion.

The main answer LLM is the final semantic relevance gate. Do not call a second LLM merely to select or rerank memories.

The current lexical tokenizer treats a long Chinese sentence poorly. Improve lexical recall with deterministic local tokenization such as:

- normalized Latin word tokens;
- Chinese two- and three-character n-grams;
- exact phrase bonus;
- stable weighting for long-term memory over daily notes;
- bounded candidate and result counts.

This tokenization is a retrieval mechanism, not a semantic memory classifier. Do not use it to decide what should be remembered.

## On-Device Vector Readiness

Do not introduce a cloud embedding request. Do not add LangChain4j, Koog, or a backend-oriented agent framework. Do not download a large embedding model as part of this refactor.

Create a stable retrieval boundary now so a future on-device vector implementation can be added without changing `ChatViewModel`, `ChatRepositoryImpl`, `MemoryPromptBuilder`, or Markdown storage.

Suggested contracts:

```kotlin
interface MemoryRetriever {
    suspend fun retrieve(request: MemoryRetrievalRequest): Result<List<MemoryRetrievalResult>>
}

data class MemoryRetrievalRequest(
    val query: String,
    val recentContext: String?,
    val limit: Int,
    val candidateLimit: Int,
    val tokenBudget: Int,
    val includePrivate: Boolean,
    val sourcePath: String? = null
)

data class MemoryRetrievalResult(
    val chunkId: String,
    val entryId: String?,
    val sourcePath: String,
    val text: String,
    val type: String?,
    val sensitivity: String?,
    val contentHash: String,
    val lexicalScore: Float? = null,
    val vectorScore: Float? = null,
    val fusedScore: Float,
    val updatedAt: Long
)
```

Use naming consistent with the current code rather than mechanically copying these examples.

Architecture requirements:

- Adapt the current `MemoryIndexSearcher`/`MemoryIndexRepository` behind the stable retrieval facade as the lexical implementation.
- Keep stable `chunkId`, `entryId`, `sourcePath`, and `contentHash` values so future embedding rows can reference derived Markdown chunks.
- Keep embedding/vector data rebuildable from Markdown. Vector rows must never become the source of truth.
- Allow future `LEXICAL`, `VECTOR`, and `HYBRID` strategies without changing call sites.
- A future hybrid retriever should combine ranked lists with a method such as reciprocal-rank fusion instead of directly adding incomparable raw scores.
- A future vector row should be versioned by model ID, model version, vector dimension, and source content hash so changing the model invalidates stale vectors deterministically.
- Index write/rebuild results should expose enough changed chunk information for a future vector backend to update incrementally.
- Keep the current task's default strategy lexical. Add interfaces, adapters, metadata, and tests needed for readiness, but do not add a concrete vector dependency unless the repository already contains an approved one.

Document, but do not prematurely choose between, future Android-compatible options such as:

- Room plus a small brute-force cosine search for a small memory corpus;
- `sqlite-vec` if Android packaging and ABI support are verified;
- USearch or another embedded ANN library if binary size and ABI maintenance are acceptable;
- an on-device embedding model with explicit model lifecycle, download, and version management.

Future embeddings must be generated on device by default. Any optional remote embedding provider would require a separate explicit product decision and must never be silently enabled.

## Source Of Truth And Legacy Cleanup

Target ownership:

- `MEMORY.md` and `memory/YYYY-MM-DD.md`: canonical semantic memory.
- `memory_document`, `memory_chunk`, and future vector rows: rebuildable derived indexes.
- pending turns, checkpoints, and maintenance jobs: operational state.
- `PersonalMemory` and `ChatClassification`: migration-only legacy data, not active runtime memory sources.

Requirements:

- Reuse the existing migration from active Room memories into Markdown.
- Make the migration idempotent.
- Stop writing new `PersonalMemory` and `ChatClassification` rows after the new batch path is enabled.
- Stop recalling legacy Room memories after migration succeeds.
- Do not immediately drop legacy tables if doing so risks upgrade compatibility. It is acceptable to leave them unused for one schema cycle with a clear removal note.
- Remove or retire legacy intelligence methods and tests once no production path calls them.
- Do not keep the legacy chain as an automatic fallback; that would recreate the API load this change is intended to remove.

## Retry And Rate Limits

The current maintenance scheduler can retry indefinitely. This must be bounded for LLM jobs.

- `attempts` includes the first execution.
- Allow at most three automatic attempts for the same immutable consolidation batch.
- Suggested retry delays: 30 seconds before attempt 2 and 120 seconds before attempt 3.
- After attempt 3 fails, mark the job terminal and rely on the existing maintenance notification/manual retry path.
- A manual retry may create one new attempt cycle, but must retain the same turn snapshot and idempotency semantics.
- Add a connected-network constraint to LLM maintenance work.
- Process at most one memory LLM batch at a time globally.
- Local index rebuild and repair jobs may remain separate, but they must not accidentally invoke the memory LLM.

## Implementation Tasks

Complete these tasks in order. After each task, run the listed focused tests and `:app:compileDebugKotlin` before continuing.

### Task 0: Audit And Baseline Call Counts

- [x] Re-read `AGENTS.md` and capture `git status --short --branch`.
- [x] Trace current foreground recall, post-save learning, Markdown learning, compaction flush, maintenance retry, and memory-toggle paths.
- [x] Identify all production call sites of every `MemoryIntelligence` method.
- [x] Add or update fakes so tests can assert exact method/network call counts.
- [x] Record the current database version and migration registration path.
- [x] Run the existing memory test suite before changes.

Audit record (2026-07-10):

- Baseline branch was `main` at `96ff1df`; implementation branch is `feature/five-turn-memory-batching-vector-readiness`.
- Foreground recall calls `classifyConversation` and may call `selectMemories`; post-save learning calls `classifyConversation`, `extractMemoryCandidates`, and `planMemoryUpdates`, then `MarkdownMemoryLearningService` calls `proposeMarkdownMemoryWrites`.
- `scheduleCompactionFlushIfNeeded(...)` creates `COMPACTION_FLUSH`; retry work is dispatched by `MemoryMaintenanceProcessor`; the memory toggle is read by `ChatViewModel` before recall/learning but does not yet cancel pending maintenance state.
- `FakeMemoryIntelligence` already exposes exact per-method counters for all five legacy methods; the batch method added later must follow the same pattern.
- `ChatDatabaseV2` is version 12 and migrations through `MIGRATION_11_12` are registered in `DatabaseModule.addMigrations(...)`.
- Baseline `./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"` passed. No emulator was connected at audit time.

Verification:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"
./gradlew.bat :app:compileDebugKotlin
```

### Task 1: Add Durable Pending-Turn And Checkpoint Storage

Likely files:

- `data/database/entity/`
- `data/database/dao/`
- `data/database/ChatDatabaseV2.kt`
- `data/database/ChatDatabaseV2Migrations.kt`
- `di/DatabaseModule.kt`
- new tests under `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/database/`

- [x] Add durable checkpoint/pending-turn storage with stable keys and foreign-key behavior.
- [x] Add DAO operations for upsert, count, oldest-unclaimed selection, atomic claim, success cleanup, release, and due-idle queries.
- [x] Add the next valid Room migration without destructive fallback.
- [x] Preserve all existing message, token usage, memory, and maintenance data.
- [x] Add migration tests for both empty and populated databases.

Acceptance:

- [x] Process death does not lose pending turns.
- [x] The same user message cannot increment the count twice.
- [x] Different chats have independent counters and idle deadlines.
- [x] Chat deletion cleans related pending state safely.

Task 1 implementation record (2026-07-10):

- Added Room-backed `memory_chat_checkpoint` and `memory_pending_turn` tables, unique `(chat_id, user_message_id)` enforcement, chat cascade cleanup, and exported schema 13.
- Added transactional claim/completion behavior in `MemoryTurnBatchDao`; a chat with claimed rows cannot create a second active batch, and successful completion deletes claimed rows while advancing the checkpoint.
- Added non-destructive `MIGRATION_12_13`, registered it in `DatabaseModule`, and covered empty/populated migration SQL plus durable DAO behavior in focused JVM tests.

### Task 2: Record Completed Turns Without Calling The LLM

Likely files:

- `presentation/ui/chat/ChatViewModel.kt`
- `data/repository/MemoryRepository.kt`
- `data/repository/MemoryRepositoryImpl.kt`
- new batch coordinator/service under `data/memory/`

- [ ] Replace the direct `learnFromSavedChat(...) -> learnFromChat(...)` network path with a local `recordCompletedTurn(...)`/enqueue operation.
- [ ] Reuse the existing post-save completion boundary so partial streaming content is not recorded.
- [ ] Implement completed-turn and canonical-assistant rules.
- [ ] Keep multi-provider deduplication.
- [ ] Update user activity as soon as a real new user prompt is accepted.
- [ ] Ensure the chat UI never waits for consolidation.

Acceptance tests must cover:

- [ ] Four completed turns produce zero memory LLM calls.
- [ ] The fifth completed turn produces one queued batch, not five jobs.
- [ ] One successful and one failed provider still count as one turn.
- [ ] All providers failing count as zero turns.
- [ ] Retry/revision does not add a turn.
- [ ] Navigating away does not lose the local pending snapshot.

### Task 3: Implement Threshold, Idle, And Compaction Scheduling

Likely files:

- `MemoryMaintenanceScheduler.kt`
- `MemoryMaintenanceWorkScheduler.kt`
- `MemoryMaintenanceProcessor.kt`
- `MemoryMaintenanceRepairer.kt`
- `MemoryMaintenanceJob.kt` and DAO
- `ChatRepositoryImpl.kt`

- [ ] Add one unified consolidation job type, for example `CONSOLIDATE_TURN_BATCH`.
- [ ] Claim at most five oldest pending turns from one chat per job.
- [ ] Implement the 30-minute persisted idle deadline and recheck-on-wake behavior.
- [ ] Push the deadline forward on new user activity without making an LLM request.
- [ ] Route context-compaction urgency into the same job type and idempotency key.
- [ ] Serialize memory LLM work globally.
- [ ] Schedule the earliest due job correctly across multiple chats.
- [ ] Repair due unclaimed turns on app launch and boot.
- [ ] Bound automatic attempts to three.
- [ ] Add a network-connected WorkManager constraint.
- [ ] Handle legacy pending `APPEND_DAILY_NOTE`/`COMPACTION_FLUSH` jobs without stranding or duplicating their data.

Use a fake clock and fake WorkManager boundary in unit tests. Do not make tests wait 30 real minutes.

Acceptance tests must cover:

- [ ] One to four turns consolidate once after the idle deadline.
- [ ] A new prompt before the deadline causes zero calls and postpones the job.
- [ ] A worker waking early causes zero calls.
- [ ] Five turns cause immediate eligibility and invalidate the partial idle wait.
- [ ] Threshold and compaction triggers for the same range create one job.
- [ ] Ten pending turns become two sequential calls, never concurrent calls.
- [ ] Three failed attempts become terminal rather than retrying forever.
- [ ] Memory disabled causes zero calls and no retry loop.

### Task 4: Replace The Learning Chain With One Consolidation Call

Likely files:

- `data/memory/MemoryIntelligence.kt`
- `data/memory/LlmMemoryIntelligence.kt`
- `data/memory/MarkdownMemoryLearningModels.kt`
- `data/memory/MarkdownMemoryLearningService.kt`
- `data/repository/MemoryRepositoryImpl.kt`
- `di/MemoryRepositoryModule.kt`

- [ ] Add the single batch consolidation request/response contract.
- [ ] Build a bounded input containing one to five immutable turn snapshots and locally retrieved existing memories.
- [ ] Implement provider routing using the current memory platform selection.
- [ ] Parse strict JSON and fail closed.
- [ ] Apply controlled create/replace/remove/ignore operations atomically.
- [ ] Rebuild affected Markdown index data after successful writes.
- [ ] Treat empty operations as successful processing.
- [ ] Remove production calls to classify, select, extract, plan, and the second Markdown proposal pass.
- [ ] Remove automatic legacy fallback behavior.
- [ ] Keep logs that identify batch ID, trigger, turn count, attempt, elapsed time, proposal count, and final status without logging sensitive memory text.

Acceptance tests must assert exact call counts:

- [ ] A valid five-turn batch calls `consolidateMemoryBatch` exactly once.
- [ ] The same successful job replay calls it zero additional times.
- [ ] Invalid JSON writes nothing and does not advance the checkpoint.
- [ ] A replace operation updates one existing ID without creating a duplicate.
- [ ] An empty proposal advances the checkpoint and writes nothing.
- [ ] No legacy intelligence method is invoked.

### Task 5: Make Recall Entirely Local

Likely files:

- `MemoryRepositoryImpl.kt`
- `MemoryIndexRepository.kt`
- `MemoryPromptBuilder.kt`
- new retrieval facade/interfaces under `data/memory/`
- `ChatRepositoryImplTest.kt`

- [ ] Remove pre-answer classify/select LLM calls.
- [ ] Build a local query from current user text and bounded recent context.
- [ ] Improve Chinese lexical tokenization with character n-grams.
- [ ] Deduplicate by stable entry ID/content hash.
- [ ] Apply result-count and token budgets before prompt injection.
- [ ] Preserve `mergePromptSections(...)` and every provider path.
- [ ] Degrade to no memory on local search failure.

Acceptance tests:

- [ ] Every recall test asserts zero `MemoryIntelligence` calls.
- [ ] Relevant Markdown memory is injected.
- [ ] Irrelevant/no-match memory is omitted.
- [ ] Chinese queries can recall a partially matching Chinese memory without exact full-sentence equality.
- [ ] Memory disabled performs no search and no injection.
- [ ] Token budget cannot be exceeded by many matching memories.
- [ ] Tool/search/system/context-summary prompt sections remain intact.

### Task 6: Migrate Away From Legacy Room Semantics

- [ ] Reuse and verify the active-memory-to-Markdown migration.
- [ ] Stop new writes to `PersonalMemory` and `ChatClassification`.
- [ ] Stop runtime recall from those tables after migration succeeds.
- [ ] Keep legacy tables temporarily if required for upgrade safety, but mark them migration-only.
- [ ] Update the Memory screen only as needed to keep showing `MEMORY.md`; do not turn it into a maintenance console.
- [ ] Remove stale tests that enforce legacy semantic behavior and replace them with batch/Markdown tests.

Acceptance:

- [ ] Existing users retain active memories after upgrade.
- [ ] Running migration twice creates no duplicates.
- [ ] New conversations create no new legacy semantic rows.
- [ ] Memory export/view continues to work.

### Task 7: Establish Vector-Ready Retrieval Boundaries

- [ ] Introduce or evolve a provider-neutral `MemoryRetriever` facade.
- [ ] Adapt the lexical index as the default implementation.
- [ ] Add stable content hashes and score provenance to retrieval results.
- [ ] Keep caller code independent of lexical/vector/hybrid strategy.
- [ ] Add strategy/config types with lexical as the only enabled production strategy.
- [ ] Ensure index rebuild output can identify changed chunks for future embedding updates.
- [ ] Add a short architecture note comparing small-corpus brute force, `sqlite-vec`, and an embedded ANN library on Android, including ABI, app-size, model lifecycle, and rebuild implications.
- [ ] Do not add a vector dependency or embedding model merely to satisfy the interface.

Acceptance:

- [ ] A fake vector retriever can be substituted in unit tests without changing repository or ViewModel APIs.
- [ ] Retrieval results carry stable chunk identity and content hash.
- [ ] Replacing the retrieval strategy does not change Markdown ownership.
- [ ] No cloud embedding request exists.

### Task 8: End-To-End Verification And Cleanup

- [ ] Run focused tests after each phase and then the full memory/repository suite.
- [ ] Run Kotlin compilation and debug assembly.
- [ ] Inspect the final production call graph with `rg` to prove legacy methods have no call sites.
- [ ] Inspect `git diff --check` and `git status`.
- [ ] If an emulator is available, validate four turns, fifth-turn consolidation, idle scheduling state, memory-off behavior, and one multi-provider turn.
- [ ] Capture logcat evidence using batch IDs and call counts, not memory content.
- [ ] Update relevant existing plan/docs so they no longer claim learning runs after every completed turn or that recall optionally uses an LLM selector.

Verification commands:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImplTest"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.context.*"
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:assembleDebug
git diff --check
git status --short
```

If `adb devices` reports an available emulator, also use the existing repository script rather than inventing another install path:

```powershell
adb devices
./run-on-emulator.ps1 -NoBuild -DeviceSerial emulator-5554
adb logcat -d | Select-String "MemoryBatch|MemoryMaintenance|consolidate"
```

## Required Test Matrix

At minimum, automated tests must cover:

| Case | Expected result |
|---|---|
| Memory off, normal chat | No recall search, pending turn, job, or memory LLM call |
| Turn 1 through 4 | Pending count grows; no memory LLM call |
| Turn 5 | One immutable five-turn job; one consolidation call |
| Turn 10 after delayed worker | Two sequential five-turn jobs |
| Partial batch idle for less than 30 minutes | No call |
| Partial batch idle for at least 30 minutes | One call |
| New prompt before idle worker executes | Deadline moves; no call |
| Multi-provider success | One pending turn |
| All providers fail | No pending turn |
| Assistant retry/revision | No extra pending turn |
| Process death before execution | Same job resumes once |
| Process death after Markdown commit before status update | Idempotent recovery, no duplicate entry |
| Invalid structured output | No writes, no checkpoint advance |
| Empty valid operations | Successful checkpoint advance |
| Compaction and threshold overlap | One job/call |
| Search index unavailable | Chat proceeds with no memory prompt |
| Chinese partial lexical match | Relevant memory recalled locally |
| Memory LLM fails three times | Terminal state and notification/manual retry path |
| Existing Room memories on upgrade | Idempotently migrated to Markdown |
| Fake future vector backend | Swappable without changing chat call sites |

## Non-Goals

- Do not change normal provider chat behavior, reasoning modes, tool loops, web search, token usage, attachments, editing, retry, export, or multi-provider UI beyond what is required for memory correctness.
- Do not add another visible assistant message for memory work.
- Do not make chat completion wait for memory consolidation.
- Do not combine multiple chats into one memory LLM request.
- Do not keep a hidden per-turn LLM classifier after introducing five-turn batching.
- Do not use regex/keywords to decide whether information deserves memory.
- Do not introduce a cloud vector database, remote embedding API, LangChain4j, or a full agent framework.
- Do not make vector rows canonical.
- Do not expose daily notes or maintenance internals on the normal Memory screen.
- Do not use destructive database migration or silently discard pending user memory work.
- Do not leave infinite automatic retries.

## Completion Report

The final handoff must include:

- changed files grouped by persistence, scheduling, LLM consolidation, recall, legacy migration, and vector readiness;
- the exact before/after memory LLM call budget;
- the completed-turn definition actually implemented;
- the idle timeout and retry policy;
- proof that pre-answer recall performs zero dedicated LLM calls;
- proof that five completed turns invoke exactly one consolidation request;
- database migration and idempotency behavior;
- tests and build commands run with their results;
- emulator/runtime verification performed or the reason it could not be performed;
- any remaining vector-backend decision explicitly deferred.

Do not report the task complete while the legacy production call chain still runs, while recall still calls a selector LLM, or while a failed job can retry forever.
