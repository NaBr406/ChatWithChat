# ChatWithChat OpenClaw-Style Memory Refactor Plan

> **For agentic workers:** This is an implementation planning document and handoff prompt. Track work with checkbox (`- [ ]`) items. Start with read-only audit, then implement one task at a time. Do not replace the existing Room-backed memory system until the Markdown-backed path is proven, tested, and explicitly selected as the source of truth.

> **Status (2026-07-10):** This is a historical migration plan. The current implementation is superseded by `2026-07-10-five-turn-memory-batching-and-vector-readiness-prompt.md`: recall is local and performs no memory LLM call; completed turns are stored locally and consolidated once per five turns or after 30 minutes idle; `PersonalMemory` and `ChatClassification` are migration-only; the Memory page shows only `MEMORY.md` and export. References below to per-turn `learnFromSavedChat`, Room recall fallback, optional LLM selection, and a maintenance console describe the pre-batching design and must not be reintroduced.

## Goal

将 ChatWithChat 当前的跨会话记忆系统，渐进改造成类似 OpenClaw 的 **Markdown-first** 记忆架构：

- 记忆文件是事实源。
- Room / SQLite / FTS / embedding 只作为可删除重建的派生索引。
- 发送前召回相关记忆并注入 provider prompt。
- 回复完成后把值得保留的内容写入每日记忆或长期记忆。
- 长对话摘要/裁剪前有机会把重要信息写入记忆文件。
- 记忆整理、索引重建、daily note -> `MEMORY.md` 提升等维护任务具备后台调度、自启动补偿和失败修复手段。
- 用户侧只需要查看最终长期记忆。Memory 查看系统只显示 `MEMORY.md` 内容；不提供逐条确认、归档、已解决、删除等记忆状态工作流。

第一阶段不要追求完整 OpenClaw/QMD parity。先做 Android 本地可控、可测试、可回滚的 Markdown 记忆层，再逐步把召回、学习、整理、UI 迁移过去。

## OpenClaw Reference

开工前请联网核对 OpenClaw 官方文档，不要只凭已有印象：

- https://docs.openclaw.ai/concepts/memory
- https://docs.openclaw.ai/concepts/memory-builtin
- https://docs.openclaw.ai/concepts/memory-search
- https://docs.openclaw.ai/concepts/active-memory
- https://docs.openclaw.ai/concepts/compaction
- Optional later: dreaming / QMD 相关文档。

本计划只借鉴这些原则，不照搬服务端路径：

- `MEMORY.md` 存长期、精炼、跨会话应加载的记忆。
- `memory/YYYY-MM-DD.md` 存每日工作记忆、观察、会话摘要和短期事实。
- 搜索索引是派生缓存，可以重建。
- 召回可以从普通搜索开始，之后升级为 active memory 前置召回。
- compaction / 摘要 / 长上下文裁剪前，应先保存值得长期保留的信息。
- 记忆应尽量透明、可读、可 diff、可导出。

## Current State To Preserve

这些行为必须保留，除非某个任务明确迁移它们：

- 设置页总开关控制记忆召回和记忆学习。
- 发送前通过 `ChatViewModel.prepareMemoryPrompt(...)` 准备记忆 prompt。
- 回复完成并保存聊天后，通过 `learnFromSavedChat(...)` 触发后台学习。
- 学习工作运行在 application scope，而不是容易随页面消失取消的 ViewModel scope。
- `MemoryRepositoryImpl` 当前的 classify -> extract -> plan -> create/update/merge/archive 链路仍可作为兼容层或 fallback，但目标 Markdown-first 设计不应继续暴露 archive / resolved 等用户侧状态。
- `LlmMemoryIntelligence` 负责 LLM 语义判断；代码负责存储、安全、去重、索引、失败兜底。
- `MemoryPromptBuilder` 负责把召回结果拼成 provider 可用的 prompt section。
- `ChatRepositoryImpl` 通过 `mergePromptSections(...)` 合并 system prompt、runtime context、memory prompt、conversation summary、web search/tool prompt。
- `searchChatsV2(...)` 只负责本地聊天历史搜索，不参与 prompt-time 长期记忆召回。
- 附件、编辑、重试、导出、多 provider、per-chat model override、tool/web search、token usage 等现有聊天能力不能被记忆改造破坏。

## Non-Goals

