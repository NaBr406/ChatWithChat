# ChatWithChat Mobile AI UI Redesign Plan

> **For agentic workers:** This is an implementation planning document. Track work with checkbox (`- [ ]`) items. Do not treat the visual direction as permission to copy ChatGPT or Gemini branding, icons, names, or proprietary layouts exactly. The goal is to match familiar mobile AI chat habits while keeping this app's own product identity and existing capabilities.

## Goal

彻底重构 ChatWithChat 的移动端 UI，使第一屏、聊天页、历史会话、模型选择、设置入口更接近 ChatGPT / Gemini 手机端的使用习惯：打开即能对话，历史会话随手可达，模型切换轻量，输入框始终是核心操作区。

默认推荐方向是 **ChatGPT 为主、Gemini 轻量借鉴、分阶段上线**。原因是当前项目已经有多 provider、多模型、记忆、附件、会话导出、回复重试/编辑等能力，ChatGPT 式的对话优先 + 侧边历史抽屉更适合承载这些功能。

## User Decisions

以下决策来自用户当前确认，后续实施按这些约束执行。

- [x] 多模型默认交互：保留上一次所选的模型为默认模型；新聊天优先使用该模型。
- [x] 模型切换位置：模型切换直接放在聊天页顶部，而不是只藏在弹窗/菜单里。
- [x] 记忆链路：设置页增加总开关，用于决定是否启用记忆召回和记忆学习。
- [x] 记忆默认态：鉴于当前记忆系统仍是半成品，推荐默认关闭，用户主动开启后才运行记忆链路。
- [ ] 视觉和交互基准：默认按 ChatGPT 手机端为主，Gemini 只借鉴空会话状态和模型选择的轻量感。
- [ ] 实施深度：默认分阶段上线，先重构聊天主体验，再改历史抽屉和设置页，避免一次性大改导致行为回归。
- [ ] 品牌呈现：默认显示层逐步改成 ChatWithChat，内部包名、类名、数据库表暂不做品牌迁移。

## Verified Current Project Shape

当前项目实际情况如下，后续实施必须以这些文件为准。

- App 使用 Kotlin + Jetpack Compose + Material 3 + Hilt + Navigation Compose。
- `MainActivity` 调用 `enableEdgeToEdge()`，并通过 `SetupNavGraph()` 承载所有页面。
- `SetupNavGraph()` 的当前 `startDestination` 是 `Route.CHAT_LIST`，并包含 onboarding、setup、migration、settings、chat room 等 route。
- 主页是 `HomeScreen`：列表式会话历史、顶部搜索/设置、FAB 新聊天、长按多选删除/复制。
- 聊天页是 `ChatScreen`：顶部返回栏、模型按钮、更多菜单、消息列表、滚动到底部按钮、底部输入框、附件、标题编辑、模型覆盖、导出、文字选择 sheet、消息编辑 dialog。
- 消息气泡在 `ChatBubble.kt`：用户气泡、助手内容、平台切换按钮、复制/选择/编辑/重试、revision 翻页、思考块、附件缩略图。
- 聊天状态在 `ChatViewModel`：`enabledPlatformsInChat`、`chatPlatformModels`、`loadingStates`、`groupedMessages`、`selectedAttachments`、记忆准备/学习、附件预处理、保存会话、重试、编辑、导出都在这里。
- 会话列表状态在 `HomeViewModel`：搜索、选择模式、删除、复制、平台选择 dialog、新聊天平台 uid 列表。
- 设置页是 `SettingScreen` + `PlatformSettingScreen`：主题、添加平台、平台配置、Memory、About、License。
- 数据层已经支持多平台会话：`ChatRoomV2.enabledPlatform: List<String>` 存 uid 列表，`ChatPlatformModelV2` 存每个 chat/platform 的模型覆盖。
- 当前 UI 字符串以中文为默认资源文本，`values/strings.xml` 和 `values-zh-rCN/strings.xml` 都有中文内容。

## Current Code Anchors

这些锚点用于后续实施时快速定位真实入口。开工前仍需重新 `rg` 一次，因为行号可能随其他改动漂移。

