# ChatWithChat 上下文管理与自动压缩 V1 实施计划

> **实现交接提示词：** 实现代理必须先完整阅读 `AGENTS.md` 和本文件，再修改代码。先做只读审查，确认当前 checkout、分支、Room schema、测试基线和脏文件。本文是产品和实现约束；文中标注「实测」的事实来自 2026-08-08 对 `main` 分支的代码审查，其余不保证行号或结构未变，以 live checkout 为准。

> **核心产品决定：** 默认使用自动压缩。用户可以在对应供应商的设置页面选择"原文优先"模式。原文优先不是永不压缩，也不是无条件注入整段聊天记录：在有效输入预算内，完整注入用户选择的会话范围；达到阈值后自动压缩，不弹确认，不静默丢弃消息，也不改变用户设置。官方 OpenAI provider 必须使用 Responses API 原生 compaction，不走 MCP，也不使用应用侧 LLM 摘要替代。

## Goal

建立一套可持久化、可恢复、按供应商隔离的会话上下文管理系统：

- `AUTO_COMPACT`：默认策略，达到软阈值后生成并保存语义检查点，使用"检查点 + 最近原文尾部"。
- `RAW_PREFERRED`：原文优先策略，在预算内完全保留用户选择范围的原始完整回合；超出预算时自动压缩该范围中较早的回合。
- 上下文范围和压缩策略分离：范围决定参与哪些回合，策略决定是否优先保留原文。
- 供应商页面保存供应商默认策略；聊天页面保存具体会话的原文范围。
- 官方 `ClientType.OPENAI` 使用 Responses API 的原生 compaction；其他 provider 使用本计划定义的本地持久化语义 checkpoint。
- 长期记忆、跨会话历史、工具调用、附件和 provider 协议保持现有职责。

## Product Semantics

### Strategy

```kotlin
enum class ContextStrategy {
    AUTO_COMPACT,
    RAW_PREFERRED
}
```

`PlatformV2` 保存供应商默认策略。V1 不增加全局 DataStore 开关，也不把上下文范围放进供应商配置。

### Scope

原文范围以完整用户回合为边界，并按 `chatRoomId + platformUid` 持久化。

**实测：回合的持久化模型只有 user message + assistant message。** `ConversationTurn` 定义为 `(userMessage, assistantMessage?, isCurrentTurn)`，`MessageV2` 没有任何 tool call / tool result 字段，工具调用只存在于单次 provider 请求的内存态，从不落库。因此本计划必须区分两个层面：

- **持久化层（范围、checkpoint）**：边界单位是"一个用户消息 + 其对应的助手消息"。范围端点吸附到这个边界，不能只选中用户消息或只选中助手消息。
- **请求装配层（provider input array）**：tool call 与 tool result 的成对性、以及 OpenAI native compaction item 与其后 items 的连续性，由装配代码在构造单次请求时保证，与持久化范围无关。

不得把这两层混为一谈。持久化层不存在"拆开 tool call/result"这种失败模式，请求装配层才存在。

当前用户消息始终追加在上下文末尾。范围之外的旧消息不会被偷偷摘要补回。

### 跨供应商历史的处理（必须显式决定）

**实测：`ContextBuilder` 目前会静默丢弃其他供应商产生的历史回合。** `firstValidAssistantCandidate(platformUid)` 中 `if (message.platformType != platformUid) return null`，随后该回合因 `assistantMessage == null` 被过滤掉。

这与"不静默丢弃消息"的核心决定直接冲突：同一聊天里换过供应商后，用户选中一段跨越切换点的范围，会得到一个自己看不见的缺口。

V1 采用如下规则，且必须实现，不得沿用现状：

- 跨供应商的历史回合**仍然进入上下文**，作为普通的 user/assistant 文本参与原文或 checkpoint。
- 仅当该回合的助手内容为空、或属于错误消息时才排除（沿用现有 `isAssistantErrorMessage` 判定）。
- 助手消息带有 provider 专属结构（附件 provider file reference、native item）时，跨供应商注入必须降级为纯文本，不得把 A 供应商的 file id 传给 B 供应商。
- 范围选择器在跨供应商回合上不做特殊标记，但如果发生了降级，`ContextBuildResult` 必须记录降级计数以便测试断言。

### RAW_PREFERRED behavior

在有效输入预算内，请求内容为：

```text
system prompt
+ optional long-term memory
+ selected complete turns in original form
+ current user message
```

原文优先阶段不得做摘要、recent-N 过滤、历史回合裁剪或无提示的附件/工具结果删除。仅允许 provider 协议要求的 role 映射、附件引用和 tool-call 格式转换，以及上一节定义的跨供应商降级。

达到自动压缩阈值后：

- 自动压缩所选范围中较早的完整回合；
- 保留最近一段所选范围的原文；
- 保存并复用新的 checkpoint；
- 保持 `RAW_PREFERRED` 设置不变；
- 可在界面显示"已自动压缩"，但不要求用户确认。

因此，`RAW_PREFERRED` 的含义是"容量允许时原文优先"，不是"永远禁止压缩"。对官方 OpenAI provider，这个 checkpoint 是服务端返回的不透明 native compaction item；其他 provider 才是应用侧的语义 checkpoint。

### AUTO_COMPACT behavior