- 不一次性删除 `PersonalMemory` / `ChatClassification` / 当前 Memory 页面。
- 不把现有 Room 表继续伪装成最终事实源；长期目标是 Markdown 文件为事实源。
- 不在第一阶段引入 LangChain4j、Koog 或完整 agent 框架。
- 不在第一阶段做本地大模型 embedding 或外部向量库。
- 不要求 Android 直接暴露一个 OpenClaw 风格的自由工作区。MVP 先使用 app 私有目录。
- 不把 Android 自启动当成唯一可靠保障。系统/OEM/用户强制停止都可能阻断后台启动，必须有下次打开 app 时的补偿和手动修复入口。
- 不让模型直接任意编辑文件。LLM 只能输出受限结构，代码负责实际写入。
- 不设计“等待用户确认后才保存”的记忆状态。该 app 的记忆存储目标是本地优先，LLM 可以自主整理、重写和保存；用户不需要参与记忆生成、归档、解决或逐条删除。
- 不把 Memory 页面做成记忆管理台。目标 Memory 页面只显示 `MEMORY.md` 的长期记忆文本，可选提供复制/导出，不暴露 daily note 和逐条状态。
- 不把 `memory/YYYY-MM-DD.md` 变成完整聊天 transcript。它只保存提炼后的记忆和摘要。
- 不新增可见 UI 按钮，除非对应 ViewModel / Repository 行为已经实现。

## Current Code Anchors

开工前仍需重新 `rg`，因为行号和实现细节会随其他分支变化。

- Chat route/state: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt`
- Chat repository contract: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepository.kt`
- Provider dispatch and prompt merge: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt`
- Memory repository contract: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/MemoryRepository.kt`
- Current memory implementation: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/MemoryRepositoryImpl.kt`
- LLM memory intelligence: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/LlmMemoryIntelligence.kt`
- Memory models and prompt builder:
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryModels.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryPromptBuilder.kt`
- Current memory entities / DAOs:
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/entity/PersonalMemory.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/entity/ChatClassification.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/dao/PersonalMemoryDao.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/dao/ChatClassificationDao.kt`
- Database migrations and DI:
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/ChatDatabaseV2.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/ChatDatabaseV2Migrations.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/MemoryRepositoryModule.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/DatabaseModule.kt`
- Settings / UI:
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/datastore/SettingDataSource.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepository.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/memory/MemoryViewModel.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/memory/MemoryScreen.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingScreen.kt`
- Context / compaction-adjacent path:
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/context/ContextBuilder.kt`

## Target Architecture

```text
ChatViewModel.completeChat()
  -> prepareMemoryPrompt(...)
    -> MarkdownMemoryRepository.prepareMemoryContext(...)
      -> MemoryIndexRepository.search(...)
      -> optional LLM selection/rerank
      -> MemoryPromptBuilder builds prompt section
  -> ChatRepositoryImpl.completeChat(..., memoryPrompt)
    -> mergePromptSections(system, runtime, memory, summary, search/tool, extra)
    -> provider request
  -> all provider streams finish
  -> saveChat(...)
  -> learnFromSavedChat(...)
    -> MarkdownMemoryLearningService
      -> LLM extracts controlled memory write proposals
      -> MemoryFileStore appends daily note or rewrites long-term MEMORY.md
      -> MemoryIndexRepository rebuilds affected chunks
```

Core boundaries:

- `MemoryFileStore` owns file paths, UTF-8 IO, atomic writes, backups, and export/import helpers.
- `MarkdownMemoryCodec` owns parsing and rendering stable Markdown sections.
- `MemoryIndexRepository` owns document/chunk indexing and search.
- `MarkdownMemoryRepository` or an evolved `MemoryRepositoryImpl` owns the public repository contract.
- `LlmMemoryIntelligence` remains the semantic layer, but output shapes should become file-write proposals instead of direct Room rows.
- `MemoryPromptBuilder` can stay as the final prompt section formatter.
- `ChatRepositoryImpl` should not need broad provider rewrites.

## Proposed File Layout

MVP should use app-private storage, avoiding runtime storage permissions:

```text
filesDir/
  memory_store/
    MEMORY.md
    memory/
      2026-07-09.md
      2026-07-10.md
    .backups/
      MEMORY.md.20260709-153012.bak
```

Recommended Markdown shape:

```markdown
# ChatWithChat Memory

## Stable Preferences

<!-- memory:id=mem_20260709_153012 type=communication_style sensitivity=normal source=explicit_user_statement -->
- The user prefers natural Chinese explanations with concrete implementation steps.

## Projects

<!-- memory:id=mem_20260709_153244 type=project_context sensitivity=normal source=assistant_inferred -->
- ChatWithChat should preserve attachments, export, edit, retry, multi-provider, and memory flows during UI refactors.
```

Daily note shape:

```markdown
# 2026-07-09

## Conversation Notes

<!-- memory:id=day_20260709_210501 chat=123 sensitivity=normal -->
- User asked to evaluate an OpenClaw-style Markdown-first memory architecture for ChatWithChat.

## Long-Term Updates

