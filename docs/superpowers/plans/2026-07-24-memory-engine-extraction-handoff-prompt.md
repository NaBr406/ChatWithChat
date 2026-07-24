# 交接提示词：从 ChatWithChat 拆出可跨 AI 平台接入的记忆主流程模块

> 用途：在新仓库/新会话中直接粘贴本文件，作为实现任务的完整上下文。  
> 来源仓库：`E:\code\ChatWithChat`  
> 创建日期：2026-07-24  
> 范围决策：只拆**记忆主流程**；通知策略、UI、Android Worker 外壳等 App 绑定能力不进入新模块。

---

## 0. 你的任务

新建一个独立项目`CWC-memory-core`，从 ChatWithChat 现有实现中抽出**可被其他 AI 平台接入的记忆主流程模块**。

目标不是“把 Android 包原样搬走”，而是形成：

1. **平台无关的记忆核心库**（协议 + 主流程引擎）
2. **清晰的宿主接入 SPI**（LLM、存储、任务调度由宿主注入）
3. **可选的参考 adapter**（可先做 JVM/Kotlin 内存或 SQLite 实现；ChatWithChat 适配可后做）

最终其他 AI 客户端应能做到：

- 写入对话轮次
- 自动/半自动提炼长期记忆
- 以 `MEMORY.md` 为真相源持久化
- 在下次对话前召回相关记忆并生成可注入 prompt 的上下文

---

## 1. 产品原则（必须遵守）

这些原则来自 ChatWithChat 线上已验证设计，不要改坏：

1. **`MEMORY.md` 是唯一普通聊天召回真相源**  
   路径约定：`{root}/MEMORY.md`
2. **日记文件只是维护输入**  
   `{root}/memory/YYYY-MM-DD.md` 供 daily distillation 使用；普通 chat recall 默认只读 `MEMORY.md`
3. **索引/向量库永远是派生态**  
   可删、可重建，绝不能反向覆盖 Markdown
4. **LLM 输出必须 fail-closed**  
   只能通过 controlled operations schema 校验后写入；非法 JSON / 非法 ops 不得落盘
5. **写入必须可恢复、尽量幂等**  
   staging + backup + mutation receipt / job 状态机；进程被杀后可继续或安全终止
6. **召回失败不得阻断主聊天**  
   retrieve 失败应降级为空记忆，而不是抛死宿主对话
7. **隐私字段要保留语义**  
   `normal` / `private` / `sensitive`；prompt 中要求模型谨慎使用，不主动暴露

---

## 2. 明确范围

### 2.1 In Scope（主流程，必须做）

主链路：

```text
ingestTurn / noteActivity
  -> pending turns 入队
  -> 触发凑批 (threshold / idle / context_compaction)
  -> LLM 生成 controlled ops
  -> 校验 ops + render markdown
  -> mutation 原子提交到 daily 和/或 MEMORY.md
  -> prepareContext: 从 MEMORY.md 召回 + 生成 prompt 片段
```

模块能力清单：

| 能力 | 说明 |
| --- | --- |
| Turn ingest | 记录完成轮次，过滤无价值/错误助手回复 |
| Batching | 按阈值、空闲、上下文压缩等原因形成 batch job |
| Consolidation | 调 LLM，产出 create/replace/remove/ignore ops |
| Validation + render | 校验 destination/action/type/sensitivity/source/evidence，渲染 markdown |
| Mutation commit | staging、备份、原子替换、冲突/覆盖语义 |
| Lexical recall | 从当前 `MEMORY.md` 做词法召回并按 token/条数打包 |
| Prompt build | 生成可注入 system/context 的记忆提示文本 |
| Enable switch | 关闭后不再入队/调度；开启可修复调度 |
| Export/read | 读取/观察长期记忆 markdown |

建议第一期一并包含，但可标为 Phase 1.5：

- **Daily distillation**：把 daily md 中值得长期保留的内容蒸馏进 `MEMORY.md`

### 2.2 Out of Scope（不要搬进新模块）

以下高度绑定 ChatWithChat / Android App，**不要**作为核心库职责：

- 通知策略、通知渠道、Maintenance Notification EventSink
- Memory UI / `MemoryScreen` / `MemoryViewModel`
- WorkManager / Hilt Worker / WakeWorker 外壳
- Android 前台服务、系统闹钟、App 设置页文案
- Activity log 的产品化展示（诊断日志接口可留 no-op SPI）
- ObjectBox / ONNX / 端侧 embedding 模型打包（可留接口，第一期不实现）
- 云同步、多端 CRDT、账号体系
- 与聊天 Room 总库的强绑定（chat/message/provider 表）

