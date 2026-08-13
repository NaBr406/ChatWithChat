# ChatWithChat 单平台 Token 用量与缓存命中统计实施计划

> **实现交接提示词：** 实现代理必须先完整阅读 AGENTS.md 和本文件，再修改代码。开始前先做只读审查，确认当前 checkout、分支、Room schema、测试基线和脏文件。本文的代码路径来自 2026-08-14 只读审查，实施时必须以 live checkout 为准。

> **核心产品决定：** ChatWithChat 已放弃“多个平台共同回答同一条用户消息”的路线。本计划不新增多平台聚合，不维护“所有平台回答合计”，也不把历史遗留的多个 assistant candidate 合并成用户可见总量。统计边界是当前选定 platform/model 的请求及其工具循环请求。

## Goal

建立一套单平台、可解释、可持久化的 token accounting：

- 每次真实 provider 请求记录输入、输出、总量、缓存命中、缓存未命中和可选缓存写入量。
- 对支持缓存的 provider 统一计算加权命中率；不支持或未返回字段时隐藏缓存指标，不能伪装成 0%。
- search decision、工具请求、工具结果后的最终回答分别作为请求明细，并在当前单平台回答内合并一次。
- 用户主视图只看当前平台的“本次回答”用量；展开后看工具请求和本轮总计，不显示“所有平台回答合计”。
- 在回答轮次右上角显示当前会话的缓存命中率；统计仍限定为当前选定 platform/model。
- 旧版 TokenUsageRecord JSON 可以继续读取；新增字段缺失时按缓存数据不可用处理。
- 明确区分 provider 官方统计和本地估算。

## Product Semantics

### Single-platform boundary

一次用户回合的统计范围：

    当前选定 platformUid/model
      ├─ 可选：search decision 请求
      ├─ 可选：工具决策/工具循环请求
      ├─ 可选：工具结果后的最终回答请求
      └─ 最终可见回答

明确取消：

- 不再计算 assistantMessages 中不同平台 candidate 的总和。
- 不再展示“所有平台回答合计”。
- 不再用跨平台 candidate 数量推导 token 总量、命中率或成本。
- 不新增多平台 fan-out 统计接口、聚合 DTO 或 UI 摘要。

### Stable prompt prefix

提示词构造增加稳定前缀策略，但不改变模型可见语义：

- 静态 system instructions、工具定义/schema、长期不变的共享上下文和示例放在前面。
- 会话中稳定的历史消息保持原顺序；当前用户消息、用户专属数据、时间戳、随机 trace id 和其他动态字段放在可缓存前缀之后。
- 不在稳定前缀中插入请求级动态值；工具定义、工具顺序、序列化格式和空值策略保持确定性。
- 对支持 `prompt_cache_key` 的接口，为同一 platformUid/model 使用稳定 key；key 不跨 provider、账号或不同 system prompt 复用。
- DeepSeek 优先保持 system prompt、工具描述和共享上下文的字节级稳定，将本轮问题和工具结果置于后部；不为缓存命中牺牲必要的上下文或安全指令。
- 仅调整排列和序列化稳定性，不新增 provider 不支持的缓存控制字段；OpenRouter response cache 不计入 prompt cache 命中。

稳定前缀变更必须通过 provider 实测 A/B 验证，不能仅凭本地 token 估算宣称命中率提升。

旧聊天数据仍有多个 assistant candidate 时：

- 每个 candidate 的历史 TokenUsageRecord 可以继续读取。
- 当前选中的 candidate 只展示自己的用量。
- 其他 candidate 不参与当前用量、缓存命中率或本轮总计。
- 不删除历史消息或历史 usage JSON。

### Request-level truth

provider usage 是请求级事实。先求和、后计算比率：

    cacheInputTokens = cacheHitTokens + cacheMissTokens
    cacheHitRate = cacheHitTokens / cacheInputTokens

规则：

- input、output、total、cache hit、cache miss、cache write 均按请求求和。
- 命中率使用聚合后的分子/分母，不能平均各请求百分比。
- 缺少缓存字段的请求状态是未知，不能把缺失填成 0 后参与分母。
- 混合已知和未知请求时，可显示已知请求的加权率，但必须标记“部分请求有缓存统计”。
- cacheWriteTokens 仅用于成本/诊断，不重复加入 input 或 total。

### Session-level cache rate

回答轮次右上角的会话命中率定义为当前聊天会话、当前选定 platformUid/model 下所有请求的加权比例：

    sessionCacheHitRate =
        sum(cacheHitTokens) / sum(cacheHitTokens + cacheMissTokens)

