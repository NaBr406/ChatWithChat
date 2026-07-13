# ChatWithChat Direct Task 8 / Room Schema 16 Cleanup Prompt

> **交给执行 Agent：** 先完整阅读 `AGENTS.md`、本文档和 `docs/architecture/on-device-vector-memory-readiness.md`，再修改代码。这是已获用户授权的直接实施任务，不是再次评估是否应该等待。必须按本文顺序先切断旧 Room 索引运行路径，再执行 schema 16 迁移；禁止只添加两条 `DROP TABLE` 就宣布完成。

> **用户明确覆盖旧等待条件：** 用户是当前项目唯一使用者，确认现有安装中没有重要数据，不希望继续等待 schema 15 的观察期，并明确要求现在执行 Task 8。本文因此覆盖 `2026-07-12-openclaw-style-vector-memory-maintenance-prompt.md` 中“Task 8 必须等待后续发布/观察期”的时间门禁，但不覆盖业务数据边界、非破坏迁移、真实迁移测试和 Markdown 权威性要求。

> **当前快照：** 本文写于 2026-07-13，代码基线为本地 `main` 的 `6db485ff8dfe7a4e225768260d8564a922aa73f7`（`feat: 启用生产混合记忆召回`），相对 `origin/main` 超前 19 个提交。工作区已有用户改动：`README.md`、`app/build.gradle.kts`、`build-apk.ps1`、`run-on-emulator.ps1`，内容涉及单 ABI 打包、模拟器运行与发布脚本。不得 reset、checkout、覆盖或顺手纳入 Task 8 提交。执行前必须重新核对 live `HEAD` 和工作区。

## Goal

在当前正式 `1.0` 发布前（live `versionName = "0.7.5"`）一次性完成旧 Room Markdown 索引退役：

- Room 从 schema 15 非破坏升级到 schema 16；
- 仅删除 `memory_chunk` 和 `memory_document` 两张可重建派生索引表；
- 删除旧 DAO、entity、repository、DI、job 处理器、DTO、测试 fake 和无生产调用者的调试适配代码；
- 普通召回继续使用已经上线的 `HybridMemoryRetriever`；
- maintenance working set 继续使用当前 Markdown lexical reader；
- ObjectBox 继续只从当前 `MEMORY.md` 重建，不从旧 Room chunk 迁移；
- 模型、向量或 ObjectBox 不可用时继续永久 fail closed 到当前 `MEMORY.md` lexical；
- 保留聊天、消息、平台、pending turns、checkpoints、维护任务、活动日志、mutation receipts、corpus state、daily distillation state 和 Markdown 文件；
- 不为了可能回退继续保留双轨索引和死代码。

Task 8 的完成标准不是“数据库版本号变成 16”，而是生产源码对旧 Room 索引零依赖、真实迁移保留业务数据、启动恢复从 Markdown 重建向量索引，并且 schema 15 的旧 APK 回退风险已经被明确记录和接受。

## Explicit Risk Acceptance

用户明确接受以下残余风险，不得再以这些项目为理由要求等待数天或停止实施：

- 跳过 schema 15 的额外日常使用/soak 等待期；
- 真实 Android backup-agent 云备份恢复尚未验证；
- 真实 ARM64 设备性能与 OEM 环境验证仍是补充项；
- 32 位 ABI 的长期支持策略尚未在本任务内最终统一；
- schema 16 落到真实数据库后，schema 15 APK 不能直接打开该数据库；
- 若升级后出现严重问题，恢复方式是发布 schema 17 前向修复，或由用户主动清应用数据/使用事前快照恢复旧版，而不是直接安装 schema 15 APK 降级；
- 旧 Room 索引内容会永久丢弃，因为它不是权威数据。

用户“没有重要数据”的判断降低的是回滚成本，不是工程正确性标准。以下风险没有被接受：

- migration 或 Room schema validation 失败导致整个 `chat_v2` 数据库打不开；
- 错删聊天、消息、平台、维护状态或 receipt；
- 使用 `fallbackToDestructiveMigration()`、清库、卸载重装代替迁移实现；
- `MEMORY.md` 写入成功后因为派生索引失败而回滚 Markdown；
- 旧 maintenance job 在 schema 16 中继续访问已删除表；
- 跳过 populated migration test，只以干净安装或 SQL 字符串测试宣称通过；
- 为赶进度留下永久 legacy adapter、双写路径或无人使用的兼容层。

## Pre-1.0 Cleanup Policy

当前应用仍为 `0.7.5`，尚未正式发布 `1.0`。本任务采用“保留必要升级历史，删除无用运行时兼容层”的策略：

