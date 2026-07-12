# ChatWithChat Tool Platform Hardening Prompt

> **For agentic workers:** This is an implementation handoff prompt for hardening the existing tool-calling platform before more tools are added. Work through the tasks in order, verify each completed task, and preserve all unrelated dirty work. This is not a request to redesign chat, add MCP, or implement new end-user tools.

## Goal

在当前已经可运行的通用工具调用平台上，补齐未来扩展最容易出问题的四类框架能力：

- Android 工具权限改为上下文式、按需授权，不在应用启动时集中弹权限。
- 工具设置和可用性由 `ToolRegistry`/`ToolProvider` 目录驱动，消除注册表、`ToolDefinition.BuiltIns`、ViewModel 和 Compose 手写列表之间的漂移。
- 扩展工具参数 Schema，并在执行前做确定性参数校验，而不只是把 Schema 当作模型提示。
- 为未来写入型工具建立强制确认、幂等和审计边界；本轮不添加任何真正的写入型工具。

完成后，新只读工具应当主要通过新增一个 `ToolProvider` 并注册来接入；新权限工具不会导致新安装用户在启动时收到权限弹窗；复杂工具参数能够被所有 provider adapter 正确序列化并在本地校验；未来写入型工具即使错误注册，也不能绕过用户确认直接执行。

## Current Repo State To Respect

开始前必须重新读取代码，不能把 2026-07-02 的旧计划当作当前实现。当前工作区已知状态如下，但仍需现场验证：

- 已有内置工具：`web_search`、`fetch_url`、`current_datetime`、`device_location`。
- `ToolProvider` 当前拥有 `definition`、`policy`、`permissionRequirements`、`progressLabel(...)`、`execute(...)` 和 `sourceMetadata(...)`。
- `BuiltInTools.providers()` 是内置 provider 注册入口。
- `ToolRegistry` 向执行器暴露定义、handler、policy、权限和来源映射。
- `ToolLoopOrchestrator` 已有轮数、调用数、每工具预算、结果长度和不可用工具过滤。
- OpenAI Responses、OpenAI Chat Completions/OpenRouter、Anthropic、Google 已有原生 adapter；其他兼容路径保留 JSON fallback。
- `ChatRepositoryImpl.completeChat(...)` 会为每次请求计算 active tool definitions。
- 设置层正在加入 `disabled_tool_names`、总开关和每工具开关。
- `ToolCallingMode.fromStorageValue(...)` 的当前未提交实现把缺省值改成了 `Auto`。
- `MainActivity` 的当前未提交实现会在应用启动后读取所有已启用工具并集中申请缺失的运行时权限。
- `SettingScreen` 当前为四个工具逐项手写设置行，并直接读取 `ToolDefinition.BuiltIns`。
- 工作区存在大量未提交修改，包含工具设置、聊天启动状态、附件交互和记忆测试。它们属于用户，禁止重置、覆盖、丢弃或擅自整理提交。

关键代码锚点：

- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolProvider.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolDefinition.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolRegistry.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolExecutor.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolLoopOrchestrator.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolPermissionRequirement.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/BuiltInTools.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/provider/`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/main/MainActivity.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingScreen.kt`
- `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingViewModelV2.kt`
- `docs/superpowers/tool-calling.md`

## Product And Safety Defaults

除非用户在执行会话中明确修改范围，否则按以下默认实现：

