# ChatWithChat Tool Calling Developer Guide

This guide describes the provider-neutral tool platform and its safety boundaries. A normal read-only tool should be implemented as a `ToolProvider`, registered in `BuiltInTools.providers()`, and covered by focused tests. It must not add tool-specific execution branches to `ChatRepositoryImpl`.

## Architecture

Core types live in `app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/`:

- `ToolDefinition`: model-facing name, description, and JSON parameter schema.
- `ToolProvider`: owns one definition, settings metadata, effect/security policy, runtime permissions, execution policy, progress label, execution, and source mapping.
- `ToolRegistry`: validates unique names and exposes the runtime catalog as an immutable, fully validated
  snapshot. Duplicate or invalid built-in names fail during construction; `installDynamicProviders`
  swaps the MCP half of the catalog all-or-nothing and returns a `Result` instead of throwing.
- `ToolEnablementResolver`: combines provider defaults with explicit enabled/disabled DataStore overrides.
- `ToolArgumentsValidator`: validates parsed arguments before permissions or provider execution.
- `ToolExecutor`: final boundary for active-tool checks, schema validation, Android permissions, approval, timeout, result limits, idempotency context, and audit events.
- `ToolLoopOrchestrator`: applies round/call budgets, requires an explicit active catalog, and reuses a `ToolLoopExecutionSession` so call and result budgets remain cumulative across native rounds.
- Native adapters: OpenAI Responses, OpenAI Chat Completions/OpenRouter, Anthropic, and Google.
- `OpenAICompatibleJsonToolAdapter`: JSON fallback for provider paths without native tool calling.

Generic bindings live in `ToolModule`; web-search repositories and extractors remain in `WebSearchModule`. The settings UI renders `ToolRegistry.catalog`. It does not define executable tools or security policy.

