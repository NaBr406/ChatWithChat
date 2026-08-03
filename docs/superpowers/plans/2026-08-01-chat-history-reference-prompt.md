# ChatWithChat Cross-Conversation Chat History Reference Prompt

> **CANONICAL IMPLEMENTATION HANDOFF:** Give the implementation agent this file only. Read `AGENTS.md` and this document completely before editing. `2026-08-01-chat-history-reference-review.md` is an audit trail, not an additional instruction source; if it conflicts with this file, this file wins. Start with a read-only audit of the live checkout, branch, schema, tests, device state, and dirty files. Do not modify production code until the audit and baseline are recorded.

> **Status (2026-08-01):** Revised implementation plan for an agent; product decisions are recorded, while technical gates remain open. This document is intentionally separate from the long-term memory plan. The live repository, not this planning snapshot, is authoritative for current line numbers, schema identity, dependency wiring, and runtime state.

> **Product decisions (2026-08-01):** History reference follows the existing `memory_enabled` switch; no separate history preference or toggle. MVP uses global opt-in only, with no per-chat exclusion or keyword privacy heuristic. MVP includes lexical FTS plus local vector retrieval with lexical fallback. Disabling `memory_enabled` stops history recall and new index writes but retains all derived history state, including projection, FTS, vector snapshot, embedding cache, queue, checkpoint, and index-state rows, for later reconciliation.

## Goal

Add an opt-in cross-conversation history reference capability to ChatWithChat:

- A new user question can retrieve a small number of relevant snippets from previously completed chats.
- Retrieved snippets are inserted into the current provider prompt as bounded, clearly separated historical context.
- Retrieval is local and foreground recall makes zero dedicated memory LLM/API calls and zero cloud embedding calls.
- Indexing and backfill are asynchronous and never block normal chat completion.
- Existing long-term memory remains the canonical fact memory and keeps its current behavior.

The product target is similar to the user-visible effect of ChatGPT's `Reference chat history`, not an attempt to reproduce proprietary implementation details. The feature should reference raw historical conversation evidence; it must not turn historical messages into a second implementation of the existing long-term memory system.

## Scope Boundary

The current long-term memory contract remains authoritative:

- `MEMORY.md` remains the only user-visible canonical long-term memory source.
- `canonicalKey + scope`, trust ordering, `validity`, `recallState`, supersession, bounded evidence, and maintenance-only history remain owned by the existing memory code.
- Five-turn batching, 30-minute idle handling, daily distillation, whole-corpus consolidation, independent memory-model routing, activity-run semantics, and the existing local memory vector fallback remain unchanged.
- Existing `MemoryCorpus.CHAT_RECALL_LONG_TERM` continues to mean the active long-term memory projection. Do not place raw chat transcripts in `MEMORY.md` or in the existing long-term corpus.

The 2026-07-28 long-term memory plan is the constraint document, not the implementation scope for this feature:

- `docs/superpowers/plans/2026-07-28-long-term-memory-consistency-recall-and-tool-token-prompt.md`

The earlier OpenClaw-style and five-turn plans are historical design context where the current plan says they are superseded. Do not restore their old per-turn memory learning or legacy Room-memory behavior.

## Product Semantics

### Two independent context sources

The final provider context may contain two separate sections:

1. **Long-term memory facts**: stable user facts selected by the existing memory pipeline.
2. **Historical chat references**: bounded excerpts from older conversations, each carrying local provenance for diagnostics and optional UI navigation.

Historical excerpts are evidence, not canonical facts. They must not be merged, promoted, superseded, or written to `MEMORY.md` by this feature.

### Shared memory switch

Reuse the existing `memory_enabled` preference and Settings control. Do not add `reference_chat_history_enabled`, a second repository API, or a separate history toggle.

- When `memory_enabled=false`, history recall returns `DISABLED`, prompt injection stops on the next context preparation, history workers do not consume or write the queue, and all existing derived history state remains on disk.
- When `memory_enabled=true`, history indexing and recall are allowed. Re-enabling resumes pending work and runs deterministic stale/missing reconciliation before treating the retained index as complete.
- The existing preference default remains the memory system's current default (`false` when unset); do not introduce a `null` first-run state.
- The Settings description must say that enabling memory also permits relevant eligible excerpts from previous chats to be referenced.
- Re-enabling schedules asynchronous backfill/reconciliation. It must not synchronously scan every chat on the UI or foreground chat path.

### Eligible history

Version 1 indexes only completed, persisted `ChatRoomV2` turns and excludes:

- the current in-flight turn;
- blank user messages and blank assistant slots;
- assistant error/loading placeholders;
- local attachment bytes; only existing safe display metadata may be included;
- rows whose source chat is deleted;
- rows that fail projection validation or hash validation.

The current chat is excluded from cross-conversation results by default because the normal conversation context already contains it. A later product decision may allow an explicit same-chat history search, but it is not part of this first delivery.

### Canonical turn projection

`messages_v2` stores one user message and potentially multiple provider answers for a turn. To avoid multiplying equivalent provider answers:

- Index the user question once.
- Choose one nonblank assistant answer using the existing stable platform order and preferred-platform behavior where available.
- Preserve the source message ID and platform identity in the derived row for diagnostics, but do not expose internal IDs in the provider prompt.
- Index the currently effective content (`MessageV2.effectiveContent()` semantics) and the current active revision. Historical assistant revisions are not separate references in version 1.
- Keep the full original message/revision data in Room; the history projection is rebuildable.

Reuse the existing `MemoryTurnBatchCoordinator` canonical assistant rule exactly: successful assistant only, preferred platform first, then stable platform order, then stable platform UID. If no successful assistant exists, do not create a queryable history projection. Do not introduce a new LLM selection call or a user-only fallback.

## Non-Goals

- Do not rewrite `MEMORY.md`, `MarkdownMemoryCodec`, canonical merge policy, long-term consolidation, daily distillation, or memory-model routing.
- Do not use historical chat snippets as inputs to a new automatic memory-learning path.
- Do not add a cloud history index, remote vector database, cloud embedding, or provider-specific history API.
- Do not add LangChain, LlamaIndex, an agent framework, or another general orchestration framework as an Android runtime dependency.
- Do not send the full chat corpus, full chat titles list, index metadata, or maintenance diagnostics to a provider.
- Do not turn the Memory screen into a history-search administration console.
- Do not make a history index the source of truth for messages or long-term memory.
- Do not change the existing chat model selection or memory-model selection contracts.
- Do not clear app data, delete user chats, reset the repository, absorb unrelated dirty work, or force-push.

## Verified Live Baseline To Recheck

### Implementation baseline (2026-08-03, branch `codex/chat-history-reference`)

- `git status --short --branch`: `main` was synchronized with `origin/main`; existing untracked `.codex/`, this prompt, and the companion review document were preserved.
- `git stash list`: one pre-existing stash (`codex: preserve main worktree changes before sync 2026-07-10`) was preserved and not applied.
- `git worktree list`: the main worktree and the pre-existing identity-migration worktree were present; no worktree was removed or rewritten.
- `adb devices`: `emulator-5554` is online.
- Baseline verification passed before production edits: `./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"` and `./gradlew.bat :app:compileDebugKotlin`.
- The baseline branch was created as `codex/chat-history-reference` from `a3f419e`.
- Prompt-trace and provider-runtime fixtures remain open and will be captured with the integration changes.

The following facts were observed during planning and must be rechecked by the implementation agent before edits:

- `ChatDatabaseV2` is Room schema version 19 and owns `ChatRoomV2`, `MessageV2`, and the current memory maintenance entities.
- `MessageV2Dao.loadMessages(chatId)` loads one chat at a time. `searchMessagesByContent(query)` performs a `LIKE` search and returns only chat IDs.
- `ChatRepositoryImpl.searchChatsV2(query)` searches titles and message content, then returns `ChatRoomV2` objects. It is a chat-list search, not a ranked snippet retriever.
- `ChatRepositoryImpl.saveChat(...)` performs message add/update/delete reconciliation inside the existing chat transaction.
- After completion persistence, `ChatViewModel` records the completed turn asynchronously through `MemoryRepository.recordCompletedTurn(...)`.
- Before provider calls, `ChatViewModel.completeChat()` prepares one `PreparedMemoryContext` and passes the same prompt to each enabled provider. `TurnMemoryContextCache` freezes the per-turn result for retries/tool rounds according to the existing tests.
- `MemoryRepositoryImpl.prepareMemoryContext(...)` currently retrieves only `MemoryCorpus.CHAT_RECALL_LONG_TERM`, filters to the long-term Markdown source, and renders long-term memory facts.
- The current ObjectBox vector store validates the long-term memory corpus and `MEMORY.md` source path. It is not a raw chat history index.
- Settings already use `SettingDataSource`, `SettingDataSourceImpl`, `SettingRepository`, and `SettingRepositoryImpl` with DataStore preferences.
- Existing WorkManager-based memory maintenance infrastructure is available, but history indexing must remain a separate semantic responsibility even if it reuses the same WorkManager mechanism.

Useful live anchors:

- `app/src/main/kotlin/cn/nabr/chatwithchat/data/database/dao/MessageV2Dao.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/ChatRepositoryImpl.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatViewModel.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/MemoryRepositoryImpl.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/memory/MemoryModels.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/database/ChatDatabaseV2.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/database/ChatDatabaseV2Migrations.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/datastore/SettingDataSource.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/datastore/SettingDataSourceImpl.kt`

## Target Architecture

```text
Room chats/messages
        |
        v
ChatHistoryProjectionBuilder
        |
        +--> rebuildable metadata rows
        +--> local lexical FTS index
        +--> local turn/chunk vector index (MVP)
        |
current user query + bounded recent local context
        |
        v
ChatHistoryRetriever
        |
        +--> lexical candidates
        +--> vector candidates (MVP; lexical fallback on failure)
        +--> metadata/privacy filters
        +--> score fusion, deduplication, chat diversification
        +--> bounded historical snippets
        |
        v
HistoryRecallSnapshot
        |
        +--> existing long-term memory snapshot
        +--> one combined PreparedMemoryContext
        |
        v
provider prompt
```

The history index is derived state. The canonical source is always the current Room message/chat state. A stale or corrupt history index must degrade to no history or a safe lexical fallback; it must never alter or roll back Room messages.

## Data Contracts

Use names that make the separation from long-term memory obvious. Keep these contracts in a new `data/history` package unless the live repository has a stronger local convention.

### `ChatHistoryTurnProjection`

Represents the indexable semantic unit:

- stable derived turn key;
- `chatId`;
- user message ID and selected assistant message ID;
- chat title snapshot;
- user text and selected assistant text;
- creation/update timestamps;
- source content hash and projection version;
- eligibility/index state;
- optional platform UID for local diagnostics only.

Do not store raw attachment bytes, provider credentials, prompts, memory metadata, or full model request payloads.

### `ChatHistorySnippet`

The model-visible result should contain only natural-language text plus bounded provenance needed by the app:

- snippet text;
- chat ID and message/turn ID for local navigation and trace only;
- chat title and date for optional UI display;
- lexical score, vector score, or ranking diagnostics only in local trace, never in provider prompt.

### `HistoryRecallSnapshot`

The immutable per-turn result should include:

- source/index revision or projection hash;
- selected snippets in stable order;
- retrieval mode (`none`, `disabled`, `lexical`, `hybrid`, `failed`);
- bounded diagnostics and error code;
- final rendered history prompt;
- estimated token count.

The same snapshot must be reused for all providers and tool rounds for one turn. A background index update must not change an in-flight provider request.

### `PreparedMemoryContext` integration

Preserve the existing `PreparedMemoryContext` API unless a smaller compatible boundary is proven. Add a separate history snapshot/result field or a composition object; do not mix historical snippets into `MemoryRetrievalResult` or `MemoryCorpusChunk` merely to avoid creating a new type.

The final `prompt` must preserve a separate section boundary, for example:

```text
[Long-term memory]
...

[Relevant previous conversations]
...
```

Only the second section is new. Existing memory prompt tests must continue to assert that long-term metadata, IDs, paths, lifecycle fields, and maintenance text are absent.

## Binding Technical Contracts

The implementation agent must not reopen the following design choices unless the live checkout makes one impossible. If that happens, stop and report the exact conflict before substituting another architecture.

### Projection identity and eligibility

- Use `turn_key = "chat:<chatId>:user:<userMessageId>"` as the stable derived identity. `turn_key` and `(chat_id, user_message_id)` are both unique.
- A queryable projection requires a persisted, nonblank user message and one successful canonical assistant message. Select the assistant with the current `MemoryTurnBatchCoordinator` order: preferred platform, then `stablePlatformOrder`, then stable platform UID. Use `effectiveContent()`, strip the existing assistant error note, and reject blank/error assistants.
- Do not index a user-only turn. `blank_user` and `no_successful_assistant` are bounded skip diagnostics, not model-visible rows.
- The projection row contains an integer `projection_id` for FTS row identity; stable `turn_key`; chat/user/assistant IDs (including a non-null canonical `assistant_message_id`); assistant platform UID; title/user/assistant text; content hash; projection version; eligibility state; and created/updated timestamps. If no canonical assistant exists, remove any prior queryable row and retain only a bounded skip diagnostic; do not persist a user-only projection.
- `chat_id` references `chats_v2(chat_id)` with `ON DELETE CASCADE`. Queryable rows use `eligibility_state = 'eligible'`; repair markers such as `stale` or `invalid_source` are fail-closed and are omitted from FTS/vector results.

### Canonical source hash