- App shell: `presentation/ui/main/MainActivity.kt`, `presentation/common/NavigationGraph.kt`, `presentation/common/Route.kt`
- Home/history: `presentation/ui/home/HomeScreen.kt`, `presentation/ui/home/HomeViewModel.kt`
- Chat route/state: `presentation/ui/chat/ChatScreen.kt`, `presentation/ui/chat/ChatViewModel.kt`
- Chat message UI: `presentation/ui/chat/ChatBubble.kt`, `presentation/ui/chat/ThinkingBlock.kt`, `presentation/ui/chat/ChatDialogs.kt`
- Settings/setup: `presentation/ui/setting/SettingScreen.kt`, `presentation/ui/setting/PlatformSettingScreen.kt`, `presentation/ui/setup/*`
- Theme/resources: `presentation/theme/Theme.kt`, `presentation/theme/Color.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values-zh-rCN/strings.xml`
- Persistence contracts: `data/database/entity/ChatRoomV2.kt`, `data/database/entity/ChatPlatformModelV2.kt`, `data/database/entity/MessageV2.kt`
- Repository contracts: `data/repository/ChatRepository.kt`, `data/repository/ChatRepositoryImpl.kt`, `data/repository/MemoryRepository.kt`
- Preferences contracts: `data/datastore/SettingDataSource.kt`, `data/datastore/SettingDataSourceImpl.kt`, `data/repository/SettingRepository.kt`, `data/repository/SettingRepositoryImpl.kt`

## Product Principles

- 对话优先：第一屏不是会话列表，而是可立即输入的新聊天界面。
- 历史隐到侧边：历史会话、搜索、设置、记忆入口放进 drawer，保持聊天页干净。
- 模型选择低摩擦：聊天页顶部直接显示和切换当前模型；新聊天默认使用上一次所选模型；多模型对比作为明确的高级入口。
- 半成品能力显式开关：记忆链路默认不隐式运行，必须由设置页总开关控制。
- 不丢现有能力：附件、记忆、导出、编辑、重试、revision、多 provider、模型覆盖、会话复制/删除都必须保留。
- 不伪造功能：不新增语音输入、联网搜索、停止生成等 UI，除非先补相应 ViewModel / Repository 行为。
- 视觉克制：少用大面积彩色卡片，减少 Material 默认列表感；背景、顶栏、输入框、消息动作更接近现代 AI chat app。
- 移动端优先：竖屏手机是第一目标；横屏/平板只保证不崩、不遮挡、基础可用。

## Target Information Architecture

推荐信息架构：一个主 Chat Shell 包住“空会话/当前会话 + 历史抽屉”。

```text
App
├─ Splash / migrate / setup flow (keep current routes)
├─ ChatShell
│  ├─ Drawer
│  │  ├─ New chat
│  │  ├─ Search chats
│  │  ├─ Chat history grouped by time
│  │  ├─ Memory
│  │  └─ Settings
│  ├─ EmptyChat / NewChat
│  └─ ChatRoom
│     ├─ Top model/title bar
│     ├─ Message timeline
│     └─ Composer
└─ Settings graph (keep nested graph, restyle later)
```

Route 建议：MVP 尽量复用现有 route，降低风险。

- `Route.CHAT_LIST`：由“全屏会话列表”改为 `ChatShellScreen` 的空会话入口。
- `Route.CHAT_ROOM`：继续接收 `chatRoomId` 和 `enabledPlatforms`，但 UI 由 `ChatShellScreen` 统一提供 drawer/top-level chrome，`ChatScreen` 逐步拆成可复用的聊天内容。
- `Route.SETTING_ROUTE`：保持现有 nested graph，不迁移数据流。
- 后续若需要更像主流 App，可新增 `Route.CHAT_HOME = "chat?chatRoomId={id}&enabled={uids}"`，但第一轮不推荐改 route contract。

## Target Screens

### 1. Chat Shell / Drawer

目标：替代当前 `HomeScreen` 作为第一屏，让历史列表从“主页面”变成“侧边抽屉”。

视觉与操作：

