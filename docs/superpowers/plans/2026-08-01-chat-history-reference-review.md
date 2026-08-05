# Chat History Reference Plan Review - Production Readiness Assessment

> **AUDIT ONLY - DO NOT HAND THIS FILE TO THE IMPLEMENTATION AGENT.** The canonical implementation handoff is `2026-08-01-chat-history-reference-prompt.md`. This review preserves findings and rationale; the prompt owns the final contract when wording differs.
>
> **Review Date:** 2026-08-01
> **Document Reviewed:** `2026-08-01-chat-history-reference-prompt.md`
> **Status After v2 Revision:** ⚠️ **Design Review Stage (product decisions recorded; technical validation pending, NOT production-ready)**
> **Last Updated:** 2026-08-03 (live branch audit; prototype rejected)

## Live Decision (2026-08-03)

The branch now contains a partial implementation (`b042602`, `059538a`, `f455d93`, `dd2bf2e`), but it is rejected as the base for further feature work. JVM compilation/unit tests pass and the 8 migration tests pass on `emulator-5554`; the fresh-database Chinese retrieval test fails with `expected: LEXICAL, actual: NONE`. The vector path is not an atomic snapshot, worker continuation is unproven, and index/switch/process-death coverage is incomplete.

**Decision:** perform a clean rewrite of the history feature. Do not incrementally patch the current `data/history` implementation or reuse its tests as acceptance evidence. Preserve the existing chat/message tables and the complete long-term memory system (`MEMORY.md`, canonical memory policy, `TurnRecallSnapshot`, routing, and vector identity). If schema 20 has not shipped outside this branch, the rewrite may replace its history migration; if it has shipped, use a safe additive migration and fail closed on legacy derived rows.

One hour is an audit/reproduction window, not a credible estimate for the full rewrite and runtime verification.

## Task 0 Live Audit Evidence (2026-08-03)

- Checkout: `codex/chat-history-reference` at `dd2bf2e`; `origin/main` is `a3f419e`; local branch is 7 commits ahead and 0 behind.
- Dirty state preserved: the plan file is modified; `.codex/`, the existing history instrumented-test directory, and this review file are untracked. The pre-existing stash and `ChatWithChat-identity-migration` worktree remain untouched.
- Publication audit: `origin/main` declares Room schema 19; no remote branch or tag contains `b042602`/`dd2bf2e`; the only GitHub release is `v1.0.0` published 2026-07-23, before the schema-20 commits. Schema 20 is therefore not published outside this branch.
- Device: `emulator-5554` is online (`ChatWithChat_API35_16K_X86_64`, API 35 / 16 KB page AVD).
- Baseline JVM/build: `:app:testDebugUnitTest --tests '*Memory*'` passed; `:app:compileDebugKotlin` passed.
- Baseline Android: `ChatDatabaseV2MigrationInstrumentedTest` passed all 8 tests. The existing prototype `ChatHistoryRetrieverInstrumentedTest` fails on a fresh database with `expected:<LEXICAL> but was:<NONE>` for the Chinese recall case.
- Rewrite decision: remove the unpublished schema-20 history prototype and its history-specific tests, restore schema 19 as the new base, and create fresh contracts/tests. Do not alter `messages_v2`, `chats_v2`, `MEMORY.md`, the long-term memory corpus, or memory-model routing.

## ⚠️ CRITICAL: GPT Independent Review Findings

**Reviewed by:** GPT-4 Code Review Agent
**Review Date:** 2026-08-01
**Verdict:** **Cannot mark as "Production-Ready" or handoff to implementation agent**

### Key Issues Identified:

1. **P0: Completion Status Misrepresented** - Lines 1121-1158 marked unimplemented phases as ✅ complete
2. **P0: Vector Interface Errors** - Used non-existent APIs (`embed()`, `upsert(namespace=)`)
3. **P0: FTS Lifecycle Incomplete** - Missing UPDATE/DELETE triggers and rebuild logic
4. **P1: Memory Boundary Violated** - Proposed extending `TurnRecallSnapshot` (long-term memory component)
5. **P1: Idempotency Unproven** - Auto-increment PK cannot guarantee idempotent projection
6. **P1: Chinese Tokenization Incorrect** - `unicode61` does not provide CJK word segmentation
7. **P1: Statistical Data Inaccurate** - Entity count (17 not 19), test count needs verification

**Corrective Action:** This document separates historical design suggestions from implementation evidence. The live branch decision above supersedes any older recommendation to extend the prototype.

---

## Executive Summary

The original v1 plan (60-65% complete) provided conceptual foundation but lacked implementation details. This v2 revision adds concrete code examples based on actual project interfaces. The product decisions are now recorded below, but the document **remains in design review stage** pending:

1. Complete DDL verification and migration testing
2. A history-specific vector storage adapter design (snapshot-based, not incremental upsert)
3. Actual code implementation and test evidence

**Historical Status: design review stage (not handoff-ready). Live status: rejected prototype; use the canonical prompt's Rewrite Mode for the next implementation pass.**

---

## Product Decision Record (2026-08-01)

The following decisions are binding for the first implementation handoff:

1. **One switch:** Chat history reference follows the existing `memory_enabled` setting. Do not add a separate `reference_chat_history_enabled` preference, repository API, or Settings toggle. When `memory_enabled` is false, history indexing and history recall are both disabled.
2. **Global privacy boundary:** MVP is global opt-in only. Do not add `ChatRoomV2.excludeFromHistory`, keyword-based privacy heuristics, or per-chat exclusion UI in this delivery. Enabling the existing memory switch means eligible turns from all chats may be referenced, except the current chat and other technical eligibility exclusions documented below. The Settings copy must make this consequence clear.
3. **Index retention:** Disabling `memory_enabled` prevents history from entering any newly prepared turn context and stops new index writes/worker execution, but retains all derived history state, including projection, FTS, vector snapshot, embedding cache, durable queue, checkpoint, and index-state rows. An already-issued provider request remains immutable. Re-enabling resumes pending work and runs a deterministic stale/missing projection reconciliation before relying on the retained index as complete.
4. **Hybrid MVP:** Lexical FTS and local semantic vector retrieval are both required for MVP. Vector failure, unavailable embedding artifacts, or a corrupt history vector snapshot must degrade to lexical retrieval without affecting the long-term memory vector store.

These decisions replace the earlier v1 assumptions about an independent history setting and an optional vector phase. Any conflicting text below is corrected to match this record.

Sections below that are explicitly labelled as an issue, older draft, or historical example preserve the audit trail. They are not implementation instructions; the canonical prompt is authoritative for the handoff contract.

---

## Critical Missing Components

### 1. **Data Model Specification Gaps**

#### 1.1 Missing Schema Details
**Issue:** The plan defines abstract entities (`ChatHistoryTurnProjection`, `ChatHistorySnippet`) but lacks:
- Exact Room entity annotations (`@Entity`, `@PrimaryKey`, `@ForeignKey`, `@Index`)
- Precise column types, nullability constraints, and default values
- Foreign key cascading behavior (what happens when source chat/message is deleted?)
- Index strategy for FTS table (which FTS version: FTS3, FTS4, FTS5?)
- Collision handling for derived turn keys

**Current State:** Lines 159-172 describe fields conceptually but don't specify:
```kotlin
// Missing: What is the actual primary key structure?
// Missing: Is (chatId, userMessageId, assistantMessageId) a composite key?
// Missing: How do we handle message edits that change IDs?
```

**Required:** Complete Room entity definitions with migration DDL from schema v19 to v20.

#### 1.2 Content Hash Algorithm Unspecified
**Issue:** Line 169 mentions "source content hash and projection version" but doesn't specify:
- Which hash algorithm? (SHA-256, MD5, xxHash?)
- What exactly is being hashed? (raw content, normalized content, with metadata?)
- Hash collision policy (extremely unlikely but unhandled)
- Performance implications (hashing on every save?)

**Production Risk:** Different hash algorithms could break index rebuilds across app versions.

#### 1.3 Turn Canonicalization Logic Incomplete
**Issue:** Lines 71-76 describe "canonical turn projection" but leave critical ambiguities:
- "Choose one nonblank assistant answer using existing stable platform order" — What is this order? Where is it defined?
- What if all assistant answers are blank/errors?
- What if the user enables a new platform after the turn was indexed?
- How do multi-platform tool loops affect turn grouping?

**Current Project Reality Check:**
```kotlin
// From ChatViewModel.kt:181 - memoryContextCache
// This suggests turn-level caching exists, but projection logic is undefined
```

---

### 2. **Retrieval Quality & Calibration**

#### 2.1 No Relevance Threshold Definition
**Issue:** Line 282 mentions "absolute minimum relevance gates" but provides:
- No numeric thresholds (lexical score? vector similarity?)
- No calibration methodology
- No A/B testing framework for tuning
- No user feedback mechanism to improve relevance

**Production Impact:** Without thresholds, the feature may surface irrelevant history or suppress useful context. The "initial calibration targets" (line 291) are vague guesses, not evidence-based.

**Missing Test Cases:**
```kotlin
// Should pass: User asks "what was my cat's name?"
//   → retrieve turn from 3 months ago mentioning "Whiskers"
// Should fail: User asks "what's the weather?"
//   → do NOT retrieve unrelated history about code reviews
```

#### 2.2 Deduplication Strategy Underspecified
**Issue:** Line 283 mentions "deduplicate equivalent snippets" but doesn't define:
- What makes two snippets "equivalent"? (exact text match? semantic similarity?)
- How to handle near-duplicates (90% overlap?)
- Should we deduplicate across different chats if the same answer was given twice?

