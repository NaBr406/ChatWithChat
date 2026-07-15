# ChatWithChat Tool Calling Developer Guide

This guide describes the provider-neutral tool platform and its safety boundaries. A normal read-only tool should be implemented as a `ToolProvider`, registered in `BuiltInTools.providers()`, and covered by focused tests. It must not add tool-specific execution branches to `ChatRepositoryImpl`.

## Architecture

Core types live in `app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/`:

- `ToolDefinition`: model-facing name, description, and JSON parameter schema.
- `ToolProvider`: owns one definition, settings metadata, effect/security policy, runtime permissions, execution policy, progress label, execution, and source mapping.
- `ToolRegistry`: validates unique names and exposes the immutable runtime catalog. Duplicate names fail during construction.
- `ToolEnablementResolver`: combines provider defaults with explicit enabled/disabled DataStore overrides.
- `ToolArgumentsValidator`: validates parsed arguments before permissions or provider execution.
- `ToolExecutor`: final boundary for active-tool checks, schema validation, Android permissions, approval, timeout, result limits, idempotency context, and audit events.
- `ToolLoopOrchestrator`: applies round/call budgets, requires an explicit active catalog, and reuses a `ToolLoopExecutionSession` so call and result budgets remain cumulative across native rounds.
- Native adapters: OpenAI Responses, OpenAI Chat Completions/OpenRouter, Anthropic, and Google.
- `OpenAICompatibleJsonToolAdapter`: JSON fallback for provider paths without native tool calling.

Generic bindings live in `ToolModule`; web-search repositories and extractors remain in `WebSearchModule`. The settings UI renders `ToolRegistry.catalog`. It does not define executable tools or security policy.

## Catalog And Defaults

Each provider declares `ToolSettingsMetadata` near its definition:

- `userVisible`: whether the tool appears in Settings.
- `category`: stable data-layer category without Compose dependencies.
- `defaultEnabled`: effective state when the user has made no choice.
- `isSensitive`: marks private/sensitive access for presentation and audits.
- `presentationKey` and `iconKey`: stable keys resolved by presentation code with generic fallbacks.

Current built-in defaults are:

| Tool | Default | Additional availability |
|---|---:|---|
| `web_search` | Enabled | Requires Tool Calling Auto, Web Search Auto, and a configured SearXNG URL |
| `fetch_url` | Enabled | Requires Tool Calling Auto, Web Search Auto, and a configured SearXNG URL |
| `current_datetime` | Enabled | Requires Tool Calling Auto |
| `device_location` | Disabled | Requires explicit enablement and Android location permission |

Web search has only `Off` and `Auto` modes. `Auto` exposes search tools to the model-driven tool loop when Tool Calling is also `Auto`; `Off` hides them. Stored legacy `always` values are interpreted as `Auto`, so upgrades retain search availability without retaining the removed mandatory pre-search behavior.

Provider defaults are fail-safe at resolution time. A provider marked sensitive, carrying runtime permissions, classified as a private read, or requiring approval cannot become enabled from `defaultEnabled=true`; it needs an explicit enabled override. This protects new installs even if future provider metadata is misconfigured.

DataStore keeps `enabled_tool_names` and `disabled_tool_names`. Resolution precedence is explicit disabled, explicit enabled, then provider default. Unknown names are retained for forward compatibility but ignored until a matching provider exists.

The earlier disabled-only preference shape could not prove that a non-disabled tool was explicitly enabled. Existing disabled names remain disabled; all other names use the provider default after this change. This deliberately leaves `device_location` off unless it is explicitly enabled.

## Add A Read-Only Tool

Create a provider next to the other built-ins:

```kotlin
class ExampleToolProvider : ToolProvider {
    override val definition = ToolDefinition(
        name = "example_tool",
        description = "Returns one bounded public value.",
        parameters = ToolDefinition.Parameters()
    )

    override val settingsMetadata = ToolSettingsMetadata(
        category = ToolCategory.Utility,
        defaultEnabled = true,
        isSensitive = false,
        presentationKey = "example_tool",
        iconKey = "generic"
    )

    override val securityPolicy = ToolSecurityPolicy.ReadOnlyPublic
    override val policy = ToolPolicy(maxCallsPerRequest = 1)

    override suspend fun execute(call: ToolCall, config: ToolLoopConfig): ToolResult =
        ToolResult(callId = call.id, name = call.name, content = "bounded result")
}
```