- 左上角 menu icon 打开 drawer。
- Drawer 顶部显示 ChatWithChat 品牌和“新聊天”按钮。
- Drawer 内置搜索框，复用 `HomeViewModel.searchQuery` 和 `searchChatsV2()`。
- 历史列表按更新时间分组：今天、昨天、7 天内、更早。当前数据只有 `updatedAt`，够用。
- 每个历史项单行或两行：标题 + 平台/更新时间；点击进入 `Route.CHAT_ROOM`。
- 长按历史项进入选择模式，保留删除、复制会话能力。
- Drawer 底部固定 Memory、Settings、About 入口。

文件建议：

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatShellScreen.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/ChatHistoryDrawer.kt`
- Modify: `HomeScreen.kt`，逐步拆出 `ChatHistoryList`、`ChatSearchField`、`HistorySelectionToolbar`。
- Modify: `HomeViewModel.kt`，保留现有状态，增加按更新时间分组的 UI helper 或纯函数。

### 2. Empty / New Chat Screen

目标：用户打开 app 后直接看到可以输入的问题，而不是先看到历史列表。

视觉与操作：

- 中央使用现有 `chatwithchat_logo.png` 或轻量文字品牌。
- 主标题默认：“有什么我可以帮你？”或更中性的“今天想聊什么？”最终文案需确认。
- 底部使用和聊天页同一套 composer。
- 模型入口以顶部小 pill 或 composer 上方一行显示当前主模型。
- 可以提供 3-4 个建议 chip，但必须是可点击填入输入框的真实操作，不做纯装饰文案。
- 如果没有启用平台，主操作应跳到 setup/add platform，而不是显示无法发送的假输入框。

文件建议：

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/EmptyChatScreen.kt`
- Modify: `NavigationGraph.kt` 中 `homeScreenNavigation()` 的 `Route.CHAT_LIST` 目标。
- Reuse: `ChatInputBox`，但需要先抽成更通用的 `ChatComposer`。

### 3. Chat Room

目标：保持功能完整，同时减少“工具栏 + Material 卡片”的旧感。

视觉与操作：

- 顶栏左侧：drawer/menu 或 back，取决于是否采用 shell 包裹全部聊天页。
- 顶栏中间：当前模型切换控件常驻显示，可直接切换当前会话模型；会话标题可作为副信息或放入详情入口。
- 顶栏右侧：新聊天 icon、更多菜单。
- 消息列表使用自然正文布局：助手消息尽量无卡片背景，用户消息保留右侧圆角气泡。
- 助手消息动作默认隐藏或降低视觉权重，长按/悬停区域后显示：复制、选择、编辑、重试。
- 单模型会话中，顶部模型切换是主入口；多平台响应不再用显眼的一排按钮作为主路径。
- 对比模式会话中，顶部显示当前对比组入口，消息内再显示 compact segmented chips 用于切换各 provider 回复。
- `ThinkingBlock` 保留，但样式改成可折叠的轻量块。
- `ScrollToBottomButton` 保留，视觉改成小型浮动圆形按钮。

文件建议：

- Modify: `ChatScreen.kt`，拆成 `ChatRoute`、`ChatContent`、`MessageTimeline`、`ChatTopBar`、`ChatComposer`。
- Modify: `ChatBubble.kt`，重做 `UserChatBubble` / `OpponentChatBubble` / `PlatformButton` / actions row。
- Keep: `ChatDialogs.kt`，先只做样式收敛，不改行为。
- Keep: `ChatViewModel` 的 send/retry/edit/export/memory logic，除非 UI 状态聚合确实需要拆分。

### 4. Composer

目标：像主流 AI 手机端一样，输入区成为稳定、熟悉、可扩展的核心控件。

当前 `ChatInputBox` 已有 TextFieldState、附件、发送、准备状态显示，应抽成通用 composer。

MVP 行为：

- 左侧 `+` / attachment icon：只打开当前支持的图片选择，不显示未实现的文件类型菜单。
- 中间多行输入，最多 5 行，保留 IME padding 行为。
- 右侧发送按钮：空文本且无 ready/preparing 附件时 disabled。
- 附件缩略图横向展示，保留 Preparing / Failed / notice 状态。
- 生成中只禁用发送，不显示停止按钮；停止生成需要另立任务补 cancellation contract。

