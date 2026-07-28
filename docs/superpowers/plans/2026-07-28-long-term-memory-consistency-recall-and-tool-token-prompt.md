# ChatWithChat Long-Term Memory Consistency, Model Routing, Activity Runs, Tiered Recall, And Tool-Loop Token Budget Prompt

> **For implementation agents:** 这是一个可直接执行的实现交接提示词。先完整阅读 `AGENTS.md`，再重新审计当前分支、真实代码路径、数据库 schema、测试和设备状态，然后按 Task 0-8 顺序实施。本文记录的是 2026-07-28 的规划快照，不可替代 live repo。不得把本任务重新缩减为概念讨论，也不得把编译通过写成运行时验证完成。

## Goal

在不取消任何用户回合的本地长期记忆召回、不中断现有五轮记忆批处理、daily distillation、Markdown canonical store 和本地向量 fallback 的前提下，同时完成五件事：

1. 让长期记忆具备确定性的写时合并和周期性全集一致性整理，避免“同一个称呼、偏好或边界被存成多条互相竞争的记忆”。
2. 把普通聊天召回改为“始终存在的紧凑核心胶囊 + 通过绝对相关度门槛的查询相关层”，并保证维护元数据、过期项和 superseded 历史永不进入模型 prompt 或 embedding 文本。
3. 在 Memory 页面提供独立的默认记忆生成模型选择；默认 `Auto` 保持现有选择顺序，固定选择按 `platformUid + modelId` 持久化并由所有语义记忆任务共用。
4. 将一次语义记忆任务目前分散的 model call、generation、organization 三条日志合并为一个可推进 phase 的 activity run；Memory 页面每个 job attempt 只显示一行可读摘要。
5. 降低贴纸工具循环的重复请求和重复输入：JSON fallback 的正常 `search_stickers -> send_sticker -> final` 路径最多三次模型请求，native 路径最多三次且最终轮不再携带工具定义。

以下数值是首轮校准目标。Task 0 必须先记录基线；若实测要求调整，必须保留硬边界、补充测试，并在完成报告中写明旧值、新值和依据。

| Budget | Current snapshot | Initial target | Hard contract |
|---|---:|---:|---|
| Core capsule | 所有 `communication_style` 可能被强制加入 | 80-150 tokens，最多 4 个 canonical facts | 每个非空用户回合都构建；低相关时仍保留 |
| Query-related recall | 最多 8 条、900 tokens | 2-3 条、200-300 tokens | 必须通过绝对相关度门槛 |
| Complete model-visible memory section | 实际 prompt 还附带逐条 metadata，且最多 2200 chars | 目标不超过 450 tokens | 初始 hard cap 500 tokens，按最终渲染文本计数 |
| Activity rows per semantic job attempt | 当前 model call、generation、organization 各一行，通常共 3 行 | 1 个 activity run / 1 个 UI row | phase 只更新同一 run，不得再创建 top-level stage rows |
| JSON fallback sticker happy path | 通常 2 次本地工具执行、4 次模型请求 | 2 次本地工具执行、3 次模型请求 | 禁止 `draft final -> formal final` 的重复请求 |
| Native sticker happy path | 通常 2 次本地工具执行、3 次模型请求 | 保持最多 3 次 | search 后收窄工具；final request 的 tools 必须为空 |
| Serialized input reduction | 未建立固定基线 | JSON 至少下降 30%；native 至少下降 20% | 用同一 fixture 和 provider DTO 实测，不得只数调用次数 |

## Product Semantics And Hard Constraints

### Every turn still recalls locally

- 记忆开启时，每个具有正文或附件语义的用户回合都执行一次本地长期记忆召回，包括“你好”“在吗”等问候。
- “低相关”只意味着查询相关层为空，不意味着关闭记忆；核心胶囊仍应进入该回合。
- foreground recall 不得新增专用 LLM 调用、cloud embedding 调用或网络请求。
- 一个用户回合只读取和计算一个 recall snapshot。该 snapshot 的核心胶囊、相关记忆、projection hash 和最终 prompt 在整个工具循环中冻结，不得每轮重新召回或因后台写入而漂移。
- 向量索引缺失、过期或损坏时，若 canonical Markdown 可读，核心胶囊仍必须可用，查询相关层回退到 lexical；只有 canonical 文件本身不可读时才允许无记忆继续聊天并留下失败诊断。
- 尚未存储任何合格 core fact 时，仍要执行本地 recall 并返回空 core，不得为了“始终存在”而编造用户信息。

### Core capsule is small and canonical

核心胶囊只用于真正需要跨话题持续生效的少量事实，初始覆盖：

- 用户希望被如何称呼；
- 用户偏好的回复语言；
- 已合并成一条的沟通风格；
- 需要始终遵守的重要边界。

不得继续把所有 `communication_style` 逐条强制加入。核心胶囊必须按稳定顺序、稳定 key 和 token budget 构建；同一 `(canonicalKey, scope)` 最多一个 active/current 值。

### Scope is part of identity

- 单值事实的唯一键是 `canonicalKey + scope`，不能只按自然语言相似度合并。
- 法定姓名、通用昵称、工作场景称呼是不同语义；即使文本相似也不得错误折叠。
- 初始 scope 至少支持 `general`、`work`、`personal` 以及受控的 `project:<stable-id>` / `chat:<id>`。scope 值必须是 bounded ASCII slug，不允许把任意用户正文塞入 metadata。
- 当前请求没有明确 scope 时，默认使用 `general`，但不得让较弱的 assistant inference 覆盖已有的用户明确 scope。

### Trust and time are deterministic

证据可信度固定为：

```text
user_confirmed > explicit_user_statement > assistant_inferred
```

- 较新的低可信度推断不得覆盖较旧的用户明确事实。
- 只有可信度相同时，才用证据发生时间解决冲突；时间仍相同时使用稳定 ID 做确定性 tie-break。
- `createdAt` 表示该 canonical active entry 首次建立的时间。
- `updatedAt` 只在自然语言事实、scope、validity、recall state 或 supersession 关系发生语义变化时更新。
- `lastObservedAt` 表示最近一次支持或纠正该事实的证据时间。重复观察同一事实只推进 `lastObservedAt`，不得推进 `updatedAt`，也不得因“该记忆被召回”而刷新。
- 不得在旧条目迁移时用“当前时间”伪造新鲜度。旧条目优先回填 `lastObservedAt = updatedAt`，其次为 `createdAt`；两者均为 `0` 时保持未知。

### Historical state is maintenance-only

- `validity` 初始只允许 `current`、`contested`、`obsolete`。
- `recallState` 初始只允许 `core`、`query`、`maintenance_only`。
- `supersededBy` 只对 `obsolete` 项有效，必须引用同一 canonical file 中存在的稳定 entry ID。
- 只有 `validity=current` 且 `recallState in {core, query}` 的 canonical entry 可以进入普通聊天 recall corpus。
- `contested`、`obsolete`、`maintenance_only`、缺少必要身份字段而被判定不安全的条目仅供维护整理使用。
- 历史条目可保留用于审计和后续整理，但不得进入 lexical candidates、vector candidates、Hybrid 融合、prompt packing 或 provider payload。

### Maintenance metadata is never model-visible chat context

以下字段只服务于本地维护、冲突解决、诊断或索引一致性，不得出现在普通聊天 prompt、embedding input 或 memory token budget 中：

```text
canonicalKey
scope
createdAt
updatedAt
lastObservedAt
validity
supersededBy
recallState
evidenceRefs
entry id
source path
source/provenance
sensitivity label
maintenance/job/checkpoint ids and hashes
```

普通聊天的模型可见记忆只包含经过筛选的自然语言事实和一段全局使用说明。内部 recall diagnostics 可以保留 opaque entry ID 用于调试，但该信息必须在 provider DTO 组装前被投影掉。

### Long-term consolidation is not an unconditional daily rewrite

- 写时 canonical merge 是第一道防线，所有长期写入路径都必须经过。
- 周期性全集整理是第二道防线，用于修复 legacy/unkeyed 条目、跨批次语义重复、冲突和失效历史。
- 默认触发策略为“累计 20 次 material long-term mutations”或“距离上次成功全集检查已 7 天且应用进入 maintenance idle window”，以先满足者为准。
- 不得每天无条件把整个 `MEMORY.md` 发给模型或重写文件。
- 本地确定性预检未发现任何 collision、unkeyed candidate 或语义候选组时，必须以 byte-identical no-op 完成，不调用 LLM、不重建索引。
- 全集过大时必须持久化分区游标并逐批检查；“bounded”不能等价为永远只检查最新 24/100 条。

### Memory generation uses an independent model preference

- Memory 页面提供紧凑的“记忆模型”选择器。首项固定为 `Auto`，其余选项来自当前 enabled chat-model catalog，并以“平台 / 模型”消歧展示。
- `Auto` 是既有用户和新用户的默认值，保持当前 platform ordering，并选择其中第一个通过 enabled model、provider support、model ID 与凭据校验的候选；该模式明确允许跳到下一可用候选。不得调用 `resolveDefaultChatModel()`，因为最近聊天模型会随聊天选择漂移，`PlatformModelV2.isDefault` 也只表示单个平台内部默认。
- 固定选择必须以稳定的 `platformUid + modelId` 原子持久化；显示名称只用于 UI，不得作为 identity。两项都缺失表示 `Auto`，半对、空白或损坏值必须 fail closed，不能猜测另一半。
- 该选择只控制 turn-batch consolidation、daily distillation 和 whole-corpus consolidation 使用的语义 LLM；不改变聊天默认模型、每个平台的默认模型、本地 embedding 模型或 foreground recall。
- 新的共享 `MemoryModelResolver` 必须返回精确的 `PlatformV2.copy(model = selectedModelId)` 或 typed unavailable reason。固定选择缺失、禁用、无凭据或不兼容时保留用户偏好，把任务置为 `blocked_dependency` 并显示原因；严禁静默切换其他 provider。
- semantic job 第一次 claim 后、任何 provider request 之前，必须把 resolved `platformUid + modelId` 用 CAS 持久化到 job/checkpoint。重试沿用冻结 identity；设置变化只影响尚未解析的未来任务。只有明确的 dependency repair/manual retry contract 才能清除尚无 durable proposal 的旧 binding。
- `PLAN_DAILY_DISTILLATION` 等纯本地 planner 不使用记忆模型，activity 中的模型字段必须为空。

### One memory task is one activity run

- turn-batch、daily distillation、whole-corpus consolidation 的每个 `jobId + attempt` 只能创建一个 top-level `activityRunId`。成功路径的 `model_resolution -> model_call -> generation -> organization` 是同一记录的 phase 变化，不是多个并列日志。
- 负责 semantic job 的 service 创建 run 并把同一个 `activityRunId` 传入 `LlmMemoryIntelligence` 和 organization path；底层 intelligence 不得自行新建 top-level activity rows。
- run 在 phase 推进时原位更新，terminal transition 必须幂等。摘要至少能显示 job type、最终 phase/status、平台/模型、输入条数、操作数、attempt、总耗时和 bounded error code。
- Memory 日志页每个 run 只显示一行；阶段状态可在该行内展开，但不得把阶段详情再次渲染成多个顶层列表项。若持久化阶段摘要，只能使用同一 parent row 上 bounded structured fields 或 strict/versioned summary，不得包含正文。
- planner 自身仍是一条独立 activity run，因为它可以 scheduled/skipped/no-op 而完全不调用模型；不能为了“单行”把 planner 和后续 semantic job 错误合并。
- 18 -> 19 migration 必须保留全部旧 activity rows。旧 `model_call` / `memory_generation` / `memory_organization` categories 只作 legacy read fallback；不得用不可靠的时间启发式改写历史记录。新写入路径从 migration 后开始满足单 run 合同。
- activity 只保存结构化状态和 bounded diagnostics，禁止保存 prompt、记忆正文、daily evidence、模型完整响应或贴纸候选正文。

