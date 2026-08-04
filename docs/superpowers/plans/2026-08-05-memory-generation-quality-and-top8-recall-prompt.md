# ChatWithChat Memory Generation Quality And Top8 Recall Prompt

> **CANONICAL IMPLEMENTATION HANDOFF:** Give the implementation agent this file only after it has read `AGENTS.md` and this document completely. The agent must begin with a read-only audit of the live checkout, branch, schema, tests, device state, and dirty files. Do not modify production code until the audit and baseline are recorded. This document is a follow-up to `docs/superpowers/plans/2026-07-28-long-term-memory-consistency-recall-and-tool-token-prompt.md`; where this document changes recall budgets or memory-generation semantics, this document wins.

> **Status (2026-08-05):** Implemented on branch `codex/memory-generation-quality-top8`. JVM, compile, and isolated Core device gates pass; ktlint, the full Core/maintenance device path, and live provider gates remain open because this checkout has no ktlint task/CLI and provider/API execution has not been exercised.

## Goal

Improve the quality of the canonical long-term memory corpus while restoring broad, lossless retrieval of the best query candidates:

1. Ordinary long-term memory query recall returns up to the previous Top8 instead of the current Top3.
2. The long-term query layer has no memory-specific Token budget and must not silently discard a candidate because of `packFor` Token accounting.
3. Core recall has no fixed fact-count or per-layer Token cap. Every eligible active, model-visible Core fact is retained independently of query relevance.
4. Memory generation becomes conservative by default: `ignore` is the default, and a fact enters `long_term` only when it is durable, reusable, and supported by adequate user evidence.
5. Existing low-value, stale, duplicated, or diagnostic entries can be retired recoverably instead of remaining active forever.
6. Project state, conversation history, and temporary debugging observations are not mistaken for general user memory.

The target is a smaller and more useful `MEMORY.md`, not a smaller recall result. Recall breadth and memory-writing quality are separate contracts.

## Binding Product Decisions

### Recall

- `MemoryCorpus.CHAT_RECALL_LONG_TERM` query recall is **up to 8 results**.
- The production candidate pool remains 24 unless live audit proves that it is independently truncating a required Top8 result.
- There is **no long-term query-layer Token budget**. Retrieval and packing must not reject a result because accumulated memory text exceeds 300, 900, or another configured memory budget.
- Keep deterministic deduplication, active/current filtering, safe model-visible text filtering, and internal metadata exclusion. Removing the Token budget does not authorize obsolete, maintenance-only, or malformed entries into the provider prompt.
- Core facts are selected independently of query relevance and are not included in the query Top8 count.
- There is no fixed Core fact-count limit and no Core-only Token budget.
- Core recall must not apply a second hardcoded `coreKeyPriority` allowlist or a general-scope equality filter. The generation contract decides which facts may be labeled `recall=core`; recall includes every active/current, model-visible Core fact that passes privacy and safety validation.
- The provider's own context-window limits remain a provider concern. They must not be implemented as a hidden memory relevance filter. If a provider boundary requires a failure or an explicit truncation diagnostic, preserve Core first and make the result observable.

### Context-aware query construction

- The latest user message is the primary relevance anchor. The bounded `recentContext` from the current conversation is a secondary disambiguation signal for long-term memory recall.
- Build one deterministic context-aware query snapshot per turn and use it consistently for lexical, vector, and hybrid long-term retrieval. A memory whose decisive term appears only in recent context must still be eligible for recall.
- Give the current user message greater relevance weight than recent context. Recent assistant text may resolve references, but it must not dominate the query or introduce assistant-only conclusions as user intent.
- Use recent context to recover project/topic continuity and cross-turn references such as pronouns or omitted subjects. Unrelated recent turns must not pollute the long-term candidate ranking.
- Keep query character/turn bounds separate from the long-term memory result count and Token budget. Bounds constrain query input only; they must not become another hidden Top8 or memory-text clamp.
- History recall and long-term recall should use the same per-turn query snapshot, while retaining separate corpora and diagnostics.

### Memory writing

