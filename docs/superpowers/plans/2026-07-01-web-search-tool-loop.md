# ChatWithChat Web Search And Tool Loop Plan

> **For agentic workers:** This is an implementation planning document. Track work with checkbox (`- [ ]`) items. Implement one task at a time, run the listed verification commands after each task, and do not add visible tool/search UI before the matching repository behavior exists.

## Goal

为 ChatWithChat 引入真正可执行的联网搜索与工具循环能力，让 LLM 可以在回答前自行判断是否需要搜索网页、读取网页内容，并在必要时多轮调用工具，直到生成最终答案。

第一阶段先做 provider-neutral 的工具循环，不绑定某一家模型厂商的原生 tool-calling 协议。等通用链路稳定后，再把 OpenAI Responses、Anthropic、Google、OpenRouter/Groq 等 provider 的原生工具调用逐步接进去。

## Non-Goals

- 不把 `searchChatsV2(...)` 改造成联网搜索。它仍然只负责本地聊天记录搜索。
- 不先做假的“联网搜索”按钮或工具状态 UI。必须先有真实数据层行为。
- 不在第一阶段引入 LangChain4j、Koog 或其他完整 agent 框架重写架构。
- 不为了搜索能力重写现有 provider 请求链路、记忆链路、附件链路或聊天保存逻辑。
- 不默认把所有问题都联网搜索。Auto 模式必须能选择不搜索。

## Current Code Anchors

开工前仍需重新 `rg` 一次，因为行号可能随其他改动漂移。

- Chat entrypoint: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt`
- Chat repository contract: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepository.kt`
- Provider dispatch: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt`
- OpenAI Responses request DTO: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/dto/openai/request/ResponsesRequest.kt`
- OpenAI Responses stream events: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/dto/openai/response/ResponsesStreamEvent.kt`
- Provider network clients: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/*API*.kt`
- Settings storage: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/datastore/SettingDataSource.kt`
- Settings repository: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepository.kt`
- Prompt merging helper: `mergePromptSections(...)` in `ChatRepositoryImpl.kt`
- Local chat history search: `searchChatsV2(...)` in `ChatRepositoryImpl.kt`
- Streaming state: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/dto/ApiState.kt`

## Target Architecture

```text
ChatViewModel.completeChat()
  -> ChatRepositoryImpl.completeChat(...)
    -> ToolLoopOrchestrator
      -> ProviderAdapter asks model
        -> final answer
        -> or tool calls
      -> ToolExecutor
        -> web_search
        -> fetch_url
        -> future tools
      -> ProviderAdapter continues with tool results
      -> final ApiState.Success stream
```

Core boundaries:

- `ChatRepositoryImpl` remains the main chat completion entrypoint.
- `ToolLoopOrchestrator` owns loop control, max rounds, error handling, and scratchpad state.
- `ToolExecutor` owns execution of registered tools.
- `WebSearchRepository` owns search API calls.
- `WebPageExtractor` owns webpage fetching and text extraction.
- Provider-specific request rendering lives behind adapters, not directly in the orchestrator.

## Recommended Dependency Direction

Use existing stack first:

- Ktor for HTTP search/fetch requests.
- kotlinx.serialization for JSON DTOs.
- jsoup for HTML parsing and page text extraction.
- DataStore for web-search settings.
- Existing Flow/ApiState streaming path for UI propagation.

Optional later:

- Self-hosted SearXNG as the first open-source search backend.
- Firecrawl or Crawl4AI behind a server endpoint if mobile-side HTML extraction is not enough.
- Koog or LangChain4j only if the app later needs a broader agent framework that justifies the extra abstraction.

---

## Task 1: Add Web Search Foundation

**Goal:** Add a standalone search/fetch foundation without changing chat behavior.

**Files:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/websearch/WebSearchRepository.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/websearch/WebSearchRepositoryImpl.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/websearch/WebSearchModels.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/websearch/SearxngDtos.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/websearch/WebPageExtractor.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/*`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/websearch/`

**Implementation:**

- [x] Add jsoup dependency through version catalog.
- [x] Define `WebSearchResult` with `title`, `url`, `snippet`, `source`, and optional `publishedAt`.
- [x] Define `FetchedWebPage` with `url`, `title`, `text`, `excerpt`, and optional `siteName`.
- [x] Add `WebSearchRepository.search(query: String, limit: Int): Result<List<WebSearchResult>>`.
- [x] Implement SearXNG JSON search using `/search?q=...&format=json`.
- [x] Add search timeout and result-count limit.
- [x] Treat blank query as an empty result, not an exception.
- [x] Implement `WebPageExtractor.fetchAndExtract(url: String): Result<FetchedWebPage>`.
- [x] Use Ktor to fetch HTML and jsoup to extract title/body text.
- [x] Trim normalized page text to a configurable max character limit.
- [x] Add Hilt bindings/providers for repository and extractor.

**Tests:**

- [x] Parse SearXNG JSON into `WebSearchResult`.
- [x] Blank search returns empty list.
- [x] HTTP failure returns `Result.failure` or a typed error result.
- [x] HTML extraction returns title and readable text.
- [x] Oversized page text is clipped.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

---

## Task 2: Add Manual Web Search Injection

**Goal:** Wire search results into chat only when manually enabled. This proves the search foundation works before any LLM decision logic.

**Files:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/websearch/WebSearchMode.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/websearch/WebSearchPromptBuilder.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/datastore/SettingDataSource.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/datastore/SettingDataSourceImpl.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepository.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/websearch/WebSearchPromptBuilderTest.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImplTest.kt`