### Tool optimization cannot weaken final answers

- 初始工具决策轮仍使用用户选择的 reasoning 配置。
- 只有成功 `search_stickers` 后的机械选图/发送轮可以使用独立的低 reasoning policy；正式最终回答恢复用户选择的 reasoning 配置。
- 不支持显式 reasoning 参数的 provider 使用其默认行为，不得发送不兼容字段。
- 贴纸候选仍必须包含 stable ID。JSON fallback 依赖文本中的候选 ID，不能直接删除 `ToolResult.content`；native transport 应通过 provider-specific projection 避免同时序列化等价的 `content` 和 `structuredContent`。
- 不能改变每条 assistant response 最多一个 sticker、typed `StickerRef` 持久化、本地渲染、不上传图片字节等既有产品合同。

## Verified Current State

以下事实来自当前 `main` 的只读审计。实现前仍必须重新确认，因为行号、schema 和用户工作区可能变化。

### Existing timestamp and Markdown protocol

- `MarkdownMemoryEntry` 已有 `createdAt`、`updatedAt`；`MarkdownMemoryCodec` 以隐藏 Markdown metadata `created=` / `updated=` 持久化，旧文件缺失时解析为 `0L`。
- create 会设置创建和更新时间；replace 会保留 `createdAt` 并刷新 `updatedAt`。
- 当前没有 `canonicalKey`、`scope`、`lastObservedAt`、`validity`、`supersededBy`、`recallState` 或 bounded evidence refs。
- codec 只要求 `id/type/sensitivity/source`，metadata tokenizer 按空白切分。新增值必须使用受控无空格编码，且旧 Markdown 必须继续解析。

### Current writes only close exact-text duplicates

- `MemoryBatchConsolidationService.retrieveExistingMemories()` 只从 maintenance retrieval 取与当前 batch 相关的最多 24 条、2400 tokens；它不是长期记忆全集索引。
- `validateOperations()` 和 render 后防线只阻止规范化文本完全相同的重复写入。
- 语义相同但措辞不同的称呼，或同一称呼 key 下互相冲突的值，仍可能同时 active。
- daily distillation 只消费今天之前的 daily files，并把最多 100 条、32000 chars 的长期记忆交给该批次；它不是对全部 `MEMORY.md` 的一致性整理。
- 当前 maintenance job type 中没有专用的 whole-corpus long-term consolidation job。
- `MemoryDailyDistillationOperationController` 已实现 `assistant_inferred < explicit_user_statement < user_confirmed` 的 source 排序；turn-batch 路径只验证 source 合法性，没有统一执行该冲突策略。

### Current corpora are not entry-level projections

- `MemoryCorpus.CHAT_RECALL_LONG_TERM` 与 `MemoryCorpus.MAINTENANCE_WORKING_SET` 已存在，不应再造平行枚举。
- 当前 corpus 差异主要决定是否读取 daily files；`MemoryChunker` 对可解析条目使用同一逻辑。
- 如果只给条目新增 `recallState=obsolete` 而不在 chunk/index 之前过滤，历史文本仍会进入普通 recall 和向量索引。
- embedding 文本当前来自 `chunk.text`，隐藏 metadata 本身没有直接嵌入；但 `MemoryChunker.contentHash` 包含 `createdAt/updatedAt`，snapshot `sourceHash` 又来自完整文件 bytes。纯维护时间变化会让向量快照失效并可能触发无意义重嵌入。

### Current recall is broad and relatively gated

- `MemoryRepositoryImpl.prepareMemoryContext()` 当前请求 `HYBRID`，`limit=8`、`candidateLimit=24`、`tokenBudget=900`，并把 `communication_style` 放入 `alwaysIncludeTypes`。
- lexical candidate 只要求 `score > 0`。当前单个中文字符也可能产生弱匹配。
- vector relevance floor 和 MMR diversity floor 都是相对当前最高分的 `0.85`；若所有候选都很弱，最高者仍可能被保留。
- `MemoryPromptBuilder` 会为每条事实重复输出 `type/sensitivity/source/id/path` 和逐条指导语，最后按 2200 chars 截断。
- retrieval token packing 主要按事实正文加固定 overhead 估算，没有按最终渲染后的真实 prompt 成本验收。
- 现有 `MemoryRecallRelevanceEvaluationTest` 使用 18 个 target + 90 个 distractor 的 108-entry corpus 验证 top results，但没有覆盖问候、绝对低分和 core-only 语义。

### Current planner observability is incomplete

- `PLAN_DAILY_DISTILLATION` 已是 repair-family 的持久 job。它是本地规划动作，不等同于 LLM 调用；真正的语义提炼 job 是 `DISTILL_DAILY_NOTES`。
- `MemoryMaintenanceProcessor.planDailyDistillation()` 执行 planner 并更新 job 状态，但没有对应的 `MemoryActivityLogger` 生命周期。
- 当前 activity categories 只有 `model_call`、`memory_generation`、`memory_organization`；activity entity 没有显式 `jobId/jobType/phase/triggerReason`。
- 因此 activity log 中看不到 `PLAN_DAILY_DISTILLATION` 是当前代码结构的预期结果，不足以证明 planner 从未运行；同时 startup optional step 捕获异常后缺少持久原因，也可能造成“零 job、零日志”。
- 当前 Room schema 为 18。若新增 durable whole-corpus checkpoint 和 activity structured columns，应集中到一个 18 -> 19 migration，并导出 schema。

### Current memory-model routing and activity granularity are implicit

- `MemoryBatchConsolidationService` 与 `MemoryDailyDistillationService` 各自选择第一个 `enabled && model.isNotBlank()` 的 `PlatformV2`，没有共享的记忆模型 preference 或 resolver。
- `LlmMemoryIntelligence.resolveMemoryPlatform()` 在首选平台不可用时还会再次选择第一个支持的平台；这会让未来的固定选择发生不可见的 provider fallback。
- `SettingRepository` 已有 `fetchPlatformModels()`、`fetchEnabledChatModels()` 和稳定的 `platformUid + modelId` 模型身份，但 `SettingDataSource`、Memory ViewModel 与 Memory 页面目前没有记忆模型专用状态或控件。
- `resolveDefaultChatModel()` 优先最近聊天模型，`PlatformModelV2.isDefault` 只表示平台内默认；两者都不能直接充当跨平台的记忆模型 preference。
- `LlmMemoryIntelligence` 当前为一次请求分别创建 `MODEL_CALL` 与 `MEMORY_GENERATION` 两行，batch organization path 再创建 `MEMORY_ORGANIZATION` 一行，因此一次成功的语义记忆 attempt 通常显示三行。
- 当前 `MemoryActivityLogDao.finish()` 只能结束记录，不能在同一 `logId` 上推进 phase；Memory UI 也按 category 逐行直接渲染。

### Current sticker loop amplifies repeated context

- JSON fallback 正常 sticker 路径通常是四次模型请求：`search_stickers`、`send_sticker`、工具协议内 `final_answer` draft、仓库再次生成 formal final answer；实际本地工具执行只有两次。
- native tool paths 通常为三次模型请求；普通工具轮仍重复携带 active tool schemas，只有额外 final round 明确设置 `tools=null`。
- 同一个 `memoryPrompt` 每个用户回合只生成一次，但会与 system prompt、runtime context、history/summary 和工具协议一起在各模型请求中重复序列化。
- `ToolLoopConfig` 当前 `maxToolRounds=3`、`maxScratchpadChars=8000`；`ToolPromptBuilder` 整体 prompt 上限为 12000 chars。
- `SearchStickersToolProvider` 同时写入等价的 textual `content` 与 `structuredContent`；native adapters 会把两者一起序列化。
- 机械工具轮沿用用户选择的完整 reasoning 配置。
- web-search auto decision 可能在工具循环前增加一次模型请求；当 decision 为 false 时，当前调用成本和 usage 可见性需要单独审计，不能把它混作 sticker tool call。

## Definitions

### Canonical memory identity

```text
CanonicalMemoryIdentity = (canonicalKey, scope)
```

- `canonicalKey` 是受控、稳定、语义化的 ASCII key，例如 `identity.preferred_address`、`locale.response_language`、`communication.response_style`。
- `scope` 描述事实生效上下文，不包含自然语言正文。
- open-ended event/project facts也必须获得稳定 key，但可以使用由 evidence identity 派生的 suffix；不得使用完整事实文本或不稳定的模型措辞直接作为 key。

### Material mutation

下列变化计入 long-term mutation threshold：create active fact、事实文本变化、scope/key 纠正、validity/recall state 改变、supersession/retraction。

仅更新 `lastObservedAt`、追加已存在的 evidence ref、索引修复或 activity log 不计入 material mutation threshold。

### Maintenance working set

`MAINTENANCE_WORKING_SET` 可读取完整 canonical metadata、active/contested/obsolete 历史、daily evidence 和内部 identifiers。它不等于 provider prompt，也不受普通聊天 500-token 上限约束，但每次 LLM maintenance request 仍必须 bounded。

### Chat recall projection

`CHAT_RECALL_LONG_TERM` 是从 canonical Markdown 派生的 active-only 投影：只包含 `validity=current` 且 `recallState=core|query` 的自然语言事实，以及本地筛选所必需但不会发给模型的内部 ranking fields。

### Turn recall snapshot

一个 immutable value，至少包含：

```text
canonical file revision
chat recall projection hash
core facts
query-related facts
retrieval mode/fallback diagnostics
fully rendered model-visible memory prompt
```

同一用户回合的所有 provider/tool requests 必须引用同一个 snapshot。

### Memory model preference and resolution

```text
MemoryModelPreference = Auto | Fixed(platformUid, modelId)
MemoryModelResolution = Resolved(platform snapshot, platformUid, modelId) | Unavailable(reason)
```

- preference 是 DataStore 中的用户设置；resolved identity 是 Room semantic job/checkpoint 中的执行快照，两者不能混为一份可变状态。
- `Auto` 两键均不存在；`Fixed` 两键必须同时存在且非空。resolution 发生在首次 claim 后并在网络调用前冻结。
- 固定模型失效属于可解释的 dependency block，不属于允许自动换 provider 的 transient network failure。

### Memory activity run

一个 activity run 表示一个 `jobId + attempt` 的顶层可读记录。它具有稳定 `activityRunId`、logical job type、current phase、status、resolved model identity/display snapshot、counts、timestamps 和 bounded diagnostic。phase update 不增加顶层 row，retry 可以产生新的 attempt run。