- Use SHA-256 over a versioned canonical payload encoded as UTF-8.
- Frame each named field as `<UTF-8 byte length>:<fieldName>=<value>\n`, where the length is the UTF-8 byte count of `fieldName=value`; do not use Kotlin character count as byte length.
- Hash, in stable order: projection version, turn key, chat title, user message ID, user content, assistant message ID, assistant platform UID, and assistant content.
- The hash is a freshness check, not row identity. Replaying an unchanged source must preserve the existing projection row and `updated_at`.

### Durable queue, checkpoint, and generation

Use Room tables with these minimum contracts:

```text
chat_history_index_queue
  turn_key TEXT PRIMARY KEY
  chat_id INTEGER NOT NULL
  user_message_id INTEGER NOT NULL
  operation_hint TEXT NOT NULL          -- RECONCILE; worker reloads current source
  requested_at INTEGER NOT NULL
  attempt_count INTEGER NOT NULL

chat_history_backfill_checkpoint
  checkpoint_id TEXT PRIMARY KEY        -- fixed value: history_backfill
  last_chat_id INTEGER
  last_user_message_id INTEGER
  projection_version INTEGER NOT NULL
  status TEXT NOT NULL                  -- IDLE, RUNNING, PAUSED, FAILED
  updated_at INTEGER NOT NULL

chat_history_index_state
  state_id TEXT PRIMARY KEY             -- fixed value: history
  projection_generation INTEGER NOT NULL
  projection_hash TEXT
  vector_published_generation INTEGER
  vector_status TEXT NOT NULL
  updated_at INTEGER NOT NULL
```

- When `memory_enabled=true`, write the source message change and queue upsert in one Room transaction where the live repository permits it. Repeated enqueue replaces/coalesces by `turn_key`; do not use an auto-increment queue identity.
- A worker reloads the current Room source and derives the operation. If the source no longer exists or is no longer eligible, delete/mark the projection; never write stale content copied into a work request.
- Projection replacement, FTS trigger effects, generation advance, and queue acknowledgement share one Room transaction. Vector publication is a separate replay-safe step keyed by the committed projection generation.
- Backfill order is `(chat_id, user_message_id)`. Advance the checkpoint only after the page transaction commits, so process death can safely replay a page.
- When `memory_enabled=false`, do not enqueue or consume new history work. Preserve queue, checkpoint, index-state, projection, FTS, vector snapshot, and embedding-cache data. Re-enable performs a full stale/missing reconciliation so turns created while disabled are discovered.
- WorkManager is only a wake-up mechanism. Use dedicated unique history work names, `KEEP` for queue drain, exponential retry backoff, and explicit `CancellationException` propagation.

### FTS5 lifecycle and tokenizer gate

- Use an external-content FTS5 table over title, user content, and assistant content. Migration and rebuild logic must include INSERT, UPDATE, and DELETE triggers plus the FTS `rebuild` command.
- Every query joins back to the projection table, requires `eligibility_state = 'eligible'`, excludes the current chat, and orders with `bm25` before application-level fusion.
- `unicode61` is not Chinese word segmentation. Treat `trigram` only as a candidate until an Android SQLite instrumentation fixture proves availability, Chinese substring/short-query behavior, English behavior, emoji behavior, and acceptable latency on min SDK 31.
- If bundled SQLite cannot use `trigram` or its short-query behavior fails, store deterministic derived CJK n-gram search terms in a search-only column and index that with a supported tokenizer. Do not fall back to a full-corpus `LIKE` query.

### History vector store

- Implement an independent `HistoryVectorStore`; do not add raw chat history to `MemoryCorpus`, the existing ObjectBox directory, or the long-term `MemoryVectorStore` snapshot.
- Reuse only the current local `MemoryEmbeddingProvider` methods: `embedDocuments()` for projection text and `embedQuery()` for queries. There is no `embed()` or incremental `upsert()` API on the existing memory interfaces.
- Publish immutable history snapshots identified by projection generation/hash, embedding descriptor, chunker version, index schema version, and expected chunk count.
- Cache document embeddings by `turn_key + content_hash + embedding descriptor` so publishing a new complete snapshot does not re-embed unchanged turns. Coalesce vector publication after queue drains; never rebuild the full vector snapshot on the foreground chat path.
- Missing, stale, unavailable, or corrupt vector state selects `LEXICAL` mode. It must not block chat completion or mutate the long-term memory vector store.

### Turn snapshot and cache