文件建议：

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatComposer.kt`
- Move from: `ChatScreen.kt` 的 `ChatInputBox`、`FileThumbnailRow`、`FileThumbnail`、`copyFileToAppDirectory()` 等 UI/文件选择辅助。
- Keep tests around attachment helpers and existing `ChatViewModel` behavior.

### 5. Top Model Switcher / Compare Mode

目标：把当前“选择多个平台开新聊天 + 每个平台单独响应”的能力变得更像主流模型选择，同时保留差异化。

默认新聊天：

- 使用上一次所选的模型作为默认模型，并用该模型创建 `enabledPlatforms = listOf(platformUid)`。
- “上一次所选模型”至少包含 `platformUid` 和 `model` 两部分；如果该 platform 被删除/禁用，回退到第一个 enabled platform。
- 默认模型选择需要持久化到 DataStore，不能只放在 `HomeViewModel` 内存状态里。
- 聊天页顶部常驻模型切换控件；点击后打开 bottom sheet 或 compact menu，列出已启用平台和模型名。
- 用户在聊天页顶部切换模型后，应更新当前会话的 `chatPlatformModels`，并同步更新全局“上一次所选模型”。

高级对比：

- 在模型 picker 中提供“比较多个模型”开关。
- 打开后复用 `SelectPlatformDialog` 的多选逻辑，但改成 bottom sheet。
- 创建会话时仍传多个 uid 给 `Route.CHAT_ROOM`，继续复用 `ChatViewModel.enabledPlatformsInChat` 和平台切换逻辑。
- 对比模式下显示每个助手回复的 provider chip；单主模型模式下隐藏 chip。

文件建议：

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ModelPickerSheet.kt`
- Modify: `HomeViewModel.kt`，读取持久化的 last selected model 作为新聊天默认模型，保留 `isCompareMode` UI 状态。
- Modify: `SettingDataSource.kt` / `SettingDataSourceImpl.kt`，增加 last selected model 的读写，例如 `last_chat_platform_uid` 和 `last_chat_model`。
- Modify: `SettingRepository.kt` / `SettingRepositoryImpl.kt`，暴露 last selected model 的读取和更新方法。
- Modify: `ChatScreen.kt` 的 `ChatModelDialog` 入口，保持 per-chat model override。
- Do not modify database schema for MVP.

### 6. Memory Enable Switch

目标：当前记忆系统仍是半成品，必须由设置页明确开关控制，避免用户无感触发记忆召回/学习。

默认行为：

- 新增全局偏好 `memory_enabled`，默认 `false`。
- 开关关闭时，聊天请求不注入 memory prompt。
- 开关关闭时，保存会话后不运行 `learnFromSavedChat()`。
- 开关关闭时，Memory 管理页仍可进入，用于查看/清理已有记忆；页面顶部需要显示“记忆链路未启用”的状态。
- 开关打开时，才允许 `prepareMemoryPrompt()` 和 `learnFromSavedChat()` 进入 `MemoryRepository`。

文件建议：

- Modify: `SettingDataSource.kt` / `SettingDataSourceImpl.kt`，增加 `updateMemoryEnabled(enabled: Boolean)` 和 `getMemoryEnabled(): Boolean?`。
- Modify: `SettingRepository.kt` / `SettingRepositoryImpl.kt`，增加 `fetchMemoryEnabled(): Boolean` 和 `updateMemoryEnabled(enabled: Boolean)`；默认返回 `false`。
- Modify: `SettingViewModelV2.kt`，增加 `memoryEnabled` StateFlow 和 `toggleMemoryEnabled()`。
- Modify: `SettingScreen.kt`，在 Personalization 分组增加 `Switch` 行：标题“启用记忆”，说明“开启后会在聊天中使用并学习长期记忆”。
- Modify: `ChatViewModel.kt`，在 `completeChat()` / retry path 准备 memory prompt 前检查开关；在 `observeStateChanges()` 触发学习前检查开关。
- Modify: `MemoryScreen.kt`，展示当前开关状态，关闭时仍允许管理已有记忆但不暗示链路正在工作。
- Modify: strings resources，增加所有新文案。

