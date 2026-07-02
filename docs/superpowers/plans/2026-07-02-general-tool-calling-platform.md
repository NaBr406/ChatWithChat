# ChatWithChat General Tool Calling Platform Plan

> For Codex workers: this document starts from the current repo state, where web search, fetch URL, a provider-neutral tool loop, OpenAI Responses native tools, JSON fallback adapters, tool progress UI, and source metadata already exist. Do not reimplement the older web-search/tool-loop roadmap from scratch. First audit the current code, then complete the generalization tasks below one by one.

## Goal

Turn the current web-search-centered tool loop into a general tool calling platform so future tools can be added with a focused tool module, small registry wiring, and targeted tests.

After this plan is complete, adding a normal read-only tool should not require editing `ChatRepositoryImpl` or tool-loop control flow. A new tool should provide:

- a `ToolDefinition`
- a `ToolHandler`
- optional progress-label logic
- optional budget/safety policy
- optional UI/result metadata mapping
- tests

## Current State To Preserve

The repo already has these pieces and they should be preserved unless a task explicitly replaces them:

- Tool models and loop:
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolDefinition.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolCall.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolResult.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolLoopConfig.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolLoopOrchestrator.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolExecutor.kt`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolRegistry.kt`
- Current built-in tools:
  - `web_search`
  - `fetch_url`
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/BuiltInTools.kt`
- Provider adapters:
  - `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/provider/ToolCallingAdapter.kt`
  - `OpenAIResponsesToolAdapter.kt`
  - `OpenAICompatibleJsonToolAdapter.kt`
  - `AnthropicToolAdapter.kt`
  - `GoogleToolAdapter.kt`
- Chat entrypoint and trigger:
  - `ChatRepositoryImpl.completeChat(...)`
  - current tool loop is triggered when `WebSearchMode.Auto`
  - `WebSearchMode.Always` still uses direct web-search prompt injection in provider request building
- Settings:
  - `SettingDataSource`
  - `SettingDataSourceImpl`
  - `SettingRepository`
  - `SettingRepositoryImpl`
  - `SettingScreen`
  - `SettingViewModelV2`
- UI state:
  - `ApiState.ToolStarted`
  - `ApiState.ToolFinished`
  - `ApiState.ToolFailed`
  - `ChatViewModel.ToolProgressState`
  - `ToolActivityBlock`
  - `ChatBubble.SourceMetadataBlock`
- Tests already present:
  - `ToolLoopOrchestratorTest`
  - `ToolExecutorTest`
  - `ToolPromptBuilderTest`
  - `ProviderToolAdapterTest`
  - `OpenAIResponsesToolAdapterTest`
  - `ChatViewModelToolProgressTest`
  - web search and repository tests

## Non-Goals

- Do not remove web search or fetch URL.
- Do not change `searchChatsV2(...)`; it remains local chat-history search.
- Do not add risky write-capable tools in this plan.
- Do not make all providers native-tool-only. JSON fallback must remain available.
- Do not expose raw tool-call JSON in user-visible assistant messages.
- Do not add a new large orchestration framework.

## Task 0: Current-State Audit

**Goal:** Confirm the code still matches this document before editing.

**Commands:**

```powershell
rg -n "WebSearchMode.Auto|ToolLoopOrchestrator|ToolDefinition.BuiltIns|provideToolRegistry|ToolProgressState|SourceMetadataBlock|ToolLoopConfig" app\src\main\kotlin app\src\test\kotlin
git status --short
```

**Checklist:**

- [x] Confirm `ChatRepositoryImpl.completeChat(...)` still uses `WebSearchMode.Auto` as the tool-loop trigger.
- [x] Confirm `BuiltInTools.registry()` still hard-codes `ToolDefinition.BuiltIns`.
- [x] Confirm `ToolBudgetState` still contains web-search/fetch-url-specific budget logic.
- [x] Confirm `ToolActivityBlock` still has special copy for `web_search` and `fetch_url`.
- [x] Confirm source metadata is still mapped mainly from web-search/fetch-url `ToolResult.metadata`.

**Verification:**

No code changes in this task.

## Task 1: Decouple Tool Calling Mode From Web Search Mode

**Goal:** Make "tool calling is allowed" independent from "web search is enabled".

**Why:** The current tool loop is entered only when `WebSearchMode.Auto`. That makes future non-search tools conceptually depend on a setting named web search.

**Files likely involved:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolCallingMode.kt`
- Modify: `SettingDataSource.kt`
- Modify: `SettingDataSourceImpl.kt`
- Modify: `SettingRepository.kt`
- Modify: `SettingRepositoryImpl.kt`
- Modify: `SettingViewModelV2.kt`
- Modify: `SettingScreen.kt`
- Modify: `ChatRepositoryImpl.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Add or update tests in `ChatRepositoryImplTest`

**Implementation:**

- [x] Add `ToolCallingMode` with at least `Off` and `Auto`.
- [x] Store `ToolCallingMode` in DataStore with default `Off`.
- [x] Keep `WebSearchMode` for search availability and search-specific behavior.
- [x] Update `ChatRepositoryImpl.completeChat(...)`:
  - if `ToolCallingMode.Auto`, run the tool loop path
  - if `ToolCallingMode.Off`, do not run the tool loop
  - preserve existing `WebSearchMode.Always` direct search injection behavior unless intentionally migrated in a later task
- [x] Ensure `web_search` is only advertised/executable when web search is enabled or allowed by mode.
- [x] Add Settings UI copy that separates:
  - tool calling
  - web search backend/mode

**Acceptance criteria:**

- [x] `ToolCallingMode.Off` never executes `ToolExecutor`.
- [x] `ToolCallingMode.Auto` can execute tools.
- [x] `WebSearchMode.Off` prevents `web_search` from being available to the model.
- [x] Existing `WebSearchMode.Always` behavior still works or is explicitly migrated with matching tests.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImplTest"
./gradlew.bat :app:compileDebugKotlin
```