<!-- memory:id=long_20260709_210702 sensitivity=private -->
- Long-term update for `MEMORY.md`: ...
```

Use comments or another parseable metadata line sparingly. The visible Markdown must remain useful to a human.

## Recommended Dependency Direction

Use existing stack first:

- Kotlin/JVM file IO under app-private storage.
- Room for index metadata.
- Room FTS4 or a compatible SQLite full-text table for MVP search; do not depend on FTS5 unless verified on target SDK/device.
- kotlinx.serialization for structured LLM proposal DTOs.
- Existing Hilt modules for repository wiring.
- Existing Flow/StateFlow patterns for UI state.
- Existing `MemoryMarkdownCodec` ideas, if still useful for export/preview.

Optional later:

- Provider-based embeddings if search quality is insufficient.
- Hybrid keyword + embedding ranking.
- LLM query expansion / rerank.
- SAF-backed user-chosen memory directory.
- Active memory preflight sub-agent.

## Open Source Reference Projects

Use these projects as references or optional backends. Do not treat any of them as a first-phase Android dependency without a focused feasibility check.

| Project | Link | What to study | Fit for ChatWithChat Android |
| --- | --- | --- | --- |
| OpenClaw | https://github.com/openclaw/openclaw | Markdown memory as source of truth, daily notes, memory search, QMD backend wiring | Good architecture reference; not a drop-in mobile library |
| QMD | https://github.com/tobi/qmd and https://docs.openclaw.ai/concepts/memory-qmd | Local-first sidecar combining BM25, vector search, and reranking over Markdown/docs/transcripts | Good optional desktop/server sidecar; too heavy for first APK-native MVP |
| memsearch | https://github.com/zilliztech/memsearch and https://zilliztech.github.io/memsearch/ | OpenClaw-style Markdown source plus hybrid vector search, Python API/CLI, rebuildable index | Strong backend reference; Python/Milvus path is not suitable for pure local Android MVP |
| Hermes Agent | https://github.com/NousResearch/hermes-agent | Small curated `MEMORY.md` / `USER.md`, FTS5 session search, background review | Good product reference; skip write-approval as a default product behavior |
| Hermes memory providers | https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/memory-providers.md | Provider abstraction: built-in memory remains active, one external provider adds prefetch/sync/extract/tools | Useful shape for future `MemoryBackend` interface |
| Mem0 | https://github.com/mem0ai/mem0 | Semantic memory service, extraction, REST/Python ecosystem | Useful if accepting cloud/self-host backend; not Markdown-first |
| hermes-agentmemory | https://github.com/MukundaKatta/hermes-agentmemory | Pull-model episodic memory and audit trace for Hermes | Useful audit/reference project; do not copy user-facing delete-heavy flows |

Reference conclusions:

- For MVP, implement the local path in Kotlin: Markdown files, Room index, FTS search, controlled LLM writes.
- Borrow QMD/memsearch's principle that Markdown is source of truth and search indexes are rebuildable.
- Borrow Hermes's separation between tiny curated core memory, searchable session history, and background review. Do not copy write approval as a default memory-save gate.
- Borrow provider projects only as interface inspiration. A future `MemoryBackend` should be additive, not replace local Markdown memory by default.
- Avoid Milvus, node-llama-cpp, Python daemons, or cloud-only memory services in the first Android implementation slice.

Evaluation checklist before adopting an external backend:

- [ ] Is it local-first or does it require cloud/user account?
- [ ] Can Markdown remain the source of truth?
- [ ] Can the index be deleted and rebuilt from files?
- [ ] Does it run inside Android, or require a sidecar/server?
- [ ] What are the license and distribution implications for an Android app?
- [ ] Can a simple `MEMORY.md` viewer/export surface provide enough transparency while LLM handles consolidation and removal?
- [ ] Does it support real deletion and auditability?
- [ ] Does failure leave repairable local state?

## Data Source Strategy

Migration should be gradual:

1. **Parallel write:** keep current Room memory behavior and additionally append Markdown daily notes.
2. **Parallel recall:** search Markdown index first, fallback to existing `PersonalMemory` recall if index is empty or disabled.
3. **Markdown-first:** migrate existing active `PersonalMemory` rows into `MEMORY.md`, then rebuild index.
4. **Compatibility:** keep old tables readable for rollback/export until a later cleanup task.

Rollback rule:

- If Markdown recall fails, app should continue normal chat with no memory prompt or current Room fallback.
- Corrupt Markdown must not crash chat completion.
- Index can be wiped and rebuilt from files.

## Memory Maintenance Reliability Strategy

OpenClaw-style memory will introduce maintenance jobs such as:

- Rebuilding Markdown chunk indexes.
- Distilling daily notes into long-term `MEMORY.md` candidates.
- Retrying failed learning writes.
- Repairing malformed metadata.
- Flushing durable notes before long-context summarization/clipping.

These jobs must not rely only on an in-memory coroutine. The app needs a durable maintenance path:

```text
Memory write or maintenance request
  -> persist MemoryMaintenanceJob row
  -> enqueue unique WorkManager work
  -> app startup schedules repair scan
  -> BOOT_COMPLETED receiver schedules repair scan
  -> worker processes idempotent jobs
  -> stale running jobs are moved back to retry
  -> settings/diagnostics surface exposes failed/pending maintenance and manual retry