实现注意：

- `prepareMemoryPrompt()` 和 `learnFromSavedChat()` 两处都要被同一个开关控制，不能只关 UI 入口。
- 关闭开关不删除已有记忆；删除/归档仍由 Memory 页负责。
- 切换开关不需要数据库迁移，使用 DataStore 即可。
- 若用户在一次生成中途关闭开关，不要求取消已发起的请求；下一次请求必须生效。

### 7. Settings / Setup Restyle

目标：设置页仍是二级页面，但更贴近 AI app 的“账户/模型/记忆/外观/关于”组织。

建议分组：

- Models & Providers：添加平台、平台列表、启用状态。
- Personalization：启用记忆开关、Memory 管理页。
- Appearance：主题、深色模式、动态色。
- App：About、License。

文件建议：

- Modify: `SettingScreen.kt`，把扁平 `SettingItem` 改为分组 section。
- Modify: `SettingScreen.kt`，新增记忆总开关；Memory 管理入口和开关放在同一分组。
- Modify: `PlatformSettingScreen.kt`，保留所有字段，样式从大列表改为更紧凑的设置单元。
- Modify: `MemoryScreen.kt`，后续统一 top bar 和 section 样式。
- Move hard-coded `MemoryPageItem` 文案到 strings resources。

## Component Refactor Map

推荐先拆 UI 结构，再换视觉。这样每一步都能编译通过。

- [ ] `ChatScreen.kt`: route-level state collection 和 pure content 分离。
- [ ] `ChatScreen.kt`: composer 相关代码迁移到 `ChatComposer.kt`。
- [ ] `ChatBubble.kt`: message bubble 和 message actions 分离。
- [ ] `HomeScreen.kt`: 会话列表、搜索、选择工具栏拆成 drawer 可复用组件。
- [ ] `NavigationGraph.kt`: `CHAT_LIST` 指向新的 chat shell / empty chat entry。
- [ ] `SettingScreen.kt`: 设置页按 section 重组。
- [ ] `Theme.kt` / `Color.kt`: 评估是否改为更中性的 light/dark scheme；动态色保持可选。
- [ ] `SettingDataSource` / `SettingRepository`: 增加 last selected model 和 memory enabled 两类偏好。
- [ ] `strings.xml`: 补齐新 UI 文案，并保持 `values` 和 `values-zh-rCN` 对齐。

## Implementation Tasks

### Task 1: Prepare UI architecture without behavior changes

**Files:**

- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatComposer.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatBubble.kt`

- [ ] Split `ChatScreen` into route/state collection and stateless `ChatContent`.
- [ ] Move composer and attachment thumbnail composables to `ChatComposer.kt`.
- [ ] Keep all existing callbacks and `ChatViewModel` method calls unchanged.
- [ ] Add previews for empty composer, attachment preparing, attachment failed, user bubble, assistant bubble.
- [ ] Run: `./gradlew :app:compileDebugKotlin` or Windows `./gradlew.bat :app:compileDebugKotlin`.

### Task 2: Redesign chat room visual structure

**Files:**

- Modify: `ChatScreen.kt`
- Modify: `ChatBubble.kt`
- Modify: `ThinkingBlock.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

- [ ] Replace current top app bar with compact model/title bar.
- [ ] Restyle assistant messages as plain content blocks with subtle action row.
- [ ] Restyle user messages as right-aligned compact bubbles.
- [ ] Keep copy/select/edit/retry/revision controls reachable.
- [ ] Keep `ScrollToBottomButton` and IME auto-scroll behavior.
- [ ] Verify a real chat with normal text, long markdown, code block, reasoning thoughts, image attachment, retry, edit, revision navigation.

### Task 3: Introduce Chat Shell and history drawer

