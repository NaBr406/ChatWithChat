# ChatWithChat Memory Consistency Risk Closure Prompt (Schema 17)

> **用途：** 本文是一份可直接交给新的实现 Agent 执行的仓库提示词。它以当前代码为准，关闭 2026-07-14 记忆系统审计中确认的功能一致性风险。不要只做分析或再写一份计划；执行 Agent 应按本文完成实现、验证、分片提交和推送。

> **当前快照：** 本文写于 2026-07-14。本地 `main` 为 `26b998dff26526e7e6b77a3dba1dd3151c15648e`，相对 `origin/main` 超前 25 个提交；`versionName = "0.7.5"`、`versionCode = 21`、Room schema = 16。工作区已有大量与本任务无关的聊天 UI、导航、设置和多语言改动。当前 `adb devices` 没有连接设备。执行时必须重新核对这些事实，不得盲信快照和旧行号。

## Goal

在正式 `1.0` 发布前，以尽量少的永久代码完成以下风险闭环：

1. Room 从 schema 16 非破坏升级到 schema 17，删除已经退出权威链路的 `personal_memory` 和 `chat_classification` 两张 legacy 语义表及其全部运行时代码。
2. 五轮记忆整理不再向同一 canonical 目标文件写入规范化文本完全相同的 `CREATE`；历史重复项在 lexical 和 Hybrid 的最终 prompt 注入边界只出现一次。
3. Memory 页面在后台提交新的 `MEMORY.md` 后自动刷新；导出永远重新读取当前 canonical 文件，而不是导出陈旧 UI 缓存。
4. mutation receipt 遇到确定不可恢复的 staging 缺失、非法或哈希不符时进入持久化终态，不再在每次启动时无限修复；瞬时 I/O 错误仍保持可重试。
5. Android backup 规则明确排除 `memory_store/.staging/` 和 `memory_store/.backups/`，但不借机改变数据库、token、DataStore、SharedPreferences 或整套备份产品政策。
6. 保持当前普通 Hybrid 召回、maintenance lexical reader、ONNX、ObjectBox、RRF/MMR、五轮批处理、receipt/corpus/index 同步和 provider prompt 合并语义不变。

完成标准不是“编译通过”或“版本号变成 17”，而是上述五类真实功能缺口均有自动化证据，schema 16 到 17 的 populated 升级保留全部业务/维护数据，当前 `MEMORY.md` 字节不被迁移改写，普通召回仍能从 Markdown lexical 或新鲜 Hybrid 路径得到结果。

## User Authorization And Risk Acceptance

用户已明确以下事实和授权：

- 当前只有一个使用者。
- 当前没有需要保留的重要 legacy Room 记忆数据。
- 应用仍在正式 `1.0` 之前，优先删除已经无业务价值的过渡代码，不继续堆叠 adapter、marker、双写或下一版本再清理的任务。
- 明确接受 schema 17 升级时删除尚未进入 `MEMORY.md` 的 `PersonalMemory` 行；不要为了挽救这些已接受丢失的数据再运行 importer。
- 明确接受 schema 17 数据库不能由 schema 16 APK 直接打开。回退方式是 schema 18 前向修复，或用户明确选择清数据/恢复事前快照，不是静默安装旧 APK。
- 真实 ARM64 设备性能/OEM 验证和 32 位 ABI 决策仍是独立补充项，不阻塞本任务。
- 真实 cloud backup restore 没有验证时必须继续标记 `OPEN`，不能以本地文件复制或 LocalTransport 冒充云恢复通过。

以上授权不等于允许破坏其他数据。以下风险没有被接受：

- destructive migration、`fallbackToDestructiveMigration()`、自动清库、卸载重装代替升级；
- 丢失聊天、消息、平台、pending turns、checkpoint、维护 job、activity log、mutation receipt/group、corpus state 或 distillation state；
- schema 迁移修改、导入或重写 `MEMORY.md`/daily Markdown；
- 为解决 receipt 卡死而把普通 `IOException`、权限问题或瞬时文件错误一律标成 terminal；
- canonical 已是 receipt target 时，因为 staging 已清理而错误终止；
- canonical 仍是 base 且 staging 不可恢复时继续无限排 repair；
- 将 stale/corrupt/missing vector 结果注入 prompt，或取消 lexical fallback；
- 把当前工作区的无关 UI/多语言改动纳入本任务提交；
- 用新通用框架、第二套缓存、filesystem watcher、轮询器或额外 Room 表掩盖局部问题。

## Pre-1.0 Cleanup Policy

本任务采用“保留升级历史，删除运行时遗留”的原则：

- 保留 `MIGRATION_4_5` 到 `MIGRATION_15_16` 的完整历史链和旧版本升级所需 SQL helper。
- 不新增 `4 -> 17`、`14 -> 17` 或 `15 -> 17` 快捷迁移；旧数据库按完整链逐级升级，最终由 `16 -> 17` 删除两张 legacy 表。
- 不新增“一次性导入完成”SharedPreferences、DataStore flag、migration marker 表、桥接表或 legacy adapter。
- 不保留已删除 entity/DAO 的 deprecated 壳、空实现或仅供旧测试构造的 fake。
- `MemoryMarkdownCodec` 是旧 `PersonalMemory` 导出器，应删除；当前 canonical 解析器 `MarkdownMemoryCodec` 必须保留。
- `MemoryModels.kt` 只删除静态确认无生产调用者的旧 classification/selection/extraction/update DTO 和转换函数；保留当前召回、批处理或 provider 路径仍使用的类型。
- 不批量清洗、重写或删除 `MEMORY.md` 中已经存在的历史重复项。本任务阻止新增，并在召回出口防御性去重。

## Verified Current State

执行时先用 `rg` 重新核对，不要只相信以下行号。

### Canonical And Recall Path