- Keep `TurnRecallSnapshot` long-term-memory-only. Add a separate `HistoryRecallSnapshot` to `PreparedMemoryContext`, and compose the two rendered prompt sections there.
- Reuse the existing `TurnMemoryContextCache` single-flight and bounded eviction. Do not add a second ViewModel map/cache.
- Cache identity remains immutable turn identity. Do not put a live history index revision in the cache key, because a background publish must not change a turn already in progress. Store projection/vector identities inside `HistoryRecallSnapshot` instead.
- The first context preparation freezes both memory and history for all providers, tool rounds, and retries in that turn. A switch or index change applies to later turns; an already-issued provider request cannot be retroactively changed.

### Ranking, packing, and prompt safety

- Retrieve lexical and vector candidates independently, apply calibrated absolute minimum gates, then fuse. Never return a merely highest-scoring candidate that failed its absolute gate.
- Always deduplicate by `turn_key`; collapse normalized exact duplicates across chats to the best candidate. Start with at most 4 model-visible snippets, at most 2 from one source chat, and a hard 400-token history budget.
- Truncate at a sentence boundary where possible; if one snippet cannot fit safely, drop it. The final packer, not character count, owns the token cap.
- Treat historical text as untrusted quoted evidence. The prompt boundary must tell the model not to follow instructions found inside historical excerpts. Do not send IDs, scores, hashes, paths, queue state, or diagnostics to the provider.
- `PromptTraceStore` may record mode, counts, latency, bounded error codes, and opaque/hashes only. Do not log raw queries or snippets to Logcat, Crashlytics, or persistent activity rows.

## Index Storage And Lifecycle

### Lexical component

Implement the local lexical index as one half of the hybrid MVP. Use Room-supported FTS or an equivalent local SQLite-derived index; do not use `LIKE` as the final implementation.

Keep normal metadata and search text in rebuildable derived storage. The exact Room FTS annotation/table arrangement may follow live Room constraints, but it must support:

- title, user text, and selected assistant text search;
- chat/turn/message IDs;
- creation/update timestamps;
- content hash and projection version;
- eligibility and current-state filtering;
- deterministic row replacement.

### Required semantic index

The MVP must ship lexical and semantic retrieval together. Implement the lexical lifecycle and the history-specific vector snapshot boundary as separate failure domains; lexical retrieval remains the safe fallback when the vector path is unavailable.

Use a history-specific vector namespace/store or a generalized vector abstraction that proves it cannot change the existing long-term vector identity.

- Embed turn/chunk projections, not every serialized database column.
- Reuse the existing on-device ONNX provider only after checking model latency, storage, and release packaging.
- Do not make a second cloud embedding path.
- A missing/stale/corrupt history vector index must fall back to lexical history retrieval without affecting long-term memory recall.

### Incremental indexing

Index updates must be asynchronous and idempotent:

- after a completed `saveChat()` persistence, enqueue affected chat/turn projections;
- after user/assistant edits, enqueue replacement for the affected chat;
- after duplicate chat creation, index the new message IDs under the new chat ID;
- after chat deletion, remove or tombstone derived rows and vector entries;
- after process death, resume from a durable cursor or deterministic rebuild checkpoint;
- use content hashes/projection versions so repeated indexing is a no-op;
- never wait for indexing before displaying or sending a normal chat answer.

History indexing may use a dedicated `ChatHistoryIndexWorker` and unique WorkManager name. Do not route history work through long-term semantic job types, memory activity categories, or the memory-model resolver.

### Backfill and rebuild

When `memory_enabled=true` and the history projection is missing, stale, or on a new projection version:

- enqueue a bounded backfill over persisted chats/messages;
- process pages/batches with a durable cursor;
- allow foreground lexical fallback only if it does not scan the entire corpus on every question;
- expose local bounded status for diagnostics, not raw message content;
- support a complete rebuild from Room after index deletion or schema/version change.

Backfill must survive process death, reboot, repeated enqueue, and a second enable/disable cycle. Disable must prevent the index from being used by the next context preparation and must stop subsequent worker writes; an already-prepared or already-issued turn remains frozen. Disabling must not require a destructive database reset.

## Retrieval Contract

### Query construction

Build the query from:

- the latest user question;
- safe attachment display metadata only;
- a bounded recent local context, using existing context conventions where appropriate.

Do not call a model to rewrite the query on the foreground path. Query length, text normalization, and CJK handling must be deterministic and unit tested.

### Candidate pipeline

MVP hybrid implementation:

1. Retrieve lexical candidates and vector candidates independently.
2. Exclude the current chat and ineligible/stale rows; the shared `memory_enabled` gate is checked before retrieval.
3. Apply absolute minimum relevance gates before relative fusion.
4. Fuse and rerank surviving candidates.
5. Reapply deduplication, chat diversification, and token packing.