- 不保留 `MemoryIndexRepository` 作为备用召回或 shadow backend；
- 不保留 `MemoryIndexDao`、Room `MemoryDocument`/`MemoryChunk`、旧 search/rebuild DTO；
- 不保留只包装旧实现的 deprecated class、adapter 或 fake；
- 无生产调用者的 `MarkdownMemoryDebugEditor` 直接删除，不为未来可能使用继续维护；
- 旧 job type 只允许作为 persisted-data 识别符存在，用于升级时 dismiss；不得保留旧 Room rebuild 实现；
- 已被新测试覆盖的旧 `MemoryIndexRepositoryTest` 直接删除，不把它改造成“永远不运行”的历史测试；
- `MemoryChunker` 必须保留，它现在输出 storage-neutral `MemoryCorpusChunk`，仍服务 Markdown lexical 和 ObjectBox；
- `PersonalMemory` 和 `ChatClassification` 不属于本次 Task 8，继续保留其迁移兼容，不借机扩大删表范围；
- `MIGRATION_10_11`、`ensureMemoryIndexTables(...)` 等历史迁移代码继续保留，因为现有旧版本可能沿 `10 -> ... -> 16` 升级。迁移历史不是运行时冗余；不要在本任务中 squash/rewrite 旧 schema 历史。

不要新增通用 backend abstraction、feature flag、双写开关或第二套 scheduler。当前 Hybrid、mutation receipt、bootstrap、repair 和 WorkManager family 已经足够。

## Verified Current State

执行时重新验证以下事实，不要只相信本文行号。

### Schema And Ownership

- `ChatDatabaseV2` 当前 `version = 15`。
- schema 15 已导出为 `app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/15.json`。
- 普通生产 `MemoryRetriever` 已绑定 `HybridMemoryRetriever`。
- 普通召回显式请求 `MemoryRetrievalStrategy.HYBRID` 和 `CHAT_RECALL_LONG_TERM`。
- maintenance reader 单独绑定 `MarkdownLexicalRetriever`。
- `MEMORY.md` 是普通召回唯一权威语料；daily Markdown 仅供维护直到 distillation 提升。
- ObjectBox 位于 `noBackupFilesDir/memory_vector_index`，是可删除派生索引。
- 生产 ONNX provision、Hybrid shadow/cutover 和 16 KB x86_64 emulator 兼容门禁已经记录为 PASSED。

### Remaining Legacy Runtime Dependencies

旧 Room 索引虽已退出普通召回，但仍不是死表。当前生产源码仍有以下依赖：

1. `ChatDatabaseV2.kt`
   - entities 仍包含 `MemoryDocument`、`MemoryChunk`；
   - 仍暴露 `memoryIndexDao()`。

2. `DatabaseModule.kt`
   - 仍提供 `MemoryIndexDao`；
   - migration 只注册到 `MIGRATION_14_15`。

3. `MemoryRepositoryModule.kt`
   - 仍构造 `MemoryIndexRepository`；
   - 仍把它注入 `MarkdownMemoryDebugEditor`；
   - 仍把旧 `MemoryIndexRebuilder` 注入 `MemoryRepositoryImpl`。

4. `MemoryMaintenanceProcessor.kt`
   - 构造器仍强依赖 `MemoryIndexRepository`；
   - `REBUILD_MEMORY_INDEX` 和 `REPAIR_MARKDOWN_METADATA` 仍调用 `rebuildLegacyRoomIndex()`；
   - `rebuildLegacyRoomIndex()` 仍会写 `memory_document`/`memory_chunk`。

5. `MemoryRepositoryImpl.kt`
   - `PersonalMemory -> Markdown` 升级路径追加 Markdown 后仍调用 `MemoryIndexRebuilder.rebuildFile(...)`；
   - 该迁移也会由 Memory 页面触发，不能只依赖 app startup 的下一步碰巧修复。

6. `MarkdownMemoryDebugEditor.kt`
   - 当前只有 DI 和测试引用，没有 UI/业务调用者；
   - 它先修改 `MEMORY.md`，再调用旧 Room rebuild，直接删表会造成“文件已改、调用却报错”的半成功状态。

7. Tests/models
   - `MemoryIndexRepositoryTest` 和多个 processor/worker/repository fake 仍构造旧 DAO/repository；
   - `MemoryModels.selectedMarkdownMemories` 仍引用旧 `MemoryIndexSearchResult`，应在确认无人使用后删除。

Task 8 必须处理以上全部引用。只修改 Room entity/schema 会导致编译、Hilt 或运行时失败。

## Hard Data Boundary

schema 16 必须保留下列 schema 15 表及其行、索引和外键语义：

```text
chats_v2
messages_v2
platform_v2
platform_model_v2
chat_platform_model_v2
personal_memory
chat_classification
memory_maintenance_job
memory_mutation_group
memory_mutation_receipt
memory_corpus_state
memory_distillation_checkpoint
memory_chat_checkpoint
memory_pending_turn
memory_activity_log
```

唯一允许删除的 Room 表：

```text
memory_chunk
memory_document
```