- `MEMORY.md` 位于 app-private `filesDir/memory_store`，是用户可见和普通召回的 canonical 长期记忆。
- 普通 `MemoryRetriever` 已由 `MemoryRepositoryModule` 绑定为 `HybridMemoryRetriever`；`MemoryRepositoryImpl.prepareMemoryContext(...)` 显式请求 `HYBRID`。
- maintenance working set 继续使用独立的 `MarkdownLexicalRetriever`，不得切到 Hybrid。
- embedding/model/ObjectBox 缺失、损坏、陈旧、identity 不匹配或校验失败时，普通召回永久 fail closed 到当前 Markdown lexical；没有 cloud embedding fallback。
- schema 16/Task 8、生产 embedding、Hybrid cutover 和 API 35 x86_64 16 KB page-size 门禁已记录为 `PASSED`。真实 ARM64/OEM、32 位 ABI 和真实 backup-agent restore 仍为 `OPEN`。

### Legacy Room Semantics Still Present

- `ChatDatabaseV2.kt` 的 schema 16 entities 仍包含 `PersonalMemory`、`ChatClassification`，并暴露两个 DAO。
- `DatabaseModule.kt` 仍提供 `PersonalMemoryDao` 和 `ChatClassificationDao`。
- `MemoryRepositoryImpl.kt` 仍注入 `PersonalMemoryDao`，并实现 `migrateActiveMemoriesToMarkdown()`。
- importer 会扫描 legacy active rows，但成功后不删除或改变旧行，因此它仍是“已从用户可见 Markdown 删除的偏好重新出现”的来源之一。
- importer 由 `MemoryMaintenanceStartupCoordinator` 和 `MemoryViewModel` 触发。
- `MemoryMarkdownCodec.kt`、`PersonalMemory.toMarkdownMemoryEntry()`、旧 DTO 和 `MemoryTestFakes` 仍把 legacy 模型留在编译图中。
- `ChatClassificationDao` 没有实际生产业务调用，仅被 schema/DI/测试保留。

### Exact Duplicate Gap

- `MemoryBatchConsolidationService.validateOperations()` 只要求相同规范化文本写向同一 destination，没有拒绝同批重复 `CREATE`。
- `renderOperations()` 目前只用确定性 generated ID 防重放，没有在生成 ID 前比较目标文件中已有条目的规范化文本。
- 已存在的 `MemoryDailyDistillationOperationController` 使用 `normalizedWriteTexts` 和 existing-text 校验，可复用其严格校验思路。
- `MemoryRetrieval.packFor()` 在 token budget 前没有按规范化文本去重。
- lexical 和 Hybrid 目前主要按 entry ID/content hash 去重；不同 ID、相同文本仍可能重复占 candidate limit、token budget 和最终 prompt。

### Memory Page Staleness

- `MemoryViewModel` 在 `init` 调用一次 `loadMemories()`；`MemoryScreen` 又用 `LaunchedEffect(Unit)` 调用一次，形成双重加载。
- 页面没有观察 canonical `MEMORY.md` 的提交信号，后台五轮整理后可一直显示旧内容。
- `exportMarkdown()` 优先使用非空 `_uiState.markdown`，因此可以导出旧内容。
- `MemoryFileStore` 已有 singleton 内的 long-term/maintenance revision，并在 canonical long-term 写入的 `advanceRevisionFor()` 中推进；它是最小的进程内刷新信号，不需要新增 Room generation、文件 watcher 或轮询。

### Unrecoverable Receipt Loop

- `MemoryFileStore.commitStagedMemoryFile()` 已有 canonical hash 等于 target 的 `AlreadyCommitted` 和 canonical 不等于 base/target 的并发 `Conflict` 语义；但当前在 hash 分支前就解析 staging path，非法路径仍可能过早抛错，因此实现时必须把 staging path 解析也移到 canonical base 分支之后。
- canonical 仍等于 base、但 staging 不存在或 staging hash 与 receipt target 不符时，目前通过异常离开，recovery 会将其当作可再次修复的失败。
- 这类 receipt 的目标字节已无法从 hash 还原；重复启动不会让 staging 自动回来，因此继续 retry 没有意义。
- 当前已有 `MemoryMutationState.CONFLICT`、CAS transition、terminal semantic job 和通知策略，足够表达终态；不需要新增 schema/state/scheduler。

### Backup Boundary

- manifest 当前 `android:allowBackup="true"`。
- `backup_rules.xml` 和 `data_extraction_rules.xml` 基本还是空模板，因此 eligible files/DB 默认可进入备份。
- ObjectBox 和已安装模型位于 `noBackupFilesDir`，天然不应进入 backup。
- `.staging` 是事务临时件；`.backups` 可能保留用户已经从 canonical 删除的敏感内容，二者都不应恢复到新安装。
- 本任务不解决 Room/DataStore 中 token 或其他凭据的整体备份政策；该产品决策必须单独处理。

## Target State

```text
User-visible and canonical
  filesDir/memory_store/MEMORY.md
    |-- observed by Memory page through MemoryFileStore long-term revision
    |-- read fresh on export
    |-- MarkdownLexicalRetriever
    `-- snapshot/chunk/embed -> ObjectBox HNSW (derived, noBackupFilesDir)

Normal chat recall
  MemoryRepositoryImpl(HYBRID)
    |-- fresh ObjectBox + lexical -> RRF/MMR -> exact-text dedup -> token budget
    `-- unavailable/stale/corrupt vector -> lexical -> exact-text dedup -> token budget

Maintenance
  Markdown lexical working-set reader
  five-turn consolidation -> strict operations -> staged receipt -> canonical commit

Room schema 17
  13 retained business/maintenance tables
  no personal_memory
  no chat_classification

Terminal receipt
  canonical==base + unrecoverable staging
    -> receipt/group CONFLICT
    -> source semantic job FAILED_TERMINAL
    -> no index/corpus advance, no automatic repair, no Retry action
```