- A model must return an empty operation list unless a candidate passes the durability and future-utility gates below.
- A single conversation topic, opinion, diagnosis, news item, model price, temporary task, or current implementation status is not automatically a long-term memory.
- Explicit user statements can establish a memory, but explicitness alone does not make a transient fact durable.
- `assistant_inferred` facts must not create durable long-term memory from one isolated observation. They require repeated independent evidence, an existing user-confirmed fact to update, or explicit user confirmation.
- Core is reserved for facts that should apply across nearly every conversation: identity, preferred form of address, assistant name, response language, durable communication style, and hard boundaries. Stable profile or project facts remain query memories unless they truly satisfy the universal-use rule.
- Project facts retain a project scope for identity and maintenance, but scope must not be used as a hard recall exclusion in this follow-up.
- Existing canonical metadata, trust ordering, evidence references, Markdown source of truth, vector index derivation, batch scheduling, daily distillation, and crash-safe mutation protocol remain in force.

## Why This Follow-up Is Needed

The device export `C:\Users\NaBr\Downloads\memory(1).md` contains 23 entries: 19 current entries and 4 obsolete/maintenance-only entries. Only two current entries are Core identity facts. The current entries include:

- overlapping AI post-training interests that repeat the same idea;
- one-off opinions about compliance, model pricing, and data labeling;
- project architecture discussions and detailed recall-debugging state;
- a one-time software-copyright plan;
- an uncertain MBTI result;
- a tool invocation rule that is closer to an application policy than a user memory;
- useful but narrowly scoped Minecraft and ChatWithChat project facts;
- two durable identity facts and one durable communication preference.

The current generation prompts are too permissive:

- `LlmMemoryIntelligence.BATCH_CONSOLIDATION_PROMPT` broadly says to remember interests, recurring themes, life context, and ongoing project context without a strong negative list or repeat-evidence gate.
- `DAILY_DISTILLATION_PROMPT` says “stable” and “useful” but does not define a minimum durability horizon, cross-conversation utility, or treatment of one-off inferred observations.
- `LONG_TERM_CONSOLIDATION_PROMPT` can canonicalize or ignore, but it cannot retire an entry that is individually valid metadata-wise yet not worth keeping in the long-term corpus.
- `MemoryLongTermConsolidationPolicy` currently persists only canonicalization decisions. Merely changing the prompt cannot clean already-written low-value entries.

## Memory Value Contract

The implementation must encode the following contract in both prompts and deterministic validation where practical.

### Required gates for `long_term`

A proposed long-term fact must satisfy all applicable gates:

1. **Future utility:** It is likely to change a response, tool action, or decision in multiple future conversations.
2. **Durability:** It is expected to remain true for weeks or months, or the user explicitly requested that it be remembered indefinitely.
3. **Atomicity:** It expresses one concise fact, preference, boundary, identity value, stable profile fact, or project fact. It is not a transcript summary or a bundle of conclusions.
4. **Evidence:** It is explicitly stated by the user, repeatedly observed across independent user turns, or a correction to an existing fact with reliable evidence.
5. **Non-duplication:** It does not restate an existing active fact under a new key or merely add a complementary detail that does not need independent recall.

### Hard negatives: default `ignore` or daily-only

The model must not create active long-term entries for:

- current task progress, temporary plans, open bugs, test results, thresholds, scores, model dimensions, index generations, or recall diagnostics;
- one-off opinions, speculative conclusions, news, prices, product versions, or reactions to a single event;
- a topic discussed once without a durable preference or repeated interest;
- assistant-generated summaries not confirmed by the user;
- raw conversation summaries, “the user said” wrappers, or implementation logs;
- project state that belongs in chat history or a project work log rather than a durable fact;
- uncertain, explicitly outdated, or time-sensitive profile claims unless represented as a user-confirmed current fact;
- rules about how the application itself must call tools when those rules belong to product/system policy rather than the user profile.

Daily memory may retain a useful transient observation for later evidence, but daily retention is not permission to promote it automatically. If no future value is apparent, use `ignore` even for the daily destination.

### Type and scope guidance