必须先删子表 `memory_chunk`，再删父表 `memory_document`。不得删除或重建整个 `chat_v2` 数据库，不得清空其他表，不得删除 `filesDir/memory_store`、`MEMORY.md`、daily Markdown、backup/staging 文件，也不得把旧 chunk 写入 ObjectBox。

## Target State After Task 8

```text
MEMORY.md (canonical, current bytes)
  |-- MarkdownLexicalRetriever
  |-- MemoryCorpusSnapshotter + MemoryChunker
  `-- MemoryMutationCoordinator / bootstrap / repair
          |
          `-- ObjectBox HNSW (derived, noBackupFilesDir)

Room schema 16
  |-- business chat/message/platform data
  |-- PersonalMemory migration compatibility
  |-- pending turns/checkpoints
  |-- maintenance jobs/activity logs
  |-- mutation groups/receipts/corpus state
  `-- distillation checkpoint

Removed completely
  |-- MemoryIndexRepository
  |-- MemoryIndexDao
  |-- MemoryDocument / Room MemoryChunk
  |-- old Room rebuild/search DTOs
  |-- old repository tests/fakes
  `-- unused MarkdownMemoryDebugEditor
```

Hybrid stale-vector checks and lexical fallback remain unchanged. Removing old Room tables must not add a new fallback branch.

## Legacy Job Migration Contract

schema 15 can contain persisted jobs with these old types:

```text
rebuild_memory_index
repair_markdown_metadata
```

`MIGRATION_15_16` must process them before dropping tables.

For rows whose status is not already `succeeded` or `dismissed`:

- set `status = 'dismissed'`;
- set a deterministic reason such as `schema16_legacy_room_index_removed` in `last_error`;
- clear `blocked_reason`;
- clear `next_run_at`;
- clear `started_at`;
- clear `lease_owner` and `lease_expires_at`;
- increment `row_version`;
- preserve `job_id`, `type`, `payload_json`, `attempts`, timestamps/history fields not explicitly reset, and the row itself.

Do not translate old payloads into `SYNC_VECTOR_INDEX`; old payloads do not contain the generation/receipt/fingerprint identity required by the new synchronizer. Startup already performs:

```text
legacy PersonalMemory -> Markdown migration
-> production embedding provision
-> MemoryVectorIndexBootstrapService.bootstrap(current MEMORY.md)
-> MemoryMaintenanceRepairer.repairAndEnqueue()
```

This existing chain must remain the only recovery source. After migration, ObjectBox missing/stale state is rebuilt from current Markdown and current Room receipt/corpus state.

For defense in depth, a restored/unexpected runnable legacy job must be deterministically dismissed without touching deleted tables. Keep only the minimum persisted identifier recognition needed for this behavior; do not keep `MemoryIndexRepository` or `rebuildLegacyRoomIndex()`.

Modern `SYNC_VECTOR_INDEX`, mutation reconciliation, distillation, turn consolidation and all unrelated job rows must be preserved unchanged.

## Migration Contract

Add `MIGRATION_15_16` in `ChatDatabaseV2Migrations.kt` with this logical order:

```text
1. Dismiss active/retryable/blocked legacy Room-index jobs.
2. DROP TABLE IF EXISTS memory_chunk.
3. DROP TABLE IF EXISTS memory_document.
```

Then:

- set `ChatDatabaseV2.version = 16`;
- remove `MemoryDocument`/`MemoryChunk` from the entity list;
- remove `memoryIndexDao()`;
- remove the `MemoryIndexDao` provider;
- register `MIGRATION_15_16` after `MIGRATION_14_15`;
- export `16.json`;
- verify `16.json` differs from `15.json` only by removing the two legacy tables/their indexes and by the intentionally updated schema identity.

Do not remove historical `MIGRATION_10_11` or its table-creation helper. A device upgrading through `14 -> 15 -> 16` still needs the full chain even though the tables are removed at the end.

No destructive fallback is permitted. If Room validation fails, fix entity/migration parity and rerun tests.

## Runtime Refactor Contract

### Legacy PersonalMemory Migration

Keep `PersonalMemory -> MEMORY.md` migration idempotent, but replace direct file append + `MemoryIndexRebuilder` with the existing local, non-semantic, receipt-driven mutation path using `MemoryMutationCoordinator` (or the narrowest already-existing equivalent).

Requirements:

- no LLM/network call;
- controlled target Markdown remains canonical;
- stable IDs and duplicate prevention remain unchanged;
- a committed file change creates/advances durable generation/receipt state;
- vector sync is scheduled independently;
- index failure never rolls Markdown back;
- startup and Memory page invocation both behave correctly;
- no new generic abstraction solely to mimic the deleted rebuilder.

### Markdown Debug Editor

Current `MarkdownMemoryDebugEditor` has no production caller outside DI. Confirm with `rg`, then delete:

- `MarkdownMemoryDebugEditor.kt`;
- its Hilt provider/imports;
- `MarkdownMemoryDebugEditorTest.kt`;
- test-only fake rebuilders used only by that class.

Do not retain an unused class merely to avoid deleting a test. If a live caller appears after re-audit, route it through `MemoryMutationCoordinator` and test canonical-commit/index-failure semantics instead of preserving the old rebuilder.

### Maintenance Processor

- remove `MemoryIndexRepository` from the constructor;
- remove `rebuildLegacyRoomIndex()`;
- remove old Room index routing;
- retain minimal legacy type recognition only to dismiss unexpected jobs;
- remove old types from notification-heavy policy where they no longer represent runnable heavy work;
- update all processor/worker constructor fakes immediately.

### Dead Code Removal

Delete after proving no remaining caller:

```text
app/src/main/kotlin/.../data/database/dao/MemoryIndexDao.kt
app/src/main/kotlin/.../data/database/entity/MemoryDocument.kt
app/src/main/kotlin/.../data/database/entity/MemoryChunk.kt
app/src/main/kotlin/.../data/memory/MemoryIndexRepository.kt
app/src/test/kotlin/.../data/memory/MemoryIndexRepositoryTest.kt
```

Remove old `MemoryIndexSearcher`, `MemoryIndexRebuilder`, `MemoryIndexSearchRequest`, `MemoryIndexSearchResult`, `MemoryIndexRebuildResult`, `MemoryDocumentScope`, related imports, fakes and unused model fields. Keep `MemoryCorpusChunk`, `MemoryChunker`, `MemoryCorpusSnapshotter`, `MarkdownLexicalRetriever`, Hybrid/ObjectBox types and their tests.

## Direct-Execution Risk Matrix

| Risk | User decision | Mandatory mitigation in this run |
| --- | --- | --- |
| No extra schema 15 soak period | Accepted | Run complete migration/runtime matrix before push |
| Real backup-agent restore remains unverified | Accepted residual risk | Record this honestly; take best-effort local snapshot, do not claim cloud restore passed |
| Real ARM64 performance/OEM validation open | Accepted residual risk | Preserve Hybrid lexical fallback; do not modify native/model behavior |
| 32-bit ABI policy open | Outside Task 8 | Preserve current packaging worktree changes; do not silently add/remove ABI support |
| Schema 15 APK cannot open schema 16 DB | Accepted | Save schema 15 APK/hash and state that recovery is schema 17 forward fix or explicit reset/restore |
| Old Room derived index is lost | Intended | Confirm canonical Markdown exists; never migrate chunks to ObjectBox |
| Migration mismatch blocks whole business DB | Not accepted | Real populated `MigrationTestHelper`, Room reopen, FK/integrity checks |
| Old job accesses deleted table | Not accepted | SQL dismiss + runtime defense-in-depth dismiss test |
| Markdown write reports half-success | Not accepted | Remove old rebuilder; use receipt-driven mutation path |
| Accidental business table/data loss | Not accepted | Schema/table whitelist and per-table sentinel assertions |
| Legacy compatibility code continues to accumulate | Not accepted | Delete old runtime code/tests in same task; keep only required migration history/identifiers |

## Implementation Tasks

Complete every task in this document in the same implementation conversation. No calendar wait or separate soak release is required. Internal ordering remains mandatory.

### Task 0: Baseline, Branch And Recovery Evidence

- [x] Read `AGENTS.md`, this document, the prior vector-memory plan and readiness document.
- [x] Capture `git status --short --branch`, `git rev-parse HEAD`, current schema/version and current remote refs.
- [x] Do not start from `origin/main`; it is behind the Hybrid cutover. Create a dedicated branch/worktree from the current Hybrid `HEAD`/feature ref, suggested `codex/task8-schema16-cleanup`.
- [x] Preserve the dirty `README.md`, `app/build.gradle.kts`, `build-apk.ps1`, and `run-on-emulator.ps1` changes in the source worktree. Do not commit them into Task 8 unless the user separately authorizes it.
- [x] Run current `*Memory*` tests and compile before edits.
- [x] Record schema 15 APK SHA-256 if available, current `MEMORY.md` SHA-256, package/version and current table row counts.
- [x] Attempt a best-effort debug `run-as`/Device Explorer snapshot of `chat_v2` plus WAL/SHM and `memory_store`; inability to obtain it is documented but is not a blocker under the user's explicit risk acceptance.
- [x] State in the branch/plan notes that direct APK downgrade after schema 16 is unsupported.

Do not change schema in this task.

### Task 1: Retire Old Runtime Paths While Still On Schema 15