MCP bridging lives in `data/mcp/` and is bound in `McpModule`; see [MCP Servers](#mcp-servers).

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

Future write providers must return a bounded human-readable `approvalArgumentSummary(...)`; raw JSON summaries are rejected. `ToolApprovalCoordinator` creates a `ToolApprovalRequest` whose preview is bound to the real call hash, and it mints an approved or denied execution context only when the decided request still matches that call. `ToolApprovalDialog` renders the generic Compose confirmation boundary.

`add_schedule` and `set_alarm` are registered write tools that authorize through `REQUIRE_SYSTEM_PERMISSION`. MCP tools are the registered users of `REQUIRE_EACH_CALL`, and the live path is wired end to end: `ToolLoopOrchestrator` sees `tool_approval_required`, suspends on `ToolApprovalBroker.awaitApproval`, and `ChatViewModel.pendingToolApproval` drives `ToolApprovalDialog` in `ChatScreen`. Approving re-executes the same call with the minted context; denying returns a recoverable result.

### Conversation-scoped grants

Confirming every call is unusable for a multi-step task: a six-step order would raise a dialog per call. `ToolApprovalSessionGrants` lets the user answer "allow for this conversation" once, and `ToolApprovalBroker` then resolves matching calls without prompting.

What a grant does **not** do is relax the token binding. `approveFromSessionGrant` still mints a fresh `ToolExecutionContext` for that call, still bound to its tool name and canonical arguments, so a grant cannot be replayed onto another operation. The grant decides whether to *ask*; it is not itself a credential.

Four properties keep the blast radius small:

- **Scope is per tool, not per server.** `ToolProvider.sessionApprovalScope` is `null` by default, so no tool gets grants without opting in, and `McpToolProvider` returns `mcp:<serverId>:<toolName>`. Approving a menu lookup therefore never authorizes placing an order.
- **Consent is explicit.** The dialog offers a third action rather than treating the first approval as a session-wide grant, so widening consent is a choice the user makes.
- **Scope is per conversation.** Grants are keyed by chat id, threaded through `ToolExecutionSessionState.chatId`. A chat id of zero — an unsaved chat — disables grants rather than sharing one bucket across conversations.
- **Grants are in-memory and revocable.** They die with the process, and switching off a server or one of its tools calls `revokeByPrefix`, so turning it back on does not silently resume skipping confirmations.

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

## Round Scoping, Final Answers, And Usage

`ToolRoundStateMachine` keeps the model-visible schemas and the execution allowlist aligned for every JSON fallback and native tool round. For the canonical sticker-only happy path, scope and reasoning move as follows:

| Phase | Model-visible tools | Reasoning policy |
|---|---|---|
| Initial request | `search_stickers`, `send_sticker` | User-selected mode |
| Successful search with candidates | `send_sticker` only | `LOW` |
| Successful or failed send | None; final answer only | User-selected mode |

This is the canonical `2 -> 1 -> 0` sticker scope. An initial scope that also contains unrelated enabled tools can start above two, but after a successful sticker search all unrelated schemas disappear and only `send_sticker` remains. A failed search also enters the final-only phase; a successful empty search may retry only `search_stickers` within its call budget. The canonical reasoning transition is therefore `USER -> LOW -> USER`.

In the JSON fallback path, `final_answer.content` returned during the final-only phase is the actual user-visible answer. `ToolLoopOrchestrator` returns it as `CompletedWithToolResults`; it is not a draft followed by another provider request. Native provider paths issue a distinct final request with no tools: OpenAI Responses, Chat Completions, and Anthropic set both tools and tool choice to `null`, while Google sets tools and tool config to `null`.

Native serialization of a successful `search_stickers` result uses a bounded projection of at most six candidates. Each projected candidate contains only bounded `sticker_id`, `title`, `alt_text`, and `tags` fields; metadata, sources, paths, URIs, hashes, and asset bytes are not sent back to the provider. The canonical `ToolResult` remains intact for validation and presentation. Only the provider-facing continuation payload is compacted.

Search-decision provider requests are recorded with `PromptTraceStage.SEARCH_DECISION`. Their provider usage is retained even when `shouldSearch=false`, and is included in aggregate tool token totals and usage details.

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

For a large MCP server, also test against captured real schemas rather than hand-written ones:

- every tool translates, or is rejected with a reason the settings screen can explain;
- the whole set installs into a real `ToolRegistry` and fits the advertisement budget;
- a truncated resident set keeps the tools the request actually needs, and `discover_tools` can reach the rest — and can return more than one of them;
- a CJK query scores against a CJK description, an unrelated CJK query still scores zero, and "nothing matched" is not reported as a budget failure.

`app/src/test/resources/mcp/mcdonalds_tools.txt` holds 29 tools captured verbatim from a production ordering server (`McpLargeServerScopeTest`). Hand-written fixtures did not surface any of the four defects this one did: an alphabetical overflow cut, single-slot discovery, zero-scoring CJK, and a 4,000-character result cap against a 29,418-character menu. Prefer captured schemas for anything shaped like a real API.

Useful commands:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.tool.*"
./gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.tool.provider.*"
./gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.repository.ChatRepositoryImplTest"
./gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.mcp.*"
./gradlew.bat :app:compileDebugKotlin
```

MCP additionally needs on-device runs, because the SDK is pre-1.0 and Room migrations are involved:

```powershell
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.nabr.chatwithchat.data.mcp.McpSdkBuildCanaryInstrumentedTest
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.nabr.chatwithchat.data.database.ChatDatabaseV2MigrationInstrumentedTest
./gradlew.bat :app:assembleRelease
```

Neither unit nor instrumented tests substitute for one real transaction. The scope, budget, CJK, and truncation defects above all passed the full unit suite; every one of them was found by driving a live server on a device and reading what the model actually did with the results. When adding support for a new class of server, place one real order — or whatever that server's terminal write is — before calling it supported.

## MCP Servers

Remote MCP servers are supported for tools only. Everything below is the trust, authentication, schema, permission, and lifecycle design that this required; nothing here relaxes a boundary described earlier in this document.

Code lives in `app/src/main/kotlin/cn/nabr/chatwithchat/data/mcp/`. The client is `io.modelcontextprotocol:kotlin-sdk-client`, pinned to 0.11.1 because later releases pull `kotlinx-serialization-json` 1.11.0 and, from 0.15.0, a `kotlin-stdlib` newer than this project's compiler. `McpSdkBuildCanaryInstrumentedTest` is the gate for any bump: the SDK is pre-1.0 and does not claim Android support, so it must be proven to load and complete a handshake under ART.

### Trust

Server-declared `ToolAnnotations` — `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint` — are recorded and displayed, and never consulted for authorization. Every bridged tool gets a fixed `ToolSecurityPolicy(EXTERNAL_WRITE, REQUIRE_EACH_CALL)` regardless of what the server claims. Two existing rules then apply without special-casing MCP:

- `ToolEnablementResolver.isSafeForDefaultEnablement` refuses to honour `defaultEnabled` for a write-capable tool, so an MCP tool is off until explicitly enabled.
- `ToolExecutor` routes every call through `ToolApprovalBroker`, so the user confirms each invocation.

A server-level `enabled=false` keeps that server's tools out of the registry entirely, so a switched-off server stays unreachable even if a model forges one of its tool names. Deriving a weaker policy from `readOnlyHint` is the specific escalation this boundary exists to prevent; `McpToolProviderTest` asserts that the most flattering hint set still yields per-call approval.

### Authentication

Bearer token plus custom headers, entered by the user and injected through the transport's request-builder lambda. A custom header cannot overwrite `Authorization`. Credentials are stored as plain text in `mcp_servers.auth_token`, matching how provider API keys already live in `platform_v2.token`. OAuth 2.1 (metadata discovery, dynamic client registration, PKCE) is not implemented, so OAuth-only hosted servers cannot be added.

### Schema

`McpSchemaTranslator` converts a server's `inputSchema` into the subset `ToolDefinition.Parameters` can express, or refuses the tool with a stable reason code the MCP settings screen explains. The accept/reject line is whether a keyword changes the *shape* of a valid argument object:

- Structural keywords (`$ref`, `oneOf`/`anyOf`/`allOf`/`not`, `if`/`then`/`else`, tuple `items`, real union types, `const`) reject the whole tool, including when the offending property is optional. Silently dropping an optional property would hide a capability, and on a write-capable remote tool could drop something load-bearing like a `dry_run` flag.
- Narrowing keywords (`pattern`, `exclusiveMinimum`, `multipleOf`, `minItems`, non-string `enum`, …) are dropped and the property kept. `ToolArgumentsValidator` is a pre-filter that keeps malformed calls off the wire, not the authority on validity; the server still enforces its own schema.

`enum` belongs to the second group, which is not obvious. `Parameter.enumValues` is `List<String>`, so `enum: [1, 2, 3]` cannot be carried — but dropping it leaves `type: integer`, the same shape, merely accepting more values than the server will. Treating it as structural rejected almost every generated API schema, which is why integer enums are now narrowed away instead. Two related details: a mixed `enum` on a declared `string` type keeps only its string members, which is exact rather than lossy because a non-string member could never satisfy the type check anyway; and an `enum` with no declared `type` infers one from its members, since assuming `string` would mislabel every numeric enum.

Concretely: a production ordering server had 7 of 29 tools rejected as `schema_non_string_enum`, and the 7 included `calculate-price`, `create-order`, and `query-meals` — the entire transactional path, lost to a `beType: {enum: [1,2,5,6]}` that changed nothing about the argument shape. All 29 translate now. `NON_STRING_ENUM` is retained but no longer produced, so rows cached by an older version still resolve to a label.

`type: ["string","null"]` collapses to the concrete member, and a missing `type` is inferred from `properties`/`items`/`enum`. Both are stricter than the server, never looser. Depth and node ceilings mirror `ToolDefinitionValidator`, so an accepted translation always survives registry validation — `McpSchemaTranslatorTest` pins that by installing results into a real `ToolRegistry`.

The SDK's typed `ToolSchema` drops root-level `additionalProperties`, so the root is fixed to `false`; that also blocks a model from sending arguments the server never advertised.

### Naming

`McpToolNamespace` maps a server tool to `mcp_<alias>_<tool>` within the 64-character `ToolDefinitionValidator` limit. Aliases are unique per server and assigned once at creation, because renaming a server would rename its tools and orphan every enablement override and approval binding keyed by the namespaced name. Truncated names carry a short digest of the `(serverId, toolName)` pair. Two tools that still collapse to one name are recorded as a per-tool `tool_name_collision` verdict rather than failing the catalog install. Resolution back to the origin goes through the registered provider, never by parsing the name.

### Lifecycle

`ToolRegistry` now holds an immutable, fully validated snapshot behind a volatile reference. `installDynamicProviders` validates a whole batch and swaps one reference; a batch is all-or-nothing, cannot shadow a built-in name, and a rejected batch leaves the previous snapshot untouched. Any snapshot a reader observes is still immutable — what changed is that the registry's identity no longer implies its contents. The public API is unchanged, so all existing construction sites and `ToolExecutor` wiring are untouched.

`McpConnectionManager` keeps one session per server, serialized by a per-server mutex, with idle eviction and a connect timeout. `StreamableHttpClientTransport` is tried first, falling back to `SseClientTransport` for servers that only offer the older transport. Editing a server's URL, transport, or credentials invalidates its live session.

Bridged tools are advertised as `ToolExposure.Resident`, and the advertisement budget is sized to fit a whole server (48 tools / 48,000 schema characters). A server is only useful as a set — the model cannot price a basket while `calculate-price` sits behind `discover_tools`. The numbers come from measurement, not taste: a production ordering server with 29 tools costs 23,544 characters of prompt text, and at the previous ceiling of 20 tools it was the *count* that truncated, not the characters. `maxInitialOnDemandTools` deliberately stays at 1: it governs only built-in on-demand tools, which MCP no longer competes with.

Overflow degrades along two axes that were both wrong before, and both were found by running a real 29-tool server rather than by reading the code:

- **The cut is ranked, not alphabetical.** Resident tools sort by priority, then by intent match against the request, then by name. Sorting by name alone dropped `query-meals` and `query-nearby-stores` purely because `q` sorts late, leaving a model that could price a basket it had no way to assemble.
- **Discovery gets room to answer.** `discoveryHeadroom` reserves up to `maxDiscoveryResults` slots (capped at a quarter of the budget) instead of the single slot that only paid for the `discover_tools` entry itself. With one slot, residents filled everything else and a discovery call could surface at most one tool however many matched — so an overflowing server that needed four tools together could never assemble them. An earlier version of this document claimed truncation "still degrades safely" because a `discover_tools` slot was kept; that was false, and the fixture test now pins the corrected behaviour.

Intent matching works for CJK. `ToolScopePlanner.discoveryWords` splits Latin script on word boundaries and additionally emits adjacent character bigrams for each CJK run — the standard segmenter-free approach. Without it a Chinese phrase stayed one long token, so *every* Chinese query scored zero against a Chinese description while the English `nearby stores` scored 106. Bigrams are emitted term-side only, so the existing substring test needs no query-side expansion, and an exact phrase still outranks an incidental overlap.

`discover_tools` also distinguishes "nothing matched" from "budget exhausted". It previously reported `tool_scope_budget_exceeded` whenever a query matched nothing but other tools existed, which sent the model retrying a query that was never the problem instead of rewording it.

Loop budgets are sized for a chain rather than a lookup. Completing a transaction — find a store, list a menu, price a basket, place an order — spends one round per step because each call needs the previous result, so `maxToolRounds` is 10 and `maxToolCallsPerChat` is 30. These are ceilings: a chat that needs one call still costs one call.

Result caps are sized against a measured response, not guessed. `query-meals` on a real ordering server returns 29,418 characters and takes no narrowing parameter — no category, no keyword, no paging — so the menu arrives whole or not at all. At the previous 4,000-character MCP cap the model received 14% of a menu, cut mid-JSON, and then could not name a single product code; the server looked connected but was unusable. `MCP_MAX_RESULT_CHARS` and `maxToolResultChars` are now 32,000, with `maxTotalToolResultChars` at 120,000 for one catalog-sized result plus the smaller calls around it.

Clipping says so, in band. `ToolResultBounds` appends an explicit truncation notice inside the character limit rather than cutting silently, because a silent clip is indistinguishable from a short answer and a clipped JSON payload merely looks malformed. A model that cannot tell the difference retries the same call hoping for a different result, which a deterministic size limit will never give it — that retry loop was observed on device before the notice existed.

`McpToolSync` replaces a server's cached `tools/list` wholesale. A failed sync leaves the previous catalog in place, because a transient network drop should not strip tools the user already enabled. Truncation at the page/tool ceiling is recorded as `mcp_tool_list_truncated` rather than reading as a complete list. The cached raw schema lets `McpToolRegistrarImpl` rebuild the registry at cold start with no network, and lets a future app version with a wider subset re-translate without reconnecting.

Results carry text and `structuredContent`. Images, audio, and embedded resources are counted and announced in the text rather than dropped in silence, because `ToolResult` has nowhere to put them. `McpServerUrlPolicy` reuses `WebFetchUrlValidator` so both paths share one definition of "publicly routable"; reaching a LAN server is a per-server opt-in.

## Current Scope

The four native provider adapters, JSON fallback, and remote MCP servers over Streamable HTTP or SSE are supported.

Not supported: MCP resources and prompts, local stdio MCP servers, OAuth-authenticated MCP servers, non-text MCP result content, plugin marketplaces, reflection-based discovery, and downloaded providers. Adding MCP resources or prompts needs its own injection points and UI. Adding stdio needs a process model Android does not give third-party servers. Adding OAuth is a separate authentication project.