## Task 2: Introduce A Tool Provider Interface

**Goal:** Move tool definition, handler, progress label, source mapping, and policy closer to each tool.

**Files likely involved:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolProvider.kt`
- Modify: `ToolRegistry.kt`
- Modify: `ToolExecutor.kt`
- Modify: `ToolLoopOrchestrator.kt`
- Modify: `BuiltInTools.kt`
- Add tests in `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/tool/`

**Suggested API shape:**

```kotlin
interface ToolProvider {
    val definition: ToolDefinition
    val policy: ToolPolicy
        get() = ToolPolicy.default()

    fun progressLabel(call: ToolCall): String = definition.name

    suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult

    fun sourceMetadata(result: ToolResult): List<MessageSourceMetadata> = emptyList()
}
```

Use the exact API that best fits the codebase, but preserve the boundary: a tool should own its own details instead of scattering them across orchestrator, executor, repository, and UI files.

**Implementation:**

- [x] Create `ToolProvider`.
- [x] Make `ToolRegistry` accept a list of providers.
- [x] Keep `handlerFor(...)` compatibility if that reduces churn, but prefer provider lookup.
- [x] Move `web_search` execution into a `WebSearchToolProvider`.
- [x] Move `fetch_url` execution into a `FetchUrlToolProvider`.
- [x] Remove `ToolDefinition.BuiltIns` as the central source of truth, or leave it only as a compatibility helper backed by providers.
- [x] Move `ToolLoopOrchestrator.progressLabel()` hard-coded logic into provider-owned `progressLabel(...)`.

**Acceptance criteria:**

- [x] Adding a test-only tool provider does not require editing `ToolLoopOrchestrator`.
- [x] `web_search` and `fetch_url` still execute through the same public behavior.
- [x] Tool progress labels are identical to current behavior for search and fetch.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.tool.*"
./gradlew.bat :app:compileDebugKotlin
```

## Task 3: Replace Search-Specific Budgets With Tool Policies

**Goal:** Keep global tool safety while allowing each tool to define its own limits.

**Current issue:** `ToolLoopConfig` and `ToolBudgetState` contain fields like `maxSearchQueriesPerRequest` and `maxFetchedUrlsPerChat`. That does not scale to future tools.

**Files likely involved:**

- Create: `ToolPolicy.kt`
- Modify: `ToolLoopConfig.kt`
- Modify: `ToolLoopOrchestrator.kt`
- Modify: `ToolProvider.kt`
- Modify: `WebSearchToolProvider`
- Modify: `FetchUrlToolProvider`
- Update tests in `ToolLoopOrchestratorTest` and `ToolExecutorTest`

**Suggested model:**

```kotlin
data class ToolPolicy(
    val maxCallsPerRequest: Int? = null,
    val maxCallsPerChat: Int? = null,
    val timeoutSeconds: Long? = null,
    val maxResultChars: Int? = null
)
```

Keep global defaults in `ToolLoopConfig`, but allow tool-specific overrides.