### 2.3 第一期明确不做

- Hybrid / vector recall 生产实现
- Android AAR 发布流水线（可后补）
- 直接改造 ChatWithChat 完成切换（可提供迁移说明，但不阻塞新项目 MVP）
- 兼容所有历史 legacy job payload 的永久包袱（可保留最小兼容，不必神化）

---

## 3. 源项目锚点（只读参考）

源仓库：`E:\code\ChatWithChat`  
包名：`cn.nabr.chatwithchat.data.memory`

### 3.1 主入口

- `app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/MemoryRepository.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/MemoryRepositoryImpl.kt`

当前 App 入口语义：

- `onMemoryEnabledChanged(enabled)`
- `recordUserActivity(chatId, activityAt)`
- `recordCompletedTurn(input)`
- `prepareMemoryContext(...)`
- `getLongTermMarkdown()` / `observeLongTermMarkdown()`

### 3.2 主流程核心类（优先移植逻辑）

| 类 | 路径 | 职责 |
| --- | --- | --- |
| `MemoryTurnBatchCoordinator` | `data/memory/` | 完成轮次落 pending、空闲时间、触发观察 |
| `MemoryTurnBatchScheduler` | `data/memory/` | threshold/idle/compaction 调度决策 |
| `MemoryBatchConsolidationService` | `data/memory/` | claim batch → LLM → validate → commit |
| `MemoryMutationCoordinator` | `data/memory/` | mutation group/receipt、提交与恢复 |
| `MemoryFileStore` | `data/memory/` | markdown 读写、staging、backup、revision |
| `MarkdownMemoryCodec` | `data/memory/` | markdown entry parse/render |
| `MemoryControlledOperations` / daily controller | `data/memory/` | ops 校验与 render |
| `MemoryIntelligence` | `data/memory/` | LLM 智能接口 |
| `LlmMemoryIntelligence` | `data/memory/` | **仅作行为参考，不要原样搬 DTO 依赖** |
| `MarkdownLexicalRetriever` | `data/memory/` | 词法召回 |
| `HybridMemoryRetriever` | `data/memory/` | 了解 fallback 策略；第一期可只做 lexical |
| `MemoryPromptBuilder` | `data/memory/` | 召回结果转 prompt |
| `MemoryChunker` / `MemoryTextNormalization` | `data/memory/` | 切块与文本归一 |
| `MemoryDailyDistillationService` | `data/memory/` | 可选 Phase 1.5 |
| `MemoryDailyDistillationScheduler` | `data/memory/` | 可选 Phase 1.5 |

### 3.3 模型与协议

- `MemoryTurnBatchModels.kt`
- `MemoryBatchConsolidationModels.kt`
- `MemoryDailyDistillationModels.kt`
- `MarkdownMemoryModels.kt`
- `MemoryModels.kt`
- `MemoryRetrieval.kt`
- `MemoryMutationModels.kt`
- `MemoryFilePaths.kt`

### 3.4 架构文档

- `docs/architecture/on-device-vector-memory-readiness.md`
- `docs/superpowers/evaluations/2026-07-09-memory-backend-evaluation.md`

### 3.5 高价值测试（移植/对照用）

优先看 unit tests：

- `MemoryBatchConsolidationServiceTest`
- `MemoryMutationCoordinatorTest`
- `MemoryFileStoreTest`
- `MarkdownMemoryCodecTest`
- `MarkdownLexicalRetrieverTest`
- `MemoryTurnBatchCoordinatorTest` / `MemoryTurnBatchSchedulerTest`
- `MemoryPromptBuilderTest`
- `MemoryDailyDistillationServiceTest`（若做 distillation）
- `MemoryRepositoryTest`

源测试目录：

- `app/src/test/kotlin/cn/nabr/chatwithchat/data/memory/`
- `app/src/test/kotlin/cn/nabr/chatwithchat/data/repository/MemoryRepositoryTest.kt`

### 3.6 不要当核心依赖搬走的东西

- `data/memory/MemoryMaintenanceNotification*`
- `*Worker.kt` / `MemoryMaintenanceWorkScheduler.kt`
- `presentation/ui/memory/*`
- Room 总库 `ChatDatabaseV2` 全量实体
- `OpenAIAPI` / `AnthropicAPI` / `GoogleAPI` DTO 调用链