## Target Architecture

```text
Completed turns / daily evidence
  -> semantic maintenance job claim
  -> one activity run for jobId + attempt
       -> model_resolution phase
            -> MemoryModelPreference (DataStore)
            -> shared MemoryModelResolver
            -> resolve Auto or exact Fixed(platformUid, modelId)
            -> persist resolved identity before provider request
            -> explicit unavailable => blocked_dependency, no fallback
       -> model_call phase
       -> generation phase
       -> organization phase
       -> terminal status on the same row
  -> existing consolidation LLM contracts
  -> canonical operation proposal (key + scope + evidence)
  -> deterministic CanonicalMemoryMergePolicy
       -> trust ordering
       -> full-file key/scope uniqueness check
       -> stable active entry ID
       -> superseded maintenance history
  -> MemoryMutationCoordinator CAS / receipts
  -> canonical MEMORY.md
       |
       +-> MAINTENANCE_WORKING_SET projection
       |    all metadata + active/history + daily evidence
       |    -> write-time merge and periodic whole-corpus consolidation
       |
       +-> CHAT_RECALL_LONG_TERM projection
            current + core/query only
            -> compact core capsule
            -> lexical/vector candidates
            -> absolute relevance gate
            -> max 2-3 query facts
            -> natural-language-only MemoryPromptBuilder
            -> immutable TurnRecallSnapshot
            -> all requests in one chat/tool turn

Sticker tool state machine
  initial request (user reasoning, scoped tools)
  -> search_stickers result
  -> provider-specific compact candidate projection
  -> send-only mechanical request (low reasoning where supported)
  -> send_sticker result
  -> exactly one no-tools final request (user reasoning)
```

## Hard Data Boundary

| Data | Maintenance parse/working set | Local ranking/filtering | Embedding text | Model-visible chat prompt |
|---|---|---|---|---|
| Active natural-language fact | yes | yes | yes | yes |
| `canonicalKey`, `scope` | yes | identity/filter only | no | no |
| `createdAt`, `updatedAt`, `lastObservedAt` | yes | bounded recency/tie-break only | no | no |
| `validity`, `recallState`, `supersededBy` | yes | exclusion/filter only | no | no |
| source, sensitivity, evidence refs | yes | trust/privacy filter only | no | no |
| entry ID, path, file/job/checkpoint hash | yes | diagnostics/dedupe only | no | no |
| contested/obsolete/history text | yes | maintenance only | no | no |
| daily-note text | yes | maintenance only | no | no |
| memory-model preference/resolved IDs | settings/job/activity only | no recall ranking | no | no |
| activity phase/status/counts/duration | activity UI/diagnostics only | no | no | no |
| sticker candidate IDs and semantics | no memory persistence | tool round only | no | only current tool round |
| sticker bytes/path/hash | no prompt | local resolver only | no | no |

Defensive filtering must exist at the source projection and provider assembly boundaries. 只修改 `MemoryPromptBuilder` 不足以满足本合同。

## Risk Matrix

| Risk | Severity | Required mitigation |
|---|---|---|
| 不同 scope 的称呼被错误合并 | High | `(canonicalKey, scope)` identity + scope fixtures + fail closed |
| assistant inference 覆盖用户明确事实 | High | 本地 trust ordering；模型建议不能越权 |
| superseded 历史仍进入向量/prompt | High | chunk 前 active-only projection + adapter-level assertion |
| 维护时间刷新导致反复全量 embedding | High | full-file CAS hash 与 recall/embedding projection hash 分离 |
| 全集整理读取 stale snapshot 后覆盖新写入 | High | frozen base hash + CAS + replan，不做 blind overwrite |
| 全集整理输入无限增长 | High | persisted partition cursor、bounded candidate groups、最终全局 invariant check |
| startup 重复 enqueue 或 activity log storm | Medium | stable idempotency key + deterministic activity attempt key |
| 固定记忆模型失效后静默换 provider | High | typed resolver + `blocked_dependency` + no fallback contract |
| 设置变化令重试中途更换模型 | High | first-claim CAS freeze；retry 读取 persisted resolved pair |
| 同一 attempt 继续产生三条顶层日志 | Medium | service-owned activityRunId + guarded phase update + row-count tests |
| activity phase 更新乱序或 terminal 被覆盖 | Medium | monotonic/expected-phase DAO transitions + idempotent terminal replay |
| 绝对阈值过高导致相关事实丢失 | High | 108-entry corpus + hard negatives + lexical/vector/hybrid/fallback calibration |
| 绝对阈值过低导致无关记忆污染 | High | greetings/unrelated negatives + core-only acceptance tests |
| 工具集动态收窄破坏 provider continuation | High | provider-family serialized DTO tests + failure/retry matrix |
| 低 reasoning 影响正式回答 | High | 只用于 post-search mechanical round；final 恢复用户配置 |
| candidate 双投影修改破坏 JSON fallback | High | 文本 ID contract test；按 transport 投影，不删除 canonical result |
| activity log 泄露长期记忆正文 | High | structured counts/ids/hash only；禁止正文、candidate 文本和 prompt |
| Room 18 -> 19 migration 丢表或破坏恢复 | High | populated migration、schema export、FK/integrity、restart proof |

## Execution Discipline

1. 先运行 `git status --short --branch`、`git branch --show-current`、`git rev-list --left-right --count HEAD...origin/main`，保护所有已有 dirty files、stash、worktree 和设备数据。
2. 实施时使用独立分支，建议 `codex/long-term-memory-consistency-recall-token-budget`。除非用户明确授权，不 reset、不清理、不吸收无关改动。
3. 逐 Task 实施；每个 slice 先运行最小相关测试，再做更大验证。只 stage 本任务文件。
4. `MEMORY.md` 继续是 canonical user-owned memory source。Room checkpoint、activity log 和 vector store 是 operational/derived state，不得反转 source of truth。
5. LLM 负责语义归类、提出 key/scope/合并文本；Kotlin 代码负责字段枚举、trust、时间、唯一性、stable ID、bounds、CAS 和 replay safety。不要用一组关键词规则假装完成语义合并。
6. 记忆模型 preference 使用 DataStore；resolved job identity 与 activity run 使用 Room。不得把 UI 显示名称、最近聊天模型或平台内 default flag 当作跨平台记忆模型 identity。
7. 所有新的 model output contract 使用 strict serialization、unknown-field rejection、bounded arrays/text 和 controlled operations。
8. schema 变化只建立一条 live baseline 到下一版的 migration。当前快照是 18 -> 19；若实施时 schema 已变化，以 live 值为准并解释，不得跳号或覆盖旧 migration。
9. 测试、compile、APK build、connected test 和真实聊天运行证据分开报告。没有设备时继续完成 JVM/build 工作，但 runtime gate 保持 `OPEN`。
10. 每完成一个 Task，更新本文 checklist 和实现记录；若用户要求提交/推送，使用小而可回滚的 topical commits。

## Implementation Tasks

### Task 0: Freeze Baseline Call Counts, Payload Sizes, And Live Maintenance State

**Goal:** 在改变行为前建立可复现的 memory relevance、serialized request、Room scheduling 和 runtime 基线。

**Likely files/tools:**

- `PromptTraceStore.kt`
- `ChatRepositoryImplTest.kt`
- `MemoryRecallRelevanceEvaluationTest.kt`
- `SettingDataSourceImplTest.kt`
- `LlmMemoryIntelligenceTest.kt`
- `MemoryActivityLogDao` recording/in-memory fakes
- provider request DTO serializers and recording fakes
- `adb`, `run-as`, Room schema export, existing Memory activity screen

**Implementation requirements:**

- [x] 记录 branch、HEAD、origin divergence、dirty files、当前 Room schema 和可用设备。
- [x] 用固定 stub responses 建立 JSON fallback sticker fixture：search -> send -> final draft -> formal final，记录每次模型请求和两次本地工具执行。
- [x] 为 OpenAI Responses、OpenAI Chat/OpenRouter、Anthropic、Google 建立对应 native baseline；Groq、Ollama、普通 Custom 归入 JSON fallback，但至少各验证一个真实 DTO shape。
- [x] 每轮记录 serialized request chars、估算 input tokens、provider-reported usage（若有）、system/history/memory/tool schema/tool result 各自字符数。
- [x] 单独记录 auto web-search decision 是否产生额外模型请求及 decision=false 时的 usage 去向。
- [x] 对当前 `MemoryPromptBuilder` 记录 8-entry/900-token request 与最终渲染后真实 token 差异。
- [x] 扩展 relevance baseline：问候、无关闲聊、称呼问题、偏好问题、项目事实、中文改写、单中文字符干扰、108-entry corpus。
- [x] 检查 `PLAN_DAILY_DISTILLATION` job rows、checkpoint、activity rows 和 startup path；明确“job 未创建”“job 已运行但 activity 不可见”“memory disabled”三种状态。
- [x] 记录当前 turn-batch 与 daily-distillation 实际选择的平台/模型、选择顺序，以及 `LlmMemoryIntelligence` 对无效 preferred platform 的二次 fallback。
- [x] 用一个成功、一个 invalid JSON、一个 organization failure fixture 证明当前每个 semantic attempt 分别会写多少条 activity rows、各 category/status 和 UI 列表项。
- [x] 记录 Memory 页面当前没有模型选择器、DataStore 没有记忆模型 keys、现有 enabled model catalog 中重复 model ID 如何用 platform identity 消歧。
- [x] baseline instrumentation 只记录长度、计数、stage 和 opaque IDs；不得把真实用户 prompt/记忆正文写入持久日志。

**Acceptance criteria:**

- [x] 一个命令可重复生成同一固定 fixture 的 request-count/token 表。
- [x] 报告明确区分 model request、local tool execution、maintenance job 和 activity log。
- [x] baseline 明确给出“一次语义记忆 job attempt -> 当前 3 条顶层 activity rows”的可重复证据，以及实际 selected platform/model。
- [x] 所有后续性能断言使用该 baseline，而不是凭 UI 感觉或单一总 token 数。