#### 2.3 Chat Diversification Mechanism Missing
**Issue:** Line 284 requires "diversify across chats" but provides no algorithm:
- Max snippets per source chat? (Currently unbounded)
- How to balance recency vs. diversity?
- What if 5 relevant snippets are all from the same long conversation?

**Required:** Concrete diversification rules, e.g., "maximum 2 snippets per source chat unless query results < 3 total hits."

---

### 3. **Concurrency & Race Conditions**

#### 3.1 Index Update Race Conditions
**Issue:** Multiple concurrent operations can corrupt the index:

**Race Scenario 1: Edit During Retrieval**
```text
Thread 1: User asks question → retrieves snippet X from index
Thread 2: User edits source message → updates index, invalidates X
Thread 1: Injects now-stale snippet X into prompt
```

**Current Mitigation:** Line 195 mentions "background index update must not change in-flight provider request" but doesn't specify HOW. No transaction isolation level defined. No version locking mechanism.

**Race Scenario 2: Concurrent Backfill & Incremental Index**
```text
Worker 1: Backfill processing chat 5000
Worker 2: User completes new turn in chat 5000 → incremental index
Result: Duplicate projections or lost update
```

**Missing:**
- Distributed lock mechanism (e.g., WorkManager unique work constraints)
- Optimistic locking with version counters
- Transaction boundary specifications

#### 3.2 Snapshot Isolation Not Enforced
**Issue:** Line 386 states "same turn uses one frozen history snapshot" but:
- No code path specified to guarantee this
- What if index rebuild happens mid-turn?
- How is the snapshot key computed? (Lines 228-232 show memory recall key structure, but history key is different?)

**Current Project Evidence:**
```kotlin
// From ChatViewModel.kt:181
private val memoryContextCache = TurnMemoryContextCache()
// This exists for memory, but history snapshot cache is not mentioned
```

**Required:** Explicit `HistorySnapshotCache` with turn-keyed storage and cache invalidation policy.

---

### 4. **Failure Modes & Error Recovery**

#### 4.1 Partial Index Failure Handling
**Issue:** Line 296-301 lists failure behaviors but lacks recovery strategies:

**Unhandled Scenarios:**
- FTS table corrupted mid-query → Should rebuild automatically? Fail permanently? Use LIKE fallback?
- Vector index deleted but FTS intact → Graceful degradation specified, but no monitoring to detect this
- Hash mismatch during retrieval → Stale row, but how often does this happen? Is it logged? Alarmed?

**Production Requirement:** Each failure mode needs:
1. Automatic recovery action
2. User-visible error state (if applicable)
3. Logging/metrics for monitoring
4. Maximum retry count before permanent failure

#### 4.2 Backfill Interruption Recovery
**Issue:** Lines 252-262 describe "durable cursor" but don't specify:
- Cursor schema (which table? columns?)
- Checkpoint granularity (per chat? per message? per batch?)
- Idempotency proof (what if same batch processed twice?)
- Restart behavior after app upgrade with schema change

**Example Missing Logic:**
```kotlin
// Required but unspecified:
data class ChatHistoryBackfillCheckpoint(
    val lastProcessedChatId: Int,
    val lastProcessedMessageId: Int?,
    val batchStartTimestamp: Long,
    val totalChatsProcessed: Int,
    val projectionVersion: Int // Schema version matters!
)
```

#### 4.3 No Rollback Strategy for Bad Migrations
**Issue:** Schema v19 → v20 migration adds new tables. If migration fails halfway:
- Can user still chat normally?
- Is the feature permanently disabled?
- Can we safely retry or rollback?

**Missing:** Migration failure recovery plan, schema version validation, escape hatch to revert to v19 behavior.

---

### 5. **Testing Gaps**

#### 5.1 Insufficient Test Coverage Specification
**Issue:** Lines 448-460 list "required tests" but miss critical scenarios:

**Missing Integration Tests:**
- ✗ Cross-process persistence (app killed during indexing)
- ✗ Schema migration from v19 with 10,000 existing chats
- ✗ Concurrent edit + delete + query on same chat
- ✗ Memory pressure (OOM during large backfill)
- ✗ Network flakiness (if embedding model requires download)
- ✗ Device reboot during Worker execution

**Missing Adversarial Tests:**
- ✗ Malicious content injection (user message contains SQL, markdown injection)
- ✗ Extremely long messages (100KB text in single message)
- ✗ Pathological CJK text (mixed scripts, emoji, RTL)
- ✗ Time travel attacks (device clock changed during indexing)

**Missing Performance Tests:**
- ✗ Query latency target (unspecified, but should be <200ms)
- ✗ Index size growth rate (1000 chats = how much disk space?)
- ✗ Backfill time estimation (1 minute per 100 chats?)
- ✗ Battery impact measurement

#### 5.2 No Baseline Metrics Requirement
**Issue:** Task 0 (lines 320-337) suggests capturing baseline but doesn't mandate specific metrics:

**Required Before/After Measurements:**
- Average prompt length (tokens) with/without history
- Query latency P50/P95/P99
- Database write amplification
- WorkManager queue depth
- Settings toggle response time

---

### 6. **Security & Privacy Concerns**

#### 6.1 Global Opt-In Privacy Boundary
**Decision:** MVP uses the existing global `memory_enabled` switch as the privacy boundary. Per-chat exclusion is intentionally deferred and must not be simulated with keyword heuristics.

**Real-World Scenario:**
- When the switch is enabled, an eligible turn from Chat A may be referenced while the user is in Chat B.
- The Settings description must make this global scope explicit before opt-in.
- The current chat, in-flight turns, invalid projections, and stale rows remain excluded by technical eligibility gates; the global disabled state is enforced before retrieval.

**Out of scope for MVP:** `ChatRoomV2.excludeFromHistory`, per-chat exclusion UI, keyword filters, and time-based privacy heuristics. A future per-chat privacy feature requires a separate schema and product review.

#### 6.2 Sensitive Content Exposure in Logs
**Issue:** Lines 187-193 define `HistoryRecallSnapshot` with "bounded diagnostics" but doesn't specify:
- What's included in diagnostics? (snippet text? full query?)
- Where do diagnostics go? (Logcat? Crashlytics? Local file?)
- Are diagnostics cleared on disable?

**Production Risk:** Debug logs may contain sensitive user history. No scrubbing policy defined.

---

### 7. **Performance & Scalability Issues**

#### 7.1 No Query Optimization Strategy
**Issue:** Line 217 says "use Room-supported FTS" but doesn't address:
- FTS tokenizer choice (simple, porter, unicode61, icu?)
- Index refresh frequency (real-time? batch?)
- Query rewrite optimization (should "search cat photos" expand to "cat OR feline OR pet"?)

**Scalability Concern:**
- User with 10,000 chats, 50,000 messages
- Backfill time: **unestimated**
- Query time on old device (2GB RAM): **unestimated**
- Storage overhead: **unestimated**

**Missing:** Performance benchmarks on reference device (e.g., Pixel 4a, 6GB RAM, Android 12).

#### 7.2 Memory Budget Not Enforced
**Issue:** Lines 195, 291 mention "token budget" but:
- Who enforces it? (Retriever? PromptBuilder? Repository?)
- What happens if exceeded? (Truncate? Drop lowest-scoring?)
- Is budget per-snippet or total?

**Current Project Context:**
```kotlin
// From MemoryRepositoryImpl.kt:121
private const val QUERY_RECALL_TOKEN_BUDGET = 300
// This is for long-term memory. History budget is separate but enforcement unclear.
```

#### 7.3 No Cache Eviction Policy
**Issue:** Line 195 describes `HistoryRecallSnapshot` caching but:
- How long are snapshots retained in memory?
- What if user has 10 concurrent chats open? (10 cached snapshots?)
- LRU eviction? TTL-based? Explicit invalidation only?

---

### 8. **Operational & Monitoring Gaps**

#### 8.1 No Observability Instrumentation
**Issue:** Zero mentions of logging, metrics, or alerting. Production system needs:

**Required Metrics:**
- History retrieval success rate (per query)
- Average snippets returned per query
- Index staleness (age of oldest unprojected turn)
- Backfill progress (percentage complete)
- Failure reasons histogram

**Required Logs:**
- Structured JSON logs for each retrieval (query hash, hit count, latency)
- Slow query warnings (>500ms)
- Index corruption detection

**Current Project Evidence:**
```kotlin
// From MemoryRepositoryImpl.kt:9
import cn.nabr.chatwithchat.data.debug.PromptTraceStore
// This exists for memory. History needs equivalent tracing.
```

#### 8.2 No User-Facing Diagnostics
**Issue:** Users have no visibility into whether history indexing is complete or whether the feature is currently disabled by the shared memory switch. The MVP does not expose per-chat privacy controls.

**Required UI/UX:**
- Settings screen: "Indexing 1,247 / 5,000 chats (25%)"
- Debug panel: Show retrieved snippets with source chat links
- Settings copy must state that enabling memory also permits relevant eligible excerpts from previous chats to be referenced.
- Per-chat exclusion is explicitly out of scope for MVP.

---

### 9. **Integration & Dependency Issues**

#### 9.1 Dependency on Long-Term Memory Not Validated
**Issue:** The history feature must follow the existing long-term memory switch. There is no supported state in which history is enabled while `memory_enabled` is false, and the implementation must not create a second preference that can drift from the memory pipeline.

