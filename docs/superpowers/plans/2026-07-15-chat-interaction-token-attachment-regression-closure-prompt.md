# ChatWithChat Chat Interaction, Token Accounting And Attachment Regression Closure Prompt

> **用途：** 本文是一份可直接交给新的实现 Agent 执行的仓库提示词。不要停在分析、复述问题或再写一份计划；执行 Agent 应重新核对 live 代码后完成实现、测试和真实模拟器验收。范围包括模型/思考选择面板、思考能力兼容、首次配置闪屏、抽屉选择态、流结束滚动、token 分类与深色图标、多图选择。

> **当前快照：** 本文写于 2026-07-15。仓库位于 `E:\code\ChatWithChat`，当前 `main` 与 `origin/main` 同为 `e94cbd8`。工作区已有用户未提交修改：`README.md`、`app/src/main/kotlin/cn/nabr/chatwithchat/data/ModelConstants.kt`、`build-apk.ps1`。它们不属于本任务，禁止 reset、checkout、覆盖或混入提交。`adb devices` 当前可见 `emulator-5554`，但执行时仍须重新确认。

## Goal

一次性关闭以下用户可见回归，并让修复建立在稳定的状态与数据语义上，而不是增加更多延时、字符串特判或宽泛滚动副作用：

1. 对话页和空会话页的模型/思考选择不再使用不稳定的级联双浮窗，改为符合 HIG 交互原则、同时适配 Android Compose/Material 3 的单一选择面板。
2. 将“模型支持思考”和“模型支持低/中/高等级”拆开；`deepseek-v4`、其他只采用模型默认思考的模型以及未识别模型，不再错误显示为“不支持思考”。
3. 首次供应商/模型配置成功后直接进入稳定的新空会话页，中间不闪现旧首页、“请添加平台”兜底页或已重置的向导首步。
4. 会话历史抽屉通过遮罩、侧滑或返回键收起后，长按删除选择态全部重置；再次打开不得保留勾选或删除工具栏。
5. 流式输出结束、Room 保存回读、MathJax/操作栏完成布局后，界面不再被强制拉到会话顶端，也不产生二次跳动或到底部按钮闪烁。
6. token 统计只在模型实际产生工具调用时记录工具循环；仅仅向模型提供工具定义、但模型直接回答，必须仍属于普通请求。
7. token 指标图标和展开箭头在浅色、深色、动态主题下都有明确且足够的前景对比度。
8. 一次系统图片选择操作可以选择多张图片，并沿用现有附件草稿、预处理、上传、预览、删除和发送链路。

完成标准不是“编译通过”或“给 `deepseek` 增加一个关键词”，而是状态语义、provider 统计、响应式交互和真实设备行为全部一致。

## User-Reported Symptoms

原始问题应作为回归验收用例保留：

- “当前对话页选择模型以及调整思考强度的浮窗不符合 HIG 规范。”
- “在流式传输结束后界面会被强制拉回会话的顶端。”
- “在初次模型配置完成后会闪过一瞬间旧版的对话页面。”
- “主页抽屉页长按选择删除后收起抽屉页，再打开仍会保留选择的删除状态，应重置。”
- “当前 token 统计有问题，针对 GPT 模型，所有的请求 token 都会被归类为工具请求，其他模型未测试。”
- “当前 token 统计的图标在深色模式下不可见。”
- “调整附件选择，使其可以一轮选择多张图片。”
- 前序模型兼容问题：`deepseek-v4` 明明采用思考模型行为，却显示“不支持思考”；多数模型本身也不提供低/中/高等级。

## Current Repo Facts To Re-Audit

以下是提示词编写时的 live 代码事实。执行时必须重新读取相关文件和 `git diff`，不得把行号或快照当永久事实。

### Model And Reasoning Surface