```

Design rules:

- Use WorkManager for persisted background execution.
- Use a BootCompleted receiver only to schedule WorkManager work; do not run LLM/network work directly in the receiver.
- On every normal app launch, run a lightweight repair scan that re-enqueues pending/stale memory jobs.
- Persist every maintenance unit before starting it, with status, attempt count, last error, startedAt, updatedAt, and an idempotency key.
- Treat process death, app swipe-away, worker cancellation, network failure, token failure, and invalid LLM output as recoverable job outcomes.
- If Android/OEM self-start fails, the next app launch and a settings/diagnostics manual retry must still be able to repair the chain.
- If the user force-stops the app, do not claim guaranteed background execution; rely on next user launch for recovery.
- Do not require disabling battery optimization for MVP. If later needed, present it as optional user guidance, not a hard dependency.
- Memory disabled should stop learning/distillation jobs, but local repair such as index rebuild may still be allowed if it does not create new memories.
- Private/sensitive labels are metadata for display, filtering, and recall caution. They are not a save gate; LLM-generated local memories can be written directly and corrected later by the user.

## Task 0: Current-State Audit

**Goal:** Confirm the repo still matches this plan before editing.

**Commands:**

```powershell
git status --short --branch
rg -n "MemoryRepository|prepareMemoryPrompt|learnFromSavedChat|PersonalMemory|ChatClassification|MemoryPromptBuilder|LlmMemoryIntelligence|mergePromptSections|ContextBuilder|searchChatsV2" app\src
rg -n "memory_enabled|MemoryScreen|MemoryViewModel|MIGRATION_.*memory|personal_memory|chat_classification" app\src
```

**Checklist:**

- [ ] Confirm current memory setting is still read before recall and learning.
- [ ] Confirm `learnFromSavedChat(...)` still uses application scope or an equivalent long-lived scope.
- [ ] Confirm `mergePromptSections(...)` still includes `memoryPrompt` in all provider paths.
- [ ] Confirm existing `PersonalMemory` fields and statuses.
- [ ] Confirm current database version and migration pattern.
- [ ] Confirm current Memory page capabilities: view, edit, confirm, reject, delete, resolve, archive, export.
- [ ] Identify current confirm/reject/delete/resolve/archive UI paths so the target plan can remove or hide them from the user-facing Memory surface.
- [ ] Confirm how to display only `MEMORY.md` in the target Memory page while keeping daily notes internal.
- [ ] Confirm dirty worktree changes are unrelated or understand how to work around them.

**Verification:**

No code changes in this task.

## Task 1: Add Markdown Memory File Store

**Goal:** Create a safe app-private file layer without changing chat behavior.

**Files likely involved:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryFileStore.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryFilePaths.kt`
- Create or update: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/MemoryRepositoryModule.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryFileStoreTest.kt`

**Implementation:**

- [ ] Store files under `context.filesDir/memory_store`.
- [ ] Create `MEMORY.md` lazily with a stable header.
- [ ] Create `memory/YYYY-MM-DD.md` lazily with a stable date header.
- [ ] Read/write UTF-8 explicitly.
- [ ] Add append API for daily notes.
- [ ] Add replace API for `MEMORY.md` with backup.
- [ ] Add export helper that returns a zipped or structured text bundle only if existing export patterns support it; otherwise leave export for Task 5.
- [ ] Use atomic write pattern for full-file replacements.
- [ ] Ensure failed writes return typed failure or `Result.failure`, not a crash in chat flow.

**Acceptance criteria:**

- [ ] First call creates the directory and files.
- [ ] Appending to today preserves existing content.
- [ ] Replacing `MEMORY.md` creates a backup or has a clearly tested rollback path.
- [ ] File APIs are independent from LLM/provider logic.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.memory.MemoryFileStoreTest"
./gradlew.bat :app:compileDebugKotlin
```

## Task 2: Define Markdown Memory Codec And Stable Entry Model

**Goal:** Make Markdown human-readable but still parseable for indexing, LLM/internal rewrites, and migration.