**Implementation:**

- [x] Keep global `maxToolRounds`, `maxToolCallsPerRound`, `maxToolCallsPerChat`, and total scratchpad/result limits.
- [x] Move search query limits into `WebSearchToolProvider.policy`.
- [x] Move fetched URL limits into `FetchUrlToolProvider.policy`.
- [x] Make `ToolBudgetState` generic by tool name.
- [x] Preserve existing default limits:
  - search queries per request: 2
  - search queries per chat: 4
  - fetched URLs per request: 2
  - fetched URLs per chat: 4
- [x] Keep current error messages stable if tests rely on them, or update tests deliberately.

**Acceptance criteria:**

- [x] Existing budget tests pass with generic policy implementation.
- [x] A new tool can define `maxCallsPerRequest=1` without adding a new config field.
- [x] Budget rejection remains recoverable as a `ToolResult(isError=true)`.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.tool.ToolLoopOrchestratorTest"
./gradlew.bat :app:compileDebugKotlin
```

## Task 4: Generalize Tool Availability

**Goal:** Let the active tool list depend on settings, provider capability, and safety constraints.

**Files likely involved:**

- Modify: `ToolRegistry.kt`
- Modify: `ToolLoopOrchestrator.kt`
- Modify: `ChatRepositoryImpl.kt`
- Modify: `WebSearchModule.kt` or create a renamed `ToolModule.kt`
- Add tests in `ChatRepositoryImplTest`

**Implementation:**

- [x] Add a way to ask for available tool definitions for the current chat/platform/settings.
- [x] Hide `web_search` and `fetch_url` when web search is disabled or no backend is configured.
- [x] Keep non-search tools available when tool calling is enabled.
- [x] Make OpenAI native `tools` request use the filtered active tool list.
- [x] Make JSON fallback prompt use the same filtered active tool list.
- [x] If the filtered active tool list is empty, skip tool loop and run normal provider completion.

**Acceptance criteria:**

- [x] The model is not prompted with unavailable tools.
- [x] Unknown or disabled tool calls are rejected safely if they somehow appear.
- [x] Tool calling mode can be Auto while web search is Off, leaving room for non-search tools.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImplTest"
./gradlew.bat :app:compileDebugKotlin
```

## Task 5: Generalize Result Metadata And UI Rendering

**Goal:** Keep source cards for web results while adding a generic representation for non-source tool results.

**Files likely involved:**

- Modify: `ApiState.kt`
- Modify: `ChatViewModel.kt`
- Modify: `ToolActivityBlock.kt`
- Modify: `ChatBubble.kt`
- Optional create: `MessageToolTrace.kt`
- Optional Room migration only if persisted traces are required
- Update `ChatViewModelToolProgressTest`

**Implementation:**

- [x] Keep `MessageSourceMetadata` for citation-worthy web sources.
- [x] Add a generic transient `ToolTrace` or enrich `ToolProgressState` for non-source results.
- [x] Keep tool activity default collapsed or compact.
- [x] Do not persist raw tool results unless there is a clear UI requirement.
- [x] Avoid showing sensitive arguments or full tool outputs by default.
- [x] Let each `ToolProvider` optionally map `ToolResult` to source metadata.
- [x] Remove web-search/fetch-url-specific source mapping from `ChatRepositoryImpl` if provider-level mapping is available.

**Acceptance criteria:**

- [x] Search results still show as collapsed source metadata under assistant answers.
- [x] Non-source tools show useful compact progress without raw JSON.
- [x] Assistant message content is not polluted by progress or trace data.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.presentation.ui.chat.ChatViewModelToolProgressTest"
./gradlew.bat :app:compileDebugKotlin
```

## Task 6: Rename DI And Files Away From WebSearch-Only Ownership

**Goal:** Make the architecture understandable for future tool work.

**Files likely involved:**

- Rename or split: `WebSearchModule.kt`
- Create: `ToolModule.kt`
- Keep: web-search-specific providers in a web-search module if desired
- Update imports and Hilt bindings

**Implementation:**

- [x] Move generic tool bindings to `ToolModule`.
- [x] Keep `WebSearchRepository`, `WebPageExtractor`, and search decision bindings in `WebSearchModule`.
- [x] Provide `Set<ToolProvider>` or a list of providers through Hilt.
- [x] Ensure tests and compile still pass.

**Acceptance criteria:**

- [x] Generic tool types are no longer provided from a module named only `WebSearchModule`.
- [x] Search-specific dependencies remain clearly grouped.
- [x] Hilt compiles cleanly.

**Verification:**

```powershell
./gradlew.bat :app:compileDebugKotlin
```

## Task 7: Add A Demo Read-Only Tool As A Regression Test

**Goal:** Prove the extension path works without editing chat orchestration.

**Suggested demo:** `current_datetime` or `app_runtime_context`.

Be careful: if the tool duplicates existing runtime context, mark it internal/test-only or use a tool that provides a harmless deterministic value in tests.

**Files likely involved:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/builtin/CurrentDateTimeToolProvider.kt` or similar
- Add tests:
  - registry includes tool when enabled
  - tool executes
  - JSON fallback can call it
  - OpenAI Responses adapter serializes it