**Files:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatShellScreen.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/home/ChatHistoryDrawer.kt`
- Modify: `HomeScreen.kt`
- Modify: `HomeViewModel.kt`
- Modify: `NavigationGraph.kt`

- [ ] Build a `ModalNavigationDrawer` shell for `CHAT_LIST` and `CHAT_ROOM` surfaces.
- [ ] Move chat history list/search/selection into drawer components.
- [ ] Keep delete and duplicate confirmation flows.
- [ ] Drawer item click navigates with the same `Route.CHAT_ROOM` replacement logic.
- [ ] Bottom drawer actions navigate to Memory and Settings through existing routes.
- [ ] Run compile and manually verify back behavior: close drawer, exit search, exit selection, navigate back from chat/settings.

### Task 4: Replace full-page chat list with empty new-chat screen

**Files:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/EmptyChatScreen.kt`
- Modify: `ChatShellScreen.kt`
- Modify: `NavigationGraph.kt`
- Modify: `HomeViewModel.kt`

- [ ] `Route.CHAT_LIST` shows empty chat surface with bottom composer.
- [ ] If only one platform is enabled, send/new chat uses that platform directly.
- [ ] If multiple platforms are enabled, use primary model selection instead of immediate multi-select dialog.
- [ ] If no platform is enabled, show setup/add provider action.
- [ ] Optional suggestion chips fill the composer text and focus input.

### Task 5: Build top model switcher and advanced compare mode

**Files:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ModelPickerSheet.kt`
- Modify: `HomeViewModel.kt`
- Modify: `ChatScreen.kt`
- Modify: `ChatDialogs.kt` if existing `ChatModelDialog` can share UI primitives.
- Modify: `SettingDataSource.kt`, `SettingDataSourceImpl.kt`, `SettingRepository.kt`, `SettingRepositoryImpl.kt`

- [ ] Replace `SelectPlatformDialog` as the primary new-chat picker with a top-triggered model picker.
- [ ] Persist the last selected model in DataStore, including platform uid and model id/string.
- [ ] New chat defaults to the persisted last selected model.
- [ ] Chat page top bar exposes direct model switching.
- [ ] Switching the chat top model updates current chat model override and the persisted last selected model.
- [ ] Add explicit compare mode for selecting multiple platform uids.
- [ ] In single-model chats, hide per-turn platform chips unless needed for clarity.
- [ ] In compare chats, keep compact platform chips and existing `updateChatPlatformIndex()` behavior.
- [ ] Verify persisted `ChatRoomV2.enabledPlatform` and `ChatPlatformModelV2` still round-trip.

### Task 6: Add memory enable switch and gate memory chain

**Files:**

- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/datastore/SettingDataSource.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/datastore/SettingDataSourceImpl.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepository.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingViewModelV2.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingScreen.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/memory/MemoryScreen.kt`
- Modify: strings resources

- [ ] Add DataStore boolean key `memory_enabled`, defaulting to false.
- [ ] Expose memory enabled state through `SettingRepository`.
- [ ] Show a settings switch under Personalization.
- [ ] Gate memory recall before `prepareMemoryPrompt()` reaches `MemoryRepository`.
- [ ] Gate memory learning before `learnFromSavedChat()` launches.
- [ ] Keep Memory management page accessible while disabled.
- [ ] Add tests or focused verification for disabled memory: no memory prompt and no learning call.

### Task 7: Restyle settings, memory, setup, and app theme

**Files:**

- Modify: `SettingScreen.kt`
- Modify: `PlatformSettingScreen.kt`
- Modify: `MemoryScreen.kt`
- Modify: `StartScreen.kt`
- Modify: `SetupPlatformListScreen.kt`, `SetupPlatformTypeScreen.kt`, `SetupPlatformWizardScreen.kt`
- Modify: `Theme.kt`, `Color.kt`, `Type.kt` if needed.

- [ ] Group settings into Models & Providers, Personalization, Appearance, App.
- [ ] Move hard-coded Memory strings into resources.
- [ ] Align top bars, list rows, dialogs, bottom sheets with the new chat shell style.
- [ ] Keep onboarding/setup usable for first-run users.
- [ ] Check light/dark themes and dynamic theme behavior.

### Task 8: Regression verification and release polish

**Files:**

- Modify as needed from earlier tasks.