- `identity.preferred_address`, `identity.assistant_name`, response language, durable response style, and hard boundaries may be `core` when confirmed and general.
- Stable education or profile facts are `query` unless the current product explicitly needs them across all topics.
- Project facts must use `project:<slug>` and be concise. A project fact must describe a durable project invariant or active user preference, not a snapshot of current debugging.
- A collaboration preference such as “make minimal code changes and preserve existing behavior” should be represented as a durable preference/boundary with a stable key, not as an `important_event`.
- If a fact has an expiry or is likely to become stale, the implementation must preserve that distinction through existing validity/observation metadata or a new bounded field. Do not refresh it merely because it was recalled.

## Prompt Requirements

### Batch consolidation prompt

Update the behavioral instructions while preserving the existing strict JSON schema and protocol identifiers:

- Start with “default to `ignore`; no durable memory is better than a weak memory.”
- Ask the model to evaluate future utility, durability, evidence, atomicity, and duplication before proposing `create` or `replace`.
- Require explicit user evidence or repeated independent evidence for durable facts. A single assistant inference may update an existing fact only when the evidence is strong and the user has not contradicted it; it must not create a new durable profile from one turn.
- Explicitly list all hard negatives above.
- For each proposed operation, explain in `reason` which gate justified retention.
- Keep one atomic fact per entry, prefer a concise normalized sentence, and replace rather than create neighbors for corrections or progress on the same fact.
- Route transient notes to `daily` only when they may provide evidence for a later durable fact; otherwise return `ignore`.
- Do not let the broad category list (“interests”, “ongoing project context”, and “recurring themes”) override the value gates.

### Daily distillation prompt

Update the prompt to require a stricter promotion decision:

- `ignore` is the default and must be used for one-off observations, project debugging, current task state, and unconfirmed inferences.
- Promote only a stable fact or preference with clear future cross-conversation utility.
- Require repeated evidence for inferred interests or profile claims, unless the user explicitly asked to remember the fact.
- Merge overlapping evidence into one concise active entry; do not emit a new entry merely because wording or evidence changed.
- A daily entry that is useful only as a historical note must stay daily.
- Preserve explicit corrections and user-confirmed boundaries with `replace`.
- Do not return a long-term operation just because the daily evidence is detailed, recent, or technically interesting.

### Whole-corpus consolidation prompt

Extend the controlled decision schema and local policy with a recoverable retirement action:

- `canonicalize`: merge duplicate, corrected, or synonymous representations of one atomic fact.
- `retire`: mark a low-value, stale, transient, diagnostic, superseded, or wrongly classified entry as `obsolete + maintenance_only`, preserving its ID and evidence for audit/recovery.
- `ignore`: make no change when the group is already valid or evidence is insufficient.

Rules for `retire`:

- It must reference IDs from the supplied candidate group; it may retire a singleton.
- It must never delete user data outright.
- It must not be used merely because a fact is project-scoped or not relevant to the current chat. It is for corpus quality, staleness, duplication, or incorrect long-term classification.
- A `retire` reason must identify the hard-negative or quality rule that failed.
- A subsequent active replacement may set `supersededBy` through the existing deterministic mutation policy.

Manual forced consolidation must be able to review every current active entry, not only entries that already have a semantic collision. The existing `forceReview` path may be reused after the audit confirms its coverage.

## Recall Implementation Requirements

The implementation agent must re-audit all layers because the current Top3 limit is duplicated:

- `MemoryRepositoryImpl` production request and post-filter currently use `MAX_QUERY_MEMORIES = 3`.
- `MemoryRetrievalRequest` defaults to `limit = 3` and `tokenBudget = 300`.
- `queryLayerRequest()` clamps the long-term corpus to three facts and a 300-token budget.
- `MemoryPromptBuilder` defaults to three query facts, four Core facts, a 150-token Core budget, and a 500-token rendered prompt cap.
- `TieredMemoryRecall.selectCoreResults()` applies `.take(MAX_CORE_FACTS)`.

Required target behavior:

1. Set the production query result limit to 8 and remove every duplicate Top3 clamp.
2. Represent an unlimited long-term recall budget explicitly, preferably as an optional/null budget or a named unlimited mode rather than an unexplained sentinel integer.
3. Make `packFor` preserve all ranked long-term query results up to the requested Top8 when the budget is unlimited.
4. Remove the Core count and Core Token caps. Select all eligible active Core facts, deduplicate exact text, and keep deterministic ordering.
5. Remove the Core key allowlist and Core scope equality filter from `TieredMemoryRecall`; `recallState=core` is the layer contract, while generation validation prevents arbitrary facts from being promoted into Core.
6. Do not use `scope` as a hard filter in ordinary lexical or vector recall. Keep `scope` in canonical identity, maintenance, and diagnostics.
7. Keep `validity`, `recallState`, metadata safety, privacy policy, and provider preflight protection. “No Token budget” is not “no validation.”
8. Keep the per-turn recall snapshot stable across retries, tool rounds, and multi-provider fan-out.

The implementation must not solve the Top8 change by simply setting one constant while leaving downstream clamps in place.

## Existing Corpus Cleanup

The implementation must not silently rewrite the user's current `MEMORY.md` during ordinary app startup. Use the existing manual/maintenance consolidation path and preserve backups and mutation receipts.

The first cleanup fixture must classify the exported entries as follows:

- Keep as Core: preferred address and assistant name.
- Keep as durable preference/boundary: minimal-change and preserve-existing-behavior collaboration preference, after correcting its type/key if the live policy supports that migration safely.
- Keep as query/project facts: stable Minecraft server configuration and durable ChatWithChat project invariants, with project scope and concise wording.
- Merge or reduce: overlapping AI post-training interest entries.
- Retire or leave daily-only: one-off compliance, pricing, data-labeling, current recall-debugging, software-copyright plan, uncertain MBTI, and obsolete project snapshots.
- Retain obsolete/maintenance-only entries only for audit; prove they do not enter lexical candidates, vector candidates, or provider prompt.

The fixture is a quality regression test, not authorization to hard-code deletion by memory ID.

## Scope Boundary And Non-Goals

- Do not replace `MEMORY.md` with chat history or the new reference-history corpus.
- Do not delete or reset user chats, Room data, vector stores, stashes, worktrees, or unrelated dirty files.
- Do not change provider model selection, embedding model identity, vector dimensions, or schema unless the live audit proves the new retirement/unlimited-budget contract requires a safe additive migration.
- Do not add a user confirmation UI for each memory. LLM semantics remain model-owned; Kotlin remains responsible for identity, trust, bounds, CAS, replay safety, validation, and storage safety.
- Do not make recall depend on a new foreground LLM call or cloud embedding call.
- Do not use a keyword-only allowlist to replace semantic memory judgment.
- Do not treat a green JVM test as device/runtime proof.

## Required Read-Only Baseline

Before editing, record:

- branch, `git status --short --branch`, stashes, worktrees, and untracked files;
- current Room schema and migration state;
- `adb devices -l` and available emulator state;
- current values and call sites for every query/Core limit and Token budget;
- the exact prompt strings and operation validators in `LlmMemoryIntelligence`, `MemoryLongTermConsolidationPolicy`, `MemoryDailyDistillationService`, and `MemoryBatchConsolidationService`;
- current tests for prompt generation, consolidation, recall, metadata filtering, and provider guard behavior;
- a corpus report for the exported fixture and a before-change snapshot of the active/obsolete counts.

Do not claim completion from the plan snapshot or from `memory(1).md` alone. Reproduce the live Android path where possible.

## Implementation Tasks

### Task 0: Audit and freeze the baseline

- [x] Reconfirm the live recall and generation paths, all duplicate clamps, operation schemas, and policy validators.
- [x] Preserve unrelated dirty work and record the baseline counts and prompt outputs.
- [x] Write a focused fixture from the exported corpus using stable synthetic IDs where device data cannot be used directly.

### Task 1: Rewrite generation prompts and add generation-quality tests