**Focused verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryRecallRelevanceEvaluationTest" --tests "*ChatRepositoryImplTest" --tests "*LlmMemoryIntelligenceTest" --tests "*SettingDataSourceImplTest"
adb devices
git diff --check
```

#### Task 0 Implementation Record (2026-07-28)

**Isolation and live state**

- Business-code baseline: `main` / `ff04c1b6d0dde699b35018ebb08f19d0d67da9f6`; implementation branch: `codex/long-term-memory-consistency-recall-tool-token-prompt`. After the plan-only commit `04361d3`, divergence was `1 ahead / 0 behind` from `origin/main`.
- The existing `codex/chatwithchat-identity-migration` worktree and `stash@{0}` were left untouched. Task 0 introduced only deterministic test fixtures and this record.
- Source/exported/live Room schema: `18`; exported identity hash `196bf38988a82cad2e137b094552173d`. Live `chat_v2` passed `integrity_check` and had no FK violations.
- Device: `emulator-5556`, API 35, x86_64, 16 KB page size. Installed `cn.nabr.chatwithchat` was `1.0.0 (22)`, APK SHA-256 `646a03a51fea16cf51a2bfd8d375b206725c10017ff1ec34e5e180bf9150c4d3`. No install, clear-data, or app-data mutation was performed for Task 0.
- Live DataStore had no `memory_enabled` key, so the repository default was disabled. The DB contained one `sync_vector_index/succeeded` job and zero daily-plan jobs, semantic jobs, distillation checkpoints, or activity rows. This is the explicit **memory disabled -> job not created** case; it is not evidence that a planner ran invisibly.

**Recall baseline**

`MemoryRecallBaselineReportTest` fixes one invocation per case and uses the production token estimator. The existing retriever request is `HYBRID`, `limit=8`, `candidateLimit=24`, `tokenBudget=900`, with all `communication_style` entries forced into the result. This fixed fixture deliberately reports embedding as not provisioned, so the asserted runtime mode is `LEXICAL_FALLBACK`; the repeatable command below separately runs the vector-ready semantic and Hybrid fixtures.

| Case | Selected behavior | Legacy pack estimate | Final prompt chars / estimated tokens |
|---|---|---:|---:|
| greeting / unrelated | no lexical candidates, but both style rows forced | 72 | 409 / 179 |
| preferred address | address plus both style rows | 105 | 552 / 230 |
| project paraphrase | project plus both style rows | 112 | 580 / 236 |
| single-CJK weak match | address, education, and both style rows | 148 | 711 / 293 |
| 108-entry corpus | target events 18 through 11; no distractor in prompt | 464 | 1503 / 677 |

The 8-entry case demonstrates the current accounting gap directly: the production OpenAI heuristic with the legacy fixed per-result overhead estimates 464 tokens during packing, while the same estimator reports 677 tokens for the rendered prompt because metadata and repeated guidance are added afterward. These are deterministic estimates with a blank model identity, not provider-reported usage.

**Sticker request and payload baseline**

The payload fixture normalizes only the volatile local runtime timestamp before measuring. It executes the production `SearchStickersToolProvider` and `SendStickerToolProvider` against a deterministic repository fake, including candidate-session validation and one `StickerPresentationArtifact`; provider DTOs, prompts, history, tool schemas, and tool results therefore retain production shape. Per-round system/history/memory/schema/result character lists are emitted and frozen by exact assertions. Each stub response also supplies deterministic provider usage so aggregation remains observable; estimated input tokens are still measured independently with `TokenUsageEstimator`.

| Transport | Model requests | Local executions | Per-round serialized chars | Per-round estimated input tokens | Final tools | Candidate ID occurrences | Provider usage: answer / tool | Sticker presentations |
|---|---:|---:|---|---|---:|---:|---|---:|
| Custom JSON | 4 | 2 | 1371, 1748, 1928, 1069 | 614, 778, 866, 513 | 0 | 5 | 40/4/44 / 100/10/110 | 1 |
| Ollama JSON | 4 | 2 | 1367, 1744, 1924, 1065 | 617, 781, 869, 516 | 0 | 5 | 40/4/44 / 100/10/110 | 1 |
| Groq JSON | 4 | 2 | 1422, 1799, 1979, 1120 | 631, 795, 883, 530 | 0 | 5 | 40/4/44 / 100/10/110 | 1 |
| OpenAI Responses native | 3 | 2 | 1529, 2208, 2513 | 648, 850, 949 | 2 | 5 | 30/3/33 / 60/6/66 | 1 |
| OpenRouter native | 3 | 2 | 1672, 2403, 2740 | 852, 1081, 1196 | 2 | 5 | 30/3/33 / 60/6/66 | 1 |
| Anthropic native | 3 | 2 | 1525, 2227, 2557 | 825, 1048, 1163 | 2 | 5 | 30/3/33 / 60/6/66 | 1 |
| Google native | 3 | 2 | 1497, 2160, 2465 | 814, 1027, 1138 | 2 | 5 | 30/3/33 / 60/6/66 | 1 |

JSON therefore starts at 4 requests and 2771 estimated input tokens for the canonical Custom fixture. OpenAI Responses starts at 3 requests and 2447 estimated input tokens. Native final requests still advertise both `search_stickers` and `send_sticker`; every transport serializes the stable candidate ID five times across the turn. With `decision=false`, the decision adds one request before the four JSON requests (`5` total). Its exact `11/3/14` provider usage is absent from the exposed record, which contains only the answer `40/4/44` and tool `100/10/110` usage from the four provider responses.

**Model routing, activity, and UI baseline**

- Batch and daily services independently choose the first `platform_id ASC` row with `enabled && model.isNotBlank()`. `LlmMemoryIntelligence` then silently retries resolution against the first supported platform if the preferred platform is invalid; credential validation happens later and does not continue to a second candidate.
- The live code/config-derived winner was the enabled `CUSTOM / deepseek-v4-pro` row with credentials present, routed through Chat Completions. Memory was disabled and no semantic provider request ran, so this is not runtime provider-call proof.
- DataStore had no memory-model keys and the Memory screen had no model picker. Existing enabled-model identity is the exact `platformUid + modelId` pair; duplicate model IDs are disambiguated by platform in the current chat picker.

The turn-batch fixture now uses the production `RoomMemoryActivityLogger` against an in-memory implementation of the DAO contract. It asserts the persisted entity fields and the exact `observeLatest()` list consumed unchanged by `MemoryViewModel`; this is DAO/UI-source evidence, not a claim that Compose rendered on the device during Task 0.

| Turn-batch fixture | Categories | Statuses | Resolved platform / model | DAO rows / UI-source rows |
|---|---|---|---|---:|
| success | model_call, memory_generation, memory_organization | succeeded, succeeded, succeeded | Memory baseline / memory-baseline-model | 3 / 3 |
| invalid JSON | model_call, memory_generation, memory_organization | succeeded, failed, failed | Memory baseline / memory-baseline-model | 3 / 3 |
| organization failure | model_call, memory_generation, memory_organization | succeeded, succeeded, failed | Memory baseline / memory-baseline-model | 3 / 3 |

The baseline correction to the planning snapshot is that **daily distillation currently creates only the two LLM rows**, because its service has no organization logger. `PLAN_DAILY_DISTILLATION` creates no activity row at all, and startup optional-step failures can still end with no persisted reason.

**Repeatable command**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryRecallBaselineReportTest" --tests "*HybridMemoryRetrieverTest.semantic paraphrase retrieves current visible memory with vector provenance" --tests "*HybridMemoryRetrieverTest.hybrid uses deterministic RRF and keeps lexical and vector scores" --tests "*MemoryBatchConsolidationServiceTest.current semantic attempt activity baseline reports three top level rows" --tests "*ChatRepositoryImplTest.current sticker request baseline report is deterministic across provider DTOs" --tests "*ChatRepositoryImplTest.false auto search decision adds one request and drops its usage baseline"
```

The fixtures print only fixed synthetic text, lengths, counts, bounded stages, and stable synthetic IDs. They do not persist prompt, memory, candidate, or credential content. Task 5 and Task 7 acceptance measurements must reuse these exact fixtures and compare total plus component-level deltas.

### Task 1: Add Backward-Compatible Canonical Maintenance Metadata

**Goal:** 扩展 Markdown entry protocol，定义时间、validity、recall 和 supersession 语义，同时保持旧文件无损可读。

**Likely files:**

- `data/memory/MarkdownMemoryModels.kt`
- `data/memory/MarkdownMemoryCodec.kt`
- `data/memory/MemoryCorpus.kt`
- `data/memory/MarkdownMemoryLearningModels.kt`
- `data/memory/MemoryBatchConsolidationModels.kt`
- `data/memory/MemoryDailyDistillationModels.kt`
- `MarkdownMemoryCodecTest.kt`

**Implementation requirements:**

- [ ] 为 `MarkdownMemoryEntry` 增加 `canonicalKey`、`scope`、`lastObservedAt`、`validity`、`supersededBy`、`recallState` 和 bounded `evidenceRefs`。
- [ ] 保留现有 `createdAt` / `updatedAt`；按本文语义实现，不得另造含义重叠的“最后更新时间”。
- [ ] Markdown metadata keys 使用稳定 snake_case，例如 `canonical_key`、`scope`、`observed`、`validity`、`superseded_by`、`recall`、`evidence`。
- [ ] key/value 使用受控 ASCII grammar 和长度上限；evidence refs 使用 bounded、可逆、无空格编码，不允许自然语言正文。
- [ ] legacy defaults：缺少新字段时仍解析；`validity=current`、`recallState=query`，时间按已有值回填，`canonicalKey` 保持 unknown 直到 maintenance backfill。
- [ ] legacy `communication_style` 过渡期不得再次变成“全部 core”；只允许 bounded fallback，等待 canonical consolidation。
- [ ] codec render/parse round-trip 保留新字段；针对未知但语法安全的 metadata，targeted replace 不得静默删除未来版本字段。
- [ ] malformed lifecycle combinations fail closed：例如 obsolete 无有效 supersession target、current 指向 supersededBy、maintenance-only 被标 core。
- [ ] metadata-only observation update 不改 entry text、createdAt、updatedAt 或 embedding text。
- [ ] 不在启动时批量重写全部历史 `MEMORY.md`；backfill 通过后续 durable maintenance job 完成。

**Acceptance criteria:**

- [ ] 旧 Markdown byte content 可读取，未触发 backfill 时不被自动重写。
- [ ] 新字段完整 round-trip；多语言正文、手写 section/footer 和 unrelated entries 保持不变。
- [ ] `lastObservedAt` 更新只改变对应 hidden metadata。
- [ ] malformed/oversized metadata 被拒绝或降为 maintenance-only，不能进入 chat projection。