- [x] Replace `MemoryRepositoryImpl` legacy Markdown migration's rebuilder hook with receipt-driven local mutation/index scheduling.
- [x] Remove `MemoryIndexRepository` from `MemoryMaintenanceProcessor` and delete `rebuildLegacyRoomIndex()`.
- [x] Make unexpected legacy job types dismiss deterministically without Room index access.
- [x] Remove old job types from notification/runnable behavior that assumes actual rebuild work.
- [x] Delete unused `MarkdownMemoryDebugEditor`, its provider and its test after confirming no caller.
- [x] Remove old index provider injections from `MemoryRepositoryModule`, while temporarily leaving Room entities/DAO needed for schema 15 compilation until Task 2.
- [x] Remove stale `selectedMarkdownMemories`/old DTO consumers after static proof.
- [x] Update every test fake/constructor in the same slice.
- [x] Prove foreground Hybrid, maintenance lexical, startup bootstrap, mutation recovery and vector sync behavior are unchanged.

Internal gate before Task 2:

```powershell
rg -n "rebuildLegacyRoomIndex|memoryIndexRebuilder" app/src/main/kotlin
./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"
./gradlew.bat :app:compileDebugKotlin
git diff --check
```

No production call may still require the old DAO/repository before schema changes begin.

Commit this slice independently, suggested message:

```text
refactor: 退役旧 Room 记忆索引运行路径
```

### Task 2: Implement Room Schema 16 And Remove Old Code

- [x] Add `MIGRATION_15_16` with legacy-job dismissal before table drops.
- [x] Drop `memory_chunk` before `memory_document`.
- [x] Change `ChatDatabaseV2` to version 16.
- [x] Remove `MemoryDocument`/`MemoryChunk` entities and `memoryIndexDao()`.
- [x] Remove `MemoryIndexDao` provider/import and register `MIGRATION_15_16`.
- [x] Delete old DAO/entity/repository source files and their dedicated test.
- [x] Remove all obsolete DTOs, fakes, imports and notification entries.
- [x] Keep historical migration creation code intact.
- [x] Generate and add schema `16.json`; ensure no `17.json` is produced.

Static gate:

```powershell
rg -n --glob "*.kt" "MemoryIndexRepository|MemoryIndexDao|MemoryDocument|MemoryIndexRebuilder|MemoryIndexSearchResult" app/src/main/kotlin
rg -n "fallbackToDestructiveMigration" app/src/main
```

The first command must return zero production legacy-symbol matches. `MemoryChunk` must be absent as a Room entity; storage-neutral `MemoryCorpusChunk` and `MemoryChunker` are expected and must remain.

Suggested commit:

```text
feat: 升级 Room 16 并删除旧派生索引表
```

### Task 3: Add Real Migration And Legacy-Job Tests

Extend `ChatDatabaseV2MigrationInstrumentedTest` and focused JVM tests.

- [x] Populated `15 -> 16` migration.
- [x] Supported chained `14 -> 15 -> 16` migration.
- [x] Fresh schema 16 database open/reopen.
- [x] Strict drop-order JVM SQL test.
- [x] Legacy job dismissal processor/startup tests.
- [x] Schema 16 startup with ObjectBox directory absent and current `MEMORY.md` present.

For populated `15 -> 16`:

1. Create schema 15 with `MigrationTestHelper`.
2. Insert sentinel rows into all 17 schema 15 tables, including both old index tables.
3. Include legacy jobs in `pending`, expired `running`, `failed_retryable`, `waiting_repair`, `blocked_dependency`, plus a succeeded legacy row.
4. Include a modern `SYNC_VECTOR_INDEX` job and current mutation/receipt/corpus state.
5. Run `runMigrationsAndValidate(..., 16, true, MIGRATION_15_16)`.
6. Assert the 15 retained tables' sentinel rows/counts are unchanged.
7. Assert `memory_chunk`, `memory_document` and their indexes are absent from `sqlite_master`.
8. Assert active legacy jobs are dismissed with cleared leases/schedule and preserved history.
9. Assert succeeded legacy and modern jobs are not corrupted.
10. Assert `PRAGMA user_version = 16`, `PRAGMA foreign_key_check` returns zero rows and `PRAGMA integrity_check` returns `ok`.
11. Reopen through the real `ChatDatabaseV2` plus registered migrations and read through business, recovery, job, batch and activity-log DAOs.

For `14 -> 15 -> 16`, prove the existing `MIGRATION_14_15` creates recovery state and the new migration then removes only the derived index. Do not require an installed soak period.

Suggested commit:

```text
test: 覆盖 Room 15 到 16 数据保留与启动重建
```

### Task 4: Runtime Upgrade And Recovery Smoke Test

- [x] Build/install a schema 15 APK with representative chats, messages, memory jobs, pending turns and `MEMORY.md` content on an emulator/device.
- [x] Install the schema 16 APK with `adb install -r`; do not uninstall or clear data between versions.
- [x] Cold-launch and verify Room opens without crash.
- [x] Verify representative chat/message/platform data remains visible/readable.
- [x] Verify Memory page displays the same `MEMORY.md` hash/content.
- [x] Verify Hybrid semantic recall still works when the model/ObjectBox is ready.
- [x] Force/remove ObjectBox derived store in an allowed debug harness, restart and prove bootstrap/rebuild from current Markdown.
- [x] Verify lexical recall works throughout missing/rebuild state.
- [x] Verify a stale/deleted memory remains fail closed.
- [x] Verify no legacy job enters retry/notification loop.
- [x] Capture content-free logcat and APK SHA-256.