**Files likely involved:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MarkdownMemoryCodec.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MarkdownMemoryModels.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/memory/MarkdownMemoryCodecTest.kt`

**Suggested model:**

```kotlin
data class MarkdownMemoryEntry(
    val id: String,
    val text: String,
    val type: String,
    val sensitivity: String,
    val source: String,
    val chatId: Int? = null,
    val createdAt: Long,
    val updatedAt: Long
)
```

Use the exact fields that fit the repo, but keep enough metadata to preserve current safety behavior.

**Implementation:**

- [ ] Render long-term entries into stable sections.
- [ ] Render daily entries into date files.
- [ ] Parse metadata comments or metadata lines back into entries.
- [ ] Preserve unknown Markdown content instead of deleting it.
- [ ] Mark malformed entries as skipped with diagnostics, not as fatal.
- [ ] Include conversion helpers from existing `PersonalMemory`.

**Acceptance criteria:**

- [ ] Parse/render round trip keeps ids, text, sensitivity, and source.
- [ ] LLM/internal rewrites that add ordinary Markdown do not destroy parseable entries.
- [ ] Private/sensitive entries can be represented with metadata while still being saved directly.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.memory.MarkdownMemoryCodecTest"
./gradlew.bat :app:compileDebugKotlin
```

## Task 3: Add Markdown Memory Index

**Goal:** Build a rebuildable search index from Markdown files.

**Files likely involved:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/entity/MemoryDocument.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/entity/MemoryChunk.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/dao/MemoryIndexDao.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryChunker.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryIndexRepository.kt`
- Modify: `ChatDatabaseV2.kt`
- Modify: `ChatDatabaseV2Migrations.kt`
- Modify: `DatabaseModule.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryIndexRepositoryTest.kt`
- Test: database migration tests.

**Implementation:**

- [ ] Add document rows for `MEMORY.md` and each daily note.
- [ ] Add chunk rows with source path, heading, text, metadata, and timestamps.
- [ ] Add FTS table or compatible query path for text search.
- [ ] Chunk Markdown by headings first, then by character budget if needed.
- [ ] Rebuild index for one file after write.
- [ ] Rebuild all index rows from file store.
- [ ] Search with file-scope and sensitivity-aware filtering. `MEMORY.md` entries are considered live by default.
- [ ] Keep index rebuild idempotent.

**Acceptance criteria:**

- [ ] Index can be dropped and rebuilt from Markdown files.
- [ ] Search returns source path and enough metadata for prompt building.
- [ ] Private/sensitive entries can be handled with display/recall caution without archive/resolved states or a confirmation gate.
- [ ] Empty or corrupt files do not crash search.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.memory.MemoryIndexRepositoryTest"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2MigrationsTest"
./gradlew.bat :app:compileDebugKotlin
```

## Task 4: Wire Markdown Recall Into Existing Prompt Path

**Goal:** Use Markdown index results in `prepareMemoryPrompt(...)` without removing current Room fallback.

**Files likely involved:**

- Modify: `MemoryRepository.kt`
- Modify: `MemoryRepositoryImpl.kt` or introduce `MarkdownMemoryRepository`
- Modify: `MemoryPromptBuilder.kt`
- Add tests in `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/repository/`
- Add tests in `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/memory/`

**Implementation:**

- [ ] Build a recall query from latest user message and compact recent context.
- [ ] Search Markdown index first.
- [ ] Optionally ask `LlmMemoryIntelligence` to select/rerank search results.
- [ ] Use `MemoryPromptBuilder` or a new builder to format selected Markdown memories.
- [ ] Fallback to current `PersonalMemory` recall when Markdown index is empty or disabled.
- [ ] Preserve current behavior when memory setting is disabled.
- [ ] Preserve provider paths by continuing to pass one `memoryPrompt` string to `ChatRepositoryImpl`.

**Acceptance criteria:**