## Hard Data Boundary

Schema 17 必须精确保留下列 13 张表及其行、索引、外键和业务语义：

```text
chats_v2
messages_v2
platform_v2
platform_model_v2
chat_platform_model_v2
memory_maintenance_job
memory_mutation_group
memory_mutation_receipt
memory_corpus_state
memory_distillation_checkpoint
memory_chat_checkpoint
memory_pending_turn
memory_activity_log
```

Schema 17 唯一允许删除的 Room 表：

```text
chat_classification
personal_memory
```

`MIGRATION_16_17` 只允许执行：

```sql
DROP TABLE IF EXISTS `chat_classification`;
DROP TABLE IF EXISTS `personal_memory`;
```

两表没有需要保留的外键关系。不要在 migration 内读写文件、复制 legacy rows、DELETE 其他表、重建业务表或运行 importer。SQLite migration transaction 负责保证两次 DROP 与 schema version 更新整体提交/回滚；不要制造 Room 与文件系统之间无法原子提交的“最后一次导入”。

## Risk Matrix

| Risk | Required handling | Completion evidence |
|---|---|---|
| 尚未导入 Markdown 的 legacy row 丢失 | 用户已明确接受；直接删表，不再导入 | migration test 证明只删除两表，`MEMORY.md` hash 不变 |
| 误删业务/维护数据 | 精确 table whitelist、sentinel 和真实链式升级 | 13 张表逐表核对，`foreign_key_check=0`、`integrity_check=ok` |
| 旧 importer 让已删除偏好复活 | 删除 DAO/entity/importer/startup/UI 调用和旧 codec | 生产源码静态零引用、重启后无 importer |
| LLM 同批输出重复 CREATE | 严格校验拒绝同 destination 的规范化重复 CREATE | invalid proposal 无写入、无 checkpoint advance |
| 后续批次再次 CREATE 已存在文本 | 解析完整 canonical 目标，成功 no-op | 文件字节不变、write count=0、job 正常完成 |
| 历史不同 ID 重复文本反复召回 | 排名后、token budget 前按规范化文本保留最高排名项 | lexical/Hybrid/fallback 测试且唯一结果不被挤出 |
| Memory 页面/导出陈旧 | 观察 canonical long-term revision；export 每次 fresh read | 页面自动更新、导出包含最新提交 |
| staging 永久丢失导致启动循环 | 类型化 unrecoverable outcome -> persisted conflict/terminal | 第二次 recovery/startup 产生 0 新 repair/notification |
| 瞬时 I/O 被错误终止 | 只分类 missing/invalid/hash mismatch；其他异常保持 bounded retry | 注入 IOException 测试仍 retryable |
| canonical 已提交但 staging 被清理 | target hash 判断必须先于 staging 解析/读取 | `canonical==target + staging missing/invalid` 仍完成 |
| backup 恢复临时/旧敏感副本 | 两套 XML 只排除 `.staging`/`.backups` | XML/static 测试；有可用 transport 时做真实 restore |
| 把 LocalTransport 当 cloud 证据 | 明确区分 local/device-transfer/cloud | readiness 中 cloud 仍为 OPEN，除非真实云 transport 通过 |
| schema JSON 行尾造成假 hash 漂移 | schema 路径固定 LF，hash 口径写明 Git blob | `16.json` 无 diff；记录 `17.json` Git blob SHA-256 |
| 无关 dirty UI 被污染 | 独立 branch/worktree，逐文件 stage | 每个 commit 只含本任务文件 |

## Execution Discipline

1. 先完整阅读仓库根目录 `AGENTS.md` 和本文。
2. 不要在当前 dirty `main` 上直接混做。以当前 `HEAD` 创建 `codex/memory-consistency-risk-closure` 独立 branch/worktree；若分支已存在，先核对并使用新的不冲突名称。
3. 如果本文尚未进入 baseline commit，只把本文复制到新 worktree 并作为第一个文档提交；不得复制其他 dirty 文件。
4. 不得 reset、checkout、stash、覆盖或格式化源 worktree 中现有 UI/多语言改动。
5. 所有 `git add` 使用显式文件路径；禁止 `git add .`、`git add -A`。
6. 每个任务完成后运行最小相关测试再提交，保持可回滚的小提交，并及时推送远端 branch。
7. 不要 merge 到 `main`，除非用户另行明确要求。
8. 测试失败时先修复本任务引入的回归；不要删除测试、放宽断言或切回 lexical-only 来获得绿灯。

## Implementation Tasks

### Task 0: Freeze Baseline And Evidence

- [ ] 记录 `git rev-parse HEAD`、`git status --short`、`git rev-list --left-right --count HEAD...origin/main`。
- [ ] 记录源 worktree 的全部预存 dirty 文件，并证明新 worktree 不包含它们。
- [ ] 重新确认 `versionName`、Room version、`MIGRATION_15_16`、schema 16 JSON、Hybrid DI 和 maintenance lexical binding。
- [ ] 记录 `16.json` identity hash `f932278bc936f80dea6a122f5a046403`；若 live 值不同，以 live 值为准并解释。
- [ ] 用 Git blob/LF 字节口径记录 `16.json` SHA-256。当前已知值为 `e2fc5d089f3dd6b29f55c51ec2387bd151329d068b63c8fd1672597da71fcf67`。
- [ ] 在改 schema 前构建并保存一个 schema 16 debug APK 到仓库外 evidence 目录，记录 SHA-256，供后续 `adb install -r` 升级测试。
- [ ] 运行当前相关 JVM baseline；失败必须区分既有失败与本任务回归。
- [ ] 运行 `adb devices`。若无设备，记录事实并在 runtime 阶段启动 disposable API 35 emulator；不要在真实用户设备上清数据。