Initial calibration targets are implementation inputs, not promises. Establish a fixed fixture before choosing constants. A reasonable first cap is 3-6 snippets and approximately 300-400 model-visible history tokens, subject to the existing overall provider budget.

### Failure behavior

- No hit: return an empty history section and continue normally.
- Index unavailable: return a bounded diagnostic and continue without history, or use a safe lexical fallback.
- Stale row: do not inject it; enqueue repair.
- History retrieval exception: never fail chat completion.
- Long-term memory retrieval failure: preserve its existing behavior independently.

## Settings And User Controls

Reuse the existing `memory_enabled` DataStore preference, repository, ViewModel, and Settings control. Do not add a history-specific preference or toggle. The history path must observe the same state transition callback used by long-term memory.

The UI should state that enabling memory also allows the assistant to use relevant eligible excerpts from previous conversations. Do not imply that every prior message is sent to the model. Turning the shared switch off stops prompt injection and new index writes but retains derived index data.

Do not add per-chat privacy flags, keyword filters, or a partial temporary/private chat policy in the first slice. Record per-chat exclusion as a later product/schema feature.

## Planned Implementation Tasks

### Task 0: Baseline And Branch Isolation

- [x] Recheck `AGENTS.md`, current branch, `git status --short --branch`, worktrees, stashes, and origin divergence.
- [x] Confirm current Room schema, migration registration, exported schema files, and available device/emulator state.
- [x] Run the focused memory tests and compile baseline before editing.
- [ ] Capture current `searchChatsV2`, `prepareMemoryContext`, prompt trace, and chat completion behavior with fixed fixtures.
- [x] Confirm no existing user dirty files are overwritten or staged.

Suggested baseline commands:

```powershell
git status --short --branch
git worktree list
git stash list
./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"
./gradlew.bat :app:compileDebugKotlin
adb devices
```

### Task 1: Add History Projection Contracts And Derived Storage

- [ ] Create the history data contracts and projection builder under `data/history`.
- [ ] Add rebuildable Room projection storage and DAO with a non-null canonical assistant ID, stable `turn_key`, content hashes, timestamps, projection version, and fail-closed state.
- [ ] Add the three durable lifecycle tables (or equivalent Room entities) exactly as contracted above: `chat_history_index_queue`, `chat_history_backfill_checkpoint`, and `chat_history_index_state`.
- [ ] Make queue enqueue/replay idempotent by `turn_key`; do not use an auto-increment queue identity or a caller-supplied source payload/version as truth.
- [ ] Add the external-content FTS5 table, INSERT/UPDATE/DELETE triggers, and explicit `rebuild` path as one migration/rebuild contract.
- [ ] Add schema migration from the live version (currently expected to be 19) to the new version; do not assume 19 without rechecking.
- [ ] Export the new schema and add fresh/open/populated migration tests.
- [ ] Prove that raw `MessageV2` and long-term memory tables remain unchanged in meaning.

Expected areas:

```text
app/src/main/kotlin/cn/nabr/chatwithchat/data/history/
app/src/main/kotlin/cn/nabr/chatwithchat/data/database/entity/
app/src/main/kotlin/cn/nabr/chatwithchat/data/database/dao/
app/src/main/kotlin/cn/nabr/chatwithchat/data/database/ChatDatabaseV2.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/database/ChatDatabaseV2Migrations.kt
app/src/main/kotlin/cn/nabr/chatwithchat/di/DatabaseModule.kt
```

### Task 2: Implement Projection Updates, Backfill, And Rebuild

- [ ] Build one deterministic projection from each completed persisted turn.
- [ ] Add idempotent upsert/replacement by chat/turn/content hash.
- [ ] Enqueue index work after `saveChat()` completion without delaying the provider response.
- [ ] Enqueue affected rows after edit, delete, duplicate, and chat replacement paths.
- [ ] Add bounded backfill and full rebuild workers with unique work and durable cursor semantics.
- [ ] Ensure disabling `memory_enabled` prevents history recall on the next context preparation and prevents subsequent queue consumption/new index writes while retaining derived index data; an already-prepared or already-issued provider turn remains frozen.
- [ ] Add process-death, repeated-enqueue, stale-row, and rebuild tests.

Do not call `MemoryRepository.recordCompletedTurn()` as a substitute for history indexing. The existing method belongs to long-term memory batching and must remain semantically unchanged.

### Task 3: Implement Lexical History Retrieval