- [ ] Memory disabled means no Markdown recall and no Room recall.
- [ ] Markdown recall prompt merges with system/runtime/summary/search/tool prompts.
- [ ] Search failure does not fail chat completion.
- [ ] Existing `ChatRepositoryImplTest` coverage for merged prompts still passes or is updated.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImplTest"
./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"
./gradlew.bat :app:compileDebugKotlin
```

## Task 5: Add Controlled Markdown Learning Writes

**Goal:** After each completed turn, append controlled memory notes to daily Markdown while keeping current Room learning as fallback.

**Files likely involved:**

- Modify: `MemoryModels.kt`
- Modify: `LlmMemoryIntelligence.kt`
- Modify: `MemoryRepositoryImpl.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MarkdownMemoryLearningModels.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/repository/MemoryLearnerTest.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/memory/LlmMemoryIntelligenceTest.kt`

**Suggested LLM output shape:**

```json
{
  "daily_notes": [
    {
      "text": "short memory note",
      "type": "project_context",
      "sensitivity": "normal",
      "source": "explicit_user_statement",
      "reason": "why this should be remembered"
    }
  ],
  "long_term_updates": [
    {
      "text": "candidate for MEMORY.md",
      "type": "stable_profile",
      "sensitivity": "private",
      "source": "assistant_inferred",
      "reason": "why this may be long-term"
    }
  ]
}
```

**Implementation:**

- [ ] Keep existing classify/extract/plan path intact for compatibility.
- [ ] Add a Markdown-write proposal path that produces daily notes and long-term `MEMORY.md` updates.
- [ ] Persist a maintenance/retry job before any long-running Markdown learning or distillation work begins.
- [ ] Normalize and validate type/source/sensitivity.
- [ ] Deduplicate against existing Markdown entries and current `PersonalMemory`.
- [ ] Append safe daily notes to today file.
- [ ] Allow LLM to write private/sensitive local long-term notes directly while retaining sensitivity metadata for visibility and future recall policy.
- [ ] Rebuild index for affected files after write.
- [ ] Log learning outcome in a way similar to current memory learning result.

**Acceptance criteria:**

- [ ] Learning still runs after completed chat save.
- [ ] Leaving the chat page does not cancel learning.
- [ ] Invalid LLM JSON fails closed.
- [ ] No duplicate notes are appended for multi-provider completions.
- [ ] LLM-created notes are saved directly with type/sensitivity/source metadata, without requiring user approval or exposing per-entry deletion flow.
- [ ] If the app process dies mid-learning, the persisted job can be retried later without duplicating the same note.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.MemoryLearnerTest"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.memory.LlmMemoryIntelligenceTest"
./gradlew.bat :app:compileDebugKotlin
```

## Task 6: Migrate Existing Memories And Simplify Memory Viewer

**Goal:** Show only the final long-term `MEMORY.md` content to users, while keeping daily notes and maintenance details internal.

**Files likely involved:**

- Modify: `MemoryViewModel.kt`
- Modify: `MemoryScreen.kt`
- Modify or create: settings/diagnostics surface for maintenance retry, if no suitable surface exists.
- Modify: `MemoryMarkdownCodec.kt` if reused.
- Add: migration helper from `PersonalMemory` to `MEMORY.md`.
- Add or update UI tests / ViewModel tests if present.

**Implementation:**

- [ ] Add one-way export/migration from active `PersonalMemory` rows into `MEMORY.md`.
- [ ] Replace the target Memory page with a simple `MEMORY.md` viewer.
- [ ] Do not expose daily notes in the normal Memory page.
- [ ] Remove confirm/reject/delete/archive/resolve user flows from the target Memory surface.
- [ ] Optional: provide copy/export for `MEMORY.md` as a whole, not per-entry management.
- [ ] Keep legacy `PersonalMemory` data readable internally until migration is confirmed, but do not make it the target user-facing surface.
- [ ] Add a visible disabled notice when memory is off, matching current behavior, without turning the page into a management queue.

**Acceptance criteria:**

- [ ] User can view the rendered or plain-text `MEMORY.md` content.
- [ ] User is not asked to approve, reject, archive, resolve, or delete individual memory entries.
- [ ] Export/copy, if present, operates on `MEMORY.md` as a whole.
- [ ] Daily notes remain internal and are not exposed as the main Memory viewer.
- [ ] Legacy Room memories are not lost during migration.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"
./gradlew.bat :app:compileDebugKotlin
```

## Task 7: Add Active Memory And Compaction Hooks

**Goal:** Add OpenClaw-like advanced behavior after Markdown recall and learning are stable.

**Files likely involved:**

- Modify: `MemoryRepositoryImpl.kt` or new active-memory service.
- Modify: `ContextBuilder.kt` or the caller that decides when summaries/context clipping happen.
- Add tests around prompt selection and compaction-trigger behavior.

**Implementation:**

- [ ] Add optional active-memory mode that runs a small recall/selection step before main provider request.
- [ ] Keep active-memory disabled by default until tested.
- [ ] Before long-context summarization/clipping, ask the memory layer to save durable facts from trimmed turns.
- [ ] Persist compaction-flush requests as retryable maintenance jobs before starting LLM work.
- [ ] Add guardrails so compaction flush writes controlled local entries with sensitivity metadata instead of a user-confirmation queue.
- [ ] Track metrics/logs for recall count, selected count, and skipped/failure reasons.

**Acceptance criteria:**

- [ ] Active memory can return no memory without failing chat.
- [ ] Active memory does not introduce a visible second assistant answer.
- [ ] Compaction flush writes only controlled Markdown entries.
- [ ] Turning memory off disables active memory and compaction flush.
- [ ] If compaction flush fails, the skipped content is represented as a failed/pending maintenance job that can be retried or dismissed.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.context.*"
./gradlew.bat :app:compileDebugKotlin
```