建议的 Git blob hash 命令：

```powershell
cmd /c "git cat-file blob HEAD:app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/16.json | sha256sum"
```

不要把 Windows working-tree CRLF 的 `Get-FileHash` 与 tracked Git blob/LF hash 混为 schema drift。

### Task 1: Remove Legacy Semantics With Schema 17

- [ ] 新增 `MIGRATION_16_17`，内容精确为两条 `DROP TABLE IF EXISTS`，不得包含其他 SQL。
- [ ] 将 `ChatDatabaseV2` 升至 17，移除 `PersonalMemory`、`ChatClassification` entities 和两个 DAO accessor。
- [ ] 在 `DatabaseModule` 注册 `MIGRATION_16_17`，移除两个 DAO provider。
- [ ] 从 `MemoryRepository` 删除 `migrateActiveMemoriesToMarkdown()`。
- [ ] 从 `MemoryRepositoryImpl` 删除 `PersonalMemoryDao` 构造参数、importer 和 legacy 转换 helper。
- [ ] 从 `MemoryMaintenanceStartupCoordinator`/app startup 删除 migrate step；保留现有 repair、bootstrap 和 scheduler 顺序。
- [ ] 从 Memory 页面加载链删除 importer 调用。
- [ ] 删除 `PersonalMemory.kt`、`PersonalMemoryDao.kt`、`ChatClassification.kt`、`ChatClassificationDao.kt`。
- [ ] 删除旧 `MemoryMarkdownCodec.kt` 及只验证旧导出的测试；保留并继续测试 `MarkdownMemoryCodec.kt`。
- [ ] 从 `MarkdownMemoryModels.kt` 删除 `PersonalMemory.toMarkdownMemoryEntry()`。
- [ ] 删除 `MemoryTestFakes` 中 legacy DAO/entity fake 和只服务 importer 的 fixture。
- [ ] 对 `MemoryModels.kt` 做生产引用审计，只删除已无调用者的旧 DTO/转换；不得误删 `PreparedMemoryContext` 当前召回字段、`MemoryConversationMessage`、`MemorySensitivity`、`MemorySource` 或 `buildMemoryMessages()`。
- [ ] 保留所有历史 migration SQL/helper。不要重写或 squash 旧 schema 历史。
- [ ] 导出新的 `17.json`；禁止修改 `1.json` 到 `16.json`。
- [ ] 在 `.gitattributes` 增加 `app/schemas/**/*.json text eol=lf`；不要对历史文件做 broad renormalize，不得因此提交旧 schema churn。

Migration tests 必须覆盖：

- populated `16 -> 17`；
- chained `4 -> ... -> 17`，若设备成本过高，最低也必须覆盖 `14 -> 15 -> 16 -> 17`，但最终报告要明确实际起点；
- fresh schema 17 首开、关闭和重开；
- 表集合精确等于 13 张 whitelist；
- legacy 两表不存在；
- 每张保留表的 sentinel/row count 不变；
- `PRAGMA user_version=17`；
- `PRAGMA foreign_key_check` 返回 0 行；
- `PRAGMA integrity_check` 返回 `ok`；
- migration 前后 `MEMORY.md` 字节和 SHA-256 不变。

不得只写“SQL 字符串包含 DROP”的 JVM 测试后就宣称 migration 通过。

### Task 2: Close Exact-Duplicate Writes And Recall

定义一个 module-internal 的精确文本规范化 helper，写入和召回共同复用：

```text
trim
+ Unicode lowercase
+ collapse consecutive whitespace to one ASCII space
```

不要去标点、stemming、embedding compare、编辑距离、关键词规则或语义去重。

- [ ] 在 `MemoryBatchConsolidationService.validateOperations()` 中维护同 destination 的 normalized `CREATE` set。
- [ ] 同一 proposal 内两个 destination 相同、规范化文本相同的 `CREATE` 必须使整份 proposal fail closed；不得写文件或推进 checkpoint。
- [ ] 保留现有“相同写入文本不能跨 destination”的校验和 REPLACE/REMOVE target 校验。
- [ ] 在 `renderOperations()` 的 `CREATE` 分支、生成 ID 和 write count 之前，解析该目标文件完整的当前 `editedMarkdown`。
- [ ] 如果目标文件已经存在规范化文本完全相同的条目，该 `CREATE` 成为成功 no-op：不改字节、不增加 `dailyWriteCount`/`longTermWriteCount`、不覆盖旧 metadata，batch/checkpoint 正常完成。
- [ ] 去重按目标文件隔离。daily 已有相同文本不能阻止后续 distillation 在 long-term 创建；二者生命周期不同。
- [ ] 不依赖最多 24 条的 `existingMemories` retrieval working set 判断 canonical 是否重复。
- [ ] 不清理历史 Markdown 重复项，不改变确定性 ID、receipt 和成功重放语义。
- [ ] 在 lexical 和 Hybrid 最终排序之后、token budget 计算之前，按规范化文本保留排名最高的第一项。
- [ ] 继续保留 entry ID/content hash 去重；精确文本去重是额外防线。
- [ ] 检查 lexical 的 `candidateLimit` 和 Hybrid 的 fusion/MMR 顺序，确保重复项不会在去重前挤掉后续唯一结果；必要时把 backend 内的精确去重前移，但不得改变评分权重或 MMR 算法。
- [ ] `MemoryPromptBuilder` 或共同的最终 pack 边界必须再做防御，保证历史重复项只注入一次且不重复消耗 token budget。

必测：