- 不在应用启动时申请任何工具运行时权限。
- 模型发起工具调用不能直接触发 Android 系统权限弹窗。模型输出不是用户授权动作。
- 用户在设置页主动开启需要权限的工具时，可以立即显示权限说明并由用户点击授权。
- 工具调用时发现缺少权限，应返回结构化 `tool_permission_denied`，同时在 UI 提供明确的“授权”操作；不要自动重试整个用户消息。
- 用户完成授权后，可以由用户显式重试回答；第一版不要求暂停并恢复正在进行的 provider stream。
- 无权限、无敏感数据的只读工具可以默认启用。
- 需要 Android dangerous permission、读取本地私密数据、访问账号数据或产生副作用的工具默认关闭，直到用户显式开启。
- 已有用户保存的显式开关选择必须保留。不要用一次粗暴迁移重新打开用户关闭过的工具。
- `web_search` 和 `fetch_url` 继续受 Web Search 模式及 SearxNG 配置约束；本轮不合并或删除现有 Web Search 设置。
- 普通只读工具仍不得要求修改 `ChatRepositoryImpl` 中的工具专用执行代码。
- 所有工具继续受统一轮数、调用次数、超时、结果长度和总结果预算约束。
- 新增的 Schema 字段必须兼容现有四个内置工具和 JSON fallback。
- 写入型工具必须 fail closed：未获得有效授权时，`ToolExecutor` 必须拒绝执行 handler。

## Non-Goals

- 不添加 `calculator`、聊天记录搜索、记忆搜索、文件读取、联系人、日历、天气或设备状态等具体新工具。
- 不引入 MCP client/server、插件市场、远程动态工具下载或任意第三方工具端点。
- 不实现创建提醒、修改日历、发送消息、修改文件或调用外部写 API。
- 不重写现有 provider 网络客户端或聊天 UI。
- 不删除 JSON fallback。
- 不改变现有附件、记忆、导出、编辑、重试、多 provider、per-chat model override 或 token usage 行为。
- 不把工具权限和 `POST_NOTIFICATIONS` 混在同一个权限系统中。
- 不为动态工具设置引入反射、运行时类扫描或重型插件框架。
- 不因为当前工作区较脏而创建 stash、reset、checkout 或覆盖用户修改。

## Target Architecture

```text
BuiltInTools.providers()
  -> ToolProvider
       definition
       policy
       availability/defaultEnablement
       settingsMetadata
       permissionRequirements
       effect + approvalPolicy
       execute(...)
  -> ToolRegistry
       catalog entries
       effective enablement
       model-facing definitions
       execution handlers

Settings DataStore overrides
  + provider default enablement
  -> ToolEnablementResolver
  -> same active catalog used by:
       Settings UI
       permission UI
       ChatRepositoryImpl
       native adapters
       JSON fallback

Model tool call
  -> active tool check
  -> schema validation
  -> permission check
  -> effect/approval check
  -> budget + timeout
  -> provider.execute(...)
  -> bounded ToolResult
```

重要边界：

- `ToolProvider` 描述单个工具的能力和安全策略。
- `ToolRegistry` 是运行时工具目录的唯一来源。
- `ToolEnablementResolver` 组合 provider 默认值和用户持久化 override，不让 UI 自己重写规则。
- `ToolExecutor` 是权限、参数、授权、超时和结果裁剪的最终执行边界。
- Compose 设置页只渲染 registry/catalog 提供的条目，不决定工具是否真的可执行。
- provider adapter 只做协议序列化与解析，不复制工具启用、安全或参数校验策略。

## Task 0: Read-Only Audit And Baseline

**Goal:** 确认当前工作区真实状态、保护用户修改，并建立可重复的测试基线。

开始时运行：

```powershell
git status --short --branch
git diff -- app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/main/MainActivity.kt app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt docs/superpowers/tool-calling.md
rg -n "ToolProvider|ToolRegistry|BuiltInTools|ToolDefinition.BuiltIns|disabledToolNames|requestedRuntimePermissions|ToolPermissionDeniedException|tool_permission_denied" app\src\main app\src\test docs
rg -n "OpenAIResponsesToolAdapter|OpenAIChatCompletionsToolAdapter|AnthropicNativeToolAdapter|GoogleNativeToolAdapter|OpenAICompatibleJsonToolAdapter" app\src\main app\src\test
```

阅读：

- `AGENTS.md`
- `docs/superpowers/tool-calling.md`
- `docs/superpowers/plans/2026-07-02-general-tool-calling-platform.md`
- 上方列出的关键代码锚点及相关测试。