- [ ] Run: `./gradlew :app:testDebugUnitTest` or Windows `./gradlew.bat :app:testDebugUnitTest`.
- [ ] Run: `./gradlew :app:compileDebugKotlin`.
- [ ] Run: `./gradlew :app:assembleDebug` for installable debug APK validation.
- [ ] Manual verify first-run setup, migration route, new chat, existing chat, history search, delete, duplicate, settings, memory, about/license.
- [ ] Manual verify send, streaming, attachment preparing, attachment failed, retry, edit user message, edit assistant message, export chat, select text, revision navigation.
- [ ] Manual verify last selected model survives app restart and becomes the default for new chats.
- [ ] Manual verify memory disabled path: no memory prompt is injected, and save-chat does not trigger learning.
- [ ] Manual verify memory enabled path: recall and learning still use existing MemoryRepository behavior.
- [ ] Check Chinese text does not overflow in compact buttons, top bars, drawer rows, model chips, and dialogs.
- [ ] Check no visible controls suggest unsupported features such as voice input or stop generation.

## Non-Goals For The First Pass

- No database schema migration solely for UI.
- No provider/network layer rewrite.
- No exact ChatGPT/Gemini brand copying.
- No voice input, web search, tool calls, or stop generation UI unless backend behavior is implemented.
- No full package rename from `dev.chungjungsoo.gptmobile` in this UI pass.
- No tablet-specific master-detail layout beyond responsive sanity.

## Key Risks And Mitigations

- Risk: UI refactor breaks send/retry/edit/export flows. Mitigation: split route/content first, preserve callbacks, run targeted manual flows after every task.
- Risk: multi-provider behavior becomes confusing. Mitigation: single-model default plus explicit compare mode; keep current uid list storage.
- Risk: drawer navigation and Android back behavior feel wrong. Mitigation: test drawer-open, search mode, selection mode, chat page, settings nested graph separately.
- Risk: attachment pipeline regresses. Mitigation: treat `selectedAttachments`, preparing/failed state, and `AttachmentPayloadCache` as protected behavior.
- Risk: memory switch only hides UI but backend still runs. Mitigation: gate both `prepareMemoryPrompt()` and `learnFromSavedChat()` using the same persisted setting.
- Risk: memory learning silently stops when switch is enabled. Mitigation: do not move `completeChat()`, `prepareMemoryPrompt()`, or save-chat observation without tests/log verification.
- Risk: last selected model points to a deleted/disabled platform. Mitigation: validate persisted `platformUid` against enabled platforms and fall back to first enabled platform.
- Risk: Chinese strings overflow. Mitigation: avoid fixed-width text buttons for long labels, prefer icons/tooltips where appropriate, test common small-width emulator.

## Acceptance Criteria

The redesign is done when all of the following are true:

- [ ] Fresh app launch lands on a usable new-chat surface, not a plain history list.
- [ ] New chat defaults to the last selected model, including after app restart.
- [ ] Chat page top bar provides direct model switching.
- [ ] History is available from a drawer with search, open, delete, and duplicate.
- [ ] A normal user can start a chat by choosing one current model, with no multi-provider complexity in the default path.
- [ ] Advanced users can still compare multiple providers in one chat.
- [ ] Existing chats open correctly with their saved provider uid list and per-chat model overrides.
- [ ] Chat composer supports text and current image attachment behavior.
- [ ] Copy, select text, retry, edit, export, reasoning/thinking display, and revision navigation remain reachable.
- [ ] Memory context preparation and learning still run after saved chats.
- [ ] Memory context preparation and learning only run when the settings memory switch is enabled.
- [ ] Settings still allow add/edit/delete provider, theme switching, memory page, about/license.
- [ ] Light/dark theme, edge-to-edge, keyboard, and navigation bars look intentional.
- [ ] `:app:testDebugUnitTest`, `:app:compileDebugKotlin`, and `:app:assembleDebug` pass.

## Suggested First Implementation Cut

The safest first PR/change set is **Task 1 + part of Task 2** only:

- Split `ChatScreen` into route/content.
- Extract composer.
- Extract message actions.
- Restyle chat bubbles without touching navigation.
- Compile and manually verify one existing chat.

After that lands, proceed to drawer + empty chat shell. This order keeps the most important surface under control before route behavior changes.