- 同批两个仅大小写/空白不同的同目标 `CREATE`：proposal 无效、0 写入、checkpoint 不推进。
- canonical 已有相同规范化文本，后续批次 `CREATE`：成功、write count=0、文件字节不变、checkpoint 推进。
- daily 与 long-term 分别拥有相同文本：long-term 提炼允许创建。
- 重放已成功 no-op 的 batch：不再次调用 intelligence，不新增 receipt/entry。
- 不同 ID/相同文本在 lexical 只返回一次，且唯一的下一条结果不会被 candidate/token budget 挤掉。
- lexical/vector 各自或交叉返回相同文本时，Hybrid 只保留最高排名代表。
- embedding 不可用的 lexical fallback 仍执行相同精确去重。

### Task 3: Make Memory View And Export Canonical-Live

采用现有 `MemoryFileStore` long-term revision，不新增 Room schema/DAO、文件 watcher、轮询器或第二套 generation。

- [ ] 将 `longTermRevision` 变为只读可观察 `StateFlow<Long>`，或提供语义等价的只读 Flow。
- [ ] canonical long-term 文件的所有既有写入仍只通过 `advanceRevisionFor()` 推进该信号；daily 写入不得触发 long-term 内容刷新。
- [ ] staging commit 真正写入 `MEMORY.md` 后立即推进 revision，不要等待 ObjectBox/index 成功。
- [ ] 首次订阅必须立即触发读取当前文件；进程重启时不依赖旧 revision 数值。
- [ ] 在 `MemoryRepository` 增加 `observeLongTermMarkdown(): Flow<String>`，每次 revision 变化都重新读取 canonical 文件，并 `distinctUntilChanged()`。
- [ ] `MemoryViewModel` 在 `init` 中只建立一个 Markdown Flow collector；设置和 activity log 可保持独立 collector。
- [ ] 删除 `MemoryScreen` 的 `LaunchedEffect(Unit) { loadMemories() }` 和 ViewModel 的双重 one-shot 加载。
- [ ] `exportMarkdown()` 每次点击都调用 `getLongTermMarkdown()` fresh read；同一次结果同时更新页面 markdown 和 export payload。
- [ ] 禁止 `_uiState.value.markdown.ifBlank { ... }` 这类缓存优先逻辑。
- [ ] Flow/read 失败要沿用当前 UI 的最小可用行为，不新增 maintenance console、错误卡片或第二套持久缓存。

必测：

- 订阅后立即收到现有 `MEMORY.md`。
- staged long-term commit/replace 后收到新内容。
- daily 写入不产生 long-term 内容变化事件。
- 页面保持打开时，后台五轮 long-term commit 后无需返回重进即可显示新内容。
- 页面暂时持有旧内容时完成后台 commit，再点击导出，export 必须包含新内容。
- 页面进入只建立一个内容订阅，不触发 legacy migration 或重复读链。
- index 同步失败但 canonical 已提交时，页面仍显示 canonical 新内容；不得把 UI 刷新绑在 vector READY 上。

### Task 4: Make Unrecoverable Staging Terminal

保持当前 `MemoryMutationState.CONFLICT`，不新增表、状态枚举族、scheduler 或全局 retry policy。

- [ ] 为 `MemoryFileCommitOutcome` 增加窄的 `UnrecoverableStaging(reason)`，或等价的类型化专用结果。
- [ ] 调整判断顺序：先读取 canonical 并比较 target/base，再解析或读取 staging。canonical==target 时，即使 staging path 已失效或文件已清理，也必须返回 `AlreadyCommitted`。
- [ ] canonical!=base 且 !=target 时继续返回现有并发 `Conflict`。
- [ ] 只有 canonical==base 且出现以下情况时返回不可恢复结果：staging missing、staging path 非法/指向目录、staged bytes hash != receipt target hash。
- [ ] 使用稳定、无绝对路径和无记忆内容的错误码，例如：
  - `memory_mutation_unrecoverable_staging_missing`
  - `memory_mutation_unrecoverable_staging_invalid`
  - `memory_mutation_unrecoverable_staging_hash_mismatch`
- [ ] 普通 canonical/staging read/write `IOException`、权限错误、临时设备错误和 cancellation 继续走现有 bounded retry；禁止 catch-all terminal。
- [ ] `MemoryMutationCoordinator` 用现有 CAS 将 receipt 和 group 一次性转为 `CONFLICT`，group 写入 `completedAt`，canonical 文件保持不变。
- [ ] terminal 分支禁止 corpus advance、index sync、vector publication 或根据 hash 猜/重造 target bytes。
- [ ] `MemoryMutationCommitResult.Conflict` 保留稳定 reason；batch consolidation、daily distillation 和 recovery finalizer 必须把 reason 传给源 semantic job 的 `FAILED_TERMINAL`，不得覆盖成泛化 path 错误。
- [ ] `MemoryMaintenanceNotificationPolicy` 对上述 unrecoverable 前缀设置 `terminal=true, allowRetry=false`；不得展示必然失败的 Retry action。
- [ ] 没有 semantic source job 的本地 receipt 只持久化 conflict，不制造新的 notification job。
- [ ] `reconcileIncomplete()` 遇到该类结果后，不计入可再次 repair 的 generation，不 enqueue 新 `RECONCILE_MEMORY_MUTATIONS`。
- [ ] 第二次 recovery 和两次 startup 必须产生 0 新 repair、0 新通知；receipt/group attempts、rowVersion 和 error 保持稳定。
- [ ] multi-receipt 已有部分 canonical commit 时不要回滚 Markdown。Hybrid 必须因 snapshot freshness fail closed 到当前 Markdown lexical；后续 bootstrap 可重建派生索引。

必测矩阵：