- [x] Update batch, daily, and whole-corpus prompts with the value gates and hard negatives.
- [x] Keep JSON keys, enum values, evidence requirements, and fail-closed parsing intact.
- [x] Add tests proving one-off opinions, debug state, model pricing, uncertain profile facts, and assistant-only conclusions produce `ignore` or daily-only results.
- [x] Add tests proving explicit identity, boundary, durable preference, repeated interest, and stable project invariant produce concise controlled operations.
- [x] Add tests for duplicate merging and correction replacement.

### Task 2: Add recoverable corpus retirement

- [x] Extend the long-term consolidation decision model and validator with `retire`.
- [x] Apply retirement through the existing atomic mutation/CAS/backup/receipt path as `obsolete + maintenance_only`.
- [x] Preserve evidence, IDs, and supersession relationships; never hard-delete from the canonical file.
- [x] Ensure forced consolidation reviews all eligible active entries when a cleanup is explicitly requested.
- [x] Add tests for retirement, replay, stale target base, conflict, backup recovery, and no-op behavior.

### Task 3: Restore Top8 and remove long-term Token caps

- [x] Change all production and default query limits to 8.
- [x] Remove the long-term query Token clamp and make unlimited behavior explicit in the request/packer contract.
- [x] Remove the post-retrieval duplicate `.take(3)` and any equivalent hidden clamp.
- [x] Add tests where 8 relevant facts are returned, including facts from different scopes, without a Token-based drop.
- [x] Preserve candidate pool behavior and verify whether 24 candidates are sufficient for the intended Top8 fixture.

### Task 3A: Make long-term recall context-aware

- [x] Build a deterministic query snapshot from the latest user message plus bounded recent context, with the latest user message as the primary signal.
- [x] Feed that snapshot consistently into lexical, vector, and hybrid long-term retrieval without changing the unlimited long-term result-budget contract.
- [x] Preserve separate history and long-term corpora while making their per-turn query snapshots comparable in diagnostics.
- [x] Add a fixture where the decisive term appears only in recent context and assert that the matching long-term memory is recalled.
- [x] Add a fixture where the latest message and recent context together express the intent, then assert the correct memory ranking.
- [x] Add a negative fixture proving unrelated recent context does not displace a directly relevant memory.
- [x] Add coverage proving recent assistant text cannot override the current user's relevance anchor.

### Task 4: Remove Core count and Core Token caps

- [x] Remove `MAX_CORE_FACTS`, `maxCoreFacts`, and the Core-only Token budget from production selection.
- [x] Remove the `coreKeyPriority` and general-scope Core filters; ensure all eligible active Core entries survive selection and are present in `TurnRecallSnapshot` and the rendered model-visible section.
- [x] Preserve safe natural-language projection and exact-text deduplication.
- [x] Add a fixture with more than four Core facts and assert that none are silently discarded.

### Task 5: Remove scope as a hard recall filter

- [x] Remove general-scope equality filters from lexical and vector ordinary recall while retaining scope metadata for canonical identity and maintenance.
- [x] Add cross-scope lexical, vector, and hybrid tests.
- [x] Ensure project-specific facts do not become Core merely because scope filtering was removed.

### Task 6: End-to-end verification and documentation

- [x] Run focused JVM tests for generation prompts, policy validation, retirement, recall packing, prompt rendering, and repository integration.
- [x] Run `:app:compileDebugKotlin`, relevant Android test compilation, and `git diff --check`.
- [ ] Run ktlint on changed Kotlin; no ktlint CLI or Gradle task is present in this checkout.
- [ ] If a device is available, run the real Memory recall/core and maintenance paths, including a fresh cleanup fixture and a Top8 fixture.
- [x] Report compile/test/device evidence separately; do not turn a missing device into a false pass.
- [x] Update the README or other product-facing docs only after the live implementation matches the new Top8/no-query-budget contract. Do not leave the old “24 -> 8, 900 Token” statement if the runtime contract differs.

## Acceptance Criteria