**Focused verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*MarkdownMemoryCodecTest" --tests "*MemoryChunkerTest"
./gradlew.bat :app:compileDebugKotlin
git diff --check
```

### Task 2: Enforce Deterministic Write-Time Canonical Merge

**Goal:** 让 turn-batch 与 daily-distillation 的每一次长期写入都先按 `canonicalKey + scope` 合并，不能再依赖 top-24 相关记忆碰巧包含冲突项。

**Likely files:**

- `data/memory/MemoryBatchConsolidationModels.kt`
- `data/memory/MemoryBatchConsolidationService.kt`
- `data/memory/MemoryControlledOperations.kt`
- `data/memory/LlmMemoryIntelligence.kt`
- `data/memory/MemoryIntelligence.kt`
- `data/memory/MarkdownMemoryCodec.kt`
- corresponding unit tests

**Implementation requirements:**

- [ ] 扩展 strict LLM operation contract，使 create/replace candidates 带受控 `canonicalKey`、`scope`、证据时间和目标 recall state。
- [ ] 抽出共享 `CanonicalMemoryMergePolicy`（名称可按现有风格调整），供 turn-batch 和 daily-distillation 两条长期写入路径共同使用。
- [ ] LLM 提议 key/scope/合并文本；本地 policy 重新校验 grammar、type compatibility、trust、evidence、时间和唯一性。
- [ ] 每次长期 commit 前本地解析完整 `MEMORY.md` 建立 canonical identity index。相关 working-set limit 只用于 LLM 上下文，不能作为最终 collision guard。
- [ ] 同一 identity + 同一事实：stable active ID 不变；只合并 bounded evidence refs 并推进 `lastObservedAt`。这是非 material 的 metadata mutation，不得创建第二条 active fact、推进 material threshold 或触发 embedding rebuild。
- [ ] 同一 identity + 新事实：先按 trust，再按 evidence time 决胜。不能同时留下两个 current/query 值。
- [ ] active canonical entry 的 ID 在后续更新中保持稳定。需要保留旧值时，创建 deterministic maintenance-history ID，将旧值标 obsolete/maintenance-only 并令 `supersededBy` 指向 active survivor。
- [ ] 多个 legacy entries 冲突时，确定性选择 survivor；losers 进入 maintenance history。重复执行结果必须相同。
- [ ] 不同 scope、不同 canonical key、合法 multi-valued events 必须继续共存。
- [ ] 现有 exact-text duplicate 防线保留，canonical uniqueness 成为额外而不是替代约束。
- [ ] process death、mutation replay、LLM 重复返回和 concurrent file revision 均不得产生第二个 active entry。
- [ ] 较弱 evidence 被拒绝时不得创建“候选 active”旁路；如需保留，只能 contested/maintenance-only。

**Acceptance criteria:**

- [ ] 两种措辞表达同一通用称呼时，只存在一个 current/general active fact。
- [ ] 法定姓名、general nickname、work-context address 可同时存在并各自唯一。
- [ ] `user_confirmed` 不被更新的 `assistant_inferred` 覆盖。
- [ ] equal-trust evidence 由时间稳定决胜；相同输入 replay 不改变 bytes。
- [ ] 两个写入服务对 trust、timestamp、ID 和 lifecycle 的行为一致。

**Focused verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryBatchConsolidationServiceTest" --tests "*MemoryDailyDistillationOperationControllerTest" --tests "*MemoryDailyDistillationServiceTest" --tests "*LlmMemoryIntelligenceTest"
./gradlew.bat :app:compileDebugKotlin
git diff --check
```

### Task 3: Add Durable Periodic Whole-Corpus Long-Term Consolidation

**Goal:** 在后台对完整长期记忆做可恢复的一致性扫描，修复历史 semantic duplicates、冲突、unkeyed legacy entries 和失效关系。

**Likely files:**

- new `MemoryLongTermConsolidationModels.kt`
- new `MemoryLongTermConsolidationScheduler.kt`
- new `MemoryLongTermConsolidationService.kt`
- `MemoryMaintenanceScheduler.kt`
- `MemoryMaintenanceProcessor.kt`
- `MemoryMaintenanceStartupCoordinator.kt`
- `MemoryMutationCoordinator.kt`
- new checkpoint entity/DAO
- `ChatDatabaseV2.kt`
- `ChatDatabaseV2Migrations.kt`
- `DatabaseModule.kt`
- exported Room schema

**Implementation requirements:**

- [ ] 增加明确的 `CONSOLIDATE_LONG_TERM_MEMORY` job type，放入 semantic family；不得复用 daily plan 名称掩盖其语义。
- [ ] 增加 durable checkpoint，至少记录 checkpoint/job identity、trigger reason、canonical file base hash/generation、recall projection hash、entry count、ordered snapshot identity、partition cursor、persisted proposal hash/content、status、row version、attempt/error、首次 claim 后冻结的 resolved memory `platformUid/modelId` 和时间。
- [ ] 当前 live schema 仍为 18 时，建立 18 -> 19 migration；同一 migration 同时预留 Task 6 需要的 resolved job model identity 与 nullable activity-run structured columns，避免连续无意义 bump。
- [ ] planner 在累计 20 次 material mutations 或距离上次成功全检 7 天时 enqueue；阈值和周期集中配置并有纯单元测试。
- [ ] 同一时刻最多一个 active whole-corpus checkpoint/job；startup、boot、manual retry 和 repeated repair 必须收敛到同一 idempotency key。
- [ ] 本地预检先扫描全部 entries，按 identity、type/scope、normalized text 和可用本地 similarity 生成 bounded candidate groups。
- [ ] 已有 canonical identity 的确定性 collision 优先本地解决；只把 unkeyed/ambiguous semantic groups交给 LLM。
- [ ] 大 corpus 用 persisted partitions 遍历全部 frozen entry IDs；每个 request 限制 entries/chars/operations。不得通过 `.take(100)` 声称完成全集整理。
- [ ] 每个 partition 的 LLM response 一旦校验并持久化，process restart 不再重复调用该 partition；最终 proposal 在 commit 前对整个 snapshot 做 invariant validation。
- [ ] 第一次需要语义调用时通过共享 `MemoryModelResolver` 解析并 CAS 冻结模型；所有 partitions/retries 使用同一 resolved pair，不因 Memory 页面设置或最近聊天模型变化而漂移。
- [ ] commit 必须复用 `MemoryMutationCoordinator` 的 CAS、receipt、staging、index reconciliation 和 recovery；禁止直接覆盖 `MEMORY.md`。
- [ ] base hash 冲突时 discard/replan，不把 stale proposal 套到新文件。
- [ ] no-op pass 必须不改文件 bytes、不推进 material generation、不 enqueue vector sync；只更新 checkpoint completion/activity。
- [ ] 每次 pass 最多应用 bounded operations；仍有 work 时基于新 generation 安排 continuation，不能无限单 job。
- [ ] memory disabled 时 future periodic work 取消或 dismissed，不形成 retry loop；重新启用只恢复一个有效 planner。

**Acceptance criteria:**

- [ ] 一个超过单请求上限的 corpus 最终每个 entry 都被扫描，cursor 可从 process death 继续。
- [ ] 两个历史同义称呼在 pass 后收敛为一个 active survivor，且 replay byte-identical。
- [ ] clean corpus weekly pass 零 LLM calls、零 file/index mutation。
- [ ] concurrent foreground write 导致 safe replan，不丢新记忆。
- [ ] populated 18 -> 19 upgrade 保留所有旧表、rows、memory jobs 和 activity logs。

**Focused verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryLongTermConsolidation*" --tests "*MemoryMaintenanceSchedulerTest" --tests "*MemoryMaintenanceProcessorTest" --tests "*MemoryMutationCoordinatorTest" --tests "*ChatDatabaseV2MigrationsTest"
./gradlew.bat :app:compileDebugKotlin
git diff --check
```

### Task 4: Separate Maintenance, Recall, Embedding, And Prompt Projections

**Goal:** 在 chunk/index 之前建立真正的数据边界，确保维护 metadata 和历史项不会从任何 fallback 路径进入聊天。

**Likely files:**

- `data/memory/MemoryCorpus.kt`
- `data/memory/MemoryCorpusSnapshotter.kt`
- `data/memory/MemoryChunker.kt`
- `data/memory/MemoryFileStore.kt`
- `data/memory/MemoryRetrieval.kt`
- `data/memory/MemoryIndexSynchronizer.kt`
- `data/memory/MemoryVectorIndexBootstrapService.kt`
- `data/memory/vector/MemoryVectorIndexDefaults.kt`
- vector store manifest/config tests

**Implementation requirements:**

- [ ] 保留现有两个 corpus enum，但让 snapshot/chunker 明确接收 projection policy。
- [ ] `MAINTENANCE_WORKING_SET` 输出全部 entries/metadata/history/daily；`CHAT_RECALL_LONG_TERM` 在 split/chunk/vector 之前过滤为 current + core/query active entries。
- [ ] 不允许 fallback section chunking 把 raw hidden comments 或 obsolete bullets重新带入 chat corpus；chat projection parse 失败时 fail closed，并记录 diagnostics。
- [ ] 区分 canonical full-file CAS hash 与 active recall projection hash。不得改变 mutation receipt 对真实文件 bytes 的比较语义。
- [ ] embedding input 与 embedding content hash 只依赖 active natural-language semantic content；不得包含 maintenance timestamps、IDs、key/scope、path 或 lifecycle labels。
- [ ] `lastObservedAt` / evidence-only 更新不使 embedding snapshot stale；active text/membership 变化必须使 recall projection 和向量身份变化。
- [ ] `MemoryChunker` 的 ranking/diagnostic hash 与 embedding hash 如需不同，应使用不同命名字段，不能继续让一个 `contentHash` 同时承担冲突语义。
- [ ] projection/chunker 语义变化必须提升 fingerprint/version，使旧 ObjectBox snapshot 受控 rebuild 一次；不能把旧 index 当兼容数据直接读取。
- [ ] vector missing/stale/corrupt fallback 仍从同一个 active-only chat projection做 lexical，不得回退到 maintenance corpus。
- [ ] internal retrieval IDs 只能进入 `PromptTraceStore` 等本地诊断，provider assembly 必须再做 defense-in-depth assertion。

**Acceptance criteria:**

- [ ] obsolete、contested、maintenance-only 和 daily entries 在 lexical、vector、Hybrid、fallback 中均为零候选。
- [ ] metadata-only observation update 前后 embedding texts/hashes 完全相同，且不产生 vector sync job。
- [ ] active fact 文本或 membership 改变会触发恰好一次 index reconciliation。
- [ ] maintenance reader 仍能看到完整 metadata 和历史。
- [ ] canonical file CAS/recovery tests 保持通过。

**Focused verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryCorpusSnapshotterTest" --tests "*MemoryChunkerTest" --tests "*MemoryIndexSynchronizerTest" --tests "*HybridMemoryRetrieverTest" --tests "*MemoryVectorIndex*"
./gradlew.bat :app:compileDebugKotlin
git diff --check
```

### Task 5: Implement Always-On Tiered Recall With Absolute Relevance Gates

**Goal:** 每个用户回合都保留小型核心上下文，只在绝对相关时添加 2-3 条 query facts。

**Likely files:**

- `data/memory/MemoryRetrieval.kt`
- `data/memory/MarkdownLexicalRetriever.kt`
- `data/memory/HybridMemoryRetriever.kt`
- `data/memory/MemoryPromptBuilder.kt`
- `data/repository/MemoryRepositoryImpl.kt`
- `data/repository/MemoryRepository.kt`
- `presentation/ui/chat/ChatViewModel.kt`
- `data/debug/PromptTraceStore.kt`
- related tests and evaluation fixtures

**Implementation requirements:**