- `ModelSelectionMenu.kt` 同时创建两个锚定在同一位置的 `DropdownMenu`。
- 思考子菜单使用硬编码 `DpOffset(x = 176.dp, y = 214.dp)`，并设置 `PopupProperties(focusable = false)`。
- 顶部触发区最小高度只有 40dp；“不支持思考”条目仍可点击并打开一个无内容的禁用子菜单。
- `buildModelSelectionOptions()` 已经是空会话页和已有会话页共用的模型选项构造入口，应保留其业务作用。
- `ReasoningMode.kt` 当前把 capability 简化成“返回完整模式列表”或“空列表”；OpenRouter/Custom 又会把显式等级统一映射为 `reasoning_effort`。
- 当前只显式识别 `deepseek-r1`，没有 `deepseek-v4` 或“模型默认思考但不可调级别”的能力类型。

关键文件：

- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ModelSelectionMenu.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/EmptyChatScreen.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatScreen.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/model/ReasoningMode.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/ReasoningParameterMapper.kt`
- `app/src/test/kotlin/cn/nabr/chatwithchat/data/model/ReasoningModeTest.kt`
- `app/src/test/kotlin/cn/nabr/chatwithchat/data/repository/ReasoningParameterMapperTest.kt`

### Startup And First Configuration

- `SetupNavGraph()` 固定以 `Route.CHAT_LIST` 为 `startDestination`，`MainViewModel` 异步解析启动状态后再由 `MainActivity` 命令式跳到 setup/migrate。
- `HomeViewModel.availableChatModels` 初始为空，`ChatShellScreen` 进入 `RESUMED` 后才调用 `fetchPlatformStatus()`。
- `EmptyChatScreen` 把“模型还没加载”与“确实没有任何可用模型”都当成 `canChat = false`，因此首帧可能闪现旧的无平台兜底界面。
- `SetupPlatformWizardScreen` 成功后在目标导航提交前清理保存状态并 reset wizard，存在一帧重绘旧向导首步的风险。

关键文件：

- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/main/MainActivity.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/main/MainViewModel.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/common/NavigationGraph.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/setup/SetupPlatformWizardScreen.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/setup/SetupViewModelV2.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/home/HomeViewModel.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatShellScreen.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/EmptyChatScreen.kt`

### Drawer Selection State

- `HomeViewModel.ChatListState` 持有 `isSelectionMode` 与 `selectedChats`。
- `disableSelectionMode()` 已经能够清空选择，但 `ChatShellScreen.openDrawer()` / `closeDrawer()` 只操作 `DrawerState`。
- 系统返回键经过 `BackHandler` 时会清选择；点击 scrim 或侧滑关闭由 `ModalNavigationDrawer` 自行完成，不经过这条清理路径。
- 同一个 `HomeViewModel` 在抽屉关闭后继续存活，因此重新打开会原样恢复删除选择态。

关键文件：

- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/home/HomeViewModel.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/home/ChatHistoryDrawer.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatShellScreen.kt`

### Streaming Scroll

- `ChatScreen` 使用普通 `LazyColumn`，底部另有一个 key 为 `chat-bottom` 的 1dp 占位项。
- 当前滚动状态包含 `autoFollowToBottom`、`latestManualStreamingAnchor`、`pendingPostStreamAnchor`、`pendingPostStreamBottomRestore`。
- streaming -> idle 后，代码每 50ms 检查一次，最多 40 次；自动跟随分支把底部 1dp 占位项索引当成期望的 `firstVisibleItemIndex`。
- 正常处于底部时，`firstVisibleItemIndex` 通常仍是最后一轮消息，而不是 1dp 占位项，因此现有判断可把正常布局误判成“向前跳了”，主动调用 `scrollToLatestMessage()`。
- 全平台转为 Idle 后，`ChatViewModel` 仍会执行 `saveChat()`、Room 回读和 `fetchMessages()`；同时 `ChatBubble` 会从流式轻量渲染切换到 MathJax并显示操作栏，结束后仍有异步高度变化。

关键文件：

- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatScreen.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatViewModel.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatBubble.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ThinkingBlock.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/util/ApiStateFlowExtensions.kt`

### Token Accounting