| Case | Expected |
|---|---|
| canonical==base, staging missing | receipt/group CONFLICT；源 job terminal；无 index/corpus advance；无 Retry action |
| canonical==base, staged hash mismatch | 同上 |
| canonical==base, staging path invalid/directory | 同上 |
| canonical==target, staging missing/invalid | `AlreadyCommitted`，正常完成 |
| canonical!=base/target | 保持普通 concurrent conflict |
| injected transient IOException | 保持 retryable，不误终止 |
| second recovery / repeated startup | 0 新 repair/通知，状态与 attempts 稳定 |
| partial multi-file commit | 不回滚 canonical；Hybrid stale check -> lexical；bootstrap 可重建 |

### Task 5: Narrow Backup Exclusions And Honest Restore Evidence

只修改两套 Android backup XML 的记忆临时目录边界：

`backup_rules.xml`（API 30 及以下兼容规则）增加：

```xml
<exclude domain="file" path="memory_store/.staging/" />
<exclude domain="file" path="memory_store/.backups/" />
```

`data_extraction_rules.xml` 的 `cloud-backup` 和 `device-transfer` 都增加同样两条 exclude。

- [ ] 不改为 broad include-only policy。
- [ ] 不改变数据库、SharedPreferences、DataStore、聊天数据或 credential 的当前备份资格。
- [ ] 不把 `noBackupFilesDir` 中 ObjectBox/model 重新 include。
- [ ] 不在没有产品决策时启用会改变恢复能力的 `disableIfNoEncryptionCapabilities`。
- [ ] 增加 XML/static 或 instrumentation 断言，证明两套规则一致且路径精确。
- [ ] 增加 restore 后的不一致模拟测试：恢复出 PREPARED receipt 但 staging 缺失时只终止一次；canonical 已是 target 的 PREPARED receipt 可完成。
- [ ] 如果有 disposable emulator 和真实可用 backup transport，执行真实 `bmgr` backup/clear/restore；先记录 transport，结束后恢复原 transport。
- [ ] 严禁在用户真实设备执行 `pm clear`/卸载；严禁用 `adb pull/push` 文件复制冒充 backup-agent restore。

真实 restore 预检：

```powershell
adb devices
adb shell bmgr enabled
adb shell bmgr list transports
adb shell bmgr list sets
```

实际 backup/restore 命令必须以设备列出的 transport/token 为准。只有 LocalTransport 时，可以报告本地 backup-agent restore 证据，但 cloud gate 继续 `OPEN`。无可用 transport/token 时，本任务仍可完成代码和模拟恢复测试，但不得把真实 restore 标为 `PASSED`。

若执行真实 restore，应证明：

- Room 业务/维护数据与 canonical `MEMORY.md`/daily Markdown 恢复；
- `.staging`、`.backups` 未恢复；
- `noBackupFilesDir/memory_vector_index` 未恢复；
- 启动后从 Markdown 重建 ObjectBox，或在重建前安全 lexical fallback；
- staging 缺失 PREPARED receipt 只进入一次 terminal；
- canonical 已是 target 的 PREPARED receipt 正常收尾。

### Task 6: Integration, Runtime Upgrade And Documentation

- [ ] 更新 `on-device-vector-memory-readiness.md`：schema 17 风险闭环状态基于真实证据填写；不要改写历史 schema 16/16 KB/Hybrid 证据。
- [ ] 明确 `16.json` 的已记录 SHA-256 是 tracked Git blob/LF 口径，不是 Windows CRLF working-tree hash。
- [ ] 记录 `17.json` identity hash、Git blob SHA-256 和精确表集合。
- [ ] Backup gate 拆分为“规则/模拟恢复”“LocalTransport restore（若执行）”“真实 cloud restore”；没有真实云证据时 cloud 保持 `OPEN`。
- [ ] ARM64/OEM 和 32 位 ABI 状态继续保持原有 `OPEN`/补充门禁，不因本任务擅自改成 `PASSED`。
- [ ] 静态检查普通生产 recall 仍绑定 Hybrid，maintenance reader 仍 lexical，全局 default strategy 仍 lexical。
- [ ] 运行每个 provider family 的 prompt 组装测试，证明 memory prompt 最终恰好合并一次，工具/搜索/system/context summary 不丢失。
- [ ] 证明 model 不可用、ObjectBox 缺失/损坏、snapshot stale/hash mismatch 时普通聊天继续 lexical 可召回，而不是无条件无记忆。
- [ ] 在 disposable emulator 上用保存的 populated schema 16 APK 建立业务/维护/legacy sentinels 和 `MEMORY.md` hash，再用 `adb install -r` 安装 schema 17 APK。
- [ ] 升级过程中不得 uninstall、clear data 或替换 database/file store。
- [ ] 升级后验证 schema 17、13 张表、legacy 两表不存在、业务/维护 sentinels 保留、`MEMORY.md` hash 不变、Memory 页面显示当前内容、Hybrid/lexical fallback 可召回。
- [ ] 强停、冷启动、进程终止和再次启动后重复验证 receipt 不复活、ObjectBox 可重建、Memory 页面/导出仍新鲜。
- [ ] 运行完整 JVM、AndroidTest 编译、migration/device tests、debug、release R8、lint vital 和生产模型/原生库验证。
- [ ] `git diff --check`、ktlint（若仓库可用）、schema history diff 和最终 status 必须通过。

## Required Verification Commands