---

## 4. 目标架构

建议模块划分：

```text
memory-core/
  - markdown codec / models
  - text normalization / chunker
  - lexical retriever
  - prompt builder
  - controlled ops schema + validation helpers

memory-engine/
  - turn ingest + batching
  - consolidation pipeline
  - mutation engine + file store
  - job orchestration (interface-driven)
  - prepareContext facade

memory-spi/   (可与 engine 合并)
  - MemoryLlmClient
  - MemoryJobStore
  - MemoryClock / IdGenerator
  - optional MemoryActivityLogger
  - optional MemoryEmbeddingProvider / MemoryVectorStore

samples/jvm-basic/
  - 内存或 SQLite JobStore
  - 假 LLM / 可插真实 OpenAI-compatible client
  - 命令行或最小 demo：ingest -> run jobs -> retrieve
```

宿主集成示意：

```text
Host App / AI Platform
  |- provides MemoryLlmClient
  |- provides root directory + JobStore
  |- decides when to call engine.runDueJobs()
  |- calls engine.prepareContext() before model request
  |- calls engine.ingestTurn() after completed reply
```

---

## 5. 建议公共 API（稳定面）

名称可调整，但语义应保持：

```kotlin
interface MemoryEngine {
    suspend fun setEnabled(enabled: Boolean)

    suspend fun noteActivity(chatId: String, activityAtEpochSeconds: Long)

    suspend fun ingestTurn(turn: MemoryTurnInput): MemoryIngestResult

    suspend fun prepareContext(request: MemoryContextRequest): PreparedMemoryContext

    suspend fun runDueJobs(limit: Int = 1): List<MemoryJobRunResult>

    suspend fun getLongTermMarkdown(): String

    fun observeLongTermMarkdown(): Flow<String> // 若不想依赖协程 Flow，可用 callback SPI

    // Phase 1.5
    suspend fun runDailyDistillationFor(date: LocalDate): MemoryJobRunResult?
}
```

### 5.1 中立 DTO（禁止继续暴露 ChatWithChat 实体）

不要在公共 API 使用：

- `ChatRoomV2`
- `MessageV2`
- `PlatformV2`

改为中立模型，例如：

```kotlin
data class MemoryTurnInput(
    val chatId: String,
    val chatTitle: String,
    val turnKey: String? = null, // 空则 engine 生成
    val userMessageId: String,
    val userContent: String,
    val assistantContent: String,
    val assistantPlatformId: String? = null,
    val preferredLlmProfileId: String? = null,
    val attachments: List<MemoryAttachment> = emptyList(),
    val completedAtEpochSeconds: Long
)

data class MemoryContextRequest(
    val query: String,
    val recentContext: String? = null,
    val limit: Int = 8,
    val tokenBudget: Int = 900,
    val includePrivate: Boolean = true
)

data class PreparedMemoryContext(
    val memories: List<MemoryHit>,
    val prompt: String?
)
```

### 5.2 宿主必须注入的 SPI

```kotlin
interface MemoryLlmClient {
    /**
     * 返回模型原始文本。引擎负责 JSON 解析与 schema 校验。
     * 失败应抛异常或返回 Result.failure，由引擎标为 retryable/terminal。
     */
    suspend fun completeJson(
        request: MemoryLlmRequest
    ): MemoryLlmResponse
}

data class MemoryLlmRequest(
    val purpose: MemoryLlmPurpose, // BATCH_CONSOLIDATION / DAILY_DISTILLATION
    val systemPrompt: String,
    val userPrompt: String,
    val preferredProfileId: String?,
    val temperature: Double? = 0.0
)

interface MemoryJobStore {
    // pending turns
    // maintenance jobs
    // mutation groups/receipts
    // checkpoints (if distillation enabled)
    // CAS/lease primitives needed by mutation/job claim
}
```

说明：

- **LLM 多厂商协议属于宿主**，不是 memory 模块的事
- **何时在后台跑 `runDueJobs()` 属于宿主**（WorkManager/cron/queue 都行）
- **JobStore 可用 Room/SQLite/Postgres/内存实现**；核心库只依赖接口

---

## 6. 主流程行为契约

### 6.1 Ingest

输入一轮 user + assistant 完成后：

