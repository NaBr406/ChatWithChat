# 上下文管理与自动压缩 —— 实施交接报告

对应计划：`docs/superpowers/plans/2026-08-08-context-management-auto-compaction-prompt.md`
分支：`feature/context-management-auto-compaction`（19 个提交，`3195b5c`..`d4aa7d6`）
日期：2026-08-09

## 0. 验证状态：未编译验证

**本分支上的全部代码与测试都未编译、未运行。** 沙箱内没有安装 Android/Gradle 工具链，这是实施前确认过的分工：我写代码与测试，构建与运行在 Windows 侧由你执行。因此下面所有"应当如何"的描述都是静态阅读得出的结论，不是执行结果。

需要你在 Windows 上按顺序执行的验证：

```bash
./gradlew assembleDebug          # 先跑这个，见下方 21.json 说明
./gradlew test                   # JVM 单元测试
./gradlew connectedAndroidTest   # migration 测试，需要设备或模拟器
git diff --check
```

`git diff --check` 我已在沙箱内跑过，从合并基点到 HEAD 无空白错误。其余三条都未跑。

### 必须先构建再跑 instrumented 测试

`app/schemas/cn.nabr.chatwithchat.data.database.ChatDatabaseV2/21.json` 的 `identityHash` 目前是占位值 `"00000000000000000000000000000021"`（对比 `20.json` 的真实值 `45d6a407ad147e1052df7f1544bfb8f0`）。我无法生成真实哈希，因为它由 Room 注解处理器在编译期算出。

这会具体地咬人：`MigrationTestHelper` 把 JSON bundle 里的哈希写进 `room_master_table`，而新增的 migration 测试随后会用真实的 `Room.databaseBuilder(...)` 打开同一个库。编译进 APK 的哈希与占位值不一致时，Room 抛 "Room cannot verify the data integrity"。所以顺序不能颠倒：**先 `assembleDebug`（会重写 `21.json`），再 `connectedAndroidTest`**。如果构建后 `21.json` 出现改动，那是预期的，应当一并提交。

## 1. Schema 版本与 migration

数据库版本 **21**（`ChatDatabaseV2.kt:83`，`exportSchema = true`）。迁移 `MIGRATION_20_21` 定义在 `ChatDatabaseV2Migrations.kt`，已注册在 `di/DatabaseModule.kt:116-117`。

20→21 做三件事：

1. `ALTER TABLE platform_model_v2 ADD COLUMN context_window INTEGER` —— 可空，存量行迁入后为 NULL。
2. `ALTER TABLE platform_v2 ADD COLUMN context_strategy TEXT NOT NULL DEFAULT 'AUTO_COMPACT'` —— NOT NULL 带默认值。
3. `createContextManagementTables(db)` 建三张新表及其索引。

这两个新列的语义是相反的，测试也从相反方向断言。`context_window` **必须**迁入 NULL：应用自己臆造一个窗口值会让之后每一次预算计算都悄悄算错，正确做法是等下一次模型刷新从供应商回填。`context_strategy` **必须**迁入有值：列是 NOT NULL，存量供应商行没有可搬运的原值，那个 `AUTO_COMPACT` 默认值就是全部的回填逻辑。把两者都断言成"非空"会同时错过两个要点。

## 2. Checkpoint 存储键

三张表都以 `chat_id` 外键指向 `chats_v2`，`ON DELETE CASCADE`。

| 表 | 主键 |
| --- | --- |
| `context_checkpoint_segment` | `chat_id, platform_uid, segment_index` |
| `context_range_state` | `chat_id, platform_uid` |
| `context_native_compaction_state` | `chat_id, platform_uid` |

`platform_uid` 进主键是刻意的：策略、检查点链和 native 窗口都按供应商隔离，同一个会话切换供应商不会读到另一家的折叠结果。级联删除同样是刻意的，一个活过了自己会话的检查点，会在 SQLite 复用该 chat id 时被当成那个新会话的历史回放出去——`migration20To21_contextStateIsDeletedWithItsChat` 就是为此显式打开 `PRAGMA foreign_keys = ON`（`MigrationTestHelper` 默认不开）后删父行来证明的。

## 3. 各 provider 实测拿到的 context window 值

分两层：**持久化的真实值**由模型发现流程从各供应商字段抓取（Google `inputTokenLimit`、OpenRouter `context_length`、Groq `context_window`、Anthropic `max_input_tokens`、OpenAI 用其模型端点数据），字段缺失或解析失败时**回退且不抛异常**，落库 `platform_model_v2.context_window`。若尚未抓到或抓取失败，进入第二层。

**保守回退值**（`ProviderContextPolicy.kt` 的 `fallbackModelContextWindow`，这是最终依据）：

| 供应商 | 回退窗口 |
| --- | --- |
| OpenAI | 128,000 |
| Anthropic | 200,000 |
| Google (Gemini) | 32,768 |
| Groq | 8,192 |
| Ollama / OpenRouter / Custom | 6,000 |