只纳入包含完整 hit/miss 的请求；不能读取同一会话其他平台 candidate，也不能把未知请求补成 0。会话中只有未知请求时，右上角整块缓存指标隐藏；混合已知和未知时显示已知请求的加权率，并标记为部分统计。会话率与当前回答、本轮总计使用同一套聚合函数。

### Cache field meanings

| 字段 | 语义 |
|---|---|
| inputTokens | provider 报告的输入 token 总量，保持该 provider 的原始口径。 |
| cacheHitTokens | 从已有缓存读取的输入 token。 |
| cacheMissTokens | 未从已有缓存读取的输入 token；首次写入缓存的部分属于 miss。 |
| cacheWriteTokens | provider 单独报告的缓存写入 token。 |
| cacheStatus | KNOWN、PARTIAL 或 UNAVAILABLE。 |
| cacheHitRate | 派生值，分母为 hit + miss，不建议持久化。 |

本地 TokenUsageEstimator 只能估算 input/output，不能推断缓存命中。估算记录必须标记 isEstimated，缓存字段为空、状态 UNAVAILABLE，UI 不显示命中率。

## Current State To Audit

以下事实来自本计划创建前的只读审查，实施前仍需复核：

- TokenUsageRecord 位于 app/src/main/kotlin/cn/nabr/chatwithchat/data/token/TokenUsageRecord.kt，目前没有缓存字段。
- TokenUsageDetail 也不能表达缓存读写。
- ProviderUsage 位于 app/src/main/kotlin/cn/nabr/chatwithchat/data/dto/ProviderUsage.kt，目前只解析普通输入/输出/总量。
- TokenUsageRecordConverter 使用 ignoreUnknownKeys = true，新增可空字段具备旧 JSON 兼容基础。
- DeepSeek 的 prompt_cache_hit_tokens / prompt_cache_miss_tokens 尚未解析。
- OpenAI/Groq 的 prompt_tokens_details.cached_tokens / cache_write_tokens 尚未解析。
- OpenAI Responses 的 input_tokens_details.cached_tokens / cache_write_tokens 尚未解析。
- Gemini UsageMetadata 没有 cachedContentTokenCount。
- Anthropic 已有 cache_creation_input_tokens 和 cache_read_input_tokens，但 ChatRepositoryImpl.anthropicUsageFromChunks 会把它们加回普通 input，之后缓存分解丢失。
- OpenRouter provider prompt cache 与 OpenRouter response cache 是不同机制；本计划只纳入 provider prompt-cache usage，不把完整响应 HIT 当成 prompt hit。
- Ollama 没有标准云端 prompt-cache hit/miss usage 字段；keep_alive、模型驻留和 KV/context 行为不转换为 token 命中率。
- TokenUsageRow.kt 当前同时显示当前回答、本轮合并总量、所有平台回答合计、工具循环合计。
- ChatScreen.kt 将 assistantMessages 映射成 turnTokenUsages 后传入 TokenUsageRow；该参数不能继续表示所有 candidate。
- RoundNavigatorOverlay.kt 也对 assistantRow 做 token 求和；必须改成当前 candidate 口径。
- ChatRepositoryImpl 使用 withToolAggregate、withAdditionalToolUsage、aggregateToolUsage 合并工具请求，需确认每个请求只进入 aggregate 一次。

## Canonical Data Model

新增可序列化的 TokenCacheStatus：

    KNOWN
    PARTIAL
    UNAVAILABLE

推荐追加到 TokenUsageRecord 和 TokenUsageDetail，默认值必须兼容旧 JSON：

    cacheHitTokens: Int? = null
    cacheMissTokens: Int? = null
    cacheWriteTokens: Int? = null
    cacheStatus: TokenCacheStatus = UNAVAILABLE

命中率使用扩展属性或纯函数计算，不作为持久化字段。命中/未命中不得为负数，且不能超过可解释的 input token 范围；异常响应保留普通 input/output，缓存状态降级为 UNAVAILABLE。

## Provider Normalization Matrix