1. 过滤无效内容（空文本、助手错误消息等；参考源 `isAssistantErrorMessage` / `effectiveContent` 语义）
2. 生成稳定 `turnKey` / content hash，避免重复入队
3. 写入 pending turns
4. 更新 chat checkpoint / idle 计时
5. 返回 `recorded + pendingCount`

### 6.2 Batch trigger

至少支持这些 reason（可与源保持同名）：

- `threshold`：pending 达到阈值
- `idle`：用户一段时间无活动
- `context_compaction`：宿主在压缩上下文前主动 flush
- `manual_retry`

阈值/空闲参数应可配置，不要写死到无法调。

### 6.3 Consolidation

1. claim 一批 pending turns（lease/owner）
2. 读取现有相关 memories（至少 long-term；daily 视设计）
3. 调 `MemoryLlmClient` 要 JSON ops
4. 校验：
   - action: create/replace/remove/ignore
   - destination: daily/long_term
   - type/sensitivity/source 枚举
   - evidence turn keys 必须落在本 batch
   - replace/remove 目标必须存在
   - 文本归一后避免无意义重复写入
5. render 到目标 markdown
6. 经 mutation engine 提交
7. 成功后消费 pending turns；失败分 retryable / terminal

### 6.4 Mutation / File store

必须具备：

- 根目录隔离与 path traversal 防护
- `MEMORY.md` + `memory/yyyy-MM-dd.md`
- staging 文件
- backup
- 原子替换语义
- 基于 hash/generation 的冲突检测
- 崩溃恢复：未完成 receipt 可 reconcile

### 6.5 Recall

第一期：

1. 读当前 `MEMORY.md`
2. lexical retrieve
3. 去重（entryId / exact text）
4. 按 limit + tokenBudget 打包
5. `MemoryPromptBuilder` 风格生成 prompt

向量检索可后做；若预留接口，必须保持：

- lexical 永久可用
- vector 失败/未就绪时自动 fallback lexical

### 6.6 Prompt 风格

参考源 `MemoryPromptBuilder`：

- 标题：`Potentially relevant user memories:`
- 每条带 type/sensitivity/source/id/path
- 明确：只在真正相关时使用；不要强行提及记忆系统
- private/sensitive 增加谨慎处理提示
- 有最大条数与最大字符限制

---

## 7. Markdown 协议（需从源码固化到新项目文档）

从 `MarkdownMemoryCodec` / `MarkdownMemoryModels` 提炼并写进新项目 `PROTOCOL.md`：

- entry 必填字段：`id`, `text`, `type`, `sensitivity`, `source`, `createdAt`, `updatedAt`
- 可选：`chatId`, `section`
- 非法行 skip 而不是整文件失败（保持源行为，除非你有充分理由改，并写迁移说明）
- 文本归一函数用于去重与 exact compare

文件布局：

```text
{root}/
  MEMORY.md
  memory/
    2026-07-24.md
  .backup/
  .staging/
```

---

## 8. 配置项（应外置）

至少：

- `enabled`
- `batchThresholdTurns`（源里常见 5 轮量级，以源码常量为准）
- `idleFlushAfterSeconds`
- `recallLimit`
- `recallTokenBudget`
- `promptMaxCharacters`
- `maxOperationsPerBatch`
- `leaseDurationSeconds`
- `dailyDistillationEnabled`

不要把 ChatWithChat 的 UI 文案或通知文案带进配置。

---

## 9. 推荐实现阶段

### Phase 0 — 仓库脚手架

- Kotlin JVM library（优先）或 KMP 仅 JVM target 起步
- 单元测试框架
- 模块边界与包名
- `PROTOCOL.md` + `INTEGRATION.md` + `README.md`

### Phase 1 — core + engine MVP

- markdown codec
- file store
- lexical retriever
- prompt builder
- turn ingest + batch scheduler（显式 `runDueJobs`）
- consolidation pipeline + mutation commit
- 假 LLM 的端到端测试

**Phase 1 完成定义：**

- 给定 5 轮对话，能写入 pending，凑批，经 fake LLM ops 写入 `MEMORY.md`
- `prepareContext("相关查询")` 能召回刚写入内容并生成 prompt
- 非法 LLM 输出不会污染 markdown
- 模拟进程中断后，file/job 状态可安全恢复或可重试

### Phase 1.5 — daily distillation

- daily ops schema
- distillation service
- long-term 只从 daily 证据提升