- [ ] 引入明确的 `TieredMemoryRecall` / `TurnRecallSnapshot` 边界，使用一个 canonical snapshot 同时生成 core 和 query layer。
- [ ] core 按 `recallState=core` 和受控 canonical keys 构建，稳定排序、去重、最多 4 facts，目标 80-150 tokens。
- [ ] legacy fallback 必须 bounded；不得继续使用 `alwaysIncludeTypes = communication_style` 把全部风格记忆注入。
- [ ] query layer 最多 3 facts、目标 200-300 tokens；先过 absolute floor，再用 RRF/MMR 做相对排序和去冗余。
- [ ] lexical 初始 floor 以 `1.25f`（至少一个有意义 Latin/CJK bigram match 加现有 long-term bonus）作为校准起点；单个弱中文字符命中不能单独通过。
- [ ] vector cosine-similarity 初始 floor 以 `0.45f` 作为校准起点。Task 0 corpus 可支持调整，但必须写出分布、false positive/negative 和最终常量；相对 `0.85 * max` 不能替代 absolute floor。
- [ ] Hybrid candidate 需要满足 lexical floor 或 vector floor；RRF/MMR 只处理 survivors。
- [ ] “你好”与无关闲聊应返回 core-only；称呼问题应返回 scoped active称呼且没有旧值；项目问题命中项目事实。
- [ ] `MemoryPromptBuilder` 只接收 model-visible fact projection，只输出自然语言 bullet；不得再输出 `type/sensitivity/source/id/path/timestamps/key/scope/status`。
- [ ] token budget 使用最终渲染 prompt 的 estimator 验收，不再只按 memory text + 24 overhead 近似。
- [ ] 使用一段全局 privacy/usage guidance 代替每条重复指导语。
- [ ] `PreparedMemoryContext` 暴露 immutable snapshot 给同一 turn；`ChatViewModel` 只调用一次，所有 tool rounds 复用，不重新读取索引。
- [ ] core/file failure、lexical fallback、Hybrid、vector-only diagnostics 在本地 trace 中可区分，但不能出现在 provider prompt。
- [ ] 扩展 108-entry evaluation，加入 hard negatives、greeting、scope conflicts、superseded rows 和 threshold boundary fixtures。

**Acceptance criteria:**

- [ ] 在 seeded core fixture 下，greeting 的 recall invocation count 为 1，core 非空，query facts 为 0；无 core 的新用户仍调用一次并返回空 core。
- [ ] unrelated query 不再因为“最高分”被迫带回事件记忆。
- [ ] lexical/vector/Hybrid/vector-failure lexical fallback 遵守相同 active-only 和 absolute-gate 合同。
- [ ] 完整模型可见 memory section 初始 hard cap 为 500 tokens，且不含任何 maintenance metadata 字符串。
- [ ] 108-entry target coverage 不回退，新增 hard negatives 不泄漏。

**Focused verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryRepositoryTest" --tests "*MarkdownLexicalRetrieverTest" --tests "*HybridMemoryRetrieverTest" --tests "*MemoryPromptBuilderTest" --tests "*MemoryRecallRelevanceEvaluationTest"
./gradlew.bat :app:compileDebugKotlin
git diff --check
```

### Task 6: Add Default Memory Model Selection And Unified Activity Runs

**Goal:** 让用户在 Memory 页面明确选择后续语义记忆任务使用的模型，并让每个 job attempt 只产生一条可读、可推进 phase 的 activity run，同时完整保留 planner 可观测性且不记录记忆正文。

**Likely files:**

- `data/datastore/SettingDataSource.kt`
- `data/datastore/SettingDataSourceImpl.kt`
- `data/repository/SettingRepository.kt`
- `data/repository/SettingRepositoryImpl.kt`
- new `data/memory/MemoryModelPreference.kt`
- new `data/memory/MemoryModelResolver.kt`
- `data/memory/LlmMemoryIntelligence.kt`
- `data/memory/MemoryBatchConsolidationService.kt`
- `data/memory/MemoryDailyDistillationService.kt`
- `data/memory/MemoryActivityLogger.kt`
- `data/database/entity/MemoryActivityLog.kt`
- `data/database/dao/MemoryActivityLogDao.kt`
- `data/database/entity/MemoryMaintenanceJob.kt` and DAO transitions if the resolved pair lives on the job
- `data/memory/MemoryMaintenanceProcessor.kt`
- `data/memory/MemoryDailyDistillationScheduler.kt`
- `data/memory/MemoryMaintenanceStartupCoordinator.kt`
- `data/memory/MemoryMaintenanceRepairer.kt`
- new whole-corpus scheduler/service
- `presentation/ui/memory/MemoryViewModel.kt`
- `presentation/ui/memory/MemoryScreen.kt`
- `res/values/strings.xml` and `res/values-zh-rCN/strings.xml`
- `SettingDataSourceImplTest.kt`, new resolver tests, memory service/activity tests, `MemoryViewModelInstrumentedTest.kt`
- schema 19 migration from Task 3

**Implementation requirements:**

- [ ] DataStore 增加 `memory_model_platform_uid` 与 `memory_model_id`；同一次 `edit` 写入 fixed pair，切回 `Auto` 时同一次 `edit` 删除两键。两键均缺失才表示 `Auto`；半对/空白值返回 typed invalid preference，不得当成 Auto。
- [ ] repository 暴露 `MemoryModelPreference.Auto | Fixed(platformUid, modelId)` 的读取/更新 API；existing user 无 keys 时保持 Auto。不得复用 `last_selected_model`、`resolveDefaultChatModel()` 或 `PlatformModelV2.isDefault` 存储这项设置。
- [ ] Memory ViewModel 用 `fetchEnabledChatModels()` 构建 picker options，首项 `Auto`，其余按 exact pair 选择并显示“平台 / 模型”。重复 model ID 必须正确消歧；已存 fixed pair 失效时保留并明确显示“原选择已不可用”，不能伪装成 Auto。
- [ ] 在 Memory content tab 顶部增加一条紧凑 settings row，点击后打开适合该页面的单选列表；不直接复用强绑定 reasoning 控件的聊天 `ModelSelectionMenu`。页面恢复前台时刷新 catalog，长名称截断，无可用模型与保存失败有稳定状态。
- [ ] 新建共享 `MemoryModelResolver`：Auto 保持当前 platform ordering，按顺序选择第一个通过 enabled model、provider support、required credential 与 nonblank model 校验的候选；Fixed 必须精确匹配 enabled platform 与 enabled model，并返回 `PlatformV2.copy(model = modelId)` 或 typed unavailable reason。只有 Auto 可以继续检查下一候选。
- [ ] turn-batch、daily distillation、whole-corpus consolidation 全部只使用该 resolver。移除 service 内重复的 `preferredMemoryPlatform()` 和 `LlmMemoryIntelligence.resolveMemoryPlatform()` 静默 fallback；intelligence 只接受已解析的 model snapshot。
- [ ] semantic job 在首次 claim 后、provider request 前，通过 expected-row-version/CAS 持久化 `resolvedPlatformUid/resolvedModelId`；retry 读取 frozen pair，未解析的未来 job 才读取新 preference。resolution failure 使用现有 `BLOCKED_DEPENDENCY`，不消耗无意义 provider call 或形成自动 retry storm。
- [ ] preference/platform/model 状态变化后，只重新评估未解析且因记忆模型依赖被 blocked 的 job；已有 durable proposal 或 frozen in-flight attempt 不得被悄悄 rebind。manual retry 如允许清除 binding，必须是显式动作并有测试。
- [ ] 将现有 `logId` 作为新 `activityRunId`，或引入等价稳定列；每个 `jobId + attempt` 通过 deterministic identity/unique index 只 upsert 一个 top-level run。enqueue 如需先显示 scheduled，使用确定的 next-attempt identity，claim 必须更新同一 run 而不是另建一行。activity record 增加 nullable `jobId`、`jobType`、`phase`、`triggerReason`、`platformUid`、`modelId` 及通用 input count；使用 Task 3 的同一个 18 -> 19 migration。
- [ ] logger API 改为 `startRun/advancePhase/finishRun` 或等价状态机。semantic service 在 model resolution 前创建 run；resolver 写入/结束 `model_resolution` phase，`LlmMemoryIntelligence` 只能推进同一 run 的 `model_call -> generation`，organization path 再推进到 `organization`，任何阶段都不得 `start()` 新行。
- [ ] phase transition 使用 expected phase/status 或 row version，必须单调、幂等且 terminal 不可被晚到更新覆盖。retry 是新的 attempt run；同一 attempt 的 process replay 仍更新同一 row。
- [ ] logical categories 至少区分 `maintenance_planning`、`turn_batch_consolidation`、`daily_distillation` 与 `long_term_consolidation`；status 覆盖 `scheduled/running/succeeded/no_op/skipped/blocked/failed`。`model_call`、`generation`、`organization` 只作为 phase，不再作为新 category rows 写入。
- [ ] `PLAN_DAILY_DISTILLATION` 从 enqueue、claim、process 到 terminal disposition 都可见，但它是独立 planner run，`platformUid/modelId/platformName/modelName=null`，不能和后续 `DISTILL_DAILY_NOTES` run 合并。
- [ ] startup `ensurePlanningJobs()`、repair optional steps 和 memory-enabled transition 捕获异常时持久化 bounded reason，不再 silent `runCatching`。
- [ ] activity detail 只允许 job/checkpoint opaque ID、model identity/display snapshot、counts、cursor、hash prefix、duration、status/error code；禁止 credential、memory text、prompt、evidence text、完整 model response 和 sticker candidate data。
- [ ] memory disabled、no eligible daily file、not-due whole corpus、already-active、clean no-op 均有可区分状态。
- [ ] UI 保持现有 Memory activity tab，不做独立 diagnostics app；每个 run 一行摘要，紧凑显示任务、最终状态、平台/模型、输入数、操作数和总耗时。可展开的阶段详情属于该行内部，不能再次成为三条列表项。
- [ ] 若 UI 展开显示阶段结果，使用同一 activity row 中的 bounded structured phase summary（固定字段或 strict/versioned JSON）；不得为可展开详情重新创建可被顶层 DAO 查询到的 stage rows。
- [ ] 18 -> 19 migration 保留旧 activity rows 和 category 值；UI 对旧 category 提供 legacy fallback，不删除历史，也不按模糊时间窗口猜测并改写旧 run。

**Acceptance criteria:**

- [ ] 新安装与 18 -> 19 升级用户默认 `Auto`，实际选择顺序与当前 baseline 一致且不随最近聊天模型变化。
- [ ] fixed pair 在重启后保持；turn batch、daily distillation、whole corpus 都使用 exact selected model，重复 model ID 不串 provider。
- [ ] fixed model 缺失/禁用/缺凭据时产生一条可读 blocked run、零 provider requests，且 UI 仍显示原选择不可用而不是静默 Auto。
- [ ] 首次 claim 后修改 preference，当前 job 的 retry 仍使用 frozen pair；尚未解析的下一 job 使用新 pair。
- [ ] activity log 中可以看到一个 `PLAN_DAILY_DISTILLATION` 的完整本地生命周期，即使它没有触发 LLM。
- [ ] startup planning failure 不再出现“零 job 且零原因”。
- [ ] successful、model failure、invalid JSON 和 organization failure fixture 均为每个 attempt 恰好一条 DB activity run 与一条 UI row，phase/status 指向真实停止位置。
- [ ] duplicate startup/process replay 对同一 attempt 只更新一条逻辑记录；retry 可以新增一条 attempt row，但不能重新膨胀成三条 stage rows。
- [ ] 日志数据库中搜索不到 fixture 的 memory body 和 prompt body。

**Focused verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*SettingDataSourceImplTest" --tests "*MemoryModelResolver*" --tests "*LlmMemoryIntelligenceTest" --tests "*MemoryActivity*" --tests "*MemoryDailyDistillationSchedulerTest" --tests "*MemoryMaintenanceProcessorTest" --tests "*MemoryMaintenanceStartupCoordinatorTest"
./gradlew.bat :app:compileDebugAndroidTestKotlin
./gradlew.bat :app:compileDebugKotlin
git diff --check
```