- [x] A relevant long-term query can return 8 active results from the full corpus without a memory Token budget silently removing any of them.
- [x] Core results are independent of query relevance and no longer capped at four facts or 150 Core tokens.
- [x] Obsolete, maintenance-only, unsafe, and retired entries never reach ordinary lexical/vector recall or the provider prompt.
- [x] Generation defaults to no write and rejects the listed hard negatives.
- [x] Repeated/explicit durable facts are concise, atomic, deduplicated, and assigned the correct type, scope, and recall state.
- [x] Existing low-value entries can be retired through a recoverable, replay-safe path without deleting user data.
- [x] Cross-scope recall works without changing canonical identity semantics.
- [x] Long-term lexical, vector, and hybrid recall use the current user message together with bounded recent context, with the current message remaining the primary relevance anchor.
- [x] Context-only and combined-intent fixtures recall the expected long-term memory, while unrelated context does not pollute the Top8.
- [x] The per-turn recall snapshot remains stable across retries, tools, and multiple providers.
- [ ] All required test and runtime evidence is recorded with exact commands and results; provider runtime and a clean real-device Core/maintenance fixture remain open.

## Implementation Evidence (2026-08-05)

- Baseline preservation: branch `codex/memory-generation-quality-top8`; unrelated chat-history edits, untracked files, stash `stash@{0}`, and worktree `E:/code/ChatWithChat-identity-migration` were preserved. `adb devices -l` reports `emulator-5554` (API 35, 16 KB).
- Corpus fixture: 23 entries total, 19 current, 4 obsolete/maintenance-only, and 2 Core; the quality test records 16 current entries after one retirement and two duplicate merges, with 10 ignored candidates.
- Recall contract: production `MemoryRepositoryImpl` sends `limit=8`, `candidateLimit=24`, and `tokenBudget=null`; `MemoryRetrievalRequest` defaults to `limit=8` and `tokenBudget=null`; `packFor` preserves all ranked results up to 8 when unlimited. Core selection has no count/token cap and retains safe active Core facts.
- Generation contract: batch, daily, and whole-corpus prompts default to `ignore`, require future utility/durability/atomicity/evidence/non-duplication gates, reject hard negatives, and preserve strict JSON identifiers. A single `assistant_inferred` observation cannot create durable memory.
- Retirement contract: `retire` is validated against supplied candidate IDs and rendered as `obsolete + maintenance_only` through the existing deterministic mutation path while preserving ID, text, evidence, and replay/CAS safeguards.
- Verification passed: `.\gradlew.bat testDebugUnitTest --no-daemon` (129 XML suites, 1,132 tests, 0 failures, 0 errors); `.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --no-daemon`; focused retirement, prompt, corpus, and recall tests; `git diff --check` and `git diff --cached --check`.
- Task 3A implementation: `MemoryRecallQuerySnapshot` normalizes and bounds the current user section plus recent role-labeled context once per turn, exposes a deterministic snapshot hash, weights the current section above secondary context, and rejects assistant-only lexical anchors. Lexical, vector, and hybrid branches consume that same snapshot while history and long-term retrieval continue using separate corpus paths. Four fixtures cover context-only decisive terms, combined intent ranking, unrelated context, and assistant-only conclusions; the vector fixture verifies the exact snapshot passed to embedding.
- Device evidence: `:app:connectedDebugAndroidTest` passed `MemoryProductionHybridShadowInstrumentedTest` (2/2), `ObjectBoxMemoryVectorStoreInstrumentedTest` (13/13), and the isolated `MemoryRecallCoreInstrumentedTest` (1/1) on `emulator-5554`; the Core fixture now uses `recallState=CORE` and verifies both identity keys in deterministic selector order. The full maintenance-path fixture and Top8 device path remain open. Provider/API runtime was not exercised.
- Remaining verification: changed-Kotlin ktlint is unavailable because no CLI or Gradle task is present. Provider runtime, the full maintenance-path fixture, and the Top8 device path remain open.

## Final Reporting

The implementation agent must report:

- exact files and contracts changed;
- old and new recall limits and where each duplicate clamp was removed;
- old and new generation-prompt rules;
- number of fixture entries kept, merged, retired, and ignored;
- retirement and recovery evidence;
- Top8/no-budget recall evidence;
- Core-over-four evidence;
- scope-crossing evidence;
- compile, unit, lint, diff, device, and provider/runtime gates separately;
- any remaining open gate or unverified behavior.