执行 Agent 可按 live 测试类名调整 filter，但不得降低语义覆盖：

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryBatchConsolidationServiceTest" --tests "*MarkdownLexicalRetrieverTest" --tests "*HybridMemoryRetrieverTest" --tests "*MemoryPromptBuilderTest"
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryFileStoreTest" --tests "*MemoryRepositoryTest" --tests "*MemoryMutation*" --tests "*MemoryMaintenance*" --tests "*MemoryDailyDistillation*"
./gradlew.bat :app:testDebugUnitTest --tests "*Memory*"
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2MigrationInstrumentedTest
./gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintVitalRelease
./tools/memory-vector/verify-release.ps1 -SkipBuild
git diff --check
$Baseline = "<Task 0 recorded baseline SHA>"
git diff --exit-code $Baseline -- app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/1.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/2.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/3.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/4.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/5.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/6.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/7.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/8.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/9.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/10.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/11.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/12.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/13.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/14.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/15.json app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/16.json
git status --short
```

如果 `verify-release.ps1 -SkipBuild` 依赖的 release APK 尚未生成，先完成 `assembleRelease`；如果脚本 live 参数变化，以脚本帮助/源码为准。不要重新 provision 或替换已校验生产模型，除非现有验证脚本明确需要。

静态零引用至少检查：

```powershell
rg -n "PersonalMemory|PersonalMemoryDao|ChatClassification|ChatClassificationDao|migrateActiveMemoriesToMarkdown|MemoryMarkdownCodec" app/src/main app/src/test app/src/androidTest
rg -n "MemoryRetrievalStrategy.HYBRID|provideMemoryRetriever|provideMemoryMaintenanceCorpusReader" app/src/main/kotlin
rg -n "memory_store/.staging|memory_store/.backups" app/src/main/res/xml
```

第一条只允许命中新 migration/schema 历史说明或明确的 migration test fixture；生产 Kotlin 源码不得再依赖 legacy symbols。不要为了让 `rg` 为零而改写历史 schema JSON。

## Required End-To-End Test Matrix

| Area | Case | Expected |
|---|---|---|
| Schema | populated 16 -> 17 | 13 tables retained, two legacy tables absent, all sentinels preserved |
| Schema | chained old -> 17 | complete registered migration chain opens successfully |
| Schema | fresh 17 open/reopen | exact schema, FK clean, integrity ok |
| Files | schema upgrade | `MEMORY.md` bytes/hash unchanged |
| Legacy | repeated cold start | no importer, no deleted preference resurrection |
| Batch | same-batch duplicate CREATE | strict failure, 0 writes, no checkpoint advance |
| Batch | cross-batch existing exact text | success no-op, byte-identical file, checkpoint advances |
| Recall | historical duplicate text | one prompt entry, unique candidate still fits budget |
| Recall | Hybrid fresh | fused result works and exact duplicates collapse |
| Recall | vector unavailable/stale/corrupt | current Markdown lexical still recalls |
| UI | background canonical commit | open page updates without re-entry |
| UI | stale page then export | export rereads and contains current file |
| Receipt | missing/invalid/mismatched staging | persisted terminal conflict, no retry action or index advance |
| Receipt | canonical already target | idempotent completion even without staging |
| Receipt | transient I/O | bounded retry remains available |
| Startup | terminal receipt, repeated start | no new repair, notification or state churn |
| Backup | XML rules | staging/backups excluded in API<=30, cloud and device transfer |
| Backup | simulated restored PREPARED | missing staging terminates once; target-already-written completes |
| Backup | real transport if available | canonical/Room restore, transient/derived stores absent |
| Providers | each provider family | exactly one final memory prompt, other prompt sections preserved |
| Runtime | schema16 APK -> schema17 APK | `adb install -r`, no data clear, launch/recall/export/restart pass |

## Non-Goals

- 不重新设计 embedding model、tokenizer、ObjectBox schema、HNSW、RRF、MMR 或 Hybrid 权重。
- 不把 maintenance reader 改为 Hybrid，不把全局 retrieval default 从 lexical 改成 Hybrid。
- 不添加 cloud vector database、remote embedding API、LangChain/agent framework 或第二个向量后端。
- 不改变五轮阈值、30 分钟 idle、completed-turn 定义、provider routing、自动重试上限或 job serialization。
- 不增加用户确认记忆、逐条管理、maintenance console、daily note 展示或新的设置项。
- 不批量重写历史 `MEMORY.md`，不尝试语义合并相似但不完全相同的记忆。
- 不扩大 Android backup credential/privacy policy；只处理两个 memory transient 目录。
- 不把真实 ARM64/OEM、32 位 ABI 或 cloud restore 的 OPEN 状态伪装为本任务已解决。
- 不修改聊天 UI、导航、附件、工具/搜索、token accounting、模型设置或多语言文案。
- 不做 destructive downgrade 支持，不为旧 APK 保留 legacy runtime 双轨。

## Stop Conditions

只有在合理修复和重试后仍遇到以下情况，才停止并向用户报告：

- populated `16 -> 17` 或完整注册链无法通过 Room 实际 builder 打开；
- 13 张保留表中的任何表、sentinel、索引或外键语义丢失；
- `foreign_key_check` 非空或 `integrity_check` 非 `ok`；
- migration 必须改写 `MEMORY.md` 才能继续；
- 删除 legacy symbols 后仍存在真实生产调用者，且无法在本文边界内迁移到当前 Markdown 路径；
- 不能窄化区分不可恢复 staging 与 transient I/O，导致必须二选一地无限重试或误终止；
- terminal receipt 在 repeated startup 中仍被复活或产生通知/repair 循环；
- exact-text 去重导致唯一召回结果在 candidate limit 前丢失且无法在不重写 ranking 的情况下修复；
- Hybrid/lexical fallback、provider prompt 合并或 canonical-first 语义发生回归；
- 完成任务需要 reset、丢弃或吸收用户现有 dirty UI 工作。

以下不是停止理由：没有重要 legacy 数据、schema 16 soak 时间短、当前没有已连接设备、没有物理 ARM64、32 位 ABI 尚未决定、没有真实 cloud transport。应继续完成可执行工作；启动 disposable emulator 完成 schema runtime gate；无法获得真实 cloud transport时如实保持 cloud gate `OPEN`。

不得在没有完成 populated migration、receipt terminal、duplicate recall、live UI/export 和 Hybrid fallback 证据时宣称任务完成。

## Commit Sequence

建议保持以下可回滚提交边界，每个提交前运行对应最小测试并立即推送：

1. `docs: add memory consistency risk closure execution prompt`
2. `refactor: remove legacy Room memory semantics with schema 17`
3. `fix: prevent exact duplicate memory writes and recall`
4. `fix: keep Memory view and export on canonical content`
5. `fix: terminate unrecoverable memory mutation receipts`
6. `fix: exclude transient memory files from Android backup`
7. `test: verify schema 17 upgrade and memory recovery`
8. `docs: record schema 17 and backup readiness evidence`

可以在依赖紧密时合并相邻提交，但不得把整个任务压成一个大提交，也不得混入无关 UI 文件。

## Completion Report

最终报告必须按以下结构给出真实值：

```text
Baseline and isolation:
- source HEAD / origin divergence:
- implementation branch/worktree:
- pre-existing dirty files preserved:
- schema 16 APK path/hash:

Schema 17:
- MIGRATION_16_17 exact SQL:
- removed runtime symbols/files:
- retained 13-table evidence:
- populated and chained migration results:
- fresh open/reopen, FK and integrity results:
- 16.json unchanged evidence:
- 17.json identityHash / Git blob SHA-256:
- MEMORY.md before/after SHA-256:

Duplicate prevention:
- same-batch strict rejection result:
- cross-batch no-op result:
- lexical/Hybrid/fallback defensive dedup result:
- token/candidate budget result:

Memory UI/export:
- revision/Flow implementation:
- background refresh result:
- fresh export result:
- index-failure/canonical display result:

Receipt recovery:
- unrecoverable classifications:
- transient I/O behavior:
- canonical-already-target behavior:
- repeated startup/no-loop evidence:
- source job and notification behavior:

Backup:
- XML exclusions:
- simulated restore result:
- real transport used, if any:
- LocalTransport/cloud status stated separately:
- remaining credential backup-policy limitation:

Recall/runtime:
- production Hybrid and maintenance lexical proof:
- stale/missing/corrupt lexical fallback proof:
- provider prompt exactly-once proof:
- adb install -r schema16 -> schema17 result:
- process restart/ObjectBox rebuild result:

Verification:
- exact test/build/script commands and results:
- device/emulator identity and PAGE_SIZE if used:
- ktlint/diff/status result:
- remaining OPEN gates:

Git:
- per-slice commit hashes:
- pushed remote branch/ref:
```

## Copy-Paste Prompt For A Fresh Implementation Conversation

```text
在 E:\code\ChatWithChat 工作。

先完整阅读：
1. E:\code\ChatWithChat\AGENTS.md
2. E:\code\ChatWithChat\docs\superpowers\plans\2026-07-14-memory-consistency-risk-closure-prompt.md

第二份文档是本次实现的权威范围。不要再停留在审计、风险解释或另写计划，直接按文档完成实现、测试、真实 schema 升级验证、分片提交和推送。

关键授权：当前只有一个用户、没有重要 legacy Room 记忆，应用仍为 0.7.5/正式 1.0 前。直接执行 schema 16 -> 17，删除 personal_memory 和 chat_classification，并删除 importer、DAO/entity/DI、旧 MemoryMarkdownCodec、legacy DTO/fake。明确接受未进入 MEMORY.md 的旧 PersonalMemory 行丢失，不要新增一次性 marker、桥接表、兼容 adapter 或最后一次文件导入。保留完整历史 migration 链，禁止 destructive migration。

同时必须关闭四类功能风险：
1. 同批重复 CREATE 严格拒绝，跨批目标文件已存在相同规范化文本时成功 no-op；lexical/Hybrid/fallback 在 token budget 前对历史重复文本只保留最高排名项。
2. Memory 页面观察现有 MemoryFileStore long-term revision 并实时读取 canonical MEMORY.md；删除双重加载；export 每次 fresh read。
3. canonical==base 且 staging missing/invalid/hash mismatch 时，用现有 CONFLICT/FAILED_TERMINAL 持久化终止；canonical==target 仍 AlreadyCommitted；普通 IOException 保持 bounded retry；重复启动不得复活或继续排 repair。
4. 两套 Android backup XML 只排除 memory_store/.staging/ 和 memory_store/.backups/，不扩大到数据库/token/SharedPreferences 产品政策。无真实 cloud transport 时 cloud restore 继续 OPEN，不能造假。

保持普通生产 Hybrid、maintenance lexical、ONNX/ObjectBox、RRF/MMR、五轮批处理、receipt/corpus/index 和 provider prompt 合并设计不变。schema 17 精确保留文档列出的 13 张业务/维护表，MEMORY.md 升级前后字节/hash 不变。

当前 main 工作区有大量用户 UI/导航/多语言改动。不得 reset、stash、覆盖或纳入提交。先从 live HEAD 建立 codex/memory-consistency-risk-closure 独立 branch/worktree，显式逐文件 stage，小步验证、提交并推送；不要 merge main，除非用户另行要求。

必须完成 populated 16->17、链式升级、fresh 17 reopen、13 表 sentinel/FK/integrity、重复写入/召回、UI/export、不可恢复 receipt/repeated startup、每个 provider family prompt、Hybrid lexical fallback、debug/AndroidTest/release R8/lint/model artifact 验证。保存 schema 16 APK，再在 disposable emulator 上用 adb install -r 升级 schema 17，禁止 uninstall/clear-data，验证业务数据、MEMORY.md hash、召回、导出、强停和重启。

遇到问题先按文档 Stop Conditions 修复和收敛。不要因为缺少物理 ARM64、32 位决策或真实 cloud transport停止；这些保持独立 OPEN。最终按文档 Completion Report 给出 hashes、表集合、测试结果、runtime 证据、提交和远端分支。
```