## Task 8: Add Background Maintenance, Self-Start, And Repair Fallbacks

**Goal:** Ensure memory maintenance can resume after app close, process death, boot, worker failure, or self-start failure.

This task should be completed before enabling automatic daily-note distillation or any unattended memory整理 feature by default.

**Files likely involved:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/entity/MemoryMaintenanceJob.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/dao/MemoryMaintenanceJobDao.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceScheduler.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceWorker.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceRepairer.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/receiver/BootCompletedReceiver.kt`
- Modify: `AndroidManifest.xml`
- Modify: `ChatDatabaseV2.kt`
- Modify: `ChatDatabaseV2Migrations.kt`
- Modify: `DatabaseModule.kt`
- Modify: `MemoryViewModel.kt`
- Modify: `MemoryScreen.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceSchedulerTest.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceRepairerTest.kt`
- Test: database migration tests.

**Suggested job model:**

```kotlin
data class MemoryMaintenanceJob(
    val id: String,
    val type: String,
    val status: String,
    val idempotencyKey: String,
    val payloadJson: String,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val updatedAt: Long,
    val nextRunAt: Long?
)
```

Suggested job types:

- `append_daily_note`
- `rebuild_memory_index`
- `distill_daily_notes`
- `promote_long_term_candidate`
- `repair_markdown_metadata`
- `compaction_flush`

Suggested statuses:

- `pending`
- `running`
- `succeeded`
- `failed_retryable`
- `failed_terminal`
- `dismissed`

**Implementation:**

- [ ] Add a persisted job table with idempotency keys.
- [ ] Add migration for the job table.
- [ ] Add scheduler that writes a job row before enqueueing WorkManager.
- [ ] Use `enqueueUniqueWork(...)` or equivalent unique work policy so the same maintenance family does not stampede.
- [ ] Add Worker that claims pending jobs, marks them running, processes them, and updates status.
- [ ] Mark jobs stuck in `running` beyond a timeout as `failed_retryable` or `pending` during repair scan.
- [ ] Run repair scan on app startup.
- [ ] Add `BOOT_COMPLETED` receiver with `RECEIVE_BOOT_COMPLETED` permission to schedule repair scan after device boot.
- [ ] Make Boot receiver tiny: only enqueue WorkManager, no direct disk-heavy or network-heavy memory work.
- [ ] Add backoff for retryable failures.
- [ ] Ensure network/LLM-dependent jobs respect provider token/model availability.
- [ ] Ensure memory disabled prevents new learning/distillation, while allowing safe local index repair.
- [ ] Add settings/diagnostics status for pending/failed maintenance jobs without replacing the `MEMORY.md` viewer.
- [ ] Add manual retry and dismiss actions for failed jobs.
- [ ] Add logs for job id, type, status, attempts, and failure reason.

**Acceptance criteria:**

- [ ] If the app closes during memory整理, unfinished jobs remain visible and retryable.
- [ ] If WorkManager cannot start immediately, app launch re-enqueues pending jobs.
- [ ] If device boots, boot receiver schedules repair work.
- [ ] If boot/self-start is blocked by OEM policy, next normal app launch still repairs pending jobs.
- [ ] If a job runs twice, idempotency prevents duplicate Markdown entries or duplicate promotions.
- [ ] If LLM output is invalid, the job records a recoverable or terminal failure instead of silently disappearing.
- [ ] User can see failed maintenance in a settings/diagnostics surface and trigger retry.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceSchedulerTest"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceRepairerTest"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2MigrationsTest"
./gradlew.bat :app:compileDebugKotlin
```

## Task 9: Evaluate Optional External Memory Backends

**Goal:** Decide whether QMD, memsearch, Mem0, or another provider should be supported after the local Markdown-first MVP is stable.

This is an evaluation task, not an implementation task. Do not add dependencies or network backends until the tradeoffs are documented and accepted.

**Candidates:**

- QMD as a local desktop/server sidecar for users who run a companion service.
- memsearch as a self-hosted backend for Markdown + hybrid search.
- Mem0 or a Hermes-style provider as semantic memory service.
- No external backend; keep Android-local Room/FTS/embedding only.

**Questions to answer:**

- [ ] What exact user problem is not solved by local Markdown + Room FTS?
- [ ] Does the backend preserve Markdown as the source of truth?
- [ ] Does the backend require a server, cloud account, API key, or companion process?
- [ ] How does the backend handle deletion, privacy, audit logs, and export?
- [ ] How does it behave when offline?
- [ ] How does it recover from partial sync or failed extraction?
- [ ] Can it be disabled without breaking local memory?
- [ ] What Android packaging, battery, network, and security implications does it introduce?