编辑前先输出：

- 当前四个工具的实际注册和 active-tool 过滤链。
- 当前权限何时申请、何时仅返回错误。
- 当前设置持久化语义，包括缺省模式和新工具默认状态。
- 当前 Schema 在各 adapter 中的序列化差异。
- 当前工作区中与本任务重叠的用户修改。
- 拟修改文件和明确不会触碰的脏文件。

建立基线：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.tool.*" --tests "dev.chungjungsoo.gptmobile.data.tool.provider.*" --tests "dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImplTest"
.\gradlew.bat :app:compileDebugKotlin
```

如果基线失败，先定位失败是否来自现有脏改动。不要为了得到绿色结果删除或回退用户代码。

## Task 1: Replace Launch-Time Permission Requests With Contextual Permission UX

**Goal:** 新安装和普通启动不再弹工具权限；权限只由明确用户操作触发。

可能涉及：

- Modify: `presentation/ui/main/MainActivity.kt`
- Modify: `presentation/common/ToolPermissionRequester.kt`
- Modify: `presentation/ui/setting/SettingScreen.kt`
- Modify: `presentation/ui/setting/SettingViewModelV2.kt`
- Modify: chat state handling only where needed to surface permission-required UI
- Modify: `data/tool/ToolPermissionRequirement.kt`
- Modify: `data/tool/ToolExecutor.kt`
- Add/update focused unit tests
- Update localized strings in both `values` and `values-zh-rCN`

Implementation requirements:

- [ ] Remove launch-time calls that aggregate every enabled tool permission.
- [ ] Keep Activity Result APIs and execution-time permission checks.
- [ ] Enabling a permission-requiring tool in Settings must first show contextual rationale or an explicit user action, then request only that tool's permissions.
- [ ] Denial must leave the tool disabled, or clearly preserve an enabled-but-unavailable state with a visible explanation. Choose one behavior and test it; prefer leaving the switch off until permission is granted.
- [ ] A model tool call with missing permission must not open the system permission dialog automatically.
- [ ] Preserve structured `tool_permission_denied` results so the model can explain why the call failed.
- [ ] Surface a UI action that lets the user grant the relevant tool permission. Do not expose raw permission strings as primary UI copy.
- [ ] Do not automatically resend the user's message after granting permission. Let the user explicitly retry the answer in the first version.
- [ ] Revoked permissions must be detected at execution time even if Settings still shows a prior enabled preference.
- [ ] Notification permission remains completely separate.

Acceptance criteria:

- [ ] Fresh install and normal app launch show no tool permission dialog.
- [ ] Selecting `current_datetime` never requests a runtime permission.
- [ ] Enabling `device_location` can request only coarse/fine location through a user-initiated action.
- [ ] Denying location does not crash, does not execute the provider, and returns `tool_permission_denied` if called.
- [ ] Revoking location in Android Settings is handled on the next call.
- [ ] Existing web tools remain unaffected.

Verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.tool.ToolExecutorTest"
.\gradlew.bat :app:testDebugUnitTest --tests "*ToolPermission*"
.\gradlew.bat :app:compileDebugKotlin
```

If `adb devices` reports a usable emulator/device, also verify manually:

- cold launch does not request location;
- enabling location from Settings requests it contextually;
- denial leaves the app usable;
- granting then retrying allows the tool to run.

## Task 2: Make The Tool Catalog Registry-Driven And Default-Safe

**Goal:** `ToolRegistry` becomes the single runtime catalog used by settings, permissions and chat availability.

Likely design additions:

- `ToolCatalogEntry` or equivalent immutable view of provider metadata.
- `ToolSettingsMetadata` with at least:
  - stable tool name
  - user-visible flag
  - category
  - default enablement
  - whether private/sensitive access is involved
  - optional presentation key/icon key with a safe fallback