| Provider | 来源 | hit | miss | 状态 |
|---|---|---:|---:|---|
| DeepSeek | prompt_cache_hit_tokens / prompt_cache_miss_tokens | hit 字段 | miss 字段 | KNOWN |
| OpenAI Chat | usage.prompt_tokens_details | cached_tokens | prompt_tokens - cached_tokens | 有完整字段时 KNOWN |
| OpenAI Responses | usage.input_tokens_details | cached_tokens | input_tokens - cached_tokens | 有完整字段时 KNOWN |
| Groq | prompt_tokens_details.cached_tokens | cached | prompt_tokens - cached | 有完整字段时 KNOWN |
| Anthropic | cache_read_input_tokens、cache_creation_input_tokens、input_tokens | cache read | input + cache creation | KNOWN |
| Gemini | cachedContentTokenCount（若返回） | cached | prompt - cached | 有完整字段时 KNOWN |
| OpenRouter | 下游 provider usage；response cache 单独记录 | 下游 usage | 下游 usage | 不把 response HIT 当 prompt hit |
| Ollama | 无标准字段 | null | null | UNAVAILABLE |
| Custom | 仅解析明确兼容协议 | 可选 | 可选 | KNOWN 或 UNAVAILABLE |

Anthropic 规范化为：

    cacheHitTokens = cacheReadInputTokens
    cacheMissTokens = inputTokens + cacheCreationInputTokens
    cacheWriteTokens = cacheCreationInputTokens

不得把 cache read/write 再次加进普通 input，避免重复计数。具体 input 口径必须用固定 response fixture 验证。

## User-facing Presentation

### Collapsed row

回答轮次摘要直接显示当前平台本轮总计，只合并本轮 search decision、tool loop 和最终回答请求：

    输入 1.2k · 输出 180 · 合计 1.4k

有缓存统计时增加：

    缓存命中 900 · 未命中 340 · 命中率 72.6%

缓存不可用时显示：

    （不渲染缓存字段）

本地估算时显示：

    输入 估算 · 输出 估算

禁止在折叠行显示“所有平台回答合计”。

摘要不再优先显示“本次回答”用量；最终回答和工具请求的拆分只在展开详情中查看。

### Expanded row

固定为单平台明细与总计两部分，摘要使用的本轮总计在详情中置于末尾：

    本次回答
      输入 / 输出 / 合计
      缓存命中 / 未命中 / 命中率（若可用）

    工具请求（仅在存在时）
      输入 / 输出 / 合计
      缓存命中 / 未命中 / 命中率（若可用）

    本轮总计
      当前平台所有请求的输入 / 输出 / 合计
      当前平台已知缓存请求的加权命中率

本轮总计只能合并当前平台的 search decision、tool loop 和最终回答请求，不能读取同一回合的其他 assistant candidate。

回答轮次摘要与展开后的“本轮总计”必须引用同一个聚合对象，确保输入、输出、合计和缓存命中率完全一致。

UI 必须区分“官方统计”“部分统计”和“估算”。provider 不支持或未返回缓存字段时，直接隐藏缓存命中、未命中、命中率和来源标签，不显示“缓存统计不可用”占位文案。命中率保留一位小数；分母为 0 或未知时不显示百分比。折叠态继续使用紧凑数字，详情态保留完整整数。

### Answer-round header

- 在回答轮次标题栏右上角显示 `会话缓存命中率 xx.x%`，数据源为当前会话当前 platformUid/model 的 session-level 聚合。
- 无已知缓存请求时隐藏整个指标区域；有未知请求混入时显示“部分统计”标记。
- 右上角指标必须与轮次标题、平台名称和展开按钮保持不重叠；小屏幕使用紧凑标签或在标题栏内换行。
- 展开详情沿用同一 session aggregate，不因展开候选消息而重新计算跨平台总和。

## Implementation Tasks

### Task 0: Freeze the single-platform boundary

- [x] 重新读取 AGENTS.md、本计划、TokenUsageRecord.kt、ProviderUsage.kt、ChatRepositoryImpl.kt、TokenUsageRow.kt、ChatScreen.kt、RoundNavigatorOverlay.kt。
- [x] 确认 checkout、Room schema、未提交设置修改和测试基线，不覆盖用户已有改动。
- [x] 列出所有 TokenUsageRecord 创建点、UsageUpdated 发射点和跨 assistantMessages/assistantRow 的聚合点。
- [x] 固定当前 candidate 才能进入用户可见本轮统计；跨 candidate 只保留历史读取。

### Task 1: Extend canonical accounting

- [x] 添加 TokenCacheStatus、cache fields、命中率派生函数和统一校验。
- [x] 同步扩展 TokenUsageDetail，使工具请求明细能表达缓存统计。
- [x] 更新 sumTokenUsage 和工具聚合函数：缓存按 token 求和，命中率按聚合后分子/分母计算。
- [x] 保持旧 JSON 默认值和 converter 兼容；只有 live audit 证明需要独立 Room 列时才升级 schema。