- `ToolCallingMode` 默认是 `Auto`；至少 `current_datetime` 可默认启用，因此许多普通请求都会进入原生工具循环，即使模型最终没有调用工具。
- provider usage 的 input/output/total 解析大体正确，错误发生在分类层。
- OpenAI Responses、OpenAI Chat Completions、Anthropic、Google 的 native round collector 都在解析实际 calls 之前传入 `isToolRelated = true`。
- 通用 JSON fallback 也会在 usage callback 中无条件 `.asToolRelated()`。
- `aggregateToolUsage()` 会把传入的全部 usage 强制标为工具相关。
- `TokenUsageRow.roundTotalTokens()` 和轮次浮窗在 `toolTotalTokens > 0` 时优先显示工具总量，所以错误分类会直接覆盖普通请求显示。
- `TokenUsageRow` 的输入、输出、总量和展开箭头图标没有显式 tint；聊天页又使用自定义 canvas，深色模式下继承前景色不可靠。

关键文件：

- `app/src/main/kotlin/cn/nabr/chatwithchat/data/dto/ProviderUsage.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/dto/ApiState.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/token/TokenUsageRecord.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/token/TokenUsageEstimator.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/ChatRepositoryImpl.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/TokenUsageRow.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/RoundNavigatorOverlay.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatScreen.kt`
- `app/src/test/kotlin/cn/nabr/chatwithchat/data/repository/ChatRepositoryImplTest.kt`

### Attachments

- `ChatComposer`、用户消息编辑弹窗和助手消息编辑弹窗都使用单选 `ActivityResultContracts.GetContent()`。
- 下游已经使用列表：`MessageV2.attachments`、附件草稿、上传协调器和 OpenAI/Anthropic/Google 请求构造均能遍历多张图片。
- `ChatViewModel.addSelectedFile()` 当前逐文件追加和预处理；并行加入一批文件时，总大小 50MB 校验可能因各任务同时统计其他草稿而互相误拒。
- 空会话路由已经使用 `List<String>` 传递初始附件，不需要新增全局临时状态。

关键文件：

- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatComposer.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatDialogs.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/EmptyChatScreen.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatViewModel.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/AttachmentUploadCoordinator.kt`
- `app/src/test/kotlin/cn/nabr/chatwithchat/data/repository/AttachmentUploadCoordinatorTest.kt`

## Product Contracts

### Model And Reasoning Capability

不要再把“能否思考”和“能否调节思考等级”压缩为一个布尔值或一个空列表。实现一个清晰、可测试的 capability profile，至少能表达：

- `UNKNOWN`：应用没有足够证据判断；UI 显示“模型默认”或“能力未识别”，绝不能断言“不支持”。请求不额外发送思考参数。
- `DEFAULT_ONLY`：模型会按自身默认策略思考，但应用不能可靠控制等级；UI 只显示“模型默认思考”，不显示伪造的低/中/高。
- `TOGGLE`：provider 协议确实支持开启/关闭时才显示开关或 `自动/开/关`。
- `EFFORT`：provider 与模型组合明确支持等级时，才显示其真实支持的档位。
- `UNSUPPORTED`：只有明确确认普通模型不支持思考时使用。

`deepseek-v4` 和其他 `deepseek-*` 不应再因未命中 `deepseek-r1` 而进入 `UNSUPPORTED`。在没有可靠等级协议时应归入 `DEFAULT_ONLY`，请求保持模型默认，不得仅凭名称给 Custom/OpenRouter 发送 `reasoning_effort`。

能力解析优先考虑“provider 协议 + 模型族”，而不是无限增长的字符串合集。此任务不要求新增远程 capability 服务或数据库迁移，也不要求实现用户手动覆盖页面；需要为未来扩展保留纯数据边界。

### Token Accounting Invariants

必须明确并用测试锁定以下语义：

1. `inputTokens/outputTokens/totalTokens` 表示当前可见助手回答对应的 provider 请求用量。
2. 仅提供 tool definitions、但模型没有生成任何工具调用时，`toolInputTokens/toolOutputTokens/toolTotalTokens` 必须全部为 0，所有 detail 的 `isToolRelated` 必须为 false。
3. 模型至少生成一次实际、无效、被拒绝或权限不足的工具调用时，才算进入工具交互；这时工具总量覆盖整个工具循环的模型成本，包括工具决策轮、继续轮和最终回答轮。
4. 同一份 provider usage 在普通字段与工具聚合语义中可以被引用，但不得在同一个聚合总量里重复累计。
5. 服务端 usage 优先；缺失时继续使用现有 jtokkit/估算路径，并设置 `isEstimated = true`。估算与否不能改变工具分类。
6. 不启发式重写历史 token JSON。若必须增加 accounting version，使用向后兼容的默认值和 converter round-trip 测试，不为此升级 Room schema。

### Scroll Intent

滚动行为必须由用户意图驱动，而不是由“某个状态变了”驱动：

- 发送消息、打开键盘、用户点击到底部按钮，可以显式进入“跟随最新消息”。
- streaming chunk 仅在跟随态滚动。
- 用户手动向上滚动后进入“阅读历史”，后续 chunk、streaming -> idle、Room 回读和高度变化均不得抢走位置。
- 流结束本身不是滚动命令。
- 需要恢复阅读位置时，锚点必须是稳定可见项 identity + offset；不得把底部 spacer 索引当作 `firstVisibleItemIndex`，也不得用固定 2 秒轮询猜测异步工作完成时机。

### Multi-Image Selection

- 一次系统选择可返回多张图片，并按用户选择顺序进入草稿列表。
- 相机单拍仍保留；单张结果可以委托给批量入口。
- 批次内允许部分成功：复制失败或超过限制的图片不应丢掉已经成功的图片。
- 保持现有单文件 50MB、单轮总计 50MB、仅图片约束，以及 Custom inline 12MB 安全限制。
- 重复选择同一文件不得产生重复草稿；取消选择不得改变原草稿。
- 不新增全局附件临时状态，不绕过现有 `ChatAttachmentDraft`、预处理与上传协调器。

## Execution Discipline

1. 开始时读取 `AGENTS.md`，运行 `git status --short --branch`、`git diff --stat`、`git diff --` 针对所有拟修改文件。
2. 保护用户现有 `README.md`、`ModelConstants.kt`、`build-apk.ps1` 改动。不得 stash、reset、checkout 或格式化整个仓库。
3. 如执行会话需要创建分支，使用 `codex/chat-interaction-regression-closure`；创建分支不会授权把用户脏文件加入提交。
4. 每个任务先补可失败的定向测试或可观测复现，再实现；不要一次重写整个聊天页。
5. 保持 MVVM、StateFlow、Repository 和现有 Compose 组件边界。共享状态规则应进入 ViewModel/纯 Kotlin policy，不要散落在多个 Composable 中。
6. 新增可见字符串必须进入资源文件并同步项目现有 locale；不要在新代码中继续增加硬编码中文。
7. 每完成一个任务运行最小相关测试和 `:app:compileDebugKotlin`，出现失败立即定位。
8. 本文不授权推送远端。可以创建本地、小而可回退的主题提交；仅在执行会话获得明确授权后 push。

## Implementation Tasks

### Task 0: Baseline And Reproduction Evidence

编辑前完成：

```powershell
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
git diff --check
adb devices