**Implementation:**

- [x] Add `WebSearchMode` values: `Off`, `Always`.
- [x] Store global web search mode in DataStore, defaulting to `Off`.
- [x] Add repository methods to read/update web search mode.
- [x] Add `WebSearchPromptBuilder` that formats search results as a bounded prompt section.
- [x] Include title, URL, snippet, and source for each result.
- [x] Add a short instruction requiring the model to cite URLs when it uses search results.
- [x] In `ChatRepositoryImpl.completeChat(...)`, when mode is `Always`, search using the latest user message before building provider requests.
- [x] Merge the formatted search prompt via `mergePromptSections(...)`.
- [x] Keep all provider paths working: OpenAI Responses, OpenAI-compatible chat completions, Groq, Anthropic, and Google.
- [x] Do not modify `searchChatsV2(...)`.

**Tests:**

- [x] `Off` mode does not call `WebSearchRepository`.
- [x] `Always` mode calls `WebSearchRepository` with the latest user message.
- [x] Search prompt is merged without dropping system prompt, memory prompt, or context summary.
- [x] Search failures do not break normal chat completion.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

---

## Task 3: Add Auto Search Decision

**Goal:** Let the LLM decide whether web search is needed, while still using simple prompt injection rather than a full tool loop.

**Files:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/websearch/SearchDecisionService.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/websearch/SearchDecisionModels.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/websearch/SearchDecisionPromptBuilder.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/websearch/WebSearchMode.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/websearch/SearchDecisionServiceTest.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImplTest.kt`

**Implementation:**

- [x] Add `WebSearchMode.Auto`.
- [ ] Define strict JSON decision shape:

```json
{
  "shouldSearch": true,
  "queries": ["query 1", "query 2"],
  "reason": "short reason"
}
```

- [x] `SearchDecisionService` asks the current provider whether search is needed.
- [x] Keep the decision prompt short and cheap. Include only latest user message plus minimal conversation context.
- [x] Limit to at most 2 search queries.
- [x] If parsing fails, default to `shouldSearch=false`.
- [x] If decision request fails, continue normal chat without search.
- [x] If `shouldSearch=true`, search all approved queries, merge/dedupe results, and inject them into final answer request.
- [x] Add logging-friendly internal reason text, but do not expose it as final answer content.

**Tests:**

- [x] Time-sensitive/current-events question triggers search.
- [x] Translation/math/casual chat does not trigger search.
- [x] Invalid JSON defaults to no search.
- [x] Too many queries are clipped.
- [x] Search decision failure does not fail chat.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

---

## Task 4: Introduce Provider-Neutral Tool Models

**Goal:** Convert web search from special-case logic into a general internal tool protocol.

**Files:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolDefinition.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolCall.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolResult.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolLoopConfig.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolMessage.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolPromptBuilder.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/tool/`

**Implementation:**

- [x] Define `ToolDefinition` with `name`, `description`, and JSON-schema-like parameters.
- [x] Define `ToolCall` with `id`, `name`, and raw JSON arguments.
- [x] Define `ToolResult` with `callId`, `name`, `content`, `isError`, and optional `metadata`.
- [x] Define `ToolLoopConfig` with `maxToolRounds`, `maxToolCallsPerRound`, `toolTimeoutSeconds`, `maxSearchResults`, and `maxFetchedPageChars`.
- [x] Add built-in tool definitions for:
  - `web_search(query: string)`
  - `fetch_url(url: string)`