### Task 7: Reduce Sticker Tool-Loop Requests And Repeated Payloads

**Goal:** 保留完整 sticker 功能和长期记忆召回，同时消除重复 final request、重复 candidate representation、无关工具 schema 和机械 reasoning 成本。

**Likely files:**

- `data/repository/ChatRepositoryImpl.kt`
- `data/tool/ToolLoopOrchestrator.kt`
- `data/tool/ToolScopePlanner.kt`
- `data/tool/ToolPromptBuilder.kt`
- `data/tool/SearchStickersToolProvider.kt`
- `data/tool/ToolResultBounds.kt`
- `data/tool/provider/OpenAICompatibleJsonToolAdapter.kt`
- `data/tool/provider/OpenAIResponsesToolAdapter.kt`
- OpenAI Chat, Anthropic and Google native adapters
- `data/websearch/SearchDecisionService.kt`
- `data/debug/PromptTraceStore.kt`
- tool/provider/repository tests

**Implementation requirements:**

- [ ] 把 sticker flow 表达为显式 state transition，而不是每轮始终复用初始 full tool scope。
- [ ] 初始请求按现有 enablement/scope 选择工具；成功、非空 `search_stickers` 后只暴露 `send_sticker`。空候选可以保留一次 bounded re-search，但不能重新暴露无关工具。
- [ ] `send_sticker` 成功后立即进入 final-only state，不再发一个仍带工具 schema 的“看看模型是否 final”请求。
- [ ] JSON fallback 的 final-only round 可以要求合法 `final_answer` envelope，但该 content 本身就是正式用户回答；不得再把它作为 draft 发给同一 provider 请求一次。
- [ ] native final-only request 的 `tools` / `toolChoice` 必须为空；所有 provider adapter 有 serialized DTO assertion。
- [ ] `ToolResult` 保留 canonical candidate data供 session validation。JSON prompt 使用 bounded textual representation；native adapters 只序列化一份 compact structured representation，不能重复发送等价 content。
- [ ] candidate projection 仍包含 stable `sticker_id`、bounded title/alt/tags；不包含 path、URI、asset hash、bytes 或完整 catalog。
- [ ] post-search mechanical round 使用 `ToolRoundReasoningPolicy.LOW` 或等价 provider-aware mapping；initial/final 使用用户 reasoning。unsupported provider 不发送伪参数。
- [ ] JSON scratchpad 只保留下一轮所需 calls/results；已被 compact summary覆盖的候选不得全文重复累积到 8000 chars。
- [ ] system/history/memory snapshot 在同一 turn 不重新计算。对必须重发的 stateless provider 保持 compact；支持 continuation 的 provider 不应重建重复 tool result。
- [ ] direct answer with tools available 仍为一次模型请求；failed/malformed/stale sticker ID 路径 bounded 且能生成最终文本或清晰错误。
- [ ] auto web-search decision 的额外调用在 request trace 和 usage aggregation 中可见；decision=false 不得静默丢失 usage。
- [ ] 不改变 typed `StickerRef` persistence、revision/retry、render、source metadata 和 token aggregation contracts。

**Acceptance criteria:**

- [ ] JSON fallback sticker happy path：恰好 3 model requests、2 local tool executions、1 persisted sticker presentation、1 final answer。
- [ ] native sticker happy path：最多 3 model requests；第二轮 schema 只含 companion tool，最后一轮 tools 为空。
- [ ] JSON fallback textual IDs 和 native structured candidates 均能成功 `send_sticker`，但每个 transport 的 serialized payload 只有一份 candidate list。
- [ ] formal final reasoning 与用户设置一致；只有 send-selection mechanical round 被降低。
- [ ] 固定 fixture 相比 Task 0：JSON total estimated input tokens 至少下降 30%，native 至少下降 20%；输出质量和 tool correctness tests 通过。
- [ ] usage detail 数量、tool-related flags、aggregate totals 与真实请求数一致。

**Focused verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*ToolLoopOrchestratorTest" --tests "*ToolScopePlannerTest" --tests "*ToolPromptBuilderTest" --tests "*StickerToolProviderTest" --tests "*ProviderToolAdapterTest" --tests "*OpenAIResponsesToolAdapterTest" --tests "*ChatRepositoryImplTest" --tests "*SearchDecisionServiceTest"
./gradlew.bat :app:compileDebugKotlin
git diff --check
```

### Task 8: Integrate, Migrate, Prove Runtime Behavior, And Document Evidence

**Goal:** 用完整测试、populated migration、provider payload、真实设备和持久状态证明新合同，而不是只证明代码可编译。

**Likely files:**

- all tests above
- `ChatDatabaseV2MigrationInstrumentedTest.kt`
- `MemoryViewModelInstrumentedTest.kt`
- sticker instrumented tests
- `docs/architecture/` memory/tool documentation
- this plan checklist and completion record

**Implementation requirements:**

- [ ] 跑全部 memory-focused JVM tests、tool/provider tests、compile 和 debug assembly。
- [ ] 导出并检查 schema 19；做 fresh open、populated 18 -> 19、restart、FK 和 integrity proof。
- [ ] 在 connected device/emulator 上保留 app data 升级，不使用 clear data 代替 migration；安装/替换前记录设备和 APK identity。
- [ ] seed legacy duplicates、scope variants、trust conflicts、obsolete history 和 pending maintenance job，升级后验证 canonical file、Room checkpoint、activity log 和 vector rebuild。
- [ ] 在 Memory 页面验证 `Auto`、fixed model、重复 model ID、不可用 fixed selection、长名称和返回前台刷新；重启应用后 preference 必须保持。
- [ ] 对 turn batch、daily distillation 和 whole-corpus 各触发一个 semantic attempt，核对实际 provider/model、frozen job identity，并确认每个 attempt 在数据库和 UI 中都只有一个 activity run。
- [ ] 真实发送“你好”并走一次 sticker flow：确认 recall exactly once、core-only、query layer empty、请求数/token、final tools empty、`StickerRef` 持久化并渲染。
- [ ] 再发送称呼问题和无关问题，确认唯一 active scoped称呼命中、旧称呼/无关事件不进入 prompt。
- [ ] 触发 mutation threshold 与 weekly-due simulation；验证 whole-corpus job、checkpoint resume、no-op、frozen model 和单行 activity phase progression。
- [ ] 检查 prompt trace/provider DTO 中不存在 maintenance metadata、obsolete text、重复 candidates 或 final tools。
- [ ] 运行 `git diff --check`、检查 status，只 stage 本任务文件；更新本文 checklist 和完成报告真实值。
- [ ] 没有设备、真实 provider usage 或 weekly clock runtime 时明确标记 `OPEN`，不得用 JVM fake 冒充。

**Acceptance criteria:**

- [ ] 所有 hard contracts 有自动化测试或明确 runtime evidence。
- [ ] `MEMORY.md` remains canonical；metadata-only update、semantic update 和 no-op 的 file/index 行为分别正确。
- [ ] Room 18 -> 19、process restart、vector fallback 和 activity UI 均有证据。
- [ ] 默认/fixed/unavailable memory-model 路由、retry freeze 和 single-run row counts 均有自动化及至少一次 UI/runtime 证据。
- [ ] sticker request/token target 在至少一个 JSON fallback 和一个 native provider path 上达到。
- [ ] app 的 attachment、edit、retry、export、multi-provider、reasoning、web search、memory toggle 和普通无工具聊天没有回归。

## Required Verification Commands

实施时以 live repo 的 task/class 名为准；若名称变化，使用等价命令并在报告中解释。

```powershell
git status --short --branch
git branch --show-current
git rev-list --left-right --count HEAD...origin/main

./gradlew.bat :app:testDebugUnitTest --tests "*MarkdownMemoryCodecTest" --tests "*MemoryChunkerTest" --tests "*MemoryCorpusSnapshotterTest"
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryBatchConsolidationServiceTest" --tests "*MemoryDailyDistillation*" --tests "*MemoryLongTermConsolidation*"
./gradlew.bat :app:testDebugUnitTest --tests "*SettingDataSourceImplTest" --tests "*MemoryModelResolver*" --tests "*LlmMemoryIntelligenceTest"
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryMaintenanceSchedulerTest" --tests "*MemoryMaintenanceProcessorTest" --tests "*MemoryMaintenanceStartupCoordinatorTest" --tests "*MemoryActivity*"
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryRepositoryTest" --tests "*MarkdownLexicalRetrieverTest" --tests "*HybridMemoryRetrieverTest" --tests "*MemoryPromptBuilderTest" --tests "*MemoryRecallRelevanceEvaluationTest"
./gradlew.bat :app:testDebugUnitTest --tests "*ToolLoopOrchestratorTest" --tests "*ToolScopePlannerTest" --tests "*StickerToolProviderTest" --tests "*ProviderToolAdapterTest" --tests "*ChatRepositoryImplTest" --tests "*SearchDecisionServiceTest"
./gradlew.bat :app:testDebugUnitTest --tests "*ChatDatabaseV2MigrationsTest"

./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:compileDebugAndroidTestKotlin

adb devices
./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=cn.nabr.chatwithchat.data.database.ChatDatabaseV2MigrationInstrumentedTest"
./gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=cn.nabr.chatwithchat.presentation.ui.memory.MemoryViewModelInstrumentedTest"