Register it in `BuiltInTools.providers()`. The registry catalog, settings row, active native definitions, JSON fallback definition, executor handler, and generic icon/title fallback then work without changes to `ChatRepositoryImpl`.

Do not use reflection, package scanning, or `ToolDefinition.BuiltIns` as a runtime catalog.

Model-originated execution must always pass the resolved active definitions to `ToolLoopOrchestrator`, which passes their names to `ToolExecutor`. Neither layer has an implicit "all registered tools" execution default. This is what keeps an explicitly disabled or default-off private tool unavailable even if a model forges its name.

## Contextual Android Permissions

Tool permissions are provider-declared `ToolPermissionRequirement` values. `ANY_OF` means one listed permission is sufficient; `ALL_OF` means every permission is required.

Permission behavior is deliberately contextual:

- App startup never requests tool runtime permissions.
- Selecting Tool Calling Auto does not request permissions.
- Enabling a permission-requiring tool shows rationale first, then requests only that tool's permissions after an explicit user action.
- The enabled override is persisted only after the requirement is satisfied. Denial leaves the switch off.
- A model-generated tool call never opens the system permission dialog.
- `ToolExecutor` checks current Android permission state on every call, including after permissions are revoked in system Settings.
- Missing permission returns `error_code=tool_permission_denied`; chat progress exposes an explicit Grant action but never resends the user's message automatically.
- `POST_NOTIFICATIONS` uses its own notification permission flow and is not a tool permission.

## Parameter Schema And Validation

Supported schema capabilities are:

- `object`, `array`, `string`, `integer`, `number`, and `boolean`
- nested `properties` and `required`
- array `items`
- string `enum`
- `additionalProperties`
- numeric `minimum` and `maximum`
- string `minLength` and `maxLength`
- optional `format`
- descriptions on roots and nested properties

`ToolExecutor` resolves the active definition and runs `ToolArgumentsValidator` before permission checks. Missing required fields, wrong types, invalid enum values, invalid nested values, disallowed extra properties, and bounds violations return a bounded result with `error_code=tool_arguments_invalid`. Invalid calls never reach a provider.

`ToolRegistry` validates every definition during construction. Tool names must fit the common provider subset; the root must be an object; nested types, array `items`, `required` references, keyword/type combinations, bounds, formats, recursion, depth, and total schema nodes are checked before any provider request is built.

Model-generated arguments default to a 16,000-character limit per call. Native collectors enforce the limit and the per-round call-identity count before retaining streamed events, and native adapters check again before creating `ToolCall` values. SSE input lines have a separate 128 KiB hard limit. JSON fallback bounds the whole protocol response before parsing, parses it once, then applies the same per-call limit. Oversized argument, identity, and protocol payloads terminate with stable error codes instead of retrying an unbounded response as an ordinary chat request. `ToolExecutor` repeats the size check before JSON validation.

Provider-specific business checks remain in the provider. `fetch_url` uses a dedicated no-proxy client with automatic redirects disabled. Every redirect target is revalidated, and the DNS addresses approved by the SSRF policy are the addresses passed to the connection. By default, non-global IPv4/IPv6 targets are rejected, redirects are limited to five, and the complete redirect chain shares one 10-second deadline. Response bodies have independent 512 KiB encoded and decoded byte limits; gzip is decoded manually only after the encoded body is bounded. Unsupported content encodings are rejected.

Schema projection is deterministic. OpenAI enables strict mode only for its closed, all-properties-required subset and drops unsupported formats. Google deliberately omits unsupported `additionalProperties`; Anthropic and JSON fallback preserve the supported canonical schema. JSON fallback includes typed examples and only emits complete definition blocks that fit its prompt budget.

## Effects, Approval, Idempotency, And Audit

Every provider must declare a `ToolSecurityPolicy`:

- `READ_ONLY_PUBLIC`: public web or non-private local reads; no approval required.
- `READ_ONLY_PRIVATE`: private data reads such as device location; no write approval, but enablement and Android permissions still apply.
- `LOCAL_WRITE`, `EXTERNAL_WRITE`, and `IRREVERSIBLE`: always require `REQUIRE_EACH_CALL`.

Constructing a write-capable policy with `NOT_REQUIRED` fails. The legacy registry constructor without provider metadata uses `ToolSecurityPolicy.FailClosed`.

Write approval is carried in a trusted `ToolExecutionContext`, never in `ToolCall.arguments`. `ToolApprovalAuthority` signs an opaque token bound to the tool name, call ID, and canonical sorted-JSON argument hash. Approval for one call cannot authorize another call or modified arguments. Missing, denied, and invalid approval return stable recoverable error codes.