This runtime test is mandatory even though the user's current data is unimportant. A clean schema 16 install alone is insufficient.

### Task 5: Final Build, Documentation And Push

- [x] Run the complete verification commands below.
- [x] Update `docs/architecture/on-device-vector-memory-readiness.md` to mark Task 8/schema 16 complete and record the explicit user risk acceptance.
- [x] Update the older plan's Task 8 checkbox/status only if doing so does not rewrite historical evidence incorrectly; link to this prompt as the overriding authorization.
- [x] Record that real backup-agent restore, physical ARM64 performance/OEM and 32-bit policy remain residual/non-blocking items, not silently PASSED.
- [x] Record schema 15 downgrade incompatibility and the schema 17/reset recovery choices.
- [x] Verify `16.json` table list against the whitelist.
- [x] Run `git diff --check` and final `git status --short --branch`.
- [x] Commit docs separately, suggested message: `docs: 记录 schema16 风险接受与验证证据`.
- [x] Push the dedicated branch and report the exact remote ref. Do not merge into `main` unless the user explicitly asks.

## Required Verification Commands

Run focused checks after each slice and the full set before completion:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2MigrationsTest"
./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"
./gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2MigrationInstrumentedTest
./gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintVitalRelease
git diff --check
git status --short --branch
```

Also verify statically:

```powershell
Test-Path "app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/16.json"
Test-Path "app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/17.json"
rg -n --glob "*.kt" "MemoryIndexRepository|MemoryIndexDao|MemoryDocument|MemoryIndexRebuilder|MemoryIndexSearchResult" app/src/main/kotlin
rg -n "fallbackToDestructiveMigration" app/src/main
rg -n "memory_chunk|memory_document" app/src/main/kotlin
```

Expected results:

- `16.json` exists;
- `17.json` does not exist;
- old production symbols are absent;
- `fallbackToDestructiveMigration` is absent;
- `memory_chunk`/`memory_document` appear only in historical migration creation SQL, `MIGRATION_15_16` drop SQL, migration tests, schema 10-15 history and explanatory docs;
- no runtime repository/DAO/entity references remain.

Run the existing Hybrid/16 KB scripts only if their prerequisites are available and they do not require modifying the user's dirty packaging files. Native/model behavior is not changed by Task 8, so inability to repeat a physical ARM64 performance test is not a blocker. Release R8 and schema-upgrade runtime smoke remain required.

## Required Test Matrix

| Scenario | Required result |
| --- | --- |
| Populated schema 15 upgrade | Schema 16 opens; only two derived tables disappear |
| Direct supported schema 14 chain | `14 -> 15 -> 16` succeeds with business data retained |
| Fresh schema 16 | Opens/reopens with exactly the 15 retained tables |
| Legacy pending/retryable/blocked index job | Dismissed; leases/deadline cleared; no old SQL |
| Legacy succeeded job | Preserved as historical row |
| Modern vector/mutation/distillation jobs | Preserved unchanged |
| Foreign keys | `foreign_key_check` returns zero rows |
| Database integrity | `integrity_check` returns `ok` |
| PersonalMemory migration | Commits idempotent Markdown through receipt path; no old rebuilder |
| Memory page migration trigger | Current Markdown visible; vector sync independent |
| ObjectBox absent after upgrade | Rebuilt from current `MEMORY.md`, never from Room chunks |
| Embedding unavailable | Current Markdown lexical recall works |
| Stale/deleted vector | Rejected before prompt injection |
| Process restart | No legacy job retry loop; Hybrid/lexical remain usable |
| Schema 15 APK downgrade attempt | Documented unsupported; not presented as a valid rollback |
| Business data whitelist | Every retained table sentinel survives |
| Existing dirty packaging files | Unchanged and not included in Task 8 commits |

## Execution Result (2026-07-13 to 2026-07-14)

- Baseline: `6db485ff8dfe7a4e225768260d8564a922aa73f7`.
- Branch: `codex/task8-schema16-cleanup`.
- Verified slice commits before this documentation update:
  `d616430` (retire legacy runtime), `8321729` (schema 16 migration/cleanup),
  and `66443a9` (real migration/startup tests).
- Pre-existing `README.md`, `app/build.gradle.kts`, `build-apk.ps1`, and
  `run-on-emulator.ps1` changes remained unstaged and uncommitted. Their
  SHA-256 values remained unchanged throughout this run.

### Schema And Test Evidence

- Schema comparison: 17 entities became 15 by removing only
  `memory_chunk`/`memory_document`; all 15 retained entity objects are exact
  matches (185 fields, 37 indexes, 6 foreign keys). `16.json` SHA-256 is
  `E2FC5D089F3DD6B29F55C51EC2387BD151329D068B63C8FD1672597DA71FCF67`;
  `17.json` is absent.
- The focused migration JVM class passed 17 tests. The `*Memory*` filter
  passed 267 tests with no failures/errors. Kotlin and AndroidTest Kotlin
  compilation passed.
- `ChatDatabaseV2MigrationInstrumentedTest` passed all 3 tests both through
  direct instrumentation and Gradle `connectedDebugAndroidTest` on
  `emulator-5560` (API 35 x86_64, `PAGE_SIZE=16384`). Coverage includes
  populated `15 -> 16`, chained `14 -> 15 -> 16`, fresh 16 open/reopen, exact
  snapshots for the 14 untouched retained tables, contract-field assertions
  for maintenance jobs, legacy-job dismissal followed by startup repair, DAO
  reads, foreign keys, and integrity.
- `MemorySchema16StartupRecoveryInstrumentedTest` passed with the current
  Markdown bytes/hash unchanged, ObjectBox initially absent, corpus rebuilt
  READY, and the store closed/reopened with its sentinel queryable.
- `MemoryProductionHybridShadowInstrumentedTest` passed with real production
  ONNX artifacts and ObjectBox: semantic paraphrase retrieval, unavailable
  model lexical equivalence, stale-store rejection, and deleted-entry
  exclusion before and after rebuild.
- `assembleDebug`, `assembleRelease` including R8, and `lintVitalRelease`
  passed. The release unsigned APK is 153,883,230 bytes with SHA-256
  `FB9908F9138E9706F2C97AD78399C748AA929C8592DF97242A1910244822CA4D`.

### In-Place Upgrade Evidence

- The saved schema 15 debug APK SHA-256 is
  `E3D908B564B34825CD59302CE39AAA15B9A234AFEB51D2A2C994AA111F163C4A`;
  the schema 16 debug APK SHA-256 is
  `CA55E22A14DF4001B288E9ED20F9D5A6C083ED767EBA80B90CFB30A85395442E`.
- After the one allowed pre-test reset, a populated schema 15 database and
  Markdown store were installed. Schema 16 was then applied with
  `adb install -r`; no uninstall, clear-data, or database replacement occurred
  between versions.
- Room opened at version 16 with `integrity_check=ok`, zero foreign-key rows,
  and no old tables/indexes. The chat, message, provider, pending turn, modern
  job, and legacy-job history remained readable. Both legacy jobs were
  dismissed with the schema 16 reason and stable attempts/row versions across
  repeated starts.
- `MEMORY.md` SHA-256 remained
  `633EA02D15E3651DD099974B3CE0D74BE26ED6A51742F4AE13F8F5E42982F7AF`.
  The Memory page displayed the same canonical content.
- Deleting only `noBackupFilesDir/memory_vector_index` while stopped made the
  derived store absent. The next production cold start recreated ObjectBox by
  the second poll and returned the same corpus/hash to READY. The old Room-only
  chunk was not copied.
- The initial UI fixture accidentally labeled its sole message as an assistant
  without a preceding user message, violating the established chat ordering
  invariant. The fixture was corrected to a user message with the same ID and
  content; the migrated chat/message/provider/Memory UI rerun then passed with
  no `AndroidRuntime` error. This did not require a production-code change.
- There were zero legacy activity rows and zero active app notifications after
  repeated startup/rebuild checks. Content-free logs, APKs, database snapshots,
  hashes, XML dumps, and screenshots are outside the repository under
  `E:\code\ChatWithChat-task8-evidence\runtime-upgrade`.

### Recovery Boundary

The skipped schema 15 soak, physical ARM64/OEM performance run, 32-bit ABI
policy, and real backup-agent/cloud restore are accepted non-blocking residual
items, not passing claims. A schema 15 APK cannot directly open a schema 16
database. Recovery is a schema 17 forward fix or an explicit user-directed
reset/pre-upgrade snapshot restore; no destructive automatic fallback exists.

## Non-Goals

- Do not revisit ObjectBox, ONNX model, tokenizer, RRF/MMR or Hybrid ranking design.
- Do not disable Hybrid DI or remove lexical fallback.
- Do not add another vector backend or cloud embedding.
- Do not change normal Memory UI, chat UI, attachments, tools/search, provider flows or token accounting.
- Do not remove `PersonalMemory` or `ChatClassification`.
- Do not implement real cloud backup UX/policy in this task.
- Do not decide 32-bit ABI support in this task or absorb the dirty ABI packaging changes.
- Do not wait for a multi-day soak or physical ARM64 performance run.
- Do not keep old Room index code for hypothetical rollback.
- Do not squash historical Room migrations.
- Do not use destructive migration, automatic clear-data recovery or silent backup restore claims.
- Do not merge to `main` without explicit user direction.

## Stop Conditions

Do not stop merely because the old plan said Task 8 was deferred; this document is the user's explicit override.

Stop and report a blocker only if one of these remains after reasonable fixes:

- populated `15 -> 16` or `14 -> 15 -> 16` real migration cannot pass;
- Room cannot reopen schema 16 through the actual database builder;
- any business/operational table or sentinel is lost;
- `foreign_key_check` or `integrity_check` fails;
- production code still needs the old DAO/repository after the refactor;
- Hybrid/lexical recall or startup bootstrap regresses;
- completing the task would require resetting/discarding the user's dirty worktree changes.

Do not treat missing important user data, unavailable physical ARM64 hardware, unverified cloud backup restore or the absence of a soak period as blockers under this authorization.

## Completion Report

The final implementation report must include:

```text
Baseline and branch:
- baseline commit:
- implementation branch/worktree:
- pre-existing dirty files preserved:

User override and residual risk:
- schema 15 soak skipped:
- backup-agent restore status:
- physical ARM64 status:
- 32-bit ABI status:
- downgrade/recovery statement:

Deleted legacy runtime:
- files/classes/providers removed:
- old job behavior removed/replaced:
- static zero-reference evidence:

Schema 16:
- MIGRATION_15_16 SQL behavior:
- legacy job transition behavior:
- dropped tables:
- retained table whitelist evidence:
- 16.json SHA-256/table diff:

Migration proof:
- populated 15 -> 16:
- chained 14 -> 15 -> 16:
- fresh 16:
- Room reopen:
- foreign_key_check:
- integrity_check:

Runtime proof:
- schema 15 APK/hash:
- schema 16 APK/hash:
- adb install -r upgrade result:
- business data/Markdown hash retention:
- ObjectBox delete/rebuild result:
- Hybrid and lexical fallback result:
- legacy job loop check:

Tests/builds:
- exact commands and results:
- device/emulator used:
- intentionally unverified residual items:

Commits and push:
- per-slice commit hashes:
- remote branch/ref:
```

Do not call Task 8 complete while old production symbols remain, while only a clean install was tested, while `16.json` is absent, or while migration tests do not inspect retained business/operational rows.

## Copy-Paste Prompt For A Fresh Implementation Conversation

```text
Work in E:\code\ChatWithChat.