### Task 2: Normalize provider usage

- [x] 扩展 ProviderUsage 支持 DeepSeek、OpenAI、Groq 和 OpenRouter 下游常见嵌套字段。
- [x] 扩展 OpenAI Responses usage DTO，支持 input_tokens_details。
- [x] 扩展 Gemini UsageMetadata 的 cached content 字段。
- [x] 修复 Anthropic usage 归一化，保留 cache read/write，不吞成普通 input。
- [x] Custom 只解析明确兼容字段，未知协议不猜测。
- [x] Ollama 保持 UNAVAILABLE，不从 keep_alive 或 duration 字段推断命中。
- [ ] 若未来需要 OpenRouter response cache，另建 metadata，不进入 prompt cacheHitTokens。

### Task 3: Fix request and tool-loop accounting

- [x] search decision usage 作为当前平台独立 detail 进入本轮总计一次。
- [x] tool request usage 每次只进入 aggregate 一次，避免中间 UsageUpdated 与最终 aggregate 重复。
- [x] 最终回答 usage 只有存在可见回答时作为“本次回答”显示；无回答的失败/工具请求只进入工具明细或诊断。
- [x] 保留旧 toolInputTokens/toolOutputTokens 兼容字段，但展示模型必须区分工具请求和回答请求。
- [x] 删除或改造 assistantRow.sumOf、turnUsages.sumOf 等跨 candidate 统计；当前平台请求列表必须有明确 platform binding。

### Task 4: Simplify UI

- [x] 从 TokenUsageRow 删除 turnUsages 的跨平台语义和 allPlatformTotal 行。
- [x] ChatScreen 只传当前选定 assistant candidate 的 usage 与当前平台请求明细。
- [x] 展开态改成“本次回答 / 工具请求 / 本轮总计”三层；折叠态直接显示“本轮总计”，缓存明细和统计来源紧邻展示。
- [x] RoundNavigatorOverlay 的轮次 token 只来自当前选定 candidate；删除或隐藏跨平台 token sum。
- [x] 增加中文和英文资源：命中、未命中、命中率、官方统计、部分统计、估算。
- [x] provider 缓存字段缺失或不支持时，直接隐藏缓存字段和占位文案；仅保留输入/输出/合计等可用统计。
- [x] 在回答轮次右上角增加当前会话缓存命中率，绑定当前 platformUid/model，并复用统一加权聚合函数。
- [ ] 验证小屏幕下 cache labels 不重叠；详情态使用完整整数。

### Task 5: Tests and verification

- [x] ProviderUsage fixtures：DeepSeek、OpenAI Chat、OpenAI Responses、Groq nested details、Anthropic read/write、Gemini cached content、缺失字段。
- [x] 断言命中率使用加权 token，不是平均请求百分比。
- [x] 断言负数、超过 input、缺 miss 等异常字段降级为 UNAVAILABLE，普通 input/output 保留。
- [x] 断言旧 TokenUsage JSON 缺少 cache fields 时显示不可用而不是 0%。
- [x] 断言工具循环 search decision、tool requests、final answer 各计一次，最终 aggregate 不重复。
- [x] 断言多个 assistant candidate 存在时，当前 candidate 不包含其他 candidate，不生成 allPlatformTotal。
- [x] 断言 Anthropic cache read/write 不被加倍计入 input/total。
- [x] 断言估算 usage 不生成 cache hit/miss 或命中率。
- [x] 断言 provider 缓存字段缺失/不支持时缓存字段完全不渲染，会话右上角指标也隐藏。
- [x] 断言会话级命中率只聚合当前 platformUid/model 的已知请求，并按总 token 加权；混合未知请求时显示部分统计。
- [ ] 运行稳定提示词 A/B 实测：固定 provider、账号、model、`prompt_cache_key`（若支持）、工具/schema、system prompt 和请求序列；A 使用现有提示词顺序，B 使用稳定前缀顺序。每组先执行冷启动/缓存写入请求，再执行至少 5 次相同前缀的变动请求，记录每次官方 `cacheHitTokens`、`cacheMissTokens`、`cacheWriteTokens`、输入 token 和响应时间。
- [ ] A/B 结果使用 `sum(hit) / sum(hit + miss)` 比较，不平均每次百分比；报告首请求写入、后续请求命中率、绝对提升和相对提升，并记录 TTL、路由、并发和服务端缓存淘汰等干扰因素。
- [ ] 若没有可用 provider 凭据或 provider 不返回缓存字段，运行离线 prompt snapshot/token fixture 仅验证前缀稳定性和统计逻辑，明确标记“无法证明真实命中率变化”，不伪造 A/B 结果。
- [x] 运行至少 :app:testDebugUnitTest、:app:compileDebugKotlin、:app:assembleDebug、git diff --check；若修改 Compose UI，再运行可用 instrumented/UI gate。