**Required:** Explicit integration test matrix:

| `memory_enabled` | History indexing/recall | Expected Behavior | Test Status |
|---|---|---|---|
| `true` | enabled | Long-term memory and history reference run independently; each keeps its own snapshot and vector identity | ❌ Not specified |
| `false` | disabled | No memory or history context injection; no history writes or worker execution, while retained history index data is untouched | ❌ Not specified |

The transition tests must also cover `false -> true`: pending history work resumes, stale/missing projections are reconciled, and retained index rows are never injected before eligibility and freshness checks pass.

#### 9.2 WorkManager Constraints Unspecified
**Issue:** Line 249 mentions "dedicated ChatHistoryIndexWorker" but:
- Constraints: WiFi-only? Charging-only? Battery-not-low?
- Backoff policy on failure? (Linear? Exponential?)
- Conflict resolution with existing memory workers?
- Unique work name convention to prevent duplicates?

**Current Project Evidence:**
```kotlin
// From grep results: 8 Worker files exist for memory
// Naming convention: Memory*Worker.kt
// Should history follow: ChatHistory*Worker.kt?
```

---

### 10. **Semantic Vector Implementation Risk**

#### 10.1 Vector Phase Dependency Risk
**Decision impact:** Hybrid retrieval is part of the MVP. The earlier lexical-first/optional-vector sequencing is superseded. The remaining risk is implementation and runtime validation of a history-specific snapshot store, not product scope.

**Problem:** Lexical-only retrieval is fundamentally weak for:
- Paraphrased queries ("where did I put my keys?" vs. "key location")
- Synonym/multilingual queries ("chat" vs. "conversation", "猫" vs. "cat")
- Conceptual queries ("machine learning" should match "neural networks")

**Production Impact:** Without semantic search, feature may underdeliver user value, leading to:
- Low adoption
- Negative reviews ("history search doesn't work")
- Wasted v1 investment

**Required MVP behavior:** Implement lexical and local semantic candidates from the same eligible projection set, fuse them only after absolute relevance gates, and fall back to lexical retrieval whenever the vector path is unavailable or unhealthy. Do not silently turn a vector failure into a long-term memory failure.

#### 10.2 Embedding Model Logistics Unclear
**Issue:** Line 234 says "reuse existing on-device ONNX provider" but:
- Where is this model? (Bundled in APK? Downloaded on first run?)
- Model size impact on APK? (Current APK ~20MB, model ~50MB?)
- Latency for 1000-message backfill embedding? (Unestimated)
- Fallback if model loading fails? (Lexical-only, but permanently or temporary?)

**Current Project Check:**
```kotlin
// From test files: OnnxMemoryEmbeddingProviderTest.kt exists
// Suggests ONNX infrastructure is ready, but history integration unplanned
```

---

### 11. **User Experience Gaps**

#### 11.1 No Independent Rollout Flag
**Issue:** History has no independent rollout flag. It follows the existing `memory_enabled` switch, whose current repository contract defaults to disabled (`fetchMemoryEnabled()` returns `false` when unset). The plan must define how the existing Settings entry communicates that enabling memory also permits eligible previous chats to be referenced.

**Missing:**
- How do users discover that enabling memory also enables history reference?
- What copy explains the global scope without exposing raw history?
- A/B test plan to measure impact on engagement/satisfaction?
- Rollback strategy if feature causes crashes?

**Required for this delivery:** Use the existing memory switch as the rollout gate. A separate history flag or independent phased rollout is out of scope. Crash rollback means turning off `memory_enabled`; this must stop prompt injection and new indexing while retaining derived index data.