rg -n "ModelSelectionMenu|reasoningModesForModel|DpOffset|PopupProperties" app\src\main app\src\test
rg -n "startDestination|OpenSetup|availableChatModels|resetWizard|clearSaveStatus" app\src\main app\src\test
rg -n "isSelectionMode|selectedChats|disableSelectionMode|DrawerValue" app\src\main app\src\test
rg -n "pendingPostStream|latestManualStreamingAnchor|chat-bottom|fetchMessages\(\)" app\src\main app\src\test
rg -n "isToolRelated|aggregateToolUsage|asToolRelated|roundTotalTokens" app\src\main app\src\test
rg -n "GetContent|PickMultipleVisualMedia|addSelectedFile" app\src\main app\src\test
```

在模拟器上先记录问题证据，至少包括：

- 当前模型/思考双浮窗在 360dp 与深色模式下的截图。
- 清数据后完成首次配置的屏幕录制，标出闪现帧到底是旧首页、无平台兜底还是向导重绘。
- 长会话流结束跳顶时的 `firstVisibleItemIndex/offset`、`isIdle`、`autoFollowToBottom` 和消息刷新时序日志。
- GPT 在 Auto 工具模式下直接回答但被统计为工具请求的结构化 usage 日志；不要记录聊天正文或 token/API key。
- 抽屉通过遮罩、侧滑、返回键三种方式关闭后的选择状态。

如果某一症状在当前 HEAD 无法复现，不要跳过任务，也不要伪造根因；记录实际状态序列，并用回归测试锁住用户描述的行为。

### Task 1: Replace The Cascading Model Popup And Fix Reasoning Capability Semantics

目标：空会话页与已有会话页共用同一个稳定、可访问的模型选择体验。

实现要求：

- 移除双 `DropdownMenu`、硬编码 `DpOffset` 和 `focusable=false` 子浮窗。
- 手机紧凑宽度使用单个 `ModalBottomSheet` 或等价的单层 Compose Material 3 surface；宽屏可使用受约束的 anchored surface，但必须由窗口尺寸计算，不能用固定坐标。
- 同一面板内先显示可滚动模型列表，再显示当前模型的思考能力/控制；不创建嵌套浮窗。
- 当前模型使用明确 checkmark；重复模型 ID 保留 provider subtitle；长名称截断但可通过语义读取完整名称。
- 触控目标不小于 48dp；系统返回、点击遮罩和拖拽仅关闭一层面板。
- `UNKNOWN/DEFAULT_ONLY` 显示“模型默认”，没有无效 chevron；`UNSUPPORTED` 才显示明确禁用说明；`EFFORT` 才显示真实档位。
- 保留当前 `onOptionSelected`、`onReasoningModeSelected`、每聊天模型持久化和 Home/Chat 两处调用契约，必要时用新的 state holder 适配。
- 清理确认无调用的 `openModelPicker` 空回调等遗留接口。
- HIG 在这里表示清晰层级、直接操控、可预期 dismiss、充足点击面积、适配安全区与大字体；底层仍使用 Android Compose/Material 3，不复制 UIKit，也不引入 iOS 专用依赖。

测试至少覆盖：

- `deepseek-v4` -> `DEFAULT_ONLY`，不返回低/中/高，也不发送 `reasoning_effort`。
- 已知 graded 模型仍暴露正确档位并映射正确参数。
- 普通明确不支持模型与未知别名不会混淆。
- 空会话页和已有会话页选择模型后状态一致。
- 320dp、360dp、横屏、最大字体、浅色、深色、动态主题、TalkBack。

### Task 2: Make Startup And First-Configuration Navigation State-Driven

目标：在首个模型真正加载完成前，不渲染任何错误的“无平台”或旧页面状态。

实现要求：

- 将启动决策表达为明确的 `Loading / Resolved(destination)` 状态；在 destination 决定前保持 splash/中性背景，不先组合 `CHAT_LIST` 再异步跳转。
- `SetupNavGraph` 的 start destination 应来自已解析状态，或者只在解析完成后创建 NavHost。
- 保留 setup、legacy migrate、已有配置 home 三条启动路径和通知进入 Memory 的既有行为。
- Home 模型加载也要区分 `Loading`、`Ready(empty)`、`Ready(models)`；只有 `Ready(empty)` 可以显示“添加供应商”兜底。
- 首次配置成功后，先确保数据库保存和模型列表状态可被新首页读取，再原子导航并清理 setup back stack。
- 不要在仍可见的向导页面上先 `resetWizard()`/`clearSaveStatus()`；让图级 ViewModel 随 back stack 销毁，或在导航提交后的安全边界清理。
- 返回键不得回到 setup；进程重建后不得重复导航或显示错误首帧。

新增/更新纯 Kotlin policy 测试和必要的导航/Compose 测试，覆盖 fresh install、已有 V2 平台、legacy migrate、模型刷新失败但平台已保存四条路径。

### Task 3: Reset Drawer Selection Whenever The Drawer Is Dismissed

目标：所有抽屉关闭入口共享同一状态清理语义。

实现要求：

- 提取明确的 `resetDrawerSelection()` 或等价 ViewModel action，清空 `selectedChats`、退出 `isSelectionMode` 并关闭遗留删除确认框。
- 监听 `DrawerState` 从 Open/Opening 进入 Closed 的真实边界，覆盖 scrim、侧滑、系统返回与程序化关闭。
- `openDrawer()` 前做防御性选择态清理，避免异常中断后重新暴露旧状态。
- 不要顺手清除用户搜索 query，除非关闭动作本来就明确退出搜索；选择态与搜索态保持独立。
- 删除确认、复制、打开现有会话和创建新会话仍按当前契约工作。

测试覆盖三种关闭方式；重新打开后 `isSelectionMode == false`、选中数为 0、没有勾选行和删除工具栏。

### Task 4: Replace Post-Stream Polling With An Explicit Scroll-Intent State Machine

目标：流结束不再产生任何隐式滚动命令，阅读位置在异步布局变化后仍稳定。

实现要求：

- 删除或重构 `pendingPostStreamBottomRestore` 和固定 40 次/2 秒轮询。
- 不把 `chat-bottom` spacer 当作可见锚点，也不比较它与 `firstVisibleItemIndex`。
- 用清晰状态表示 `FollowingLatest` 与 `ReadingHistory(anchor)`；状态转换由发送、键盘、用户拖动、到底部按钮等意图触发。
- 捕获锚点时使用不会随 Room id、时间戳、内容长度、MathJax 或附件准备路径变化的稳定 turn identity + offset。
- streaming -> idle 只结束 streaming 状态；不自动滚动。
- Room `saveChat()/fetchMessages()` 保持业务行为不变。若确需 UI 恢复，应在实际数据刷新/布局完成信号后检查稳定锚点，而不是猜测延时。
- 多 provider 中一个先结束、另一个仍流式时不能提前进入全局 idle 恢复。
- 到底部按钮只反映用户离开跟随态，不随 chunk/加载状态闪烁。

测试与设备验收必须覆盖：

- 长会话在底部观看直到完成。
- 流式中手动上滑阅读历史。
- 新会话第一轮从临时状态持久化。
- 含思考、Markdown/公式、工具调用、token 行的回答。
- 多 provider 分时完成。
- 键盘打开/关闭与手动点击到底部按钮。

### Task 5: Correct Token Classification Across Every Provider And Restore Dark-Theme Contrast

目标：工具分类由实际工具交互决定，不由工具是否可用决定。

实现要求：

- provider round collector 只产生未分类的原始 usage；不要在解析 calls 之前硬编码 `isToolRelated = true`。
- native tool loop 在解析出 calls 后再决定 `hasToolInteraction`。首轮 calls 为空并直接回答时，发出普通 usage，tool 字段为 0。
- 一旦模型实际生成工具 call，即使 call 后续被 schema、权限或安全策略拒绝，也将本次完整工具循环按 Product Contracts 聚合。
- 通用 JSON fallback 同样由 `ToolLoopResult`/实际结果门控，不再无条件 `.asToolRelated()`。
- 统一 OpenAI Responses、OpenRouter Chat Completions、Anthropic、Google、Groq、Ollama、Custom；不能只为 GPT 写 model-name 特判。
- 审计 `aggregateToolUsage()`、`withToolAggregate()` 和 `roundTotalTokens()`，确保同一 round 不重复累计，普通合计和工具合计含义一致。
- 保留 provider 精确 usage：OpenAI input/output、Anthropic cache 字段、Google thoughts、Chat Completions stream usage；缺失才估算。
- 不额外叠加 Google 已包含在 `totalTokenCount` 中的 tool-use prompt 字段。
- `TokenUsageRow` 的三个指标图标和展开箭头显式使用主题前景色，例如 `onSurfaceVariant`；聊天 Scaffold/surface 也提供正确 `contentColor`。
- 不用 `Color.Black/White` 修深色问题，不依赖偶然的 `LocalContentColor`，不通过提高整行 alpha 掩盖对比度。

必须新增 provider 参数化测试矩阵：

| Provider/path | Tools available, direct answer | Actual tool call + final answer | Usage missing |
|---|---|---|---|
| OpenAI Responses | 普通，tool=0 | 完整工具循环且不重复 | jtokkit estimated |
| OpenRouter Chat Completions | 普通，tool=0 | 完整工具循环且不重复 | estimated |
| Anthropic native | 普通，tool=0 | 完整工具循环且 cache 字段保留 | estimated |
| Google native | 普通，tool=0 | 完整工具循环且 thoughts 保留 | estimated |
| Groq/Custom/Ollama fallback | 普通，tool=0 | 实际调用才聚合 | estimated |

再覆盖：工具关闭直连、两套 ProviderUsage 字段名、旧/新 JSON round-trip、普通/工具/多平台 `roundTotalTokens()`。深浅色 Compose screenshot 或设备截图中，图标对背景的非文本对比度至少达到 3:1。

### Task 6: Add Deterministic Multi-Image Picking To Every Attachment Entry Point

目标：一次选择多图，复用现有附件列表与 provider 管线。

实现要求：

- 主 `ChatComposer` 使用 `ActivityResultContracts.PickMultipleVisualMedia` + `ImageOnly`，或当前 Activity 版本提供的等价多图 contract。
- 用户消息编辑和助手消息编辑中的图片入口保持一致；不要留下一个单选、一个多选的割裂体验。
- 回调接收 `List<Uri>`，在 IO dispatcher 按顺序复制；完成后通过 `addSelectedFiles(List<String>)` / 编辑态批量入口一次性更新草稿集合。
- 现有 `addSelectedFile()` 可作为兼容委托；相机 `TakePicture()` 继续单拍并委托批量入口。
- 批次先做确定性累计准入，再启动预处理，避免多个协程同时计算 50MB 总量导致互相误拒。
- 复制半途失败时清理不完整目标文件；一次汇总提示失败/拒绝数量，成功项保留。
- 以稳定规范化路径或来源 identity 去重；保留选择顺序。
- 缩略图横向列表、删除单张、Preparing 状态、发送按钮等待、空会话 initial attachment 路由保持可用。
- 下游本来已遍历附件列表；除非测试证明必要，不重写 `AttachmentUploadCoordinator` 或 provider 请求结构。

验收：一次选择 3 张图片，顺序一致显示 3 个缩略图；删除中间一张后发送，其余两张全部进入同一用户轮次。再覆盖取消、重复选择、部分复制失败、超过 50MB、空会话首轮、普通会话、编辑弹窗、OpenAI/Anthropic/Google/兼容渠道序列化。

### Task 7: Integrated Regression And Device Verification

完成所有实现后执行以下验证。测试类名可按最终新增文件调整，但覆盖范围不能缩减。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.model.ReasoningModeTest" --tests "cn.nabr.chatwithchat.data.repository.ReasoningParameterMapperTest"
.\gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.repository.ChatRepositoryImplTest" --tests "cn.nabr.chatwithchat.data.repository.AttachmentUploadCoordinatorTest"
.\gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.presentation.ui.setup.*" --tests "cn.nabr.chatwithchat.presentation.ui.chat.*" --tests "cn.nabr.chatwithchat.presentation.ui.home.*"
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:connectedDebugAndroidTest
git diff --check
```