- [ ] Add `ChatHistoryRetriever` and `ChatHistoryPromptBuilder`.
- [ ] Implement deterministic query normalization, FTS candidate retrieval, current-chat exclusion, metadata filtering, deduplication, chat diversification, and token packing.
- [ ] Add `HistoryRecallSnapshot` and local trace data with only bounded counts, scores, hashes, and opaque IDs.
- [ ] Define empty, failed, stale, disabled, and backfill-incomplete modes.
- [ ] Add fixed relevance fixtures: exact name, CJK phrase, paraphrase, unrelated query, duplicate provider answers, same-chat exclusion, deleted chat, stale projection, and long answer truncation.
- [ ] Add an Android SQLite tokenizer fixture covering trigram availability, Chinese short queries/substrings, English, emoji, exact phrase behavior, and latency; record the selected tokenizer/search-column contract in the migration tests.

The first lexical implementation must not use a full-corpus SQL scan per user question as its normal path. A small bounded fallback may be used for repair diagnostics only.

### Task 4: Compose History With Existing Memory Context

- [ ] Inject the history retriever through the existing `MemoryRepository.prepareMemoryContext()` composition boundary or an equivalent narrowly scoped context composer.
- [ ] Preserve the existing long-term `TurnRecallSnapshot` and its 500-token memory contract.
- [ ] Add a separately bounded history section and a combined prompt result.
- [ ] Ensure `ChatViewModel.prepareMemoryContextForTurn()` calls retrieval once per turn snapshot.
- [ ] Ensure all enabled providers and tool rounds reuse the same history snapshot.
- [ ] Ensure retries use the correct snapshot key and do not silently retrieve a different history state.
- [ ] Add prompt assertions that no message IDs, paths, hashes, ranking scores, memory metadata, or internal diagnostics reach provider DTOs.
- [ ] Test `HistoryRecallSnapshot` immutability and reuse through all enabled providers, tool rounds, and retries; a background projection/vector publication must not alter an in-flight turn.

Expected integration areas:

```text
app/src/main/kotlin/cn/nabr/chatwithchat/data/memory/MemoryModels.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/MemoryRepository.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/MemoryRepositoryImpl.kt
app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatViewModel.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/ChatRepositoryImpl.kt
```

### Task 5: Reuse The Existing Memory Switch

- [ ] Reuse `memory_enabled`; do not add a history preference, repository API, or independent toggle.
- [ ] Add localized Settings copy and a disabled/empty/backfill status that does not expose raw content.
- [ ] Verify one memory switch transition gates both long-term memory and history workers/recall.
- [ ] Verify turning it off prevents history injection on the next context preparation and prevents subsequent queue consumption/new index writes, while all derived history state remains intact; already-prepared or already-issued turns remain frozen.
- [ ] Verify re-enable resumes pending work and runs stale/missing reconciliation before normal hybrid recall.

### Task 6: Add Required Local Semantic Retrieval

This task is required for MVP. It may be implemented after the lexical projection/FTS contracts are stable, but it cannot be deferred to a later product milestone.

- [ ] Define a history-specific vector index identity/namespace separate from the long-term memory identity.
- [ ] Reuse the existing on-device embedding provider only after checking its release/runtime availability and latency.
- [ ] Embed turn/chunk text, not raw Room serialization or maintenance metadata.
- [ ] Add incremental vector sync, stale detection, rebuild, corruption repair, and lexical fallback.
- [ ] Add lexical/vector/hybrid hard-negative fixtures and calibrate absolute thresholds from recorded score distributions.
- [ ] Test complete snapshot publication via `embedDocuments()`/`embedQuery()`, embedding-cache reuse, stale/corrupt snapshot detection, and lexical fallback without touching the long-term vector store.
- [ ] Prove long-term memory vector snapshots and `MEMORY.md` behavior are byte/index unchanged by history indexing.

If the current ObjectBox restrictions cannot be generalized without weakening long-term guarantees, keep the vector phase behind a separate history store abstraction. Shipping lexical-only without the history vector path is not the selected MVP scope.

### Task 7: Product And Runtime Verification