Read E:\code\ChatWithChat\AGENTS.md and then read this execution prompt completely:
E:\code\ChatWithChat\docs\superpowers\plans\2026-07-13-direct-task8-schema16-memory-index-cleanup-prompt.md

Implement the full prompt now. The user is the only current user, confirms there is no important installed data, does not want to wait for a schema 15 soak period, and explicitly overrides the older Task 8 deferral. The project is still pre-1.0 (currently versionName 0.7.5), so remove obsolete legacy runtime code instead of accumulating dual index paths and compatibility adapters.

This authorization does not permit destructive migration. Preserve the business Room database, all tables listed in the prompt, MEMORY.md, daily Markdown, mutation receipts, corpus state, pending turns, checkpoints, jobs and logs. Drop only memory_chunk and memory_document, in that order, after all production dependencies have been removed.

Start from the current Hybrid cutover HEAD, not the stale origin/main. Preserve the source worktree's existing README.md, app/build.gradle.kts, build-apk.ps1 and run-on-emulator.ps1 modifications without resetting or including them in Task 8 commits. Create a dedicated branch/worktree first.

Execute in order:
1. While still on schema 15, replace every old MemoryIndexRepository/MemoryIndexRebuilder path with the existing receipt/bootstrap/vector recovery path and delete unused runtime adapters.
2. Add MIGRATION_15_16, dismiss persisted legacy index jobs, drop memory_chunk before memory_document, move ChatDatabaseV2 to 16, remove old entities/DAO/repository/DI/tests, and export 16.json.
3. Add real populated 15->16, chained 14->15->16, fresh-16, legacy-job and startup-rebuild tests with table sentinels, Room reopen, foreign_key_check and integrity_check.
4. Install schema 16 over a populated schema 15 APK using adb install -r, then verify business data, MEMORY.md hash, ObjectBox rebuild, Hybrid recall and lexical fallback.
5. Run every required test/build/static command, commit each slice separately, update readiness docs, push the branch and provide the completion report.

Do not wait for multiple days, physical ARM64 performance/OEM validation, 32-bit policy or real cloud backup-agent restore. Record them as accepted residual risks. Schema 16 downgrade to schema 15 is unsupported; failure recovery is a schema 17 forward fix or an explicit user-driven reset/snapshot restore.

Do not stop at another plan or partial refactor. Continue through implementation, migration verification, runtime upgrade smoke, commits and push unless a Stop Condition in the document is actually met.
```