`ModelContextWindowTable` 里的模型名前缀表（`gpt-4.1`→1,047,576、`gpt-5.2`/`gpt-5.3`→400,000、`o4`/`o3`/`o1`→200,000 等）只在前缀命中时作为中间层；任何一层都不从"模型名推断窗口"。**Ollama 是个特例：无论模型名还是响应里出现多大的数字，永远解析到 `fallbackModelContextWindow`**，因为本地模型没有可信的供应商声明（`ContextWindowResolver.kt:36-38`）。

一处刻意防护，值得知道：`maxContextTokens` **不是**上下文窗口，绝不能喂进预算公式（`ProviderContextPolicy.kt:10-13`、`ContextBudget.kt:8-11` 都写明）。预算的下限是 `MIN_INPUT_BUDGET = 2,048`。

## 4. Native 门控命中的模型族

`NativeCompactionModelGate` 只认 `ClientType.OPENAI` 且模型名前缀命中 **`gpt-5.2` / `gpt-5.3` / `gpt-5.4`** 三者之一。`gpt-5`、`gpt-5.1` 被刻意排除；`CUSTOM`/`OPENROUTER` 网关供应商整体排除——应用侧路径无法保证在网关后面跑的确实是官方 Responses API。未知族默认不支持。

门控之上还有一个**持久化的负信号覆盖**：供应商若返回 `unsupported_parameter` 拒绝 `context_management`，该 chat+platform 对会被标记 `native_unsupported`，之后 `ContextBuilder.usesNativeCompaction()` 恒为 false，不再每轮重试。

## 5. 自动压缩失败行为

分三条路径：

- **LLM 摘要调用失败**（超时/调用失败/输出非法）：`ContextCompactionService` 不抛异常，返回 `Failed` 并附带一个**确定性抽取摘要**作为 fallback，该摘要明确被丢弃、不落库；请求端 `ContextBuilder` 在装配时会产生同样的抽取摘要，所以聊天内容不受影响。后台折叠的重试耗尽（`FAILED_TERMINAL`）后进入 `ContextFoldDegradationRegistry` 的 30 分钟冷却，防止反复撞同一堵墙。
- **checkpoint 写库失败**：`appendSegment` 不包 `runCatching`，Room 异常原样上抛，由 `MemoryMaintenanceProcessor` 捕获并记为作业失败；检查点链保持逐字节不变——"折叠成功之前什么都不写"。
- **native 路径**：`saveNativeState` 写失败被吞掉，`commit()` 返回 false，语义是"照旧发你原本要发的东西"；`markNativeUnsupported` 写失败同样吞掉，下轮重试。`unsupported_parameter` 是三个里唯一持久的负信号。

## 6. 统一入口：所有 provider 都走同一条路

`ChatRepositoryImpl` 里 10 条 provider 路径全部经由 **`buildProviderConversationSnapshot` → `buildManagedContextResult` → `contextBuilder.buildManagedContext(...)`** 这一个漏斗，唯一的策略/范围决策点。逐条核过：OpenAI Responses、OpenAI Chat Completions、Anthropic、Google、Groq、以及 Ollama/OpenRouter/Custom 共用的 Chat Completions 路径，各自的 tool-loop 变体与直发变体都在内。tool-loop 里的 `?:` 复用只是跳过重复装配，不是旁路。

唯一的刻意旁路是 `searchDecisionRecentContext`（2 轮、每消息 400 字符的搜索决策提示），KDoc 里写明它是唯一绕过统一入口的历史装配。另外 `buildManagedContextResult` 在 `contextCheckpointRepository == null` 或 `chatRoomId <= 0` 时返回 null、回退到旧的 `buildContext`，该分支仅测试用。

## 7. 测试覆盖

**单元测试（`data/context/` 下 14 个文件、223 个用例 + 2 个测试替身）**：

- `ContextBudgetTest` 14、`ContextBuildResultTest` 9、`ContextBuilderManagedContextTest` 25、`ContextBuilderTest` 10、`ContextCheckpointRepositoryImplTest` 33、`ContextCompactionServiceTest` 19、`ContextFoldDegradationRegistryTest` 11、`ContextFoldSchedulerTest` 15、`ContextFoldServiceTest` 20、`ContextStrategyTest` 8、`ContextTurnHasherTest` 14、`ContextWindowResolverTest` 11、`NativeCompactionExchangeTest` 36、`NativeCompactionModelGateTest` 8
- 相关但不在该目录：`ModelDiscoveryContextWindowTest` 9、`ResponsesRequestCompactionTest` 7、`ChatDatabaseV2MigrationsTest` 21