**Expected output:**

- [ ] A comparison table covering QMD, memsearch, Mem0/provider-style services, and local-only.
- [ ] A recommended default.
- [ ] A clear "not now" list for options that are too heavy for mobile MVP.
- [ ] If a backend is recommended, a separate implementation plan with settings, tests, security, and rollback.

**Verification:**

No code changes in this task.

## Suggested First Implementation Slice

If the user says "start implementation", do **only** this slice first:

- [ ] Task 0 audit.
- [ ] Task 1 `MemoryFileStore`.
- [ ] Task 2 minimal `MarkdownMemoryCodec`.
- [ ] Unit tests for file creation, append, parse/render, and UTF-8 handling.
- [ ] No provider path changes.
- [ ] No Memory UI rewrite.
- [ ] No database migration unless Task 2 truly needs it.
- [ ] Do not enable unattended memory整理 until Task 8 has durable maintenance jobs, app-start repair, and manual retry.

This gives rollback safety and validates the file-first foundation before changing recall behavior.

## Copy-Paste Prompt For A Fresh Session

Use this prompt in a new conversation if you want another agent to continue:

```text
你是接手 ChatWithChat Android 项目的资深 Kotlin/Jetpack Compose 工程师。当前仓库是 `E:\code\ChatWithChat`。请阅读 `docs/superpowers/plans/2026-07-09-openclaw-style-memory-prompt.md`，并按该计划帮助我把现有跨会话记忆系统渐进改造成 OpenClaw-style Markdown-first 记忆系统。

请先不要改代码。第一步只做 Task 0 Current-State Audit：

1. 运行：
   - `git status --short --branch`
   - `rg -n "MemoryRepository|prepareMemoryPrompt|learnFromSavedChat|PersonalMemory|ChatClassification|MemoryPromptBuilder|LlmMemoryIntelligence|mergePromptSections|ContextBuilder|searchChatsV2" app\src`
   - `rg -n "memory_enabled|MemoryScreen|MemoryViewModel|MIGRATION_.*memory|personal_memory|chat_classification" app\src`

2. 重新联网核对 OpenClaw 官方 memory 文档：
   - https://docs.openclaw.ai/concepts/memory
   - https://docs.openclaw.ai/concepts/memory-builtin
   - https://docs.openclaw.ai/concepts/memory-search
   - https://docs.openclaw.ai/concepts/active-memory
   - https://docs.openclaw.ai/concepts/compaction

3. 输出：
   - 当前 ChatWithChat 记忆链路图。
   - OpenClaw-style 目标链路图。
   - 和计划文档相比，当前代码是否已有漂移。
   - 记忆整理可靠性方案：WorkManager、自启动、启动补偿、失败保底、手动修复入口。
   - 开源项目参考对照：OpenClaw、QMD、memsearch、Hermes Agent、Hermes memory providers、Mem0 等哪些只适合参考，哪些可作为后续 sidecar/backend。
   - 推荐第一步实现范围。
   - 风险和需要我确认的决策点。

约束：

- 不要一上来替换当前 Room-backed memory。
- 不要破坏附件、编辑、重试、导出、多 provider、per-chat model override、tool/web search 等现有链路。
- 不要把 `searchChatsV2(...)` 当长期记忆搜索。
- 不要用 regex/keyword 规则替代 LLM 语义判断。
- LLM 负责语义，代码负责存储、安全、去重、索引和失败兜底。
- 文件是目标事实源，Room/FTS/embedding 只能是派生索引。
- 不要设计“等待用户确认是否保存”的记忆状态；LLM 可以自主生成、整理、重写并保存本地记忆，用户不参与逐条生成或删除。
- 不要暴露已归档、已解决、待确认、逐条删除等记忆状态；Memory 查看系统只显示 `MEMORY.md` 内容。
- sensitivity/private 只作为元数据和召回/展示策略，不作为保存前审批门槛。
- 记忆整理必须有持久化任务队列、自启动/开机调度、下次打开 app 的补偿扫描，以及设置/诊断入口的手动重试/忽略失败任务保底机制。
- 不要声称 Android 自启动绝对可靠；用户强制停止、OEM 限制、电池策略都可能阻断后台工作，必须设计可恢复路径。
- 不要在第一阶段直接引入 QMD、memsearch、Mem0、Milvus、Python daemon、Node sidecar 或云记忆服务；这些只能在 Task 9 中专门评估后再决定。

等我确认后，再从 Task 1 的 `MemoryFileStore` 和 Task 2 的 `MarkdownMemoryCodec` 开始小步实现。
```