默认使用已保存的 checkpoint 加最近原文尾部。首次超过阈值时，将最早的尚未折叠完整回合压缩为 checkpoint。已有 checkpoint 时，压缩输入必须是：

```text
existing checkpoint + newly folded complete turns
```

checkpoint 成功写入前不得删除、覆盖或标记原始消息为不可恢复。

### OpenAI Native Compaction

原生 compaction 不走 MCP。它属于官方 OpenAI Responses API：

```text
POST /v1/responses
context_management: [{ type: "compaction", compact_threshold: <token threshold> }]
```

正常 OpenAI 请求必须使用 server-side compaction：

- `compact_threshold` 使用本计划的 `compactTrigger`，并且低于已确认模型输入窗口。
- 触发时，服务端在同一 SSE 流中返回不透明的 `compaction` output item。
- 客户端必须原样保存并在下一次 `/v1/responses` 请求中作为 input item 传回；不得解析、修改或展示其内容。
- 使用本地的 stateless input-array chaining：保留最近 compaction item 及其后的有效 input/output items；不要在 V1 引入 `previous_response_id` 链。
- 为避免依赖服务端历史状态，原生 compaction 请求使用 `store=false`；本地 Room 仍保存用户可见的完整聊天记录。

如果用户刚选择的范围已经大到无法作为一次 `/v1/responses` 输入提交，不能改用本地 LLM 摘要。使用官方 `/v1/responses/compact` endpoint 分段构造原生 compacted window；每次传入的 window 必须仍能放入模型上下文，且下一轮必须将 endpoint 返回的完整 output window 原样传给 `/v1/responses`。这只是首次或范围重置后的原生状态初始化，正常对话仍优先使用 server-side compaction。

除 `ClientType.OPENAI` 之外的所有 ClientType（`ANTHROPIC`、`GOOGLE`、`GROQ`、`OPENROUTER`、`OLLAMA`、`CUSTOM`）都不得发送 `context_management`，也不得假定其支持 `/responses/compact`。MCP 只用于工具协议，和上下文压缩无关。

> 注：`ClientType` 实测只有上述 7 个值，**没有 DeepSeek**。DeepSeek 一类的第三方服务在本项目中通过 `CUSTOM` 接入，因此自动落入禁止发送 `context_management` 的一侧。

**native compaction 需要一层模型能力门控作为兜底。** server-side compaction 于 2026-02-11 随 Responses API 发布，由 GPT-5.2 引入，GPT-5.4 起为原生能力（并带 1M 窗口）。因此在实际使用中，绝大多数仍在服役的官方模型都支持它，门控**不是**为了排除主流模型。它存在的理由是本项目允许用户在 `PlatformV2.model` 里自由填写模型名：可能填错、可能填某个网关的自定义别名、也可能填一个早已下线的名字。没有门控，这些情况会变成每轮请求都失败。规则：

- 维护一个显式的"已知支持 native compaction"模型族判定（前缀匹配，集中在一处常量，当前应覆盖 `gpt-5.2` 及更新的族）。
- 命中时启用 server-side compaction，这是官方 OPENAI 平台的正常路径。
- 未命中时，该 OPENAI 平台**降级使用应用侧 checkpoint 路径**。这是对"`ClientType.OPENAI` 不得调用应用侧压缩服务"的唯一例外，必须在代码注释和测试中显式标注。选择降级而不是直接报错，是为了让填错模型名或使用非常规部署的用户仍能正常对话。
- 该常量表会过时。判定必须集中在一处并附注释说明"新模型族默认按不支持处理，需人工加入"，避免散落到多个分支。
- 若服务端仍返回 `unsupported_parameter`，按 Task 7 的失败契约处理，并把该模型标记为不支持，避免每轮重试。

