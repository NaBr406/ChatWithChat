# ChatWithChat Tool Calling Developer Guide

This guide describes the current provider-neutral tool platform. Use it when adding a new read-only tool or changing tool-loop behavior.

## Architecture

Core tool types live in `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/tool/`:

- `ToolDefinition`: model-facing name, description, and JSON parameter schema.
- `ToolProvider`: owns one tool's definition, policy, progress label, execution, and optional source metadata mapping.
- `ToolPolicy`: per-tool limits for calls, timeout, and result size.
- `ToolRegistry`: stores providers and exposes definitions, handlers, policies, labels, and source metadata.
- `ToolExecutor`: executes calls safely through the registry.
- `ToolLoopOrchestrator`: runs the JSON fallback loop, enforces active-tool filtering, budgets, progress, and final prompt construction.

DI is split by ownership:

- `WebSearchModule` provides web-search repositories, page extraction, and search decision services.
- `ToolModule` provides the built-in tool provider list, `ToolRegistry`, `ToolExecutor`, `JsonToolCallParser`, and `ToolLoopOrchestrator`.

`ChatRepositoryImpl` chooses the active tool list for each chat request. It does not need tool-specific execution code for normal read-only tools.

## Add A Tool Provider

Create a `ToolProvider` implementation near the other built-in tools. For example, `CurrentDateTimeToolProvider` provides `current_datetime`.

Minimum provider shape:

```kotlin
class ExampleToolProvider : ToolProvider {
    override val definition: ToolDefinition = ToolDefinition(
        name = "example_tool",
        description = "Short model-facing description.",
        parameters = ToolDefinition.Parameters()
    )

    override val policy: ToolPolicy = ToolPolicy(maxCallsPerRequest = 1)

    override fun progressLabel(call: ToolCall): String = definition.name

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
        ToolResult(callId = call.id, name = call.name, content = "bounded result")
}
```

Register built-in tools in `BuiltInTools.providers()`. Do not edit `ChatRepositoryImpl` for ordinary read-only tools.

## Parameters

Define parameters with `ToolDefinition.Parameters`:

- `properties`: JSON object fields the model may send.
- `required`: required argument names.
- Keep names stable and snake_case.
- Keep descriptions specific and safety-oriented.
- Validate arguments in `execute(...)` using structured JSON helpers such as `ToolCall.stringArgument(...)`.

Tools with no arguments should use `ToolDefinition.Parameters()`.

## Policy And Budgets

Use `ToolPolicy` for per-tool limits:

- `maxCallsPerRequest`: per model response / tool round.
- `maxCallsPerChat`: per tool loop.
- `timeoutSeconds`: execution timeout override.
- `maxResultChars`: result-content clipping override.
- `maxCallsPerRequestErrorKey` and `maxCallsPerChatErrorKey`: optional compatibility keys for existing errors.

Global limits remain in `ToolLoopConfig`, including rounds, calls per round, calls per chat, scratchpad size, and total injected result size.

Budget rejections return recoverable `ToolResult(isError = true)` values. Unknown or inactive tool calls return `tool_unavailable:<name>` before execution.

## Availability

Tool calling is controlled separately from web search:

- `ToolCallingMode.Off`: no tool loop.
- `ToolCallingMode.Auto`: non-search tools may be active.
- `WebSearchMode.Auto` plus a configured SearxNG base URL: `web_search` and `fetch_url` may be active.
- `WebSearchMode.Off`: web tools are hidden, but non-search tools stay available.
- `WebSearchMode.Always`: direct web-search prompt injection remains separate from model-driven tool calling.

Both OpenAI Responses native tools and JSON fallback prompts receive the same filtered active tool list.

## Source Metadata

Use `MessageSourceMetadata` only for citation-worthy sources. Most tools should return no source metadata.

Providers that produce source cards should override:

```kotlin
override fun sourceMetadata(result: ToolResult): List<MessageSourceMetadata> = ...
```

`ChatRepositoryImpl` asks `ToolLoopOrchestrator.sourceMetadata(results)` and then deduplicates sources before emitting `ApiState.SourcesUpdated`.

Do not put raw tool JSON or full outputs into assistant messages. Keep progress compact and transient through `ApiState.ToolStarted`, `ToolFinished`, and `ToolFailed`.

## Required Tests

For a new tool, add focused tests for:

- Provider execution with deterministic inputs.
- Provider policy defaults.
- Registry/executor inclusion through `BuiltInTools` or the relevant provider list.
- JSON fallback tool-loop execution.
- OpenAI Responses tool serialization if the tool should be available to native OpenAI tool calling.
- Availability filtering when relevant.
- Source metadata mapping if the provider returns sources.

Useful commands:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.tool.*"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImplTest"
./gradlew.bat :app:compileDebugKotlin
```

## Safety Checklist

Before adding or enabling a tool:

- Is it read-only? Write-capable tools need a separate product and safety review.
- Is output bounded by `ToolPolicy.maxResultChars` or global result limits?
- Are network targets validated and blocked where needed?
- Are private/local network addresses rejected unless explicitly allowed?
- Are file paths, tokens, and sensitive arguments hidden from UI progress and final assistant text?
- Does the tool fail as `ToolResult(isError = true)` instead of throwing into the chat flow?
- Does availability filtering prevent disabled tools from being advertised or executed?

## Native Provider Roadmap

OpenAI Responses native tools are wired today. Other providers should keep using JSON fallback until their native protocol support is implemented and tested in isolated adapter classes.

Future native work should stay provider-specific:

- OpenAI Chat Completions / OpenRouter tool support.
- Anthropic `tool_use` / `tool_result`.
- Google function calling.
- Tests proving unsupported native protocols still fall back to normal chat safely.