#### 11.2 Loading States Unspecified
**Issue:** During backfill (potentially minutes), what does user see?
- Silent background processing? (User confused why history isn't working)
- Persistent notification? (Annoying)
- Settings screen progress bar? (Better, but unspecified)

**Required:** Loading state design for:
- Initial enable (first backfill)
- Incremental indexing after completion
- Index rebuild after corruption
- Re-enable after disable

---

### 12. **Data Migration & Versioning**

#### 12.1 No Migration Testing Strategy
**Issue:** Lines 344-346 mention "fresh/open/populated migration tests" but don't specify:
- Test database fixtures (where are they? how many?)
- Migration path testing (v19 → v20, but also v18 → v20 if user skipped v19?)
- Data integrity validation (checksums before/after?)

**Production Horror Story Prevention:**
```kotlin
// Scenario: User with 5 years of chat history
// Migration fails at message 10,000 due to encoding bug
// Result: App crashes on launch, user loses all data
// Prevention: Dry-run migration with rollback capability
```

#### 12.2 Projection Version Strategy Incomplete
**Issue:** Line 169 mentions "projection version" but:
- How is version incremented? (On schema change? Algorithm change?)
- Backward compatibility policy (can v2 app read v1 projections?)
- Force re-index trigger (when version mismatch detected?)

---

### 13. **Prompt Engineering & Context Management**

#### 13.1 History Section Formatting Unspecified
**Issue:** Lines 201-209 show example prompt structure but don't define:
- Markdown rendering (plain text? headings? bullet points?)
- Snippet ordering (chronological? relevance-ranked? newest-first?)
- Source attribution format ("From chat 'Project Alpha', 3 days ago" or "Previous conversation, 2025-07-28"?)
- Deduplication presentation (if same fact appears in 2 snippets, merge or show both?)

**Provider Compatibility Risk:**
- Different AI models may parse context differently
- Claude may prefer structured format, GPT may prefer narrative
- Plan assumes one-size-fits-all prompt format

#### 13.2 No Context Overflow Handling
**Issue:** Line 291 caps at ~300-400 tokens, but:
- What if 10 snippets all meet relevance threshold? (Drop lowest? Truncate each?)
- What if single snippet exceeds budget? (Truncate mid-sentence? Skip?)
- How to balance history budget vs. memory budget vs. message history?

**Current Project Evidence:**
```kotlin
// From MemoryRepositoryImpl.kt:147
val renderedPrompt = memoryPromptBuilder.build(
    coreFacts = ...,
    queryFacts = ...
)
// This handles memory token budget, but history integration unclear
```

---

### 14. **Code Structure & Maintainability**

#### 14.1 Package Organization Ambiguity
**Issue:** Line 157 says "new `data/history` package unless live repository has stronger convention" but:
- Doesn't check current structure
- Defers decision to implementation agent
- Risks inconsistent placement

**Current Project Structure Check:**
```kotlin
// Existing packages:
data/memory/        → Long-term memory
data/repository/    → Repository layer
data/database/      → Room entities
// History should go where? data/history/ or data/recall/ or data/repository/history/?
```

**Required:** Explicit package decision WITH justification.

#### 14.2 Interface Boundaries Unclear
**Issue:** Multiple mentions of composition (lines 197-200, 381-399) but no class diagram or interface contracts specified.

**Missing:**
```kotlin
interface ChatHistoryRetriever {
    suspend fun retrieve(query: String, ...): HistoryRecallSnapshot
}

interface ChatHistoryProjectionBuilder {
    suspend fun buildProjection(chat: ChatRoomV2, messages: List<MessageV2>): ChatHistoryTurnProjection?
}
```

**Production Risk:** Implementation agent makes suboptimal choices, requires refactoring.

---

### 15. **Localization & Internationalization**

#### 15.1 No Multilingual Retrieval Strategy
**Issue:** Plan doesn't address:
- Should history retrieval work across languages? (User asks in English, snippet from Chinese chat?)
- How does FTS tokenizer handle CJK? (Line 374 mentions "CJK/exact phrase behavior" but no solution)
- Should embedding model support multilingual queries?

**Real-World Scenario:**
- User switches system language from English to Chinese
- All past chat history in English
- Should retrieval still work? (Yes, but how?)

#### 15.2 UI Strings Not Planned
**Issue:** Line 314 mentions "localized Settings copy" but:
- How many strings needed? (Setting label, description, toggle, status messages = 10+ strings)
- Who writes copy? (Developer? UX writer? Translator?)
- Tone/voice guidelines? ("Reference previous conversations" vs. "Remember chat history"?)

---

### 16. **Technical Debt & Future-Proofing**

#### 16.1 No Extension Points for Future Features
**Issue:** Plan focuses narrowly on v1 but doesn't consider:
- Future: User-curated memory (manually save important snippets)
- Future: Cross-device history sync (via cloud backup)
- Future: History search UI (dedicated screen, not just auto-retrieval)
- Future: Analytics (which snippets are most useful?)

**Required:** Abstract interfaces that don't lock in implementation details.

#### 16.2 No Deprecation Strategy
**Issue:** If feature fails to gain adoption, how do we:
- Disable gracefully without breaking existing users?
- Remove index data without corrupting database?
- Migrate users back to memory-only workflow?

---

## Recommendations by Priority

### P0 (Blocking Production Release)

1. **Specify complete Room schema** with migration DDL, foreign keys, indexes
2. **Define relevance thresholds** with calibration test dataset (50+ query/snippet pairs)
3. **Implement snapshot isolation** with explicit turn-keyed cache
4. **Add comprehensive failure recovery** for index corruption, migration failure, backfill interruption
5. **Privacy contract** for the accepted global opt-in boundary, explicit Settings copy, and content-safe diagnostics
6. **Performance benchmarks** on reference hardware (max query latency, max backfill time)

### P1 (Production Quality)

7. **Structured observability** (metrics, logs, alerts) with dashboard
8. **User-facing diagnostics** (indexing progress, disabled/paused state, and bounded retrieved-snippet trace)
9. **Adversarial testing** (malicious input, extreme scale, concurrent operations)
10. **Localization plan** (multilingual retrieval, UI strings in 3+ languages)
11. **Rollback verification** through the existing `memory_enabled` switch and crash monitoring
12. **Context overflow handling** with graceful degradation

### P2 (Technical Excellence)

13. **History-specific vector store hardening** (lexical + semantic MVP identity, snapshot publication, and fallback)
14. **Interface boundaries** with class diagrams and dependency injection
15. **Package structure decision** with architecture document
16. **Extension points** for future features (analytics, manual curation, sync)
17. **Deprecation strategy** with backward compatibility plan

---

## Positive Aspects (What the Plan Got Right)

1. ✅ **Clear scope boundary** with long-term memory (lines 19-32) — excellent separation of concerns
2. ✅ **Shared opt-in switch** — history follows the existing `memory_enabled` default and transition behavior
3. ✅ **Async indexing goal** (line 246) — doesn't block chat completion when implemented
4. ⚠️ **Idempotent updates are an intended contract** (line 245) — stable-key implementation is still pending
5. ✅ **Stop conditions** (lines 464-475) — clear escalation path for blockers
6. ✅ **Baseline capture requirement** (Task 0) — good engineering discipline

---

## Conclusion

The original v1 plan was a **solid conceptual draft (60-65% complete)** but required significant refinement before implementation. The revised document now records the product contract; the remaining gaps are:

1. **Insufficient technical specification** (vague "derived storage", no schema DDL)
2. **Missing error recovery** (17+ failure scenarios unhandled)
3. **No production operations plan** (monitoring, diagnostics, rollback)
4. **Weak testing requirements** (no adversarial, performance, or migration tests specified)
5. **MVP implementation risk** (history-specific vector snapshot store, hybrid calibration, and global-opt-in disclosure)

**Recommendation:** Keep the plan in design review until the P0 technical contracts and validation evidence are complete. The product decisions are closed, but implementing from this document without the migration, queue, vector-store, and runtime evidence would still produce a non-production build.

**Next Steps:**
1. Assign technical lead to refine data model with complete Room schemas
2. Create calibration dataset for relevance tuning (50 query/snippet pairs)
3. Write integration test specifications for 10 critical scenarios
4. Design observability schema (metrics, logs, dashboards)
5. Review the global-opt-in copy and shared-switch behavior with the product owner
6. Only then proceed to implementation (Task 0).

---

## 基于项目实际代码的具体改进建议

### 已验证的项目实际情况

#### 1. **数据库架构 (Schema v19)**
**实际状态（2026-08-01 复核）：**
- Room database version 19，`ChatDatabaseV2` 当前列出 17 个实体；schema version 不能当作实体数量
- `messages_v2` 表结构完整，包含 `revisions`, `active_revision_index`, `sticker_refs`, `source_metadata`, `token_usage`
- 已有完善的 `ForeignKey(onDelete = CASCADE)` 机制
- 已有 `@Index` 注解的索引定义模式
- 已有完整的 migration 测试框架 (`ChatDatabaseV2MigrationsTest.kt`)

**改进建议：**
```kotlin
// 1. 聊天历史投影表应遵循现有模式
@Entity(
    tableName = "chat_history_turn_projection",
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE  // 遵循现有模式
        )
    ],
    indices = [
        Index(value = ["turn_key"], unique = true),
        Index(value = ["chat_id", "user_message_id"], unique = true),
        Index(value = ["content_hash"]),
        Index(value = ["created_at"]),
        Index(value = ["eligibility_state"])
    ]
)
data class ChatHistoryTurnProjection(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("projection_id")
    val projectionId: Long = 0,

    // Stable derived identity. Re-indexing the same chat/user turn must replace this row.
    @ColumnInfo("turn_key")
    val turnKey: String,

    @ColumnInfo("chat_id")
    val chatId: Int,

    @ColumnInfo("user_message_id")
    val userMessageId: Int,

    @ColumnInfo("assistant_message_id")
    val assistantMessageId: Int,  // canonical assistant is required; user-only projection is out of scope

    @ColumnInfo("chat_title")
    val chatTitle: String,

    @ColumnInfo("user_content")
    val userContent: String,

    @ColumnInfo("assistant_content")
    val assistantContent: String,

    @ColumnInfo("content_hash")
    val contentHash: String,  // 使用 SHA-256 (项目已用)

    @ColumnInfo("projection_version")
    val projectionVersion: Int = CURRENT_PROJECTION_VERSION,

    @ColumnInfo("eligibility_state")
    val eligibilityState: String,

    @ColumnInfo("created_at")
    val createdAt: Long,

    @ColumnInfo("updated_at")
    val updatedAt: Long
) {
    companion object {
        const val CURRENT_PROJECTION_VERSION = 1
    }
}
```

`eligibility_state` is a projection property, not a copy of the global memory switch. Store one of these stable lowercase values:

| State | Meaning | Queryable |
|---|---|---|
| `eligible` | Nonblank user turn with a valid canonical projection | yes, only when `memory_enabled=true` |
| `blank_user` | Bounded skip diagnostic; no projection row is created | no |
| `no_eligible_assistant` | Bounded skip diagnostic; no projection row is created | no |
| `invalid_source` | Source chat/message failed validation or hash construction | no |
| `stale` | Projection was superseded by a newer source hash/version and is awaiting replacement | no |

The builder must return no projection for a blank user or for a turn without a successful canonical assistant; these are bounded diagnostics only, never user-only rows. Existing rows that become invalid or stale are fail-closed and removed or marked non-queryable. The global `memory_enabled` gate is evaluated at enqueue, worker execution, and retrieval; it must never be written into `eligibility_state`.

**关键发现：**
- 项目已广泛使用 `MessageDigest.getInstance("SHA-256")`（见 `ChatComposer.kt:642`, `MemoryEmbeddingArtifactInstaller.kt:150`）
- **必须使用 SHA-256 + UTF-8 + 有界字段 framing** 作为内容哈希算法以保持一致性
- 项目有完整的 schema 导出机制 (`app/schemas/`)，新表必须导出

#### 2. **消息有效内容提取**
**实际代码：**
```kotlin
// MessageV2.kt:111-114
fun MessageV2.effectiveContent(): String = revisions
    .getOrNull(activeRevisionIndex)
    ?.content
    ?: content
```

**改进建议：**
- **不需要猜测**"如何确定canonical assistant answer"
- 直接使用 `MessageV2.effectiveContent()` 和 `MessageV2.effectiveThoughts()`
- 遵循 `activeRevisionIndex` 机制，这是项目标准
- 投影时检查 `MessageV2.isEffectivelyBlank()`（第131行）

**正确的投影逻辑：**
```kotlin
suspend fun buildProjection(
    chat: ChatRoomV2,
    userMessage: MessageV2,
    assistantMessages: List<MessageV2>,
    preferredPlatformUid: String?,
    stablePlatformOrder: List<String>,
    observedAt: Long
): ChatHistoryTurnProjection? {
    // 验证用户消息非空
    if (userMessage.isEffectivelyBlank()) return null

    // 复用 MemoryTurnBatchCoordinator 的 canonical assistant 规则：
    // preferred platform -> stable platform order -> stable platform UID。
    val assistantMessage = selectCanonicalAssistant(
        assistantMessages = assistantMessages,
        preferredPlatformUid = preferredPlatformUid,
        stablePlatformOrder = stablePlatformOrder
    ) ?: return null
    // User-only and blank/error turns are skip diagnostics, not queryable projections.
    val eligibilityState = "eligible"
    val turnKey = "chat:${chat.id}:user:${userMessage.id}"

    val assistantPlatformUid = assistantMessage.platformType ?: return null
    val assistantContent = stripAssistantErrorNote(assistantMessage.effectiveContent()).trim()
    if (assistantContent.isBlank() || isAssistantErrorMessage(assistantContent)) return null

    // 使用版本化、长度 framing 的 UTF-8 输入，避免内容边界产生 hash ambiguity。
    val hashInput = buildString {
        appendField("projection_version", ChatHistoryTurnProjection.CURRENT_PROJECTION_VERSION.toString())
        appendField("turn_key", turnKey)
        appendField("chat_title", chat.title)
        appendField("user_message_id", userMessage.id.toString())
        appendField("user_content", userMessage.effectiveContent())
        appendField("assistant_message_id", assistantMessage.id.toString())
        appendField("assistant_platform_uid", assistantPlatformUid)
        appendField("assistant_content", assistantContent)
    }
    val contentHash = MessageDigest.getInstance("SHA-256")
        .digest(hashInput.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    return ChatHistoryTurnProjection(
        turnKey = turnKey,
        chatId = chat.id,
        userMessageId = userMessage.id,
        assistantMessageId = assistantMessage.id,
        chatTitle = chat.title,
        userContent = userMessage.effectiveContent(),
        assistantContent = assistantContent,
        contentHash = contentHash,
        eligibilityState = eligibilityState,
        createdAt = userMessage.createdAt,
        // observedAt comes from the completed persistence event. The DAO preserves updatedAt
        // when contentHash and projectionVersion are unchanged.
        updatedAt = observedAt
    )
}

private fun StringBuilder.appendField(name: String, value: String) {
    val field = "$name=$value"
    val fieldBytes = field.toByteArray(Charsets.UTF_8)
    append(fieldBytes.size).append(':').append(field).append('\n')
}
```

The `turn_key`/unique-index pair is the idempotency contract. A DAO must use an upsert or
transactional replace keyed by `turn_key`; `content_hash` is a freshness check, not a row identity.
`selectCanonicalAssistant` must share the existing `MemoryTurnBatchCoordinator` ordering and
return `null` when every assistant is blank/error; a raw `firstOrNull` is not an acceptable fallback.

#### 3. **WorkManager 集成**
**实际代码分析：**
```kotlin
// MemoryMaintenanceWorkScheduler.kt:27-32
private const val SEMANTIC_IMMEDIATE_UNIQUE_WORK_NAME = "memory_maintenance_semantic_v1_immediate"
private const val INDEX_IMMEDIATE_UNIQUE_WORK_NAME = "memory_maintenance_index_v1_immediate"
private const val REPAIR_IMMEDIATE_UNIQUE_WORK_NAME = "memory_maintenance_repair_v2_immediate"
```

**改进建议：**
```kotlin
// 新的 ChatHistoryIndexWorker 应遵循命名约定
class ChatHistoryIndexWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            ChatHistoryWorkerEntryPoint::class.java
        )
        return try {
            entryPoint.chatHistoryIndexer().drainDurableQueue()
            Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatHistoryWorkerEntryPoint {
    fun chatHistoryIndexer(): ChatHistoryIndexer
}

// 调度器应遵循现有模式
object ChatHistoryWorkScheduler {
    private const val HISTORY_INDEX_IMMEDIATE = "chat_history_index_v1_immediate"
    private const val HISTORY_INDEX_DELAYED = "chat_history_index_v1_delayed"
    private const val HISTORY_BACKFILL = "chat_history_backfill_v1"

    fun enqueueIndexWork(context: Context, delaySeconds: Long = 0) {
        val workRequest = OneTimeWorkRequestBuilder<ChatHistoryIndexWorker>()
            .apply {
                if (delaySeconds > 0) {
                    setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                }
                setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
            }
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            if (delaySeconds > 0) HISTORY_INDEX_DELAYED else HISTORY_INDEX_IMMEDIATE,
            // The Room queue is durable; one worker drains/coalesces it. Do not append one
            // WorkRequest per message save and starve newer updates behind an unbounded chain.
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
}
```

**关键发现：**
- 历史索引的 durable queue/cursor 是真相，WorkManager 只负责唤醒 worker。
- `KEEP` + queue drain/coalesce 避免每次保存形成 `APPEND_OR_REPLACE` 长链；延迟 backfill 可单独使用 `REPLACE`。
- **不应重用** `MemoryMaintenanceJobFamily.SEMANTIC` — 历史索引是独立的语义责任。
- Worker 必须显式重抛 `CancellationException`，并为 retry 设置退避；不能用未定义的 `entryPoint` 伪代码。

**Durable queue/checkpoint contract:**

```text
chat_history_index_queue
  turn_key TEXT PRIMARY KEY
  chat_id INTEGER NOT NULL
  user_message_id INTEGER NOT NULL
  operation_hint TEXT NOT NULL          -- RECONCILE; worker reloads current source
  requested_at INTEGER NOT NULL
  attempt_count INTEGER NOT NULL DEFAULT 0

chat_history_backfill_checkpoint
  checkpoint_id TEXT PRIMARY KEY       -- one fixed value: "history_backfill"
  last_chat_id INTEGER
  last_user_message_id INTEGER
  projection_version INTEGER NOT NULL
  status TEXT NOT NULL                  -- IDLE, RUNNING, PAUSED, FAILED
  updated_at INTEGER NOT NULL

chat_history_index_state
  state_id TEXT PRIMARY KEY             -- fixed value: "history"
  projection_generation INTEGER NOT NULL
  projection_hash TEXT
  vector_published_generation INTEGER
  vector_status TEXT NOT NULL
  updated_at INTEGER NOT NULL
```

- `turn_key` is the queue identity. Repeated enqueue coalesces to one `RECONCILE` item; the worker reloads current Room state instead of trusting a caller-supplied source version or payload.
- The source save and queue upsert should be one Room transaction. If a legacy save path cannot include the queue write, the next reconciliation scan must repair the missing queue entry before declaring the index current.
- A worker always reloads the current source row by `turn_key` before writing. It never trusts stale content carried in the queue item. A missing source produces `DELETE`; a current source produces a projection keyed by the same `turn_key`.
- Projection replacement, FTS trigger work, and queue acknowledgement are committed in one Room transaction. Vector publication records the resulting projection revision in a durable outbox/rebuild marker and may be replayed; it must never make the Room projection appear complete when the vector snapshot is not current.
- The backfill cursor is ordered by `(chat_id, user_message_id)`. The worker advances it only after the batch transaction commits; a crash before the checkpoint update replays the batch safely.
- When `memory_enabled=false`, the worker leaves queue, checkpoint, and index-state rows intact, records `PAUSED`, and exits without source/projection writes. Re-enabling schedules a queue drain plus reconciliation from the stored cursor.

#### 4. **快照隔离机制（⚠️ 不应扩展 TurnRecallSnapshot）**
**实际代码：**
```kotlin
// TieredMemoryRecall.kt:34-45
data class TurnRecallSnapshot(
    val canonicalRevision: Long? = null,
    val canonicalSourceHash: String? = null,
    val recallProjectionHash: String? = null,
    val coreFacts: List<ModelVisibleMemoryFact> = emptyList(),
    val queryFacts: List<ModelVisibleMemoryFact> = emptyList(),
    val mode: MemoryRetrievalMode = MemoryRetrievalMode.NONE,
    val errorMessage: String? = null,
    val diagnostics: List<MemoryProjectionDiagnostic> = emptyList(),
    val prompt: String? = null,
    val estimatedTokens: Int = 0
)
// 注意：这是长期记忆专用快照，不应混入历史检索
```

**❌ 错误设计（之前建议）：**
```kotlin
// 污染了长期记忆边界
data class TurnRecallSnapshot(
    // ... 现有字段 ...
    val historySnapshotHash: String? = null,      // ❌ 不应添加
    val historicalSnippets: List<...> = emptyList() // ❌ 不应添加
)
```

**✅ 正确设计（保持边界清晰）：**
```kotlin
// 1. 定义独立的历史快照
data class HistoryRecallSnapshot(
    val projectionHash: String?,
    val snippets: List<HistoricalSnippet>,
    val mode: HistoryRetrievalMode,
    val queryLatencyMs: Long,
    val errorMessage: String?,
    val renderedPrompt: String? = null,
    val estimatedTokens: Int = 0
)

data class HistoricalSnippet(
    val chatId: Int,
    val chatTitle: String,
    val snippet: String,
    val createdAt: Long
    // 不包含分数、内部ID等维护字段
)

enum class HistoryRetrievalMode {
    NONE,           // 功能未启用
    DISABLED,       // memory_enabled=false；索引保留但不可见
    LEXICAL,        // 向量不可用时的降级路径
    HYBRID,         // MVP 正常路径：词法 + 向量
    FAILED          // 检索失败
}

// 2. 在 PreparedMemoryContext 中组合；不修改 TurnRecallSnapshot 的长期记忆合同。
data class PreparedMemoryContext(
    val retrievedMemories: List<MemoryRetrievalResult> = emptyList(),
    val snapshot: TurnRecallSnapshot = TurnRecallSnapshot(),
    val historySnapshot: HistoryRecallSnapshot? = null  // 新增独立字段
) {
    val prompt: String?
        get() {
            val parts = mutableListOf<String>()

            // 长期记忆部分（现有逻辑）
            snapshot.prompt?.let { parts.add(it) }

            // 历史引用部分（新增逻辑）
            historySnapshot?.let { history ->
                if ((history.mode == HistoryRetrievalMode.LEXICAL ||
                        history.mode == HistoryRetrievalMode.HYBRID) &&
                    history.estimatedTokens <= HISTORY_TOKEN_BUDGET
                ) {
                    history.renderedPrompt?.takeIf { it.isNotBlank() }?.let(parts::add)
                }
            }

            return parts.joinToString("\n\n").takeIf { it.isNotBlank() }
        }

    private companion object {
        const val HISTORY_TOKEN_BUDGET = 400
    }
}
```

**关键设计原则：**
- ✅ `TurnRecallSnapshot` 保持不变（长期记忆专用）
- ✅ `HistoryRecallSnapshot` 独立定义（历史检索专用）
- ✅ 在 `PreparedMemoryContext` 组合两者
- ✅ `HistoryPromptPacker` 在生成 `renderedPrompt` 时负责 token 计数、截断和丢弃最低相关片段
- ✅ `PreparedMemoryContext` 对超预算快照 fail-closed，不用字符数假冒 token 限制

#### 5. **Settings 集成**
**实际代码：**
```kotlin
// SettingDataSourceImpl.kt:76-78
private val memoryEnabledKey = booleanPreferencesKey("memory_enabled")
private val memoryModelPlatformUidKey = stringPreferencesKey("memory_model_platform_uid")
private val memoryModelIdKey = stringPreferencesKey("memory_model_id")
```

**实施合同：**
- 不新增 `reference_chat_history_enabled`，也不新增历史专用 getter/setter。
- 历史索引和召回统一读取现有 `SettingRepository.fetchMemoryEnabled()`；该 API 在偏好缺失时返回 `false`，因此默认保持当前记忆系统的关闭语义。
- `SettingViewModelV2.updateMemoryEnabled()` 的一次切换必须同时驱动长期记忆和历史索引：`false` 阻止下一次 context preparation 的历史 prompt 注入、队列消费和新 projection 写入，但不删除已有派生索引；已经准备或已发出的回合保持冻结；`true` 恢复 reconciliation/backfill。
- Settings 只保留现有“启用记忆”开关，并在描述中说明其同时允许引用相关的历史聊天片段。

#### 6. **FTS 实现**
**实际情况：**
- 原始审查快照中项目还**没有使用 Room FTS**；当前分支已经加入 Room external-content FTS4 原型，但 fresh-database 中文召回仍失败。
- `MessageV2Dao.searchMessagesByContent()` 使用 `LIKE '%query%'`（第24-27行）

**当前 live contract：使用 Room external-content FTS4。** Android system SQLite 不提供可依赖的 FTS5；FTS4 的 `unicode61`/CJK n-gram 行为必须由真实设备 fixture 锁定。下面的 FTS5 DDL 是历史草案，不是当前实现指令，不能复制给 agent。

tokenizer 仍需由真实中文 fixture 决定，下面的 `trigram` 只保留为历史待验证候选，不把它写成已验证结论。

> **Historical-only example:** The following FTS5 DDL and `fts5` test names are retained for audit history. They are superseded by the canonical prompt's FTS4 contract and must not be used in the rewrite.

**完整生命周期 DDL 草案：**
```kotlin
// Migration 19 -> 20 中执行；具体 tokenizer 需在 Android SQLite fixture 上验证。
db.execSQL("""
    CREATE VIRTUAL TABLE IF NOT EXISTS chat_history_fts
    USING fts5(
        user_content,
        assistant_content,
        chat_title,
        content='chat_history_turn_projection',
        content_rowid='projection_id',
        tokenize='trigram'
    )
""")

db.execSQL("""
    CREATE TRIGGER IF NOT EXISTS chat_history_fts_insert
    AFTER INSERT ON chat_history_turn_projection
    BEGIN
        INSERT INTO chat_history_fts(rowid, user_content, assistant_content, chat_title)
        VALUES (new.projection_id, new.user_content, new.assistant_content, new.chat_title);
    END
""")

db.execSQL("""
    CREATE TRIGGER IF NOT EXISTS chat_history_fts_update
    AFTER UPDATE ON chat_history_turn_projection
    BEGIN
        INSERT INTO chat_history_fts(
            chat_history_fts, rowid, user_content, assistant_content, chat_title
        ) VALUES (
            'delete', old.projection_id, old.user_content, old.assistant_content, old.chat_title
        );
        INSERT INTO chat_history_fts(rowid, user_content, assistant_content, chat_title)
        VALUES (new.projection_id, new.user_content, new.assistant_content, new.chat_title);
    END
""")

db.execSQL("""
    CREATE TRIGGER IF NOT EXISTS chat_history_fts_delete
    AFTER DELETE ON chat_history_turn_projection
    BEGIN
        INSERT INTO chat_history_fts(
            chat_history_fts, rowid, user_content, assistant_content, chat_title
        ) VALUES (
            'delete', old.projection_id, old.user_content, old.assistant_content, old.chat_title
        );
    END
""")

// External-content tables are empty until they are rebuilt from existing projections.
db.execSQL("INSERT INTO chat_history_fts(chat_history_fts) VALUES ('rebuild')")
```

**查询合同：**
```sql
SELECT p.*
FROM chat_history_fts fts
JOIN chat_history_turn_projection p ON p.projection_id = fts.rowid
WHERE chat_history_fts MATCH :query
  AND p.eligibility_state = 'eligible'
  AND p.chat_id != :currentChatId
ORDER BY bm25(chat_history_fts)
LIMIT :limit
```

`UPDATE`/`DELETE` trigger、migration 后 `rebuild` 和 projection 的 eligibility filter 都是必需项；仅有 INSERT trigger 不能满足编辑、删除和已有数据回填。
`unicode61` 不是中文分词器，trigram 也必须通过真实 CJK、英文、emoji、短词和性能 fixture 后才能定案。

#### 7. **测试基础设施**
**实际情况：**
- 当前 checkout 统计为 131 个 JVM 测试源文件、24 个 instrumented 测试源文件；按 `*Test(s).kt/java` 文件名计 148 个。该数字随 checkout 变化，不能写死为 124。
- 已有 `MemoryRepositoryTest`, `ChatViewModelRetryTest`, `ChatDatabaseV2MigrationsTest`
- 已有 `HybridMemoryRetrieverTest`, `MemoryRecallRelevanceEvaluationTest`

**改进建议 — 必需的测试文件：**
```kotlin
// 1. ChatHistoryProjectionBuilderTest.kt
@Test
fun `buildProjection excludes blank user messages`()

@Test
fun `buildProjection uses effectiveContent from active revision`()

@Test
fun `buildProjection generates consistent SHA-256 hash`()

// 2. ChatHistoryRetrieverTest.kt
@Test
fun `retrieve excludes current chat by default`()

@Test
fun `retrieve returns empty when history disabled`()

@Test
fun `retrieve respects token budget`()

// 3. ChatHistoryFtsSearchTest.kt
@Test
fun `fts5 search handles CJK queries correctly`()

@Test
fun `fts5 search ranks exact matches higher`()

// 4. ChatHistoryIndexWorkerTest.kt
@Test
fun `worker processes incremental updates idempotently`()

@Test
fun `worker survives process death and resumes`()

// 5. ChatDatabaseV2Migration19To20Test.kt
@Test
fun `migration creates history tables with correct foreign keys`()

@Test
fun `migration creates fts5 virtual table and triggers`()

@Test
fun `migration from v19 with 10000 messages succeeds`()
```

#### 8. **可观测性集成**
**实际代码：**
```kotlin
// MemoryRepositoryImpl 已注入 PromptTraceStore 和 MemoryActivityLogger。
// PromptTraceStore 是有界的进程内 debug trace，不是持久化 metrics/alert backend。
```

**改进建议：**
```kotlin
// 扩展 PromptTraceStore 以记录历史召回
data class HistoryRecallTrace(
    val mode: HistoryRetrievalMode,
    val hitCount: Int,
    val snippetChatIds: List<Int>,
    val queryLength: Int,
    val latencyMs: Long,
    val errorMessage: String? = null
)

fun PromptTraceStore.recordHistoryRecall(
    chatId: Int,
    turnNumber: Int,
    userMessageId: Int?,
    recall: HistoryRecallTrace
) {
    // 实现类似 recordMemoryRecall 的逻辑
}
```

#### 9. **向量索引集成（⚠️ 需要重新设计）**
**实际发现：**
- 项目已有完整的 ONNX embedding 基础设施
- `ProductionMemoryEmbeddingArtifactContract.MODEL_SHA256` 已定义
- 模型大小和发布包增量尚未用当前 artifact/最终 APK 验证，不能把 95MB 推断值当作事实
- ⚠️ **关键限制**：`MemoryVectorStore` 是基于 **snapshot** 的，不支持增量 upsert

**实际接口（基于 MemoryVectorStore.kt）：**
```kotlin
interface MemoryVectorStore {
    fun replaceSnapshot(snapshot: MemoryVectorSnapshot): MemoryVectorPublishResult
    fun query(request: MemoryVectorQuery): MemoryVectorQueryResult
    // 注意：没有 upsert(namespace, id, vector) 方法
}

interface MemoryEmbeddingProvider {
    suspend fun embedDocuments(texts: List<String>): Result<List<FloatArray>>
    suspend fun embedQuery(text: String): Result<FloatArray>
    // 注意：没有 embed(text) 方法
}
```

Older drafts used `embed()` and `upsert(namespace, id, vector)` calls. Those APIs do not exist; the following constraints are the implementation contract and the old call shapes must not be copied into production code.

**当前可确认的技术约束：**

1. `embedDocuments(texts)` 可以批量生成向量，`embedQuery(text)` 可以生成查询向量。
2. `MemoryVectorStore` 只能通过完整 `MemoryVectorSnapshot` 发布，不支持 `upsert`。
3. 当前 `MemoryCorpus` 只有长期记忆和维护工作集；ObjectBox implementation 还拒绝非长期 corpus 和非 `MEMORY.md` source path。
4. 因此不能在本节伪造一个 `CHAT_HISTORY_REFERENCE` enum 加上 `replaceSnapshot()` 就宣称完成。新增 corpus 需要同步 projection policy、snapshot source、identity validation、ObjectBox directory/DI、recovery 和 tests。

**向量阶段的非决策性接口要求：**

- 先定义独立的 `HistoryVectorStore` 或证明泛化后的 store 不会削弱长期记忆校验；不得直接把历史 projection 塞进现有 `MemoryVectorStore`。
- 以 projection 的自然语言文本批量调用 `embedDocuments()`，以独立的 history identity 发布完整 snapshot。
- 编辑、删除、回填和 rebuild 都必须产生新的完整 identity；不能假设单行 upsert。
- 向量发布失败、模型不可用或 snapshot 损坏时，必须回退到 lexical history retrieval，不能影响长期记忆 vector store。
- 这些要求属于 MVP 的实现门槛；在独立 store、模型大小/延迟和真实设备证据完成前，MVP 不能标记为已交付。

#### 10. **隐私边界（已决策）**
MVP 使用现有全局 `memory_enabled` 开关作为唯一隐私边界。项目当前没有“私密聊天”字段；关键词过滤（例如 `private`、`confidential`）不能作为隐私边界。

实施必须遵守：

- 不添加 `ChatRoomV2.excludeFromHistory`、per-chat toggle 或 keyword heuristic。
- `memory_enabled = false` 时对下一次 context preparation fail-closed：不注入历史、不消费历史队列、不写入新 projection；保留已有 projection/FTS/vector/checkpoint 数据。已经准备或已发出的回合保持冻结。
- `memory_enabled = true` 时先执行 stale/missing projection reconciliation，再允许历史召回；中文和英文 Settings 文案必须说明全局范围。

未来若增加 per-chat exclusion，必须另行设计 migration、删除/重建语义和 UI，不得在本 MVP 中隐式加入。

---

## 修订后的实施优先级（⚠️ 待办状态）

> **重要说明**：以下所有 Phase 均为**设计建议**，尚未实施。所有复选框标记为待办（❌），而非已完成（✅）。

### Phase 1: 数据层（预计 2-3天，旧原型阶段记录）
**状态：❌ 旧原型已废弃；必须从头重写并重新验证**

**待办任务：**
- ❌ 定义 `ChatHistoryTurnProjection` entity（使用上述 schema）
- ❌ 创建完整的 FTS5 DDL（包含 INSERT/UPDATE/DELETE 触发器）
- ❌ 实现 `ChatHistoryProjectionBuilder`（使用 `effectiveContent()`）
- ❌ 编写 Room migration 19→20，包含：
  - DDL 创建表和索引
  - 所有 FTS5 触发器
  - Migration 测试
- ❌ 编写数据层单元测试（最少 10 个）

**验收标准：**
- [ ] `ChatHistoryProjectionTest` 所有测试通过
- [ ] 幂等性测试：重复插入相同数据不创建重复行
- [ ] FTS5 中文查询测试通过（至少 3 个真实中文查询）
- [ ] 在真实 Android SQLite fixture 上比较 `trigram` 与候选 tokenizer，覆盖中文短词/连续子串、英文、emoji 和 exact phrase，并把选择写入 migration/test contract
- [ ] Migration 19→20 在空数据库和有数据的数据库上都成功
- [ ] 外键级联删除测试通过（删除 chat 后投影自动删除）

---

### Phase 2: 索引工作流（预计 2天，旧原型阶段记录）
**状态：❌ 旧原型已废弃；依赖新的 Phase 1 合同**

**待办任务：**
- ❌ 实现 `ChatHistoryIndexWorker`（遵循 WorkManager 模式）
- ❌ 在 `ChatRepositoryImpl.saveChat()` 后入队增量索引
- ❌ 实现回填 Worker，包含：
  - 批量处理逻辑
  - 持久化游标
  - 进度跟踪
- ❌ 编写 Worker 测试（进程死亡、重试、幂等性）

**验收标准：**
- [ ] 保存新消息后，投影在 5 秒内创建
- [ ] 回填 1000 条历史消息不阻塞 UI
- [ ] 进程死亡后重启，回填从断点继续
- [ ] 重复运行回填不创建重复投影
- [ ] Worker 失败后正确重试（最多 3 次）

---

### Phase 3: 检索集成（预计 2-3天，旧原型阶段记录）
**状态：❌ 旧原型已废弃；依赖新的 Phase 2 合同**

**待办任务：**
- ❌ 实现 `ChatHistoryRetriever`（FTS5 查询）
- ❌ 定义独立的 `HistoryRecallSnapshot`
- ❌ 在 `PreparedMemoryContext` 中组合（不修改 `TurnRecallSnapshot`）
- ❌ 让现有 `TurnMemoryContextCache` 缓存包含独立 `HistoryRecallSnapshot` 的同一份 `PreparedMemoryContext`；不新增第二套 history cache
- ❌ 编写检索测试（相关性、排除当前聊天、token budget）

**验收标准：**
- [ ] 查询"我的猫叫什么"能检索到 3 个月前的对话
- [ ] 当前聊天的消息不出现在历史检索结果中
- [ ] 历史片段总 token 不超过 400
- [ ] 同一回合的多次重试使用相同的历史快照
- [ ] 历史检索失败不影响聊天完成

**缓存合同：**

- 不新增 ViewModel 级别的裸 `mutableMap`；复用现有 `TurnMemoryContextCache` 的 `Mutex`、有界 eviction 和 per-turn single-flight 行为。
- cache identity is the immutable chat/turn/user-message identity already used by `TurnMemoryContextCache`; do not include a live projection/vector revision or switch revision, because those changes must not alter a context already prepared for an in-flight turn. Store projection/vector identities and retrieval mode inside `HistoryRecallSnapshot`; there is no independent history setting state.
- index revision 变化不能改变已开始 provider/tool/retry turn 的 snapshot；新 turn 才读取新 revision。

---

### Phase 4: UI 和设置（预计 1天，旧原型阶段记录）
**状态：❌ 旧原型已废弃；依赖新的 Phase 3 合同**

**待办任务：**
- ❌ 复用现有 `memory_enabled` 状态，不新增 history preference 或独立 toggle
- ❌ 将 `SettingViewModelV2.updateMemoryEnabled()` 的切换接入 history worker gate、recall gate 和 retained-index reconciliation
- ❌ 更新 Settings 中文和英文说明，明确开启记忆也允许引用相关的历史聊天片段
- ❌ 在 Settings 显示不含原文的索引/回填状态

**验收标准：**
- [ ] 现有“启用记忆”开关同时控制长期记忆和历史引用
- [ ] 偏好缺失时沿用现有 `memory_enabled=false` 默认语义
- [ ] 关闭开关后，下一次 context preparation 不再注入历史，worker 不再写入新 projection，已有 projection/FTS/vector/checkpoint 保留
- [ ] 重新开启后先完成 stale/missing reconciliation，再恢复历史召回
- [ ] MVP 不出现 per-chat exclusion 控件
- [ ] 本地化字符串在中英文环境下正确显示

---

### Phase 5: 向量检索与混合排序（MVP 必需，旧原型阶段记录）
**状态：❌ 旧原型已废弃；依赖新的 Phase 3/4 合同**

**待办任务：**
- ❌ 设计独立的 `HistoryVectorStore` 和 history snapshot identity，不复用长期记忆 corpus 的 namespace
- ❌ 使用 `embedDocuments()` 批量生成 projection embedding，使用 `embedQuery()` 生成查询向量
- ❌ 实现完整 snapshot 发布、编辑/删除/rebuild 的新 identity 和 stale 校验
- ❌ 实现 lexical + vector 的绝对阈值过滤、融合、去重和 chat diversification
- ❌ 添加 embedding 不可用、snapshot 损坏时回退 lexical 的测试

**验收标准：**
- [ ] 改述查询能匹配到原始问题（"猫的名字" 匹配 "宠物叫什么"）
- [ ] 正常路径同时执行 lexical 和 vector 候选检索并完成融合，不把 lexical-only 当作 MVP 成功标准
- [ ] 向量索引损坏时自动回退到词法检索
- [ ] Embedding 失败不阻塞索引工作流
- [ ] 向量检索延迟 < 200ms

---

### Phase 6: 发布验证门槛（预计 1-2天，未开始）
**状态：❌ 未开始（依赖 Phase 1-5）**

**待办任务：**
- ❌ 集成 `PromptTraceStore` 记录历史召回
- ❌ 添加性能监控（查询延迟、索引速度）
- ❌ 在参考设备上进行压力测试（10,000 条消息）
- ❌ 完成集成测试套件
- ❌ 编写迁移回滚计划

**验收标准：**
- [ ] 历史召回可以在 debug 面板中查看
- [ ] P95 查询延迟 < 200ms
- [ ] 10,000 条消息的索引在后台完成且不影响 UI
- [ ] 所有集成测试通过
- [ ] 有明确的回滚步骤文档

---

**粗略估算：Phase 1-5 的词法 + 向量 MVP 路径约 8-13 天；独立 store、模型工件、真实设备验证可能继续增加工期。估算不是交付或发布证据。**

**关键里程碑：**
- [ ] **Milestone 1 (Phase 1-2)**：数据层和索引工作流通过实测 → 才能进入内部测试
- [ ] **Milestone 2 (Phase 3-5)**：词法、向量、快照、UI 和设置通过实测 → 才能评估 Beta
- [ ] **Milestone 3 (Phase 6)**：压力、迁移和运行时证据齐全 → 才能评估正式发布

---

## 关键风险缓解

### 风险 1：Android FTS4 中文分词
**状态：阻塞。** 当前 Android baseline 使用 FTS4；`unicode61` 不提供中文词语分词，而且 fresh-database 的中文召回已经实测失败。必须在真实 Android SQLite 上重新定义 CJK n-gram/query contract，并以 exact phrase、CJK substring、短词、emoji 和英文 fixture 的结果定案。

### 风险 2：迁移大型数据库
**缓解：** 使用 additive、transactional 的 Room migration；把回填放到可重试的 durable worker。不要承诺任意 schema rollback，失败时保留 v19 数据并让历史功能 fail-closed/可禁用。

### 风险 3：嵌入模型未安装
**缓解：** 优雅降级到纯词法，不阻塞功能

### 风险 4：索引与编辑的竞态
**缓解：** 使用稳定 `turn_key` + 唯一索引、content hash/version 校验、事务内 upsert 和 snapshot revision guard；过期投影必须记录 bounded diagnostic 并静默不注入。

---

## 总结：基于项目实际的可行路径

该修订版已经记录了以下约束，但仍需要实现和验证：
1. SHA-256 + UTF-8 + framed fields 的投影 hash 合同
2. Room entity、稳定 `turn_key` 和 ForeignKey CASCADE 设计
3. `effectiveContent()` / canonical assistant 选择规则
4. 独立 history WorkManager queue 和 durable cursor
5. `TurnRecallSnapshot` 保持长期记忆专用，历史使用独立 snapshot
6. 共享 `memory_enabled` 开关、关闭保留索引和重新开启 reconciliation 合同
7. MVP 必需的 history-specific vector store、hybrid fusion 和 lexical fallback
8. 当前 checkout 的测试统计和迁移拓扑
9. `PromptTraceStore` 仅作为 bounded debug trace，不等同于生产 metrics

**这是一份设计审查和实施前置清单，不能作为已实施或可直接发布的证据。**

---

## Live Code Verdict (2026-08-03)

The branch is neither a clean design-only state nor a usable MVP. It contains a large, partially tested prototype. The strongest evidence is mixed: compile and JVM tests pass; migration/trigger tests pass; the fresh Android Chinese retrieval test fails. This means the prototype cannot be promoted by adding another feature slice.

Recommended rewrite boundary:

- Keep the product decisions, long-term-memory separation, global `memory_enabled` semantics, and the rule that Room messages remain canonical.
- Rewrite the history projection/retrieval/worker/vector implementation and its tests from fresh contracts.
- Re-establish the migration strategy only after checking whether schema 20 has shipped. Never downgrade a shipped user database.
- Treat the old history tests as regression clues only, not as proof for the rewrite.

The practical estimate is multiple verified work slices, not one hour. One hour can establish the baseline and reproduce the blocker; it cannot establish semantic retrieval, durable recovery, switch transitions, and device evidence.

## Continuation Evidence (2026-08-04)

The branch-local schema-20 audit is complete: `git branch -a --contains HEAD` and `git tag --contains HEAD` show no release ref outside `codex/chat-history-reference`, and the schema-20 history commits are not present in another containing ref. The migration remains an additive `19 -> 20` boundary for this unpublished feature branch.

Real-device evidence on `emulator-5554` now includes:

- Fresh CJK FTS4 recall with current-chat exclusion.
- FTS4 projection insert/update/delete and projection-driven rebuild synchronization.
- Durable queue persistence across database close/reopen, repeated enqueue coalescing, stale/edit/delete invalidation, disable retention, and re-enable backfill.
- Hybrid lexical/vector hard-negative filtering and duplicate-chat collapse.
- Independent history vector snapshot publication, cache reuse, corruption repair, and lexical fallback.
- Prompt snapshot identity reuse across provider/tool/retry-shaped cache calls.
- Pinned ONNX artifact session/provider inference with 512-dimensional normalized vectors; warm device query samples were 5/6/6 ms (P95 6 ms), document batch 5 ms.
- Real DeepSeek streaming provider response using temporary instrumentation arguments only; no credential was stored in the repository or output.

The FTS4 update failure found by the new fixture was fixed by replacing projection `INSERT OR REPLACE` with a stable-`turn_key` transactional update-or-insert, so Android external-content triggers receive a true update. The full legacy device suite still reports eight failures in existing long-term-memory/ObjectBox/process-death tests; these are not history acceptance evidence and remain open without modifying the long-term memory corpus, `MEMORY.md`, or model routing. OS-level history worker process-kill recovery, long-term-memory byte/index invariance, broad tokenizer/emoji profiling, and full release pressure gates remain open.

## 成熟度评估：当前文档是什么阶段？

### 原始计划 v1（2026-08-01-chat-history-reference-prompt.md）
**成熟度：概念验证（POC）阶段，未达 MVP**
- ❌ 缺少具体实现细节
- ❌ 多个关键决策未定
- ❌ 无法直接编码
- ❌ 需要额外 20-40 小时设计
- **适用于**：架构讨论、可行性评估

### 修订后计划 v2（本文档 + 改进建议部分）
**成熟度：设计审查阶段（产品合同已闭合；本 audit 不是 handoff，也不是 production-ready 证据）**

**为什么当前仍不能称为生产就绪 MVP：**

#### **MVP 核心目标（旧原型存在但已拒绝，必须重写）**
- [ ] 纯词法检索（Android FTS4，DDL/中文 tokenizer 待重新验证）
- [ ] 混合检索（FTS4 + 本地向量，向量失败回退词法）
- [ ] 异步索引和回填
- [ ] 增量更新机制
- [ ] 基本相关度过滤
- [ ] 复用 `memory_enabled` 的 Settings 开关语义、暂停和恢复
- [ ] 与长期记忆隔离的运行时回归

#### **生产就绪门槛（均未验证）**
1. **数据完整性**
    - [ ] 完整的 Room schema（包含稳定键、外键、索引）
    - [ ] Migration 19→20 DDL、schema export 和注册
    - [ ] 哈希一致性验证（SHA-256 + framed UTF-8）
    - [ ] 级联删除、编辑失效和 rebuild 保护

2. **故障恢复**
    - [ ] WorkManager 重试和退避机制
    - [ ] 进程死亡恢复
    - [ ] 索引损坏降级
    - [ ] 稳定 turn key 的幂等性保证

3. **测试覆盖**
    - [ ] 10+ 单元测试规范真正通过
    - [ ] 集成测试场景真正通过
    - [ ] Migration 测试真正通过
    - [ ] 压力测试（10,000 消息）真正完成

4. **可观测性**
    - [ ] PromptTraceStore 集成
    - [ ] 性能监控点
    - [ ] 错误日志规范
    - [ ] 调试追踪

5. **运维能力**
    - [ ] 可重试/可禁用的迁移恢复策略（不是任意 schema rollback）
    - [ ] 优雅降级
    - [ ] 性能基准
    - [ ] 风险缓解证据

#### ⚠️ **可选的后续特性（不影响 MVP 交付）**
- [ ] 高级相关度调优
- [ ] 用户反馈循环
- [ ] 跨设备同步
- [ ] per-chat 隐私排除和高级隐私控制
- [ ] 独立的历史搜索管理界面

### 与行业标准 MVP 定义对比

| 维度 | 典型 MVP | 本计划 v2 | 当前状态 |
|------|----------|-----------|------|
| 核心功能完整 | ✅ | 设计目标 | 未实施 |
| 可编码性 | ⚠️ 部分 | 仍有技术合同待补 | 未闭合 |
| 数据完整性 | ⚠️ 基础 | schema/DDL 待验证 | 未验证 |
| 错误处理 | ⚠️ 基础 | recovery 合同待验证 | 未验证 |
| 测试覆盖 | ⚠️ 核心路径 | 测试计划待执行 | 未验证 |
| 可观测性 | ❌ 通常缺失 | bounded debug trace 设计 | 未验证 |
| 运维准备 | ❌ 通常缺失 | migration/rebuild 策略已写入、尚未验证 | 未验证 |
| **总体成熟度** | **70-75%** | **设计审查稿，产品合同已闭合** | **未达到 production-ready** |

### 准确的阶段定义

```text
POC (概念验证)          ━━━━━━━━━━━━━━━┓
                                       ┃ 原计划 v1 位置
                                       ┃ (60-65%)
                                       ┗━━━━━━━━━━━━━━━┓
                                                       ┃
MVP (最小可行产品)      ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
                                                       ┃
                                                         ┃ 修订后 v2 位置
                                                         ┃ (设计审查，产品合同已闭合)
Production-Ready MVP    ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛ ← 所有实现和运行证据完成后

Full Production (v1.0)  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
(需要：高级调优、A/B测试、用户反馈、跨设备同步和可选的 per-chat 隐私控制)
```

### 明确回答：当前文档完成后是什么阶段？

**答案：仍是设计审查稿，不是生产就绪 MVP。**

只有在 Phase 1-6 的实现、测试、迁移、性能和 Android runtime evidence 全部完成后，才可以单独评估 Beta 或正式发布；本文件本身不能替代这些证据。

### 实施建议

**如果目标是快速验证用户价值：**
→ 实施 Phase 1-5（约 8-13 天），其中 Phase 5 的向量路径必须具备 lexical fallback
→ **结果**：只能进入内部测试/Beta 评估，不能预先承诺可发布

**如果目标是正式产品发布：**
→ 实施所有适用 Phase，并完成真实设备、迁移和回归证据
→ **结果**：通过 release gate 后再决定是否正式发布；不能用估算替代证据

**推荐路径：**
```
Week 1-2: Phase 1-5 (词法 + 向量混合检索 MVP)
         → 内部 Beta 测试
         → 收集用户反馈

Week 3:   Phase 6 发布验证门槛
         → 根据真实设备和 Beta 反馈决定是否继续调优
```

### 关键结论

当前文档（包含本次修正）完成后：
- ✅ 明确了主要风险、接口边界和待办验收项
- ❌ 尚未提供生产代码、迁移 DDL、测试结果或 runtime 证据
- ❌ 不能称为 Production-Ready，也不能作为正式发布批准

**阶段说明：**
- 原计划 v1 = 概念草稿（60-65%）
- 当前 v2 = 带风险清单、已闭合产品合同和验收门槛的设计审查稿
- 实施 handoff = 配对的 `2026-08-01-chat-history-reference-prompt.md`；本 audit 不得交给实施 agent

**下一步是按 canonical prompt 实现并补齐迁移、测试和运行证据；本 audit 不构成实现指令或生产发布依据。**