官方契约参考：[OpenAI Responses compaction guide](https://developers.openai.com/api/docs/guides/compaction)。实现前再次核对该页面及 API reference，避免把 opaque output item 当成用户可读摘要。

## Scope Boundary To Preserve

以下功能不属于本计划的语义改造范围：

- `MEMORY.md` 和长期记忆提取、合并、召回、维护。
- 独立的跨会话历史检索和其索引语料库。
- 工具注册、工具循环、tool progress 和 source metadata。
- 图片、文件、provider file reference 和附件上传协议。
- 消息编辑、重试、导出、revision 和 assistant candidate 选择。
- OpenAI、Anthropic、Google、Groq、Ollama、OpenRouter、Custom 的协议适配。

长期记忆仍由现有记忆系统拥有语义；上下文检查点只服务于同一聊天的 provider prompt。

**但有一处既有耦合必须处理，见 Task 4 的"记忆触发点"小节。** 上下文压缩当前是长期记忆整合的触发源，不能当作互不相干的两个系统。

## Current State To Audit

以下事实已于 2026-08-08 在 `main` 上实测确认，可直接作为实现前提；仍需在动手前用一次只读检查确认没有漂移。

**上下文构建**

- `ContextBuilder` 的"摘要"是**确定性抽取式截断**，不是 LLM 摘要。`ConversationContextSummarizer`（`ContextBuilder.kt` 内的 private object）取头部 2 轮 + 尾部若干轮，按 token 预算逐条截断，加 `"较早对话摘要："` 前缀。项目中**不存在**可复用的 LLM 摘要设施，Task 3 是从零新建。
- token 计数使用真 tokenizer（JTokkit，按模型名解析 O200K/CL100K，失败时回退到 CJK 感知的启发式），封装在 `ContextBuilder.kt` 的 `private class ContextTokenCounter`。**外部不可访问**，Task 1 必须先提取。
- 上下文构建有**单一漏斗**：所有 provider 分支 → `buildProviderConversationSnapshot(...)` → `ChatRepositoryImpl.buildConversationContext(userMessages, assistantMessages, platform)` → `ContextBuilder.buildContext(...)`。约 10 处调用点全部经由 `buildProviderConversationSnapshot`。Task 4 要求的"统一入口"因此成本很低。
- `buildConversationContext` 的签名**没有 chatRoomId**。chatId 可由 `userMessage.chatId` 推导（同文件内 `scheduleMemoryConsolidationForCompactionIfNeeded` 已有先例）。二选一：改签名显式传入，或在函数内推导并对空值/非法值早退。推荐改签名。

**Provider 路由**

- 只有 `ClientType.OPENAI` 走 `/v1/responses`；其余走 `/v1/chat/completions` 或各自原生协议。
- `ResponsesRequest` 还被 `SearchDecisionService` 和 `LlmMemoryIntelligence` 复用于内部辅助调用。这两处**不得**启用 `context_management`，也不得参与 checkpoint 状态机。

**Responses DTO（好消息，可直接复用）**

- `OutputItem(raw: JsonObject)` + `OutputItemSerializer` 原样透传原始 JSON，未知的 `type=compaction` output item **不会被丢弃**。
- `ResponsePassthroughInputItem(raw: JsonObject)` + `ResponseInputItemSerializer` 的 else 分支已经能把不认识的 item 原样回传给 `/v1/responses`。
- `ResponsesRequest` 所有可选字段均使用 `@EncodeDefault(EncodeDefault.Mode.NEVER)`，新增可空字段不会序列化出 null。新增字段必须沿用该注解。

因此 Task 1 中"保留 response body 的任意 output item JSON"基本是免费的。

**Room**

- `ChatDatabaseV2` 当前 `version = 20`，`exportSchema = true`，schema JSON 位于 `app/schemas/cn.nabr.chatwithchat.data.database.ChatDatabaseV2/`，`20.json` 已存在。
- 19 个 migration 全部**手写**，逐个注册在 `di/DatabaseModule.provideChatDatabaseV2` 的 `.addMigrations(...)`。**没有 autoMigration**，不能指望自动生成。
- `PlatformV2` 主键为 `platform_id`，业务键为 `uid: String`（本文的 `platformUid` 即指它）。
- `PlatformModelV2` 现有字段：`platformUid`、`modelId`、`displayName`、`description`、`enabled`、`isDefault`、`updatedAt`。**没有任何上下文窗口/token 上限字段。**
- `messages_v2` 对 `chat_id` 有 `ForeignKey.CASCADE`。新增表挂 `chatRoomId` 时应沿用同样的级联策略。

**ProviderContextPolicy（易误读，重点）**

`ProviderContextPolicy.maxContextTokens` 的实测值为 OPENAI/ANTHROPIC/GOOGLE `24_000`、GROQ `8_000`、OLLAMA/OPENROUTER/CUSTOM `6_000`。

**这是本地构建预算，不是模型上下文窗口。** 不得把它当作 `contextWindow` 代入本计划的预算公式，否则 RAW_PREFERRED 会在一进门就触发压缩，产品意图完全落空。

执行：

```powershell
rg -n "ContextBuilder|ProviderContextPolicy|buildConversationContext|PlatformV2|PlatformSettingScreen|RoomDatabase|MIGRATION" app\src\main app\src\test
git status --short
```

## Task 0: Model Context Window Metadata

这是全部预算计算的前置，必须先做。

**结论：绝大多数 provider 的窗口信息不需要写死，它已经在应用当前已经发出的那个请求的响应体里，只是 DTO 没有解析。**

`ModelDiscoveryRepositoryImpl` 已经为每个 ClientType 调用了模型列表接口。各家字段实测/官方文档情况：

| ClientType | 现有调用 | 窗口字段 | 状态 |
| --- | --- | --- | --- |
| `GOOGLE` | `v1beta/models` | `inputTokenLimit` / `outputTokenLimit` | 响应已含，DTO 未解析 |
| `OPENROUTER` | `v1/models` | `context_length` | 响应已含，DTO 未解析 |
| `GROQ` | `v1/models` | `context_window` | 响应已含，DTO 未解析 |
| `ANTHROPIC` | `v1/models` | `max_input_tokens` | 一方 API 已含；网关可能缺省 |
| `OLLAMA` | `api/tags` | 不可信，见下 | **不得采信架构上限** |
| `OPENAI` | `v1/models` | 无 | 官方不提供，必须回退 |
| `CUSTOM` | `v1/models` | 取决于上游 | 尽力解析，通常需回退 |

**Ollama 是特例，必须单独处理，否则会制造出比现状更糟的静默截断。**

Ollama 有两个不同的数字：`ollama show` / `api/show` 报告的是**模型架构上限**（可能是 131072），而服务端实际分配的是 `num_ctx`，**默认仅 4096**（部分模型 8192，旧版本 2048）。超出部分由 Ollama **静默丢弃**，日志里表现为 `truncating input prompt limit=4096`，客户端收不到任何错误。

更麻烦的是：本项目的 Ollama 走的是 OpenAI 兼容的 `/v1/chat/completions` 端点，该端点**无法传递 `num_ctx`**。实际生效值由用户的 `OLLAMA_CONTEXT_LENGTH` 环境变量或 Modelfile 决定，应用侧无从查询也无从设置。

因此 V1 对 Ollama 的规则：

- **不要**为 Ollama 解析 `api/show` 的 `context_length` 并当作可用窗口。采信它会让应用自信地发送 100k tokens，然后被服务端悄悄砍掉开头——这正是本计划要消灭的失败模式，而且比现状更隐蔽。
- Ollama 一律走 `ProviderContextPolicy.fallbackModelContextWindow` 的保守值。现有的 `6_000` 作为该回退值是合理的（略高于 4096 默认值的安全区，可按需下调）。
- 在 Ollama 平台的设置页说明：实际可用上下文由 Ollama 服务端的 `OLLAMA_CONTEXT_LENGTH` 决定，应用无法探测。
- 若日后改用 Ollama 原生 `/api/chat` 端点，可在请求中显式传 `options.num_ctx`（API 参数优先级高于环境变量与 Modelfile），届时才谈得上按真实窗口调度。这属于 V1 之后的工作。

实现要求：

- 扩展 `ModelDiscoveryRepositoryImpl` 的私有 DTO，新增**可空**字段：`OpenAIModelResponse` 加 `context_window` 与 `context_length`（同一个 DTO 被 OPENAI/GROQ/OPENROUTER/CUSTOM 共用，两个字段都要，取第一个非空）；`GoogleModelResponse` 加 `inputTokenLimit`；`AnthropicModelResponse` 加 `max_input_tokens`。
- **不为 Ollama 新增 `api/show` 查询。** 见上一节，那个数字会误导预算计算。`fetchOllamaTags` 保持现状。
- 所有新增字段必须可空且带默认值，解析失败或缺失一律视为"未知"，不得抛异常影响既有的模型刷新流程。
- `APIModel` 增加 `contextWindow: Int?`，`PlatformModelV2` 增加 `context_window` 列（可空 Int），在模型刷新写库时一并持久化。
- 已存在的模型行在 migration 中该列填 `NULL`，下一次模型刷新自然补齐。

`ContextWindowResolver` 的解析顺序：

1. `PlatformModelV2.context_window`（当前 platformUid + 当前 model）。
2. 集中维护的模型族前缀回退表（仅覆盖官方 OPENAI 与常见 CUSTOM 上游，条目要少而保守）。
3. `ProviderContextPolicy.fallbackModelContextWindow`（**新增字段**，与既有的 `maxContextTokens` 分开，语义是"该 provider 未知模型的保守窗口假设"）。

不得因为模型名里含 `1M`、`128k` 之类字样就未经验证地推断窗口。

> 关于 Non-Goals 的澄清：本计划**不**新增"让用户手工为每个模型填写上下文窗口"的界面，这仍是 Non-Goal。Task 0 做的是自动抓取 + 保守回退，两者不冲突。

## Task 1: Domain And Budget Model

先做一次机械提取，再新增模型：

- 将 `ContextTokenCounter` 从 `ContextBuilder.kt` 的 private class 提升为 `internal` 可复用组件（保持 JTokkit 行为与常量不变），供 Resolver、压缩服务、native threshold 计算和预算告警共用。
- 将 `ConversationContextSummarizer` 保留为应用侧 checkpoint 失败时的**确定性回退**，不要删除。

新增上下文领域模型，至少包括：

- `ContextStrategy`。
- `ContextRange`。
- `ConversationCheckpoint`。
- `OpenAINativeCompactionState`，保存不透明 native item/window，不保存可读摘要。
- `ContextWindowResolver`（见 Task 0）。
- `ContextBuildResult`，包含 strategy、range、checkpoint boundary、estimated tokens、压缩状态和跨供应商降级计数。

有效预算统一使用：

```text
modelContextWindow   = ContextWindowResolver.resolve(platform, model)
outputReserve        = min(modelContextWindow * 0.15, 8_192)
safetyMargin         = min(modelContextWindow * 0.05, 2_048)
effectiveInputBudget = max(modelContextWindow - outputReserve - safetyMargin, MIN_INPUT_BUDGET)
preFoldTrigger       = effectiveInputBudget * 0.60
compactTrigger       = min(effectiveInputBudget * 0.75, COMPACT_TRIGGER_CEILING)
recentRawTail        = min(effectiveInputBudget * 0.30, 16_000)
summaryTarget        = clamp(providerSummaryTarget, 768, 2_048)
```

其中：

- `MIN_INPUT_BUDGET` 是防御性下限，保证小窗口模型不会算出零或负预算。
- `COMPACT_TRIGGER_CEILING` 是**成本控制**上限，不是协议约束。官方示例使用 200_000，本计划取更保守的值以控制单次请求成本；该常量必须集中定义并写明理由，便于日后调整。
- `preFoldTrigger` 是**后台预折叠**的启动线，见 Task 4。它必须严格小于 `compactTrigger`，两者之间的空档就是后台任务可用的时间窗口。`compactTrigger` 退化为很少触发的兜底线。

必须以断言或测试固定下列不变量，任何 provider 与窗口取值都要成立：

```text
0 < recentRawTail
recentRawTail + summaryTarget < compactTrigger
preFoldTrigger < compactTrigger
compactTrigger <= effectiveInputBudget
effectiveInputBudget <= modelContextWindow
```

扩展 `ResponsesRequest` DTO，精确序列化官方字段（沿用 `@EncodeDefault(EncodeDefault.Mode.NEVER)`）：

```kotlin
@SerialName("context_management")
@EncodeDefault(EncodeDefault.Mode.NEVER)
val contextManagement: List<ResponseContextManagement>? = null

@SerialName("store")
@EncodeDefault(EncodeDefault.Mode.NEVER)
val store: Boolean? = null
```

其中 compaction config 的 `type` 为 `compaction`，字段为 `compact_threshold`。同时扩展 `OpenAIAPI` / `OpenAIAPIImpl` 提供非流式 `/v1/responses/compact` 调用（现有接口只有 `streamChatCompletion` / `streamResponses` / `uploadFile` / `isFileAvailable`，需新增方法），并保留 response body 的任意 output item JSON。

## Task 2: Durable Checkpoint Storage

**checkpoint 必须建模为分段链，不是单行记录。** 因为 AUTO_COMPACT 的增量语义是 `existing checkpoint + newly folded turns`，每一次折叠天然产生链上的一段。把它存成一行不断覆盖，会让失效处理只剩"全量重建"一条路。

设计 Room entity、DAO、repository 和 migration。checkpoint 段至少持久化：

- `chatRoomId`。
- `platformUid`。
- `segmentIndex`，从 0 递增，构成链。
- 该段覆盖的完整回合 ID 区间（起止），以及 boundary 回合 ID。
- summary/checkpoint body。
- source turn IDs hash 或等价版本标识（**逐段计算**，不是整链一个 hash）。
- estimated token count。
- 压缩调用自身的 token 开销（见下方"压缩开销记账"）。
- status、createdAt、updatedAt。

原文范围状态至少持久化：

- `chatRoomId`。
- `platformUid`。
- start turn ID。
- end turn ID 或 current sentinel。
- updatedAt。

官方 OpenAI 的 native state 至少持久化：

- `chatRoomId` 和 `platformUid`。
- 最新 native compaction item 或 standalone compact endpoint 返回的完整 canonical output window 的原始 JSON。
- native item 对应的 boundary/hash、token estimate 和更新时间。
- 是否已经可作为下一次 `/responses` input 的状态。

不得尝试解密或提炼该 item。用户可见的聊天记录继续以原始 `messages_v2` 为准。

### 失效与重建策略（惰性，不同步重建）

编辑、删除、重试会改动某个已折叠回合。规则如下，**任何情况下都不允许在用户发送消息的同步路径上因失效而临时发起压缩调用**：

1. 定位受影响回合所属的段 `K`。将段 `K` 及其后所有段标记为 `INVALID`（下游段的输入包含上游 checkpoint，必然连带失效），但**不删除**它们的 body。
2. 下一次构建上下文时，使用段 `0..K-1`（仍然有效）作为 checkpoint，段 `K` 起的回合以**原文**参与。
3. 如果这样仍在 `effectiveInputBudget` 内，就到此为止——**不发起任何压缩调用**。绝大多数编辑发生在较近的回合，`K` 靠后，原文尾部很短，这条路径是免费的。
4. 如果超出预算，交给 Task 4 的后台预折叠从段 `K` 起异步重建；当前这一次请求先用 Task 1 保留的确定性抽取式摘要顶上，保证请求能发出去。
5. 重建成功后覆盖段 `K` 及其后的段，并清除 `INVALID` 标记。重建失败不得破坏段 `0..K-1`。

native OpenAI 路径同理：native state 失效后不得同步调用 `/v1/responses/compact`，而是清除失效的 native state，让下一次正常请求靠 server-side compaction 自行重建。

### 压缩开销记账

压缩调用不属于任何一条 `messages_v2`，但其 token 开销必须可见。复用现有的 `cn.nabr.chatwithchat.data.token.TokenUsageRecord`（已是 `@Serializable`，`MessageV2.token_usage` 已在用同样的方式存 JSON），把它作为一列存在 checkpoint 段上：

- 应用侧压缩：记录该次压缩请求的真实 usage，`isEstimated = false`；provider/platformUid/model 填实际使用的值；`details` 加一条 `TokenUsageDetail(label = "上下文压缩")`。
- `/v1/responses/compact` standalone 调用：同上，它是独立请求，有独立 usage。
- **native server-side compaction：不得单独记账。** 折叠发生在同一次 `/v1/responses` 调用内部，其开销已经包含在该响应返回的 usage 里；再加一份就是重复计数。此路径下 checkpoint 段的 token usage 列留空，并在注释里写明原因。
- 会话级 token 汇总展示时，压缩开销应能与对话本身的开销区分开，可直接利用 `TokenUsageDetail.label`。既有的 `sumTokenUsage` 已经会把 `details` 摊平，不需要新增聚合机制。

**Migration 的具体落点（实测）**，四处必须同步改动：

1. `ChatDatabaseV2Migrations` 新增 `MIGRATION_20_21`（手写 SQL，含 Task 0 的 `platform_model_v2.context_window` 列）。
2. `ChatDatabaseV2` 的 `@Database(version = 21)` 并注册新 entity。
3. `DatabaseModule.provideChatDatabaseV2` 的 `.addMigrations(...)` 末尾追加 `MIGRATION_20_21`。
4. 提交生成的 `app/schemas/cn.nabr.chatwithchat.data.database.ChatDatabaseV2/21.json`。

必须处理：

- 删除聊天后的级联或清理（沿用 `messages_v2` 对 `chat_id` 的 `ForeignKey.CASCADE`）。
- 多供应商在同一聊天中使用时的隔离。

不要把原始消息移动到 checkpoint 表，也不要以 checkpoint 代替 `messages_v2`。

## Task 3: LLM Compaction Service

为非官方 OpenAI provider（以及未通过 native 门控的 OPENAI 模型）新增独立的压缩服务，使用当前有效供应商和模型生成语义 checkpoint。压缩请求：

- 不进入正常 tool loop。
- 不触发长期记忆学习。
- 不递归调用上下文压缩。
- 不复用 `SearchDecisionService` / `LlmMemoryIntelligence` 的调用路径。
- 失败、取消、超时和非法输出都必须可恢复，回退到 Task 1 保留的确定性抽取式摘要。

建议使用以下内部提示词：

```text
你是 ChatWithChat 的对话上下文压缩器。

请把给定的历史检查点和新增完整对话轮次，压缩成供后续模型继续工作的事实型上下文。

必须保留：
1. 用户明确提出的目标、偏好和限制。
2. 已确认的决定、方案、参数、文件路径、名称和数字。
3. 尚未解决的问题、待办事项和当前工作状态。
4. 关键工具结果、错误信息和外部引用。
5. 用户事实与助手推断之间的区别。

禁止：
1. 编造原文中不存在的信息。
2. 把助手猜测写成用户事实。
3. 删除会影响后续任务的约束、数字、代码或名称。
4. 添加新的建议、解释或行动。
5. 输出寒暄、元叙述或压缩过程说明。

只输出结构化上下文，不要输出代码围栏。
```

输出格式可以是稳定分节文本或 JSON，但必须有长度上限、解析校验和失败回滚。

通过 native 门控的 `ClientType.OPENAI` 不得调用该服务，也不得发送上述应用侧压缩提示词。它的压缩路径由 `OpenAINativeCompactionService` 负责：正常请求设置 `context_management`，首次超长范围初始化时调用 `/v1/responses/compact`。

## Task 4: ContextBuilder Integration

调整 `ContextBuilder` 和 `ChatRepositoryImpl.buildConversationContext(...)`：

- 非 native 的 `AUTO_COMPACT`：应用侧 checkpoint + recent raw tail。
- 非 native 的 `RAW_PREFERRED`：预算内 selected raw range；超阈值后应用侧 checkpoint + recent raw tail。
- native OpenAI 的两个策略：使用 native item/window + 其后原始完整回合，并在每个 `/v1/responses` 请求启用 server-side `context_management`。
- 不能重复注入已进入 checkpoint 的原始回合。
- 不能遗漏当前用户消息。
- 保持长期记忆、system prompt、附件和工具结果的现有顺序。
- 所有 provider family 必须经过同一个 strategy/range 决策入口（现有的 `buildProviderConversationSnapshot` 漏斗即为该入口）。

OpenAI Responses 的 native item 必须同时穿过普通回答和 native tool-loop 的每个 `/responses` round。捕获 `response.output_item.done` 中 `type=compaction` 的 raw JSON（`OutputItem.raw` 已原样保留），并把它追加到下一轮 input；当出现新的 compaction item 时，仅在已成功持久化后清理其前的可丢弃 input items。工具 call/result 的连续性不能被破坏。

原文范围内的消息仍可经过 provider 必需的协议转换，但不得被语义改写。checkpoint 必须明确标记为历史上下文，避免其中的用户文本被当成新的 system instruction。

### 后台提前折叠（默认路径，不要做成同步压缩）

**同步压缩会让用户按下发送后干等一次完整 LLM 往返，手机上是数秒空白，不可接受。** 因此折叠默认在后台异步发生，`compactTrigger` 退化为极少命中的兜底线。

**实测：项目已有可直接复用的异步作业设施，不要新写一套 WorkManager。** `data/memory/` 下的 `MemoryMaintenanceScheduler` + `MemoryMaintenanceWorkEnqueuer` + `memory_maintenance_job` 表已经提供了 job 类型注册、`idempotencyKey` 幂等、jobId 认领、租约看门狗、失败重试状态（`PENDING` / `FAILED_RETRYABLE`）和按 family 的 WorkManager 入队。作业表本身是通用的（`type: String`、`payload_json`、`idempotency_key`、`status`），`MemoryMaintenanceJobType` 只是一组字符串常量。

复用方式：

- 在 `MemoryMaintenanceJobType` 新增 `FOLD_CONTEXT_CHECKPOINT = "fold_context_checkpoint"`，并在 `MemoryMaintenanceJobFamily.forType` 中归入 `SEMANTIC`。
- **该作业不得受 `settingRepository.fetchMemoryEnabled()` 门控。** 现有 `MemoryTurnBatchScheduler` 的每个入口都以该开关早退，上下文折叠与长期记忆是两回事，用户关掉记忆功能后上下文管理必须照常工作。这一点必须有测试。
- 类命名和落点不要塞进 `data/memory/`，放在 `data/context/` 下，只依赖调度设施本身。

触发与并发规则：

- 每次构建上下文后评估：若预估 input tokens 超过 `preFoldTrigger`，且当前没有同 chat + platformUid 的待处理折叠作业，则入队一个折叠作业，`idempotencyKey` 由 `chatRoomId + platformUid + 待折叠回合区间 + 内容 hash` 构成，天然去重。
- 折叠作业在后台生成下一段 checkpoint 并写入。写入成功前，同步路径继续使用现有的段链，用户无感。
- 同一 chat + platformUid 同时最多一个折叠作业在飞。
- 用户切换供应商、删除聊天、或该区间的回合被编辑，都应使在飞作业的结果失效：写入前校验 `idempotencyKey` 中的 hash 仍与当前状态一致，不一致则丢弃结果，不写库。
- 只有当上下文实际超过 `compactTrigger` 而后台段仍未就绪时，才走同步压缩兜底；此时应先尝试确定性抽取式摘要，避免阻塞。这条路径应当罕见，需要打点以便验证。

native OpenAI 路径**不需要**后台预折叠——折叠由服务端在同一次请求内完成，不产生额外往返。该路径只需保证 native item 的持久化与回传。

### 记忆触发点（必须显式处理，否则是静默回归）

**实测：`buildConversationContext` 当前调用 `scheduleMemoryConsolidationForCompactionIfNeeded(conversationContext)`，该函数在 `conversationContext.omittedTurns` 非空且记忆功能开启时，调用 `scheduler.markCompactionUrgent(chatId, lastUserMessageId)`。** 也就是说，上下文压缩目前是长期记忆整合的触发源。

本计划改造后 `omittedTurns` 的语义会变化甚至恒为空，若不处理，结果要么是记忆整合再也不触发（功能回归），要么是每次 checkpoint 都触发（重复放大）。

要求：

- 定义新的触发条件：**当且仅当一批完整回合首次被折叠进 checkpoint 时**触发一次，以该批次中最后一个用户消息为 `lastUserMessageId`。
- native compaction 路径同样要触发——服务端折叠了上下文，本地记忆整合的需求不变。
- 同一批回合重复构建上下文（例如重试）不得重复触发。
- 压缩服务自身的 LLM 调用绝不触发。
- 该行为必须有独立测试，不能只靠人工验证。

这不改变"长期记忆语义由现有系统拥有"的边界，只是维持既有的触发时机。

## Task 5: Provider Settings UI

在 `PlatformSettingScreen` 中增加"上下文管理"设置项，并由 `PlatformSettingViewModel` 保存到对应的 `PlatformV2`：

- 自动压缩，默认值。
- 原文优先。

不要放到全局 `SettingDataSource`。更新：

- `PlatformV2`（新增 strategy 列，随 Task 2 的 `MIGRATION_20_21` 一起加，默认值为 `AUTO_COMPACT`）。
- `SettingRepository` 相关更新路径。
- 中文和英文 strings。
- 设置页恢复和重启测试。

设置页只负责供应商默认策略，不负责选择某个聊天的起止回合。

## Task 6: Chat-Level Range Selector

在现有聊天工具栏或更多菜单中增加会话上下文范围入口：

- 显示当前供应商和有效策略。
- 在 `RAW_PREFERRED` 下选择起始回合到当前回合的范围。
- 只允许完整回合边界（定义见 Scope 一节的持久化层）。
- 按 `chatRoomId + platformUid` 保存。
- 切换供应商时读取对应范围。
- 范围失效时自动校正或清除，不删除消息。
- 当前正在发送的用户消息始终追加。

达到阈值后显示轻量状态，例如"已自动压缩较早上下文"，但不弹确认对话。

## Task 7: Failure And Overflow Contract

应用侧自动压缩失败时：

- 不提交半成品 checkpoint。
- 不删除原始消息。
- 不静默切换或缩小范围。
- 如果当前请求仍在有效预算内，使用原始可用上下文继续。
- 如果已经超预算，先尝试 Task 1 保留的确定性抽取式摘要；仍超预算则返回可重试错误。
- **后台折叠作业失败不得影响当次对话。** 作业失败按现有 `FAILED_RETRYABLE` 语义重试，用户不感知；只有当同步兜底路径也失败时才向用户暴露错误。
- 后台作业连续失败达到上限后停止重试，此后该 chat + platformUid 退化为"每次超 `compactTrigger` 时同步兜底"，并打点记录，避免静默地永远不再压缩。

OpenAI native compaction 失败时：

- 不提交或覆盖已有 native state。
- 不降级到应用侧 LLM 摘要，也不改用 MCP。
- `/responses/compact` 的输入不能超出模型窗口；若不能分段初始化，返回可恢复错误并保留原始范围。
- 官方 API 拒绝 `context_management`、native item 或 compact endpoint 时，展示 provider 返回的可行动错误并保留原始消息；若错误为 `unsupported_parameter`，将该模型标记为不支持 native compaction 并按门控规则改走应用侧路径，不得每轮重试。

`RAW_PREFERRED` 达到约 80% 有效输入预算时记录或展示警告；超过有效预算时自动压缩，不要求用户确认。这个自动压缩是容量保护行为，不修改供应商默认策略和会话范围设置。

## Task 8: Tests And Verification

至少覆盖：

- strategy 默认值；Room migration 20→21（**属于 instrumented test**，见下方验证命令说明）。
- provider-specific strategy 隔离。
- Task 0 各家窗口字段解析：Google `inputTokenLimit`、OpenRouter `context_length`、Groq `context_window`、Anthropic `max_input_tokens`；字段缺失时回退且不抛异常。
- Ollama 平台**永远**解析到 `fallbackModelContextWindow`，即使模型名或响应里出现了更大的数字。
- `ContextWindowResolver` 三级回退顺序，以及不得从模型名推断窗口。
- Task 1 预算不变量在所有 provider × 窗口取值下成立。
- OpenAI `ResponsesRequest` 精确序列化 `context_management`、`compact_threshold` 和 `store=false`，且未设置时不出现在 JSON 中。
- 非 OpenAI provider 不发送 OpenAI-only 字段；`SearchDecisionService` 与 `LlmMemoryIntelligence` 的请求不含 `context_management`。
- native compaction 模型门控：命中族启用，未命中族走应用侧路径。
- OpenAI `/v1/responses` 触发 native compaction 时，`type=compaction` item 被原样持久化并进入下一轮 input。
- OpenAI standalone `/v1/responses/compact` 返回的完整 output window 原样进入下一次 `/v1/responses`，不被客户端裁剪。
- OpenAI native tool-loop 在有 compaction item 时仍保留 tool call/result 连续性（请求装配层）。
- OpenAI native 失败不会走 MCP；`unsupported_parameter` 后不重复重试。
- range 端点在持久化层吸附到完整 user+assistant 回合。
- checkpoint 首次生成、增量合并、幂等和 hash 失效。
- checkpoint 段链：编辑段 `K` 内的回合后，段 `0..K-1` 保持有效、段 `K` 起标记 `INVALID` 且 body 未被删除。
- 失效后若原文仍在预算内，**不发起任何压缩调用**（这是最常见路径，必须断言零 LLM 调用）。
- 失效后若超预算，当前请求走确定性摘要发出，重建交给后台作业。
- 后台预折叠：超过 `preFoldTrigger` 入队作业；同 chat + platformUid 不重复入队；`idempotencyKey` 冲突被去重。
- 后台折叠作业在 `fetchMemoryEnabled() == false` 时**仍然执行**（与长期记忆作业解耦）。
- 在飞折叠作业的结果在 hash 不再匹配时被丢弃且不写库。
- 同步压缩兜底仅在超过 `compactTrigger` 且后台段未就绪时触发。
- 压缩开销记账：应用侧压缩与 standalone `/responses/compact` 写入 `TokenUsageRecord`；native server-side 路径**不写**，避免与主响应 usage 重复计数。
- `RAW_PREFERRED` 在预算内不摘要、不 recent-N、不截断。
- `RAW_PREFERRED` 超阈值自动压缩且无需确认。
- 跨供应商历史回合进入上下文而非被丢弃；provider 专属结构被降级为纯文本且降级计数正确。
- 压缩失败、取消、超时、非法输出的 rollback，以及回退到确定性摘要。
- 80% 警告和超预算处理。
- 编辑、删除、重试后的 checkpoint/range 修复。
- 记忆整合触发点：首次折叠触发一次、重复构建不重复触发、压缩服务自身调用不触发、native 路径同样触发。
- OpenAI、Anthropic、Google、Groq、Ollama、OpenRouter、Custom 的最终 prompt 顺序。
- 设置页保存、恢复和进程重启。

验证命令（与 `AGENTS.md` 对齐；如需覆盖 JDK 或使用 `:app:testDebugUnitTest` 等更窄的任务，请在交接说明中写明原因）：

```bash
./gradlew test
./gradlew assembleDebug
git diff --check
```

**注意：`./gradlew test` 不会运行 Room migration 测试。** 本项目已有 `app/src/androidTest/.../ChatDatabaseV2MigrationInstrumentedTest.kt`，migration 20→21 的测试必须写进该文件（沿用其 `MigrationTestHelper` 用法），并通过 `./gradlew connectedAndroidTest` 在设备或模拟器上运行。若交接时无可用设备，必须在报告中明确写出"migration 测试已编写但未运行"，不得因为 `./gradlew test` 通过就宣称 migration 已验证。

交接时明确报告：实际 schema 版本和 migration、checkpoint 存储键、各 provider 实测拿到的 context window 值、native 门控命中的模型族、自动压缩失败行为、所有 provider 是否走统一入口，以及尚未运行的设备或真实 provider 验证。

## Non-Goals For V1

- 不实现全量永久上下文模式。
- 不实现跨聊天原文检索。
- 不把压缩结果写入长期记忆（但保留既有的记忆整合触发时机，见 Task 4）。
- 不新增"让用户手工为每个模型填写上下文窗口"的界面（自动抓取与保守回退属于 Task 0，不受此限）。
- 不借此重构 provider、工具、附件或消息数据模型。