### Phase 2 — 可靠性和 SPI 打磨

- lease/CAS 完善
- job 状态机文档化
- SQLite JobStore 参考实现
- OpenAI-compatible `MemoryLlmClient` sample

### Phase 3 — 可选增强

- embedding/vector SPI + 一个参考实现
- ChatWithChat adapter 迁移指南
- 基准测试与质量评估夹具

---

## 10. 从源码移植时的具体策略

1. **先抄协议与纯逻辑，再解耦 Android**  
   codec / normalization / prompt / ops validation 优先
2. **`LlmMemoryIntelligence` 只抽 prompt 与 JSON schema 期望**  
   删除所有 provider DTO；改为 `MemoryLlmClient`
3. **Room DAO 不要直接搬**  
   根据 DAO 行为设计 `MemoryJobStore`；可用内存实现先跑通测试
4. **Worker 只变成 `runDueJobs()` 的宿主调用约定**  
   不在库内依赖 WorkManager
5. **保留 fail-closed 与 lexical fallback 语义**  
   这是源项目花了大量测试换来的
6. **测试驱动搬迁**  
   能搬的 unit test 尽量改 import 后变绿；行为冲突时以“可接入模块的清晰契约”优先，并记录差异

---

## 11. 验收标准

### 功能

- [ ] 独立仓库可 build
- [ ] 无 Android SDK 依赖（core/engine）
- [ ] 无 ChatWithChat 包名依赖
- [ ] 假 LLM 端到端：ingest → batch → write → recall → prompt
- [ ] 真实 LLM client sample 至少一种（可选，但加分）
- [ ] 关闭 enable 后不再写入新记忆
- [ ] 导出/读取 `MEMORY.md` 成功

### 质量

- [ ] 主流程 unit tests 覆盖 batch/mutation/codec/recall
- [ ] 崩溃恢复或中断用例至少 1 组
- [ ] 路径穿越与非法 relative path 被拒绝
- [ ] LLM 坏输出不会写坏主文件

### 文档

- [ ] README：5 分钟接入
- [ ] PROTOCOL：markdown 与 ops schema
- [ ] INTEGRATION：宿主职责、线程/协程、何时 runDueJobs
- [ ] MIGRATION-FROM-CHATWITHCHAT：类映射表与差异

---

## 12. 风险与决策记录

| 风险 | 处理 |
| --- | --- |
| mutation/job 状态机复杂 | MVP 可简化实现，但必须保留 staging + 幂等 commit + 失败分层 |
| 与源 Room schema 强耦合 | 新项目不复用 Chat DB；只复用语义 |
| 多厂商 LLM 差异 | 全部推到 `MemoryLlmClient` |
| 向量栈过重 | 第一期不做；接口可预留 |
| 过度抽象 | 不要设计企业总线；围绕主流程 5 步建模 |

已拍板决策：

1. 只拆主流程，不拆通知/UI/Worker
2. Markdown 为真相源
3. 其他 AI 平台通过库 SPI 接入，而不是依赖 Android 模块
4. ChatWithChat 可作为后续第一个生产 adapter，但不是本任务的阻塞目标

---

## 13. 给执行 agent 的工作方式

1. 先只读扫描源仓库上述锚点文件与测试，再开始建新项目
2. 先写 `PROTOCOL.md` 与公共 API 草案，再写实现
3. 每完成一个 Phase 就跑测试并更新 README
4. 不要修改源 ChatWithChat，除非用户明确要求做双向迁移
5. 不要引入通知、UI、Android 组件“顺便完善”
6. 若源码细节与本提示词冲突：以源码真实行为为准，并在 `MIGRATION-FROM-CHATWITHCHAT.md` 记录

---

## 14. 可直接执行的第一步

1. 创建新仓库/新 Gradle 多模块项目
2. 建立 `memory-core`
3. 从源码移植并净化：
   - `MarkdownMemoryCodec`
   - `MemoryTextNormalization`
   - `MemoryPromptBuilder`
   - `MarkdownLexicalRetriever`
4. 定义 `MemoryEngine` / `MemoryLlmClient` / `MemoryJobStore`
5. 用 fake LLM 跑通最小闭环测试

---

## 15. 一句话目标

**把 ChatWithChat 已验证的“对话轮次 → 受控记忆写入 → Markdown 真相源 → 召回注入”主流程，抽成其他 AI 平台可接入的、无 App 壳依赖的记忆引擎。**