git diff --check
git status --short --branch
```

若 connected suite 使用特定 emulator，报告 serial、API、ABI、page size、APK hash、升级前后 schema 和是否保留原 app data。不得把 `adb install -r` 描述成无风险动作；先确认目标 package、APK identity 和设备数据要求。

## Required End-To-End Test Matrix

| Area | Scenario | Required result |
|---|---|---|
| Codec | legacy entry without new metadata | parses without rewrite; unknown times remain unknown |
| Codec | new metadata round-trip | all fields preserved; body/manual sections unchanged |
| Time | repeated same evidence | only `lastObservedAt` advances |
| Time | semantic fact update | stable active ID/createdAt; updatedAt and lastObservedAt advance |
| Canonical merge | synonymous general nickname | one current/general active fact |
| Canonical merge | legal/general/work names | three scoped identities coexist |
| Trust | newer assistant inference vs explicit/user-confirmed | stronger existing fact wins |
| Lifecycle | conflict/history | loser obsolete + maintenance-only + valid supersededBy |
| Replay | same batch/job twice | byte-identical; no duplicate active/history row |
| Whole corpus | more entries than one partition | every frozen ID inspected; cursor resumes after death |
| Whole corpus | clean weekly pass | zero LLM, zero file/index mutation, visible no-op |
| Whole corpus | concurrent foreground write | CAS conflict and deterministic replan; no lost write |
| Scheduling | threshold 19/20/21 | only due boundary enqueues one active job |
| Scheduling | memory disabled/re-enabled | no retry loop; one planner after re-enable |
| Model preference | no stored keys / clear selection | Auto; current first-qualified ordering preserved atomically |
| Model preference | fixed pair + app restart | exact platformUid/modelId retained; independent from last chat model |
| Model preference | duplicate model IDs across providers | exact pair selects the intended provider |
| Model resolution | fixed platform/model disabled, deleted, or uncredentialed | blocked_dependency; preference retained; zero provider calls; no fallback |
| Model resolution | preference changes after first claim | retry keeps frozen pair; next unresolved job uses new pair |
| Model UI | unavailable/empty/long-name/foreground refresh | truthful state, usable Auto option, no stale or overflowing label |
| Activity | semantic success | one DB run and one UI row; resolution through organization updates same run |
| Activity | model/JSON/organization failure | one run; phase identifies exact stopping point; no extra stage rows |
| Activity | process replay/retry | same attempt idempotent; new attempt one new run, never three rows |
| Activity | PLAN_DAILY_DISTILLATION | separate local planner run visible, model fields null |
| Activity | startup planner exception | bounded persisted failure reason, no silent zero state |
| Activity | populated 18 -> 19 legacy rows | all preserved and readable via legacy fallback; no guessed history rewrite |
| Projection | maintenance read | all metadata and obsolete history visible |
| Projection | chat read/index | current core/query only; no daily/history/metadata text |
| Index | lastObserved-only update | embedding input/hash unchanged; no sync job |
| Index | active text/membership change | one controlled rebuild/reconciliation |
| Recall | greeting | exactly one local recall; core-only |
| Recall | unrelated question | no query facts above absolute floor |
| Recall | preferred-address question | one correct active scoped fact; no old value |
| Recall | relevant project paraphrase | target query fact retained through Hybrid/fallback |
| Recall | vector missing/corrupt | core + lexical active-only fallback |
| Prompt | metadata leakage fixture | no IDs/path/type/source/sensitivity/timestamps/key/scope/status |
| Prompt | maximum core + query | final rendered memory section <= 500 tokens |
| JSON tools | search -> send -> final | 3 model requests, 2 executions, one final answer/sticker |
| Native tools | search -> send -> final | <=3 requests; narrowed second scope; final tools null |
| Candidate payload | JSON/native | stable IDs work; one candidate representation per transport |
| Reasoning | initial/mechanical/final | user / low / user policy, provider-compatible |
| Usage | sticker + decision=false | every real request visible and aggregated exactly once |
| Runtime | populated 18 -> 19 | no data loss; restart, FK, integrity and memory UI pass |
| Runtime | greeting sticker message | prompt trace, token target, persisted StickerRef and rendering all proven |

## Non-Goals

- 不取消问候或低相关问题的长期记忆召回。
- 不新增 foreground memory LLM call、cloud embedding、remote vector database、LangChain/agent framework 或第二个向量后端。
- 不改变五轮 completed-turn 定义、30 分钟 idle batch 或现有 daily-note ingestion；provider routing 只增加本文明确规定的独立 memory-model preference，不改变聊天模型选择语义。
- 不每天无条件全量重写 `MEMORY.md`，不把完整长期记忆每周无条件发给模型。
- 不用纯关键词/字符串相似度自动合并所有语义事实；模糊组由 bounded LLM proposal + deterministic validator 处理。
- 不物理删除所有历史来伪装“无重复”；合法 history 保留为 maintenance-only。
- 不把 Room、ObjectBox 或 activity log 变成 canonical memory source。
- 不把 maintenance metadata、prompt trace 或 token diagnostics 发送给聊天 provider。
- 不整体重新设计 Memory page、Settings、chat bubbles、sticker catalog/importer、attachment pipeline 或消息 schema；Memory page 只增加紧凑模型选择行并把 activity stage rows 合成 run rows。
- 不给记忆模型增加独立 reasoning、temperature、endpoint 或 credential 编辑器；继续使用所选平台/模型的现有 provider 配置和当前 memory request policy。
- 不改变 sticker binary/local-only、最多一张、typed persistence、revision/retry 和 renderer 合同。
- 不通过减少最终回答质量、关闭 reasoning、关闭工具或关闭记忆来达到 token 目标。
- 不重写 provider endpoint/session architecture；只在现有 adapter/continuation 边界内消除可证明的重复负载。
- 不处理与本任务无关的 UI、翻译、发布、签名或仓库清理。

## Stop Conditions

只有在合理审计、局部修复和重试后仍出现以下情况，才停止并向用户报告：

- live schema/branch 与本文快照发生实质变化，无法在不覆盖用户改动的情况下建立正确 migration；
- populated migration 导致任何既有表/row、foreign key、memory job、checkpoint 或 activity log 丢失；
- fixed memory-model preference 只能通过静默换 provider 才能继续，或 resolved identity 无法在首次 provider request 前持久化；
- activity logger 无法保证一个 `jobId + attempt` 一条顶层 run，或 phase transition 会覆盖 terminal 状态；
- 无法在 `canonicalKey + scope` 下区分用户明确要求保留的 legal/general/work 事实；
- long-term whole-corpus pass 只能通过 unbounded provider input 实现，且无法建立 durable partition/checkpoint；
- maintenance projection 与 chat projection 无法在现有 vector recovery identity 中安全区分，必须先重新设计持久协议；
- provider adapter 无法在保持 continuation correctness 时收窄 tool scope，且替代方案会丢 tool result 或重复 side effect；
- 完成任务需要 reset、清除设备 app data、删除用户 memory/history、force-push 或吸收无关 dirty work。

以下不是停止理由：没有连接设备、没有真实 provider token usage、weekly wall-clock 尚未自然到期、某 provider 不支持显式 low reasoning、当前 corpus 很小。继续完成可执行的 JVM/build/serialized DTO 工作，并把对应 runtime gate 标记 `OPEN`。

不得在没有以下证据时宣称完成：canonical scope/trust tests、active-only projection、absolute relevance negatives、whole-corpus replay/CAS、Auto/fixed/unavailable model routing、frozen retry identity、single-run activity row counts、planner activity、JSON/native request budgets、populated migration，以及至少一个可用设备上的真实 recall/sticker persistence 路径（若设备客观不可用则明确未完成 runtime gate）。

## Suggested Commit Sequence

仅在用户要求实施和提交时使用小型 topical commits：

1. `docs: add long-term memory consistency and token budget prompt`
2. `feat(memory): add canonical maintenance metadata`
3. `fix(memory): canonicalize long-term writes by key and scope`
4. `feat(memory): add durable whole-corpus consolidation`
5. `fix(memory): separate maintenance and chat recall projections`
6. `fix(memory): add tiered relevance-gated recall`
7. `feat(memory): add memory model routing and unified activity runs`
8. `perf(tools): reduce sticker loop requests and payloads`
9. `test(memory): verify schema 19 recall and tool budgets`
10. `docs: record memory and token verification evidence`

可在依赖紧密时合并相邻提交，但不得把全部工作压成一个提交，也不得混入无关文件。

## Completion Report

最终报告必须填入真实值，不得保留概念性占位结论：

```text
Baseline and isolation:
- source branch / HEAD / origin divergence:
- implementation branch/worktree:
- pre-existing dirty files preserved:
- Room baseline schema:
- device/emulator and APK identity:

Canonical metadata:
- final fields and Markdown keys:
- legacy defaults and unknown metadata policy:
- createdAt / updatedAt / lastObservedAt proof:
- codec round-trip and malformed-input results:

Write-time merge:
- canonical identity and scope rules:
- trust/time resolution proof:
- stable active ID and history behavior:
- full-file collision guard evidence:
- replay/concurrency results:

Whole-corpus consolidation:
- job/checkpoint schema and trigger defaults:
- corpus/partition sizes and LLM call counts:
- no-op result:
- crash resume and CAS conflict result:
- memory-disabled/re-enabled result:

Projection and index:
- maintenance vs chat projection proof:
- metadata/history leakage checks:
- full-file hash vs projection/embedding hash behavior:
- one-time vector compatibility rebuild:
- vector failure fallback:

Tiered recall:
- core/query token budgets:
- final lexical/vector absolute thresholds and calibration evidence:
- greeting/unrelated/scoped-name/project results:
- 108-entry evaluation before/after:
- exactly-once per-turn snapshot proof:

Memory model routing:
- DataStore Auto/fixed representation and atomicity:
- available/unavailable picker behavior:
- resolved platformUid/modelId for turn/daily/whole-corpus jobs:
- first-claim freeze and retry result:
- blocked dependency and no-fallback proof:

Observability:
- semantic activity rows per attempt before -> after:
- model_call/generation/organization phase progression on one run:
- PLAN_DAILY_DISTILLATION independent lifecycle run:
- whole-corpus lifecycle run:
- startup failure/disabled/no-op rows:
- legacy activity-row preservation/read behavior:
- sensitive-content absence proof:

Tool/token optimization:
- JSON request/tool counts before -> after:
- native request/tool counts before -> after:
- per-round serialized chars/tokens before -> after:
- tool schema, candidate, memory and history repetition deltas:
- reasoning policy by round/provider:
- usage aggregation/search-decision result:
- persisted StickerRef/render proof:

Migration/runtime:
- exact migration direction and schema export:
- populated upgrade/FK/integrity/restart result:
- actual greeting sticker trace:
- exact JVM/build/connected commands and results:
- remaining OPEN gates:

Git:
- diff/check/status:
- topical commit hashes:
- pushed remote ref/parity, if requested:
```

## Copy-Paste Handoff For A Fresh Implementation Conversation

```text
在 E:\code\ChatWithChat 工作，实施：
E:\code\ChatWithChat\docs\superpowers\plans\2026-07-28-long-term-memory-consistency-recall-and-tool-token-prompt.md

先完整阅读 AGENTS.md 和该计划，检查 live branch、dirty files、Room schema、相关代码锚点与 adb 设备；保护所有无关改动和设备数据。创建独立 codex/ 分支后，按 Task 0-8 顺序端到端执行并逐项更新 checklist。

把以下合同视为不可更改：
1. 每个非空用户回合都执行一次本地长期记忆召回，包括“你好”；低相关只返回 core capsule，不能关闭记忆。
2. 单值长期事实按 canonicalKey + scope 唯一，user_confirmed/explicit evidence 优先 assistant inference，active ID 稳定。
3. maintenance metadata、history、IDs、路径和时间不得进入普通 chat prompt 或 embedding text；chat projection 必须在 chunk/index 前 active-only。
4. 长期全集整理采用 material-mutation threshold + weekly idle fallback，持久、bounded、幂等、可恢复；clean pass 不调用模型、不改文件/索引。
5. Memory 页面提供独立记忆模型选择：默认 Auto 保持现有顺序，fixed 用 platformUid + modelId；固定模型失效时 blocked，不得跟随最近聊天模型或静默 fallback。
6. semantic job 首次 claim 后冻结 resolved model；每个 jobId + attempt 只有一个 activity run，model_resolution -> model_call -> generation -> organization 只更新同一行；planner 保持独立一行。
7. JSON fallback sticker happy path 最多 3 次模型请求，native 最多 3 次且 final tools 为空；不能通过取消记忆、关闭工具或降低正式答案 reasoning 达成 token 指标。

先完成 Task 0 的调用、serialized token、当前模型路由和三行 activity 基线，再改代码。每个 slice 运行最小测试，最终完成 memory/tool 全部 JVM tests、compile、debug build、schema migration、可用设备上的真实 model-picker/activity/recall/sticker runtime proof，并按 Completion Report 填真实结果。不要把 compile-only、fake DTO 或无设备状态写成 runtime 已验证。
```