- `ToolEnablementResolver` that combines catalog defaults with persisted user overrides.
- Explicit enabled and disabled overrides, or another representation that can distinguish “no user choice” from “user explicitly enabled/disabled”.

Implementation requirements:

- [ ] Settings must iterate the registry/catalog rather than hardcoding four `ToolEnabledItem` calls.
- [ ] `SettingViewModelV2` must not validate names against `ToolDefinition.BuiltIns`.
- [ ] Permission request selection must use the same effective enabled catalog as `ChatRepositoryImpl`.
- [ ] Native adapters and JSON fallback must continue receiving exactly the same filtered active definitions.
- [ ] New tools must have a safe default declared near their provider.
- [ ] `current_datetime` may default enabled.
- [ ] `device_location` must default disabled for users without an explicit prior choice.
- [ ] Network tools may retain current effective behavior, but still require Web Search mode/configuration where applicable.
- [ ] Unknown persisted tool names must be ignored but retained only if doing so helps forward compatibility; document the chosen behavior.
- [ ] Duplicate provider names must fail fast during registry construction instead of silently overwriting one provider in `associateBy`/`associate`.
- [ ] Presentation metadata must not make `data/tool` depend on Compose icons. Use stable icon/category keys and resolve them in presentation code, with a generic fallback.
- [ ] Avoid runtime reflection or package scanning.

Migration/default semantics:

- Preserve existing explicit `disabled_tool_names` choices.
- Add explicit enabled overrides if needed so a sensitive tool can default off without making it impossible for the user to enable it.
- Treat an absent preference as “use provider default”, not “enable every registered future tool”.
- Document how existing installs with the current uncommitted preference shape are interpreted.

Acceptance criteria:

- [ ] Adding a test provider to the registry makes it appear in the catalog without editing `SettingScreen`.
- [ ] A tool with no custom icon/title mapping renders a stable generic settings row.
- [ ] A sensitive test tool defaults off when no preference exists.
- [ ] Explicit enable and disable choices survive reload.
- [ ] Duplicate tool names are rejected deterministically.
- [ ] Disabled tools are absent from native and JSON fallback model-facing definitions.
- [ ] Disabled tools cannot be executed by forging a model call.

Verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*SettingDataSource*"
.\gradlew.bat :app:testDebugUnitTest --tests "*ToolRegistry*"
.\gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImplTest"
.\gradlew.bat :app:compileDebugKotlin
```

## Task 3: Expand JSON Schema And Enforce Argument Validation

**Goal:** 支持未来复杂工具的参数，并在 handler 执行前拒绝不合法参数。

Minimum schema capabilities:

- object, array, string, integer, number, boolean
- nested `properties`
- `required`
- array `items`
- string `enum`
- `additionalProperties`
- numeric minimum/maximum
- string min/max length
- optional `format` where adapters support it
- descriptions at every relevant level

Prefer extending the existing serializable model without breaking the four current tool definitions. If a recursive schema model is introduced, keep construction readable in Kotlin and preserve deterministic serialization.

Implementation requirements:

- [ ] Add a pure Kotlin `ToolArgumentsValidator` or equivalent.
- [ ] Validate parsed arguments after active-tool filtering and before permission-sensitive/provider execution.
- [ ] Reject missing required fields, wrong primitive types, invalid enum values, invalid arrays/nested objects, bounds violations and disallowed extra properties.
- [ ] Return a bounded structured error with stable `error_code=tool_arguments_invalid`; do not include stack traces.
- [ ] Keep provider-specific business validation inside providers where it cannot be expressed by Schema, such as SSRF checks for `fetch_url`.
- [ ] Serialize equivalent definitions for OpenAI Responses, OpenAI Chat Completions, Anthropic and Google within each protocol's supported subset.
- [ ] JSON fallback prompt must contain the expanded schema without exceeding existing prompt/result budgets.
- [ ] Unsupported provider-schema keywords must degrade deliberately and be covered by adapter tests; do not silently generate malformed provider payloads.
- [ ] Existing `web_search`, `fetch_url`, `current_datetime` and `device_location` calls must remain compatible.

Acceptance criteria:

- [ ] Invalid arguments never reach a provider handler.
- [ ] A valid nested-object/array test definition passes local validation.
- [ ] Every native adapter has serialization tests for required, enum, nested object and array items where supported.
- [ ] JSON fallback parser and prompt tests cover the expanded schema.
- [ ] Existing native tool-call parsing tests remain green.

Verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ToolArgumentsValidatorTest"
.\gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.tool.provider.*"
.\gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.tool.ToolPromptBuilderTest"
.\gradlew.bat :app:compileDebugKotlin
```