`ToolExecutionContext` also carries a stable idempotency key. `ToolExecutionContextFactory.explicitRetry(...)` preserves that key only when the normalized operation is unchanged. Future write providers should override contextual execution and pass `executionContext.idempotencyKey` to their idempotent local or remote operation.

Future write providers must return a bounded human-readable `approvalArgumentSummary(...)`; raw JSON summaries are rejected. `ToolApprovalCoordinator` creates a `ToolApprovalRequest` whose preview is bound to the real call hash, and it mints an approved or denied execution context only when the decided request still matches that call. `ToolApprovalDialog` renders the generic Compose confirmation boundary. Pausing and resuming a live provider stream is intentionally deferred because no real write-capable tool is registered today.

`ToolAuditSink` is injectable and defaults to no-op. Events cover attempted, approved, denied, invalid, executed, and failed states using hashes only; raw arguments, tokens, full payloads, and secrets are not stored. Oversized rejected arguments use only their original length and a bounded 256-character prefix as audit material, and one computed binding is reused across status events. A mismatched operation is audited without trusting the mismatched context's idempotency key. Audit sink failures are isolated from tool results, while authorization itself remains fail closed.

## Policies And Result Budgets

`ToolPolicy` controls per-tool calls, timeout, and result size. `ToolLoopConfig` retains global round, call, scratchpad, and aggregate result limits.

`ToolResult.content` remains the bounded model-facing fallback. Optional `structuredContent` is valid JSON and `sources` is a typed list. Bounding rules apply to text, metadata, structured JSON, and sources together:

- Text may be safely clipped.
- Structured JSON is kept whole or dropped/rejected; it is never truncated into invalid JSON.
- Sources and metadata are sanitized and deterministically bounded.
- Within a total payload budget, a stable `error_code` and fallback content are allocated first, followed by structured content, safe typed sources, and ordinary metadata.
- The orchestrator carries aggregate call and result budgets across rounds for native and JSON fallback paths. A zero round budget remains zero and falls back without issuing a tool request.

Budget and execution failures are recoverable `ToolResult(isError = true)` values. Unknown or inactive calls are rejected before handler execution.

## Structured Sources

Use `ToolSource.PublicUrl` for public `http`/`https` citations and `ToolSource.LocalApp` for app-owned entities. Local sources carry an `AppSourceNavigationTarget` enum plus a stable entity ID, never a filesystem path or arbitrary URI.

`MessageSourceMetadata` remains backward compatible: old persisted JSON decodes as `PUBLIC_URL`. Before persistence and rendering, sources must pass `safeNavigationTarget()`:

- Public URLs allow only absolute `http` and `https` URLs without credentials.
- Public URL deduplication normalizes only scheme and host; case-sensitive path, query, and fragment text is preserved.
- Local IDs use a bounded safe identifier and an app-owned `CHAT_ROOM` or `MEMORY` target.
- `file:`, `content:`, `javascript:`, raw Windows/Android paths, unsafe credentials, and arbitrary schemes are dropped.

Web search and fetched-page source lists still persist and render. Providers normally return typed `ToolResult.sources`; `ToolProvider.sourceMetadata(...)` maps them into message sources.

## Required Tests

For a public read tool, test:

- provider output and policy;
- catalog/default enablement;
- recursive argument validation;
- native schema serialization and JSON fallback;
- active/disabled filtering and result bounds;
- typed source mapping when applicable.

For a private read tool, also test:

- safe default off;
- contextual permission requirements;
- denial and revoked-permission execution paths;
- no provider execution when permission is missing.

For any future write tool, also test:

- missing/denied/mismatched approval cannot execute;
- the displayed approval request cannot authorize a different call;
- token binding to tool, call ID, and normalized arguments;
- stable idempotency key on explicit retry;
- bounded non-JSON preview;
- redacted audit events and sink-failure isolation.

Useful commands:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.tool.*"
./gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.tool.provider.*"
./gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.repository.ChatRepositoryImplTest"
./gradlew.bat :app:compileDebugKotlin
```

## Current Scope

The four native provider adapters and JSON fallback are supported. MCP, remote dynamic tools, plugin marketplaces, reflection-based discovery, downloaded providers, and arbitrary third-party tool endpoints are not supported. Adding any of those requires a separate trust, authentication, schema, permission, and lifecycle design.