**Instrumented（`ChatDatabaseV2MigrationInstrumentedTest.kt`，8 个用例，其中 3 个属于本特性）**：`migration20To21_preservesProviderRowsAndCreatesContextManagementState`、`migration20To21_contextStateIsDeletedWithItsChat`、`freshSchema21_opensAndReopensWithContextManagementState`。均沿用该文件既有的 `MigrationTestHelper` 模式：先 `createDatabase(20)` 手工插 v20 行 → `runMigrationsAndValidate(21)` 验证 DDL 与 `21.json` 逐列一致 → 再用真实 Room 打开，让 DAO 读回——后者专抓"列名对了但亲和性错了"这类原始 SQL 读不出来的错。

**migration 测试已编写但未运行**，原因见第 0 节。不要因为 `./gradlew test` 通过就宣称 migration 已验证——`./gradlew test` 根本不跑 instrumented 测试。

### 本次实施发现的产物缺陷（测试抓出来的）

`NativeCompactionExchange.DEFAULT_JSON` 配置缺 `encodeDefaults`，而 input item 的 `type` 判别字段是普通默认值。序列化往返会丢掉 `type`，同时破坏 `withBalancedToolItems()` 的 call-id 配对和重放请求。已在实现里修复——这是写测试过程中发现的真实产品 bug，不是测试问题。

## 8. 三处刻意偏离计划

1. **不设客户端硬拒绝。** 计划要求"仍超预算则返回可重试错误"；实现改为 `ContextBuildResult.exceedsEffectiveInputBudget` 让状态可观察。理由：预检报错需要改动 `ChatRepositoryImpl` 的每一条 provider 路径，而这里没有任何编译器可依；且供应商自己的限额才是权威的。
2. **AUTO_COMPACT 下确定性摘要的触发点。** 计划写"同步压缩兜底仅在超过 `compactTrigger` 且后台段未就绪时触发"；实现更严格——超出 `compactTrigger` 后还有一层 `recentRawTail` 溢出才接确定性摘要。这比计划严，但字面上不完全一致。
3. **降级注册表在内存而非 Room 表。** `ContextFoldDegradationRegistry` 是遥测加限速，不是请求依赖的状态，不值得进库。

## 9. 尚未完成的验证（设备 / 真实 provider）

以下无法在本沙箱完成，需要真机或真实密钥：

- `./gradlew connectedAndroidTest` 全量 instrumented 测试（含新 migration 测试）。
- 与真实 OpenAI 模型端点的 `context_window` 抓取及 `gpt-5.4` 的 native compaction 端到端流程（先 `assembleDebug` 让 21.json 真实哈希落地）。
- 真实 provider 拒绝 `context_management` 时，`unsupported_parameter` 标记与改道应用侧路径的完整链路。
- `values-*` 的 18 个语言目录没有新字符串键的翻译，只有默认 `values/strings.xml`（中文）里有 `context_*` 共 21 个键，且仓库没有 `values-en`——英文文案未添加。

## 10. UI 面

- 供应商级策略设置：`PlatformSettingScreen.kt:219-234` 入口 + `ContextStrategyDialog`（`PlatformSettingDialogs.kt:694-721`），文案键 `context_strategy`、`context_strategy_auto_compact` 等，标签映射集中在 `ContextStrategyLabels.kt`。
- 会话级范围选择器：`ContextRangeSheet.kt` + `ContextRangeSheetState.kt`，从聊天溢出菜单打开（`ChatScreen.kt:1062-1077`），文案键 `context_range_*` 共 14 个。

## 11. 提交清单

`3195b5c` 起 19 个提交，全在 `feature/context-management-auto-compaction`：

2a3532a 窗口发现与解析 · 3195b5c 策略/范围/检查点/预算模型 · fbe04ef 检查点链/范围/native 状态持久化 · 7bfc06f `context_strategy` 列 · cdaeace 应用侧与 OpenAI-native 压缩服务 · 65d087b 托管装配 · fc8146f 后台折叠作业入队 · 8b62bc1 ChatRepositoryImpl 统一走托管装配、记忆触发点迁移到折叠落地 · 1527103 native compaction item 捕获持久化回传 · a44e945 供应商策略设置项 · e1ac145 会话范围选择器 · 086fb89 降级记录与冷却 —— 之后 7 个 `test(context):` 提交逐层覆盖契约测试，最后 `d4aa7d6` 是 migration instrumented 测试（含对 schema 升版后三个既有测试被静默打破的修复：`freshSchema19_...` 断言 v20、一个活 Room 句柄断言 v20 表集合、四处 `addMigrations` 链缺 `MIGRATION_20_21`——这三类都只在你的 Windows 运行上炸）。

一个实施过程的事实，供你复核时留意：每个提交体里都带 `未编译验证` 标注，最后两个测试提交额外写明了 21.json 占位哈希的构建顺序。你本地尚未提交的改动（`README.md`、贴纸资源的增删、`.codex/`）全程未被动过。