**Implementation:**

- [x] Add the demo provider without modifying `ChatRepositoryImpl`.
- [x] Register through the new provider registry.
- [x] Give it a policy with low limits.
- [x] Add progress label.
- [x] Keep output bounded and non-sensitive.

**Acceptance criteria:**

- [x] New tool can be added with no `ChatRepositoryImpl` changes.
- [x] New tool can be disabled through availability filtering.
- [x] Tool loop tests prove it executes and returns results.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.tool.*"
./gradlew.bat :app:compileDebugKotlin
```

## Task 8: Provider Native Tool Calling Roadmap

**Goal:** Keep improving reliability provider by provider after the generic platform is clean.

This task can be split across future PRs. Do not block Tasks 1-7 on this.

**Current likely state:**

- OpenAI Responses native tools exist.
- OpenAI-compatible, Anthropic, Google, Groq, Ollama, OpenRouter, and Custom mostly rely on JSON fallback unless native support has been added since this document was written.

**Implementation order:**

- [x] Add native OpenAI Chat Completions / OpenRouter tool support if the app's OpenAI-compatible DTO path supports it.
- [x] Add Anthropic native `tool_use` / `tool_result` support with DTO tests.
- [x] Add Google function-calling support with DTO tests.
- [x] Keep JSON fallback as a provider capability fallback.
- [x] Add per-provider tests that prove unsupported native tool protocols do not break normal chat.

**Acceptance criteria:**

- [x] Native adapters are selected only when the provider path supports them.
- [x] JSON fallback remains available.
- [x] Provider-specific tool parsing is isolated in adapter classes.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

## Task 9: Documentation And Developer Checklist

**Goal:** Make the new tool-extension process obvious for future work.

**Files likely involved:**

- Create or update: `docs/superpowers/tool-calling.md`
- Optionally update: `AGENTS.md` if project-level instructions should mention the tool pattern

**Documentation should include:**

- [x] How to add a new tool provider.
- [x] How to define parameters.
- [x] How to set policy and budgets.
- [x] How to expose/hide a tool by setting.
- [x] How to map source metadata.
- [x] Required tests for a new tool.
- [x] Safety checklist for network, file, or write-capable tools.

**Verification:**

```powershell
Get-Content -Encoding UTF8 docs\superpowers\tool-calling.md
```

## Suggested Order For Another Codex Conversation

Use this exact handoff prompt:

```text
Work in E:\code\ChatWithChat.

Read AGENTS.md and docs/superpowers/plans/2026-07-02-general-tool-calling-platform.md.

Implement only Task N from that plan.

Before editing, run the Task 0 audit commands and inspect the files named in Task N. Keep the change scoped to Task N. Do not reimplement the older web-search/tool-loop roadmap and do not implement later tasks early.

After editing, run the verification commands listed under Task N. If a command fails, fix the issue or report the exact blocker.

Report:
- changed files
- behavior implemented
- verification result
- recommended next task
```

## Full Plan Acceptance Criteria

- [x] Tool calling mode is independent from web search mode.
- [x] Web search can be disabled without disabling future non-search tools.
- [x] New read-only tools can be added without editing `ChatRepositoryImpl`.
- [x] Tool definitions, handlers, policies, labels, and metadata mapping live close to the tool implementation.
- [x] Budgeting is generic by tool name and policy.
- [x] OpenAI native tools and JSON fallback use the same active tool list.
- [x] Tool progress UI remains compact and does not expose raw JSON.
- [x] Search source metadata remains collapsed by default and persists where currently supported.
- [x] Existing web search and fetch URL behavior does not regress.
- [x] `./gradlew.bat :app:testDebugUnitTest` and `./gradlew.bat :app:compileDebugKotlin` pass.