- [x] Add prompt builder for JSON-fallback tool calling.
- [x] Ensure all tool protocol prompts are bounded and token-aware enough to avoid unbounded scratchpad growth.

**Tests:**

- [x] Tool definitions serialize to stable prompt text.
- [x] Tool call JSON parses successfully.
- [x] Malformed tool call JSON produces a recoverable error.
- [x] Tool result formatting is deterministic and bounded.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

---

## Task 5: Add Tool Executor

**Goal:** Execute internal tools with safety limits and error results.

**Files:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolExecutor.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolRegistry.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/BuiltInTools.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/*`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolExecutorTest.kt`

**Implementation:**

- [x] Register `web_search` and `fetch_url`.
- [x] `web_search` delegates to `WebSearchRepository`.
- [x] `fetch_url` delegates to `WebPageExtractor`.
- [x] Execute tools on `Dispatchers.IO`.
- [x] Add per-tool timeout.
- [x] Return `ToolResult(isError=true)` on failure instead of throwing through the chat stream.
- [x] Add basic URL validation for `fetch_url`: allow only `http` and `https`.
- [x] Add max result and max character clipping at the tool boundary.

**Tests:**

- [x] `web_search` returns formatted search results.
- [x] `fetch_url` returns title and page excerpt.
- [x] Unknown tool returns error result.
- [x] Tool timeout returns error result.
- [x] Invalid URL returns error result.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

---

## Task 6: Add Single-Round Tool Loop Fallback

**Goal:** Replace Auto search's special-case flow with one generic tool round.

**Files:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolLoopOrchestrator.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolLoopResult.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/JsonToolCallParser.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolLoopOrchestratorTest.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImplTest.kt`

**Implementation:**

- [x] Implement `ToolLoopOrchestrator` with `maxToolRounds=1` for this task.
- [x] Ask the model to either return final answer or a JSON list of tool calls.
- [x] If final answer is returned, stream it normally.
- [x] If tool calls are returned, execute them through `ToolExecutor`.
- [x] Inject `ToolResult` content into a second final-answer request.
- [x] Keep normal provider completion behavior when web search mode is `Off`.
- [x] In `Auto`, use the generic tool loop instead of `SearchDecisionService`.
- [x] Keep `SearchDecisionService` only if still useful for low-cost preflight; otherwise remove it in this task.

**JSON fallback model output shape:**

```json
{
  "type": "tool_calls",
  "tool_calls": [
    {
      "id": "call_1",
      "name": "web_search",
      "arguments": {
        "query": "latest Android target SDK 2026"
      }
    }
  ]
}
```

Final answer shape:

```json
{
  "type": "final_answer",
  "content": "answer text"
}
```

**Tests:**

- [x] Model final answer path does not execute tools.
- [x] Model tool-call path executes `web_search` and produces second request.
- [x] Tool failure is injected into second request as an error result.
- [x] More than max allowed tool calls are clipped.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

---

## Task 7: Expand To Full Multi-Round Tool Loop

**Goal:** Support search -> fetch URL -> final answer and other future multi-step tools.

**Files:**

- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolLoopOrchestrator.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolLoopConfig.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolPromptBuilder.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolLoopOrchestratorTest.kt`

**Implementation:**

- [x] Increase default `maxToolRounds` to 3.
- [x] Maintain a bounded scratchpad of model tool requests and tool results.
- [x] After each tool round, ask the model again.
- [x] Stop when the model returns final answer.
- [x] If max rounds are reached, force a final-answer request using available tool results.
- [x] Prevent duplicate identical tool calls in the same round.
- [x] Preserve streaming final answer output.
- [x] Do not stream intermediate raw JSON tool protocol to the user as assistant text.

**Tests:**

- [x] Round 1 `web_search`, round 2 `fetch_url`, round 3 final answer.
- [x] Infinite tool-call behavior stops at `maxToolRounds`.
- [x] Duplicate tool calls are deduped or clipped.
- [x] Final answer can cite URLs from tool results.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

---

## Task 8: Add Tool Progress States

**Goal:** Make the tool loop observable without building the full UI yet.

**Files:**

- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/dto/ApiState.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/util/ApiStateFlowExtensions.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/ToolLoopOrchestrator.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt`
- Test: existing ApiState and ChatViewModel tests

**Implementation:**

- [x] Add progress states such as:
  - `ApiState.ToolStarted(toolName: String, label: String)`
  - `ApiState.ToolFinished(toolName: String, label: String)`
  - `ApiState.ToolFailed(toolName: String, message: String)`
- [x] Ensure existing `handleStates(...)` remains backward compatible.
- [x] Keep progress states separate from final assistant message text.
- [x] Store enough transient UI state in `ChatViewModel` to show progress later.
- [x] Do not change database schema in this task.

**Tests:**

- [x] Existing success/error/done handling still works.
- [x] Tool progress states do not append to assistant answer content.
- [x] Tool failure state does not stop the whole flow unless final request also fails.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

---

## Task 9: Add Minimal Tool UI

**Goal:** Show real tool activity in the chat timeline/composer area.

**Files:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ToolActivityBlock.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ThinkingBlock.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml` if present

**Implementation:**

- [x] Display "正在搜索" for `web_search`.
- [x] Display "正在读取网页" for `fetch_url`.
- [x] Show failure in a compact non-blocking row.
- [x] Keep UI text grounded in actual `ApiState.Tool*` events.
- [x] Do not expose raw JSON tool calls to users.
- [x] Keep the visual style compact and consistent with existing chat UI.

**Verification:**

```powershell
./gradlew.bat :app:compileDebugKotlin
```

Manual checks:

- [ ] Ask a current-events question in Auto mode.
- [ ] Confirm tool progress appears.
- [ ] Confirm final answer still streams normally.
- [ ] Confirm no tool UI appears when mode is Off.

---

## Task 10: Add Settings UI For Web Search

**Goal:** Let users control web search mode and search backend safely.

**Files:**

- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingScreen.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingViewModelV2.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/datastore/SettingDataSource.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/datastore/SettingDataSourceImpl.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepository.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepositoryImpl.kt`
- Modify: string resources

**Implementation:**

- [x] Add web search mode setting: Off, Auto, Always.
- [x] Default to Off.
- [x] Add SearXNG base URL setting.
- [x] Validate base URL format lightly.
- [x] Explain that public SearXNG instances may disable JSON API.
- [x] Keep this setting under a relevant group such as "Tools" or "Web Search".
- [x] Do not make search silently active after upgrade.

**Verification:**

```powershell
./gradlew.bat :app:compileDebugKotlin
```

Manual checks:

- [ ] Toggle Off/Auto/Always.
- [ ] Restart app and confirm setting persists.
- [ ] Invalid base URL does not crash chat.

---

## Task 11: Add Source Metadata Persistence

**Goal:** Preserve the sources used for an answer so citations remain visible after reopening a chat.

**Files:**

- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/entity/MessageV2.kt`
- Modify: database version and migrations
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatBubble.kt`
- Test: migration tests if this project has Room migration test coverage

**Implementation:**

- [x] Add a source metadata field to assistant messages, preferably a JSON string or typed serializable field compatible with Room.
- [x] Store title, URL, snippet, and source tool name.
- [x] Render a compact sources section under assistant answers.
- [x] Avoid duplicate URLs.
- [x] Preserve old messages through migration.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

Manual checks:

- [ ] Generate a searched answer.
- [ ] Leave and reopen the chat.
- [ ] Confirm sources are still visible.

---

## Task 12: Add OpenAI Responses Native Tool Calling

**Goal:** Use OpenAI's native tool protocol where available while keeping JSON fallback for other providers.

**Files:**

- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/dto/openai/request/ResponsesRequest.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/dto/openai/response/ResponsesStreamEvent.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/provider/OpenAIResponsesToolAdapter.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/network/OpenAIAPIImpl.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/ChatRepositoryImpl.kt`
- Test: OpenAI DTO and adapter tests

**Implementation:**

- [x] Add `tools`, `tool_choice`, and related optional request fields to `ResponsesRequest`.
- [x] Add response stream events needed to detect tool calls.
- [x] Convert internal `ToolDefinition` to OpenAI Responses tool definitions.
- [x] Convert OpenAI tool call output to internal `ToolCall`.
- [x] Convert internal `ToolResult` back into the format expected by Responses API.
- [x] Preserve current reasoning summary handling.
- [x] Preserve current streaming answer handling.
- [x] Keep JSON fallback for non-OpenAI providers.

**Tests:**

- [x] OpenAI request serializes with tools.
- [x] OpenAI stream tool event parses.
- [x] Tool result round-trip is represented correctly.
- [x] Existing reasoning/text streaming tests still pass.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

---

## Task 13: Add Provider Tool Adapters

**Goal:** Move provider-specific tool calling differences out of `ChatRepositoryImpl`.

**Files:**

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/provider/ToolCallingAdapter.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/provider/OpenAICompatibleJsonToolAdapter.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/provider/AnthropicToolAdapter.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/provider/GoogleToolAdapter.kt`
- Modify: provider request DTOs as needed
- Modify: `ChatRepositoryImpl.kt`
- Test: provider adapter tests

**Implementation:**

- [x] Define adapter responsibilities:
  - Render tool definitions.
  - Parse model tool calls.
  - Render tool results.
  - Decide whether native or JSON fallback mode is supported.
- [x] Keep orchestration provider-neutral.
- [x] Implement OpenAI-compatible JSON adapter first.
- [x] Add Anthropic native adapter only after DTO support is verified.
- [x] Add Google native adapter only after DTO support is verified.
- [x] Keep Groq/OpenRouter/Ollama on JSON fallback unless native support is confirmed and tested.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

---

## Task 14: Hardening And Safety

**Goal:** Prevent the tool loop from becoming slow, expensive, or unsafe.

**Files:**

- Modify: tool loop, executor, settings, and prompt-builder files from earlier tasks
- Test: focused unit tests around limits and failures

**Implementation:**

- [x] Add a global per-chat tool budget.
- [x] Add per-request max search queries.
- [x] Add per-request max fetched URLs.
- [x] Add max total tool-result characters injected into model context.
- [x] Add domain blocklist support for `fetch_url`.
- [x] Do not fetch local/private network URLs.
- [x] Strip scripts/styles from extracted pages.
- [x] Normalize whitespace and remove repeated boilerplate where feasible.
- [x] Add clear errors for backend not configured.
- [x] Make all failures recoverable unless the final provider request fails.

**Tests:**

- [x] Private/local URLs are rejected.
- [x] Max rounds enforced.
- [x] Max tool calls enforced.
- [x] Huge tool results clipped.
- [x] Search backend failure still produces a normal answer when possible.

**Verification:**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin
```

---

## Suggested Implementation Order

Use this order when handing work to Codex:

- [x] Task 1: Add Web Search Foundation
- [x] Task 2: Add Manual Web Search Injection
- [x] Task 3: Add Auto Search Decision
- [x] Task 4: Introduce Provider-Neutral Tool Models
- [x] Task 5: Add Tool Executor
- [x] Task 6: Add Single-Round Tool Loop Fallback
- [x] Task 7: Expand To Full Multi-Round Tool Loop
- [x] Task 8: Add Tool Progress States
- [x] Task 9: Add Minimal Tool UI
- [x] Task 10: Add Settings UI For Web Search
- [x] Task 11: Add Source Metadata Persistence
- [x] Task 12: Add OpenAI Responses Native Tool Calling
- [x] Task 13: Add Provider Tool Adapters
- [x] Task 14: Hardening And Safety

Tasks 1-3 are enough to ship a useful "LLM decides whether to search" MVP.

Tasks 4-7 turn that MVP into a real provider-neutral tool loop.

Tasks 8-11 make it visible and durable in the app experience.

Tasks 12-13 add provider-native tool calling without throwing away the fallback path.

Task 14 should be revisited after every provider adapter is added.

## Prompt Template For Each Codex Task

Use this template when asking Codex to execute one task:

```text
Work in E:\code\ChatWithChat.

Read AGENTS.md and docs/superpowers/plans/2026-07-01-web-search-tool-loop.md.

Implement only Task N: <task title>.

Before editing, re-check the current files named in the task and the current ChatRepositoryImpl.completeChat path. Keep the change scoped to this task. Do not implement later tasks early.

After editing, run the verification commands listed under the task. If a command fails, fix the issue or report the exact blocker.

Report:
- changed files
- behavior implemented
- verification result
- recommended next task
```

## Acceptance Criteria For The Full Plan

- [x] Web search can be disabled globally.
- [x] Auto mode lets the model decide when to search.
- [x] Search and fetch tools are real executable tools, not prompt-only decorations.
- [x] Tool failures are recoverable.
- [x] The final answer streams through the existing chat UI.
- [x] Tool progress can be shown without corrupting assistant message content.
- [x] Sources used by an answer can be displayed and eventually persisted.
- [x] Provider-neutral fallback works for all providers.
- [x] OpenAI Responses native tool calling works where supported.
- [x] `searchChatsV2(...)` remains local chat-history search only.