如果默认 `JAVA_HOME` 无效，再按本机已验证路径为单条命令设置：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-24'
```

模拟器验证：

```powershell
adb devices
.\run-on-emulator.ps1 -NoBuild -DeviceSerial emulator-5554 -JavaHome 'C:\Program Files\Java\jdk-24'
adb -s emulator-5554 logcat -b crash -d -v time
```

必须实际完成以下人工矩阵，并保留截图/录屏或层级证据：

1. 清除 disposable emulator 上 `cn.nabr.chatwithchat` 数据，完成首个供应商配置，全程无旧页闪帧。
2. 模型选择面板在空会话和已有会话、320/360dp、横屏、大字体、浅/深色均无裁切、重叠或级联 popup。
3. `deepseek-v4` 显示模型默认思考，不出现伪造档位和错误“不支持”。
4. 抽屉三种关闭方式全部重置选择态。
5. 至少一条长流式回答，在底部观看和手动上滑两种场景下完成后不跳。
6. GPT Auto 工具模式直接回答显示普通 token；实际调用 `current_datetime` 后显示完整工具循环。
7. 深色模式 token 三个图标与展开箭头清晰可见。
8. 系统选择器一次选择至少 3 张图片，预览、删除、发送、重启后历史展示正确。

## Required Acceptance Criteria

- 不再存在模型选择子菜单的固定 `DpOffset` 或第二个同锚点 `DropdownMenu`。
- 未识别模型不会显示为确定“不支持”；`deepseek-v4` 不暴露不真实的等级参数。
- 首次配置离开 setup 后，back stack 不包含 setup，首帧不出现错误空状态。
- 抽屉关闭后选择态始终为初始值。
- 流结束不调用无条件 bottom scroll；用户阅读锚点在 Room/MathJax 后仍稳定。
- 仅工具可用但未调用时，所有 provider 的 `toolTotalTokens == 0`。
- 实际工具循环总量包括整个模型循环且没有重复累计。
- token 图标不依赖未定义的 inherited content color。
- 一次 picker 操作返回多图，批量大小校验确定、可部分成功、无半成品泄漏。
- 现有附件上传、相机、编辑、重试、导出、记忆、web search、工具权限、多 provider 和 per-chat model override 无行为回归。
- 所有新增测试、debug 编译、AndroidTest 编译、assembleDebug、设备 smoke 和 `git diff --check` 通过；无法运行的项目必须明确说明原因与剩余风险。

## Non-Goals

- 不重写整个聊天 UI、主页、主题系统或导航架构。
- 不通过禁用 Auto 工具模式来掩盖 token 分类错误。
- 不删除 `saveChat()`、`fetchMessages()`、MathJax 或 token 行来掩盖滚动问题。
- 不为滚动增加新的固定延时、重试次数或无限自动滚动。
- 不把所有 `deepseek` 模型假装成支持 `reasoning_effort` 的 graded 模型。
- 不新增远程模型能力服务、host-based provider 路由特判或中转站全局行为变更。
- 不引入新的图片加载框架；继续复用现有本地缩略图方案。
- 不扩大附件总大小限制，不绕过 provider inline/upload 安全边界。
- 不猜测并批量改写历史 token 统计。
- 不修改数据库 schema，除非执行中出现本文未覆盖且有测试证据的必要性；若需要，先停下说明原因。

## Suggested Commit Sequence

保持提交可独立回退，不混入用户现有脏文件：

1. `fix: replace model reasoning popup with adaptive selector`
2. `fix: remove first-configuration navigation flash`
3. `fix: reset drawer selection on dismiss`
4. `fix: preserve chat position after stream completion`
5. `fix: classify token usage by actual tool interaction`
6. `fix: support deterministic multi-image selection`
7. `test: cover integrated chat interaction regressions`

每个提交前运行对应定向测试与 `git diff --check`。最终报告必须列出：实际根因、修改文件、每项用户症状对应的修复、精确测试命令及结果、模拟器证据、未验证风险、提交 ID。不要只说“已优化”。

## Copy-Paste Handoff

```text
请直接执行：
E:\code\ChatWithChat\docs\superpowers\plans\2026-07-15-chat-interaction-token-attachment-regression-closure-prompt.md

先重新审计 live HEAD、工作区和模拟器状态，然后按文档完成实现、定向测试、完整编译和真实 UI 验收。不要再写一份计划，不要覆盖 README.md、ModelConstants.kt、build-apk.ps1 的现有用户改动，不要用延时/禁用工具/删除回读来掩盖问题。每完成一个主题就验证并形成可回退提交；未经本执行会话明确授权不要 push。
```