- [ ] Run JVM tests for projection, ranking, prompt packing, settings, index lifecycle, and snapshot reuse.
- [ ] Run Room migration tests for fresh install, populated upgrade, restart, foreign keys, delete cascade, and index rebuild.
- [ ] Run `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, and `:app:assembleDebug` with extended timeout where needed.
- [ ] Run `git diff --check` and inspect the final dirty-file list.
- [ ] Check `adb devices` before claiming UI/runtime proof.
- [ ] On a connected device, verify enable/backfill, a new chat retrieving an old snippet, unrelated query returning no history, edit/delete invalidation, retry snapshot reuse, and long-term memory unchanged.
- [ ] Keep build/unit evidence separate from device/provider/runtime evidence.

## Acceptance Criteria

The implementation is complete only when all applicable criteria have evidence:

1. With `memory_enabled=false`, the next context preparation reads neither history nor updates its index, normal prompt text and long-term memory behavior are unchanged, and existing history-derived data remains on disk; an already-prepared or already-issued turn is unchanged.
2. Enabling the existing memory switch schedules asynchronous backfill/reconciliation and does not block chat completion or perform a foreground network request.
3. A relevant question in a new chat retrieves a bounded snippet from an older eligible chat with deterministic provenance in local trace.
4. An unrelated question does not receive a merely highest-scoring irrelevant snippet.
5. The current chat is not duplicated through history recall by default.
6. Editing or deleting a source chat prevents the old projection from being injected after index reconciliation; stale rows are fail-closed before reconciliation completes.
7. Process death, restart, repeated enqueue, and rebuild do not duplicate rows or lose the durable cursor.
8. The same turn uses one frozen history snapshot across multiple providers, tool rounds, and retries where the existing cache contract requires it.
9. Provider prompts contain only bounded natural-language historical excerpts and no internal IDs, metadata, paths, hashes, diagnostics, or raw attachment bytes.
10. Existing long-term memory canonical files, active-only projection, memory model routing, maintenance jobs, vector identity, and memory tests remain unchanged in meaning and pass.
11. MVP normal retrieval performs lexical/vector fusion; vector failure falls back to lexical history retrieval and never disables or corrupts long-term memory recall.

## Required Tests And Evidence

At minimum, add focused tests for:

- `ChatHistoryProjectionBuilder` turn grouping, effective revisions, blank/error filtering, and canonical assistant selection;
- lexical query normalization and CJK/exact phrase behavior;
- ranking, deduplication, chat diversification, current-chat exclusion, and token packing;
- enable/disable behavior: no queue/index writes while disabled, retained derived state remains intact, and re-enable reconciliation discovers turns created during the disabled interval;
- idempotent upsert, edit replacement, delete invalidation, duplicate chat indexing, and stale hash rejection;
- stable `turn_key` deduplication across repeated enqueue, replay, and concurrent edit/delete events;
- backfill/rebuild cursor resume after process death and repeated work enqueue;
- `PreparedMemoryContext` composition and per-turn snapshot reuse;
- the three durable lifecycle tables, queue acknowledgement/generation updates, and FTS INSERT/UPDATE/DELETE plus `rebuild` behavior;
- tokenizer selection fixture and migration contract for CJK/English/emoji/short-query behavior;
- vector snapshot publication, embedding-cache reuse, stale/corrupt detection, hybrid fusion, and lexical fallback;
- Room schema migration and foreign-key/index cleanup;
- regression of existing `MemoryRepositoryTest`, `ChatViewModelRetryTest`, memory recall tests, and prompt DTO assertions.

If a device or real provider is unavailable, report that runtime gate as open. Do not replace it with a JVM fake and claim end-to-end proof.

## Stop Conditions

Stop and report instead of guessing if:

- the live schema or migration topology differs materially and a safe additive migration cannot be established;
- the feature cannot be disabled without changing existing long-term memory behavior;
- a history index update can race a message edit/delete and still inject stale text without a repair boundary;
- the only viable vector solution requires cloud embeddings or a new remote service;
- integrating history context would force a change to the existing canonical memory source or memory-model routing;
- the change requires clearing app data, deleting user history, resetting the worktree, force-pushing, or absorbing unrelated dirty files.

No connected device, no provider credentials, or no natural backfill delay is not by itself a reason to discard build/JVM work. Leave the missing runtime gate explicitly open.

## Suggested Commit Sequence

Use topical commits only when implementation and commits are explicitly requested:

1. `docs: add chat history reference implementation prompt`
2. `feat(history): add rebuildable chat turn projection`
3. `feat(history): add lexical index and async backfill`
4. `feat(history): add bounded history recall to chat context`
5. `feat(settings): reuse memory switch for history reference`
6. `feat(history): add required local semantic recall and hybrid fusion`
7. `test(history): verify migration lifecycle and snapshot contracts`
8. `docs: record chat history reference verification evidence`

Do not mix long-term memory consolidation, Memory UI redesign, provider migration, sticker work, or unrelated cleanup into these commits.

## Handoff For The Next Agent

Start with Task 0. After the audit, confirm whether the live schema is still 19 and whether the current branch contains user changes in any expected integration file. Then implement Task 1 and Task 2 as a self-contained derived-index slice before changing provider prompt composition.

The first production-quality milestone is the hybrid path with durable lifecycle, vector snapshot, lexical fallback, and prompt snapshot tests. Semantic vectors are part of the MVP contract, not an optional second milestone.