## Task 4: Add A Fail-Closed Side-Effect And Approval Contract

**Goal:** 为未来写入工具建立执行边界，但本轮只实现框架、测试替身和 UI 通用确认组件，不注册真实写入工具。

Suggested model:

```text
ToolEffect
  READ_ONLY_PUBLIC
  READ_ONLY_PRIVATE
  LOCAL_WRITE
  EXTERNAL_WRITE
  IRREVERSIBLE

ToolApprovalPolicy
  NOT_REQUIRED
  REQUIRE_EACH_CALL
```

Exact names may differ, but the semantics must remain explicit and fail closed.

Implementation requirements:

- [ ] Every provider declares an effect classification; existing tools receive explicit classifications.
- [ ] Public web reads and local clock reads may be no-approval read-only tools.
- [ ] Location must be classified as private read and remain protected by both enablement and Android permission.
- [ ] Any local/external write or irreversible tool must require per-call approval.
- [ ] `ToolExecutor` must reject a write-capable call unless it receives a valid, call-bound authorization token/context.
- [ ] Approval must bind at least tool name, call ID and normalized arguments hash so one approval cannot authorize a different call.
- [ ] Provide a preview model with localized tool name and bounded human-readable argument summary. Never ask the user to approve raw JSON only.
- [ ] Add a generic confirmation UI boundary, but use only fake/test providers in tests; do not add real write actions.
- [ ] Denial returns a stable recoverable result such as `tool_approval_denied`.
- [ ] Add an injectable audit sink with a no-op default. Record attempted/approved/denied/executed/failed state without storing secrets or full sensitive payloads.
- [ ] Audit sink failure must not turn a successful read-only tool into a failed chat, but authorization enforcement itself must remain fail closed.
- [ ] Define idempotency support for future write providers. At minimum, carry a stable idempotency key in execution context and test that a retry uses the same key.
- [ ] Do not persist a new Room audit table unless the audit requirements cannot be met cleanly with an existing local logging boundary. If persistence is added, include schema export and migration tests.

Acceptance criteria:

- [ ] A fake write provider cannot execute without approval even when active and arguments are valid.
- [ ] Approval for call A cannot authorize call B or modified arguments.
- [ ] User denial prevents handler execution and returns a recoverable result.
- [ ] Existing read-only tools execute without new confirmation prompts.
- [ ] The model cannot manufacture a valid approval token through tool-call arguments.
- [ ] Idempotency context is stable across an explicitly retried approved call.

Verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ToolApproval*"
.\gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.tool.ToolExecutorTest"
.\gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.tool.ToolLoopOrchestratorTest"
.\gradlew.bat :app:compileDebugKotlin
```

## Task 5: Structured Results And Local Source Readiness

**Goal:** 让未来本地聊天/记忆/文件检索工具能返回结构化结果和可追踪来源，而不把所有信息塞进扁平字符串 map。

Implementation requirements:

- [ ] Preserve `ToolResult.content` as the bounded model-facing fallback text.
- [ ] Add optional typed/JSON structured content for UI, adapters and tests.
- [ ] Replace or wrap ad hoc `Map<String, String>` conventions with typed source/result metadata where practical.
- [ ] Support public URL sources and local app sources without treating arbitrary external URI schemes as trusted links.
- [ ] Local source metadata should be able to carry stable entity identifiers and an app-owned navigation target, not a raw filesystem path.
- [ ] Existing persisted `MessageSourceMetadata` must remain backward compatible. If its serialized shape changes, use defaults and add converter/round-trip tests.
- [ ] Existing web source chips/list behavior must not regress.
- [ ] Result clipping must apply to both text and structured content; a structured payload cannot bypass total result budgets.

Acceptance criteria:

- [ ] Existing web search and fetched-page sources still persist and render.
- [ ] A fake local-search result can expose an app-owned source ID/deep link without a public URL.
- [ ] Unsafe external schemes and raw file paths are not rendered as clickable sources.
- [ ] Oversized structured results are rejected or deterministically clipped before reaching the model/UI.

Verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ToolResult*"
.\gradlew.bat :app:testDebugUnitTest --tests "*MessageV2*"
.\gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImplTest"
.\gradlew.bat :app:compileDebugKotlin
```

## Task 6: Documentation, Regression Tests, And Final Audit

**Goal:** 固化扩展流程并确认没有破坏聊天主链路。

Update `docs/superpowers/tool-calling.md` with:

- [ ] Registry-driven catalog and default enablement semantics.
- [ ] How to add a normal read-only tool without editing `ChatRepositoryImpl`.
- [ ] How permission tools are enabled and how missing permission is surfaced.
- [ ] Supported Schema subset and local validation behavior.
- [ ] Effect classification and approval requirements.
- [ ] Structured result/source conventions.
- [ ] Required tests for public read, private read and future write tools.
- [ ] Explicit statement that MCP and remote dynamic tools are not yet supported.

Run final verification serially:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

Also run:

```powershell
git diff --check
git status --short
```

If a local ktlint CLI is available, run it only against changed Kotlin files. Do not apply repository-wide formatting to the dirty worktree.

Final report must include:

- Changed files grouped by task.
- Current effective defaults for all four built-in tools.
- Exact permission UX after the change.
- Schema keywords implemented and provider-specific limitations.
- Approval/idempotency guarantees implemented for future write tools.
- Targeted and full verification results.
- Any tests or device checks that could not run.
- Remaining risks before introducing `calculator`, local search, private-data tools or MCP.

## Full Acceptance Criteria

- [ ] App startup never requests tool runtime permission.
- [ ] Permission prompts require an explicit user action.
- [ ] `ToolExecutor` remains the final enforcement point for active tool, schema, permission, approval, timeout and result limits.
- [ ] Tool settings are rendered from the registry/catalog rather than a hardcoded built-in list.
- [ ] Sensitive future tools default off unless explicitly enabled.
- [ ] Duplicate tool names fail fast.
- [ ] Existing user enable/disable choices survive migration.
- [ ] Complex Schema is serialized consistently and invalid arguments never reach handlers.
- [ ] Future write tools cannot execute without call-bound user approval.
- [ ] Existing four tools continue to work through native adapters and JSON fallback.
- [ ] Existing web source metadata and chat behavior do not regress.
- [ ] No concrete new tool, MCP integration or write action is added in this change.
- [ ] Unit tests, Kotlin compilation and debug assembly pass.

## Copy-Paste Handoff

```text
Work in E:\code\ChatWithChat.

Read AGENTS.md and docs/superpowers/plans/2026-07-12-tool-platform-hardening-prompt.md completely.

Implement the plan end to end, one numbered task at a time. Start with Task 0 and report the audit before editing. Preserve the current dirty worktree and work with overlapping user changes; do not stash, reset, checkout, revert or overwrite them.

After each task, run that task's focused verification before continuing. Do not add any concrete new end-user tool, MCP integration or write action. Do not bypass ToolProvider/ToolRegistry or add tool-specific execution branches to ChatRepositoryImpl.

At the end, run the full verification in Task 6 and report changed files, behavior, tests, device-validation gaps and remaining risks. Do not create commits unless I explicitly ask.
```