## Verification Matrix

| 场景 | 期望 |
|---|---|
| DeepSeek hit 900、miss 300 | 命中率 75.0% |
| OpenAI prompt 1200、cached 900 | miss 300，命中率 75.0% |
| Anthropic input 300、read 900、write 0 | 命中 900、未命中 300，不重复加 900 |
| 两个 tool request + final answer | 当前平台请求求和，单次 aggregate |
| provider 不返回 cache fields | 隐藏行内缓存字段和回答轮次右上角缓存指标 |
| 本地估算 | 显示估算，不显示命中率 |
| 旧多 candidate 聊天 | 当前 candidate 用量，不显示所有平台合计 |
| 当前会话有已知缓存请求 | 回答轮次右上角显示当前平台/model 的加权会话命中率 |
| 当前会话无已知缓存请求 | 回答轮次右上角隐藏缓存命中率区域 |

## Stable-prefix A/B protocol

### Test groups

| 组别 | 提示词布局 | 目的 |
|---|---|---|
| A（基线） | 保持当前 live prompt 拼接顺序 | 测量现状命中率 |
| B（稳定前缀） | 静态指令/schema/共享上下文在前，动态用户内容和工具结果在后 | 测量调整后的命中率 |

两组必须使用同一 provider、账号、model、请求参数、工具定义、消息内容和调用间隔；仅允许改变提示词布局及必要的稳定序列化。每组单独使用稳定的 cache key，避免 A/B 相互污染；同一组先发一次冷请求建立缓存，再发至少五次内容位于前缀之后的请求。记录 provider 原始 usage，不以本地 tokenizer 结果替代官方 cache hit/miss。

### Result acceptance

- 主要指标：`sum(cacheHitTokens) / sum(cacheHitTokens + cacheMissTokens)`；同时报告首请求和后续请求，避免写入请求掩盖差异。
- 辅助指标：缓存写入 token、输入 token、端到端延迟中位数；只用于诊断，不改变命中率口径。
- 结果必须注明缓存 TTL、请求路由/key、并发、服务端淘汰和 provider 字段缺失情况。单次实验不能证明长期稳定提升；至少重复两轮并报告均值与范围。
- 若 provider 未返回缓存字段，A/B 只能报告 prefix snapshot 一致性和请求 token，结论为“真实命中率不可测”，不得显示 0% 或推断命中。

## Non-Goals

- 不恢复或扩展多个平台共同回答。
- 不实现 OpenRouter response cache；若未来启用需另立计划。
- 不把 Ollama 模型驻留/KV 复用宣传为 token cache 命中率。
- 不在本计划中增加 Anthropic cache_control、OpenAI breakpoint 或 Gemini 显式缓存对象；本计划仅调整应用侧稳定前缀布局和确定性序列化。
- 不删除旧消息、旧 candidate、旧 usage JSON 或历史 revision。
- 不改变回答质量、工具权限、记忆语义、附件、编辑、重试和导出行为。

## Completion Report Requirements

完成时必须报告：

- 新增的 TokenUsageRecord/ProviderUsage 字段及旧 JSON 兼容结果。
- 每个 provider 的真实 usage fixture 和缓存字段覆盖情况。
- 单平台边界如何阻止跨 candidate 聚合。
- 工具循环请求是否只计一次。
- “所有平台回答合计”已删除的代码和测试证据。
- 加权命中率证据，以及 provider 不可用时的显示。
- 已运行的命令、未运行的 device/provider runtime gates 和剩余风险。

## Handoff Prompt

你是负责 ChatWithChat 的资深 Kotlin/Jetpack Compose 工程师。先完整阅读 AGENTS.md 和本计划，检查 live branch、dirty files、Room schema、现有 token usage 创建点和 provider DTO。用户已经取消多个平台共同回答路线；不得重新引入或保留“所有平台回答合计”这一用户可见统计。按 Task 0-5 顺序实施，先完成 provider fixtures 与请求级 accounting，再改 Compose UI。保护无关工作区修改；不要把编译通过写成真实 provider 缓存已验证。最终按 Completion Report Requirements 报告真实字段、测试和未验证边界。
