# ChatWithChat LLM Sticker Library Implementation Prompt

> **For implementation agents:** This is an execution handoff for adding an LLM-driven sticker library to ChatWithChat. Work through the tasks in order, re-audit the live repository before editing, preserve unrelated changes, and verify every completed task. This is not a request to redesign chat attachments, add a general media platform, let users send stickers manually, or add a remote sticker service.

## Goal

Add a local sticker library that lets the LLM automatically send a relevant image sticker as part of an assistant reply.

The first release must:

- ship three user-provided static built-in stickers;
- let users add, edit, enable, disable, and remove their own static stickers from Settings;
- let the model discover a bounded list of candidates by stable ID, then select one by ID;
- persist and render selected stickers as typed assistant-message data, including retry/revision history;
- keep sticker binaries local: provider requests and future conversation turns receive only a compact semantic description;
- keep the storage and renderer contracts extensible for future animated raster or Lottie resources without implementing those renderers now.

The completed product must not add a chat-composer sticker picker. Users manage their own assets; only the LLM sends stickers.

## Current Repo State To Re-Audit

The following is a planning snapshot, not a substitute for reading the live code. Before making any edit, reread `AGENTS.md`, run `git status --short --branch`, inspect overlapping diffs, and confirm every listed anchor against the active checkout.

Current observed anchors:

- Package/namespace: `cn.nabr.chatwithchat`.
- `ChatDatabaseV2` is currently Room schema version 17 with exported schemas and migrations registered in `DatabaseModule`.
- `MessageV2` currently stores `content`, `thoughts`, ordinary `attachments`, assistant revisions, source metadata, and token usage. `AssistantRevision` currently does not carry media references.
- `ChatRepositoryImpl` transforms every ordinary `ChatAttachment` into provider image input on historical replay. A sticker must therefore not be represented as a normal attachment.
- `ApiState` and `ApiStateFlowExtensions.handleStates(...)` already carry text, thinking, source, usage, and tool-progress updates into the active assistant slot.
- `ToolProvider` / `ToolRegistry` / `ToolExecutor` / `ToolLoopOrchestrator` are the shared tool path. Native adapters exist for OpenAI Responses, OpenAI Chat Completions/OpenRouter, Anthropic, and Google; other compatible providers use the JSON fallback.
- `ToolLoopConfig.Default.maxToolRounds` is currently 3, which is sufficient for `search_stickers -> send_sticker -> final answer`.
- `ToolCallingMode` and per-tool enablement already exist. Do not turn on unrelated web, location, calendar, or alarm tools merely to enable stickers.
- `ChatComposer` already uses `PickMultipleVisualMedia`, but that import path is for provider-bound chat attachments and must remain separate from sticker import.
- Settings navigation currently lives in `presentation/common/NavigationGraph.kt` and `Route.kt`; `SettingScreen` is the correct entry surface for a sticker-library management page.
- `AttachmentThumbnail.kt` is a sampled 48dp attachment preview. It is not the sticker renderer.

Relevant current files to reread:

```text
app/src/main/kotlin/cn/nabr/chatwithchat/data/database/ChatDatabaseV2.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/database/ChatDatabaseV2Migrations.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/database/entity/MessageV2.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/database/entity/*Converter.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/ChatRepositoryImpl.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/ToolProvider.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/ToolRegistry.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/ToolExecutor.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/ToolLoopOrchestrator.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/ToolDefinition.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/BuiltInTools.kt
app/src/main/kotlin/cn/nabr/chatwithchat/data/dto/ApiState.kt
app/src/main/kotlin/cn/nabr/chatwithchat/util/ApiStateFlowExtensions.kt
app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatViewModel.kt
app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatScreen.kt
app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatBubble.kt
app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/setting/SettingScreen.kt
app/src/main/kotlin/cn/nabr/chatwithchat/presentation/common/NavigationGraph.kt
app/src/main/kotlin/cn/nabr/chatwithchat/presentation/common/Route.kt
docs/superpowers/tool-calling.md
```

## Product Contracts

### User-facing scope

- The model decides automatically whether a sticker helps the reply. It must use them sparingly: maximum one sticker per assistant response, never as the sole response to safety-sensitive, medical, legal, account, payment, or error-recovery requests unless the user explicitly asks for that style.
- Users do **not** send stickers through the chat composer. There is no user message sticker picker in this release.
- Users manage a single local collection named "My stickers" from Settings. They can bulk-add static images from the photo picker, edit title / alt text / tags, enable or disable items, and delete items.
- A visible user control enables or disables automatic AI sticker replies. Default it to enabled for a fresh install, as requested, but clearly state that the selected model receives sticker titles, alt text, and tags when choosing a sticker. It must not receive file paths or image bytes.
- All user-added data remains local. There is no ZIP pack sharing, online catalog, cloud synchronization, automatic image tagging, or remote download in this release.

### Model protocol

The model never receives a full catalog, an asset path, a content URI, Base64, a bitmap, or an Android resource ID.

Expose exactly these model-facing capabilities while automatic sticker replies are enabled:

```text
search_stickers(query: string, limit?: integer) -> candidates
send_sticker(sticker_id: string) -> selected sticker
```

- `search_stickers` searches enabled static items only and returns at most six candidates. Each candidate contains a stable `sticker_id`, display title, bounded alt text, and bounded tags. Its normal textual `ToolResult.content` must contain the candidate IDs as well as any `structuredContent`, because the JSON fallback cannot rely on structured content alone.
- `send_sticker` accepts only a current, enabled ID returned from the local catalog. It rechecks availability and the resource hash at execution time. Missing, disabled, malformed, or stale IDs return a bounded `sticker_not_found` / `sticker_unavailable` result and never display a sticker.
- Candidate IDs are stable catalog IDs, not filenames. Built-ins use a reserved namespace; user items use generated UUID-based IDs. A duplicate visible title never changes identity.
- `search_stickers` and `send_sticker` must have clear descriptions telling the model to use stickers only when they improve a conversational response, to call `search_stickers` before guessing an ID, and to avoid duplicate calls.
- The two tools are local presentation capabilities, not permission prompts, external writes, network calls, or arbitrary local-file reads. Do not let their activation widen the availability of other tools.

### Message and history contract

Use a dedicated typed sticker reference, not `ChatAttachment` and not Markdown syntax. A shape equivalent to the following is required; exact package/file placement may follow repository conventions:

```kotlin
@Serializable
data class MessageStickerRef(
    val instanceId: String, // tool call ID; dedupe key
    val stickerId: String,
    val assetKey: String, // immutable SHA-256/content-addressed key
    val altText: String,
    val mediaKind: String = "static_raster"
)
```

- Add `stickerRefs` (or an equally explicit name) to `MessageV2` and to `AssistantRevision`; provide `effectiveStickerRefs()` alongside the existing effective content helpers.
- A sticker-only assistant reply is valid and persistable. Every current "blank assistant message" / "successful answer" / retry / round-navigation predicate must recognize it.
- When an assistant revision is selected, render that revision's sticker references, not the latest top-level references.
- In provider-history transforms, append a short semantic marker such as `[assistant sent sticker: 开心地鼓掌]` to assistant text when appropriate. Do not add the sticker as an image content part, do not upload it, and do not include binary data in memory or provider requests.
- Preserve a snapshot of the asset key and alt text in the message so renaming, disabling, updating, or deleting a catalog item cannot break prior history.

### Media extensibility contract

Only `static_raster` is renderable and importable in this release. Preserve a forward-compatible resource descriptor now:

```kotlin
@Serializable
data class StickerAssetDescriptor(
    val assetKey: String,
    val mediaKind: String, // "static_raster", future "animated_raster", "lottie"
    val mimeType: String,
    val posterAssetKey: String? = null,
    val durationMs: Long? = null,
    val loopCount: Int? = null
)
```

- Persist `mediaKind` as a string or use an equally forward-compatible serializer. An older app must treat an unknown future type as unavailable rather than fail to parse the catalog.
- Route rendering through a small resolver/renderer boundary. Only add a static raster renderer now; reserve the future insertion point for animated raster and Lottie renderers.
- Do not add GIF playback, animated WebP playback, Lottie, video, Coil, or a dynamic-image dependency in this release.

### Asset ownership and seed data

The user has authorized the following desktop assets for inclusion in the app. Preserve the source files; copy normalized ASCII-named copies into the repository as part of implementation.

```text
C:\Users\NaBr\Desktop\表情资源\qq企鹅心碎.jpg
C:\Users\NaBr\Desktop\表情资源\小猫痛哭.jpg
C:\Users\NaBr\Desktop\表情资源\直视灵魂的大脸猫 .jpg
```

Seed the built-in catalog with these stable IDs and initial Chinese metadata. Keep IDs stable even if display copy later changes:

| Stable ID | Asset | Title | Initial semantic tags |
|---|---|---|---|
| `builtin.reactions.qq_penguin_heartbroken` | `qq企鹅心碎.jpg` | 企鹅心碎 | 心碎, 失落, 难过, 失望 |
| `builtin.reactions.crying_cat` | `小猫痛哭.jpg` | 小猫痛哭 | 痛哭, 委屈, 悲伤, 难过 |
| `builtin.reactions.soul_stare_cat` | `直视灵魂的大脸猫 .jpg` | 直视灵魂的大脸猫 | 凝视, 无语, 沉默, 尴尬 |

Put bundled metadata and images under a versioned, app-owned assets subtree, for example `app/src/main/assets/stickers/builtin.reactions/`. Do not use a localized filename or Android drawable resource ID as the persistent key.

## Non-Goals

- Do not add a composer sticker button, user-message sticker persistence, a manual send workflow, or a chat attachment shortcut.
- Do not reuse `ChatAttachment`, `AttachmentUploadCoordinator`, or provider file-upload code for stickers.
- Do not send sticker pixels, local URIs, paths, hashes, EXIF, or complete catalog contents to a provider.
- Do not add animated rendering, GIF/animated WebP input, Lottie, video, a remote sticker store, sharing/importing ZIP packs, cloud sync, or AI image classification.
- Do not modify provider network endpoints, attachment upload fallback behavior, memory retrieval behavior, normal image attachment behavior, export behavior, edit/retry semantics, multi-provider behavior, or model-selection behavior except where this feature requires explicit typed sticker preservation.
- Do not require an Android runtime permission for sticker management or presentation.
- Do not bypass the existing tool registry/executor/adapters by parsing model Markdown such as `[[sticker:id]]`.
- Do not modify unrelated user work, reset the worktree, force-push, create a branch, commit, or push unless the user explicitly asks in the implementation session.

## Target Architecture

```text
Bundled manifest + user-imported static files
  -> StickerRepository / local content-addressed asset store
  -> enabled StickerItem catalog
  -> search_stickers(query) ToolProvider
  -> bounded candidates with stable IDs
  -> send_sticker(sticker_id) ToolProvider
  -> typed local StickerPresentationArtifact
  -> ApiState.StickerAdded
  -> handleStates(...) updates current assistant MessageV2
  -> Room persistence + AssistantRevision snapshot
  -> StickerMessageBlock resolves local assetKey and renders static image

Future only:
  StickerAssetDescriptor.mediaKind
  -> renderer registry
  -> animated_raster / lottie implementation
```

Suggested ownership boundaries:

- `data/sticker/`: catalog entities, repository, storage/import validation, asset resolver, domain models.
- `data/tool/`: the two tool providers and typed tool-to-presentation mapping only.
- `data/database/`: Room entities/DAOs, converter, migration, schema export.
- `presentation/ui/setting/`: management page and ViewModel.
- `presentation/ui/chat/`: dedicated assistant sticker rendering only.
- `util/` or repository adapter: compact provider-history semantic projection only.

## Execution Discipline

Before editing:

```powershell
git status --short --branch
git diff --check
rg -n "MessageV2|AssistantRevision|isEffectivelyBlank|persistableMessages|sendableAssistantContent|attachments" app\src\main app\src\test
rg -n "ToolProvider|ToolResult|ToolLoopOrchestrator|ToolExecutor|ToolDefinition|activeToolDefinitions" app\src\main app\src\test
rg -n "ChatDatabaseV2|MIGRATION_16_17|addMigrations" app\src\main app\src\androidTest app\src\test
```

Read `AGENTS.md`, this prompt, `docs/superpowers/tool-calling.md`, and the actual affected tests before choosing file names or APIs.

If the baseline fails, establish whether it is pre-existing before changing behavior. Do not delete, reset, or overwrite unrelated work to get a green result. After every task, run the smallest relevant tests and `:app:compileDebugKotlin`; do not defer all verification until the end.

## Task 0: Read-Only Audit And Baseline

**Goal:** Confirm the active branch, current schema version, current tool enablement behavior, source asset availability, and all existing message lifecycle predicates before edits.

Audit completed on `codex/llm-sticker-library`:

- [x] Baseline working tree was limited to this untracked plan; `git diff --check` was clean.
- [x] Confirmed schema 17, shared tool execution through `ToolExecutor`, and provider-specific native/fallback loops.
- [x] Confirmed the three authorized JPEG sources exist: 444x439, 402x382, and 831x831; no connected Android device was available.
- [x] `:app:testDebugUnitTest --tests "*Tool*" --tests "*ChatViewModel*" --tests "*ChatRepositoryImplTest"` and `:app:compileDebugKotlin` passed before implementation.

Required output before implementation:

- the actual active-tool filtering path for all provider families;
- where `ToolResult` is transformed into visible `ApiState` events in fallback and each native loop;
- every existing message blank/persist/revision/context predicate that must learn about stickers;
- the current Room schema/version and migration-test construction path;
- whether the desktop asset source path exists and its MIME/dimensions;
- all overlapping user modifications, if any.

Baseline commands:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*Tool*" --tests "*ChatViewModel*" --tests "*ChatRepositoryImplTest"
.\gradlew.bat :app:compileDebugKotlin
```

If a connected device is available, record `adb devices` but do not block the unit-test and compile baseline on an unavailable emulator.

## Task 1: Create The Local Sticker Catalog And Static Asset Store

**Goal:** Establish a local, hash-addressed catalog that can seed bundled stickers and safely retain user-added static images.

Likely additions:

- `StickerPackEntity`, `StickerItemEntity`, `StickerAssetEntity` and focused DAOs, or a comparably explicit Room model;
- `StickerRepository` / `StickerRepositoryImpl`;
- a bundled-manifest loader/seed service;
- a private `filesDir` content-addressed store for user assets;
- `StickerImportService` or similarly named isolated importer;
- DI bindings and focused unit tests.

Implementation requirements:

- [ ] Use a reserved `builtin.reactions` namespace for bundled IDs and a separate generated namespace for user items. Never derive an ID from a visible name or original file name.
- [ ] Seed the three supplied assets from the exact desktop source paths into a repo assets subtree using normalized ASCII output names. Include a versioned manifest with IDs, SHA-256, MIME, title, alt text, tags, and `mediaKind = "static_raster"`.
- [ ] Bundle reading must validate the manifest and asset hash. If an app update contains an invalid bundled item, skip only that item with bounded logging; do not crash chat startup.
- [ ] Represent user items under one local "My stickers" collection in the UI, while preserving a `packId`/collection field in storage for future extension.
- [ ] Store user-imported binaries by content hash below an app-private root. Store a generated relative path or resolvable key, never an external `content://` URI or absolute original path.
- [ ] Treat user import as a separate pipeline from chat attachments. It may reuse safe generic primitives, but must not call `AttachmentUploadCoordinator`, write into attachment directories, create provider refs, or invoke upload resizing semantics.
- [ ] First release accepts only non-animated JPEG, PNG, and WebP. Check actual decodability, true MIME/signature, file size, pixel bounds, and image dimensions; reject GIF/SVG/BMP/TIFF and malformed or oversized input. Define named, tested limits rather than scattering literals.
- [ ] Copy through a staging file, hash and validate it, atomically promote it to the final content-addressed location, then update the Room catalog transaction. A failed import must not leave a catalog entry pointing at a missing file.
- [ ] Normalize/re-encode user images as needed to remove EXIF and prevent unexpectedly large resources while preserving alpha for PNG/WebP. Avoid silently converting an asset to a different type without updating MIME/hash metadata.
- [ ] Deleting or disabling a catalog item makes it unavailable for future candidates. Keep its asset while any historical `MessageStickerRef` references the asset key; only garbage-collect unreferenced local assets.
- [ ] Persist the forward-compatible descriptor fields (`mediaKind`, optional preview/poster key, optional playback metadata) even though only static raster is enabled now.

Acceptance criteria:

- [ ] The three bundled IDs resolve to valid, locally renderable assets.
- [ ] A multi-image import creates stable IDs, hash-addressed files, a "My stickers" catalog entry, and editable semantic metadata.
- [ ] Reimporting identical bytes does not create duplicate blobs.
- [ ] A bad image, unsupported MIME, oversized resource, or failed copy does not create a usable catalog entry.
- [ ] Deleting an unused custom item reclaims its asset; deleting an item already sent in a message preserves that history asset.

Focused verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*StickerImport*" --tests "*StickerRepository*"
.\gradlew.bat :app:compileDebugKotlin
```

## Task 2: Persist Typed Assistant Sticker References And Migrate Room

**Goal:** Make an assistant sticker a durable message payload that survives streaming, restart, revisions, retries, and catalog changes without becoming a provider attachment.

Implementation requirements:

- [ ] Add a serializable `MessageStickerRef` list to `MessageV2`, a matching Room converter, and the required Room column. Extend `AssistantRevision` with the same snapshot data.
- [ ] Add a `MIGRATION_17_18`, bump `ChatDatabaseV2` to 18, register the migration in `DatabaseModule`, and export the schema JSON under the current package path.
- [ ] Add the sticker catalog tables to the same migration with appropriate indexes/foreign keys. A historical message reference must not cascade-delete when a user disables or deletes a catalog item.
- [ ] Add `effectiveStickerRefs()` and update every effective-content, blank, persistence, retry, revision, selected-revision, and round-navigation helper so a sticker-only successful assistant reply is not lost.
- [ ] Make the update idempotent by `instanceId`/tool-call ID. A repeated stream event or retry must not duplicate the same sticker within one assistant revision.
- [ ] Update `persistableMessages(...)` and any initial-request recovery logic so a sticker-only assistant response remains saveable and does not look interrupted.
- [ ] Preserve existing ordinary attachment serialization byte-for-byte in behavior. A normal chat image remains a normal provider attachment; a sticker never appears in `attachments`.

Acceptance criteria:

- [ ] A schema-17 database upgrades with empty sticker lists and all existing chats readable.
- [ ] A sticker-only assistant reply is persisted and restored after process recreation.
- [ ] Selecting an earlier assistant revision shows its matching stickers, not the latest revision's stickers.
- [ ] Retrying an assistant answer snapshots the old sticker set and starts the new revision with no accidental carry-over.
- [ ] Deleting or renaming a catalog item does not erase historical message display because the message stores asset/alt-text snapshots.

Focused verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ChatDatabaseV2MigrationsTest" --tests "*ChatViewModelRetryTest"
.\gradlew.bat :app:compileDebugKotlin
```

When a device/emulator is available, also run the focused migration instrumentation test or the smallest equivalent `connectedAndroidTest` selector. Do not claim migration-device proof when no device is available.

## Task 3: Add Candidate-ID Sticker Tools And The Typed Presentation Event

**Goal:** Reuse the existing multi-provider tool infrastructure while bridging a successful local tool result into a typed assistant message event.

Likely additions/updates:

- `SearchStickersToolProvider` and `SendStickerToolProvider`;
- `ToolDefinition` entries and `BuiltInTools.providers()` registration;
- a typed local `StickerPresentationArtifact` / `MessagePresentationArtifact` model;
- a `ToolProvider`/`ToolExecutor` mapping analogous to current source metadata, or an equally centralized safe bridge;
- an `ApiState.StickerAdded`-style event and `handleStates(...)` support;
- tool enablement settings/data-store plumbing for automatic sticker replies;
- provider/fallback and repository regression tests.

Implementation requirements:

- [ ] Add the exact two tool definitions described in the Product Contracts. `search_stickers` accepts bounded text and optional bounded limit; `send_sticker` accepts a bounded exact ID only.
- [ ] Use deterministic local matching over title, alt text, tags, and aliases. Prefer exact/prefix/tag matches before weaker text matches. Do not introduce an embedding model, cloud call, network request, or full-text/vector infrastructure for a small local catalog.
- [ ] Return at most six candidates and bound every visible string. The tool result content must be useful in JSON fallback, while `structuredContent` may supplement native adapters.
- [ ] `send_sticker` must resolve the resource locally and create a typed presentation artifact only after validating the current catalog entry, availability, media kind, and asset hash. It must never pass a local path or byte array into model-visible result fields.
- [ ] Add a central `ToolProvider`/`ToolExecutor` artifact-mapping contract or another single shared extension point. Do not add five provider-specific sticker branches to `ChatRepositoryImpl`.
- [ ] Ensure the bridge is exercised by the JSON fallback and all four native adapter loops. The event must be emitted once for a successful `send_sticker`, and never for a failed, rejected, disabled, malformed, or unavailable call.
- [ ] Keep normal tool progress UI functional. Use a concise localized label such as "正在挑选表情"; do not show raw IDs or paths.
- [ ] Make `send_sticker` at most once per request through `ToolPolicy` and prevent duplicate artifact emission. `search_stickers` can be called once per request unless live behavior requires another bounded search.
- [ ] Add a separate persisted automatic-sticker setting, default enabled. It must independently add only the two sticker definitions to active capabilities; disabling generic tools must not silently enable web/location/calendar/alarm, and disabling stickers must remove both definitions even when generic tools are on.
- [ ] Put a concise model-facing instruction in the tool definitions: use no more than one sticker, only when it adds tone or empathy, and never use a guessed ID without a search result.

Acceptance criteria:

- [ ] The model receives no catalog until it calls `search_stickers`.
- [ ] A successful search followed by send emits exactly one typed sticker event and a normal final response can still stream afterward.
- [ ] The same behavior works in JSON fallback and every native provider loop without duplicated code paths.
- [ ] A forged ID, disabled item, removed item, unsupported media kind, or hash mismatch yields a bounded tool error and no UI artifact.
- [ ] Turning off automatic sticker replies removes only the sticker tools from active definitions.
- [ ] Normal tool result sources, usage, approval, and error behavior remain intact.

Focused verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*Sticker*Tool*" --tests "*ToolLoopOrchestratorTest" --tests "*ToolExecutorTest"
.\gradlew.bat :app:testDebugUnitTest --tests "*ChatRepositoryImplTest" --tests "*ProviderToolAdapterTest"
.\gradlew.bat :app:compileDebugKotlin
```

## Task 4: Keep Sticker Semantics Out Of Provider Image Uploads And Memory Pollution

**Goal:** Preserve conversational continuity without resending local sticker pixels or treating an assistant reaction as a user fact.

Implementation requirements:

- [ ] Update every provider-message transform used by `ChatRepositoryImpl` so an assistant sticker contributes only a bounded semantic text marker based on the effective revision's `altText`.
- [ ] Ensure no sticker reference reaches `AttachmentUploadCoordinator`, provider file APIs, inline Base64 encoding, or image content parts.
- [ ] Update context token estimation and truncation so semantic markers are counted deterministically and do not make a sticker-only assistant message disappear.
- [ ] Do not teach long-term memory new user facts from a sticker-only assistant reply. Preserve existing memory behavior for surrounding ordinary user/assistant text.
- [ ] Ensure assistant editing, retry, copying, export/search helpers, and error recovery preserve or deliberately render sticker semantics without exposing local storage details.

Acceptance criteria:

- [ ] Provider request tests prove a stored sticker does not generate an image input or provider upload attempt.
- [ ] A later model turn can see a compact assistant semantic marker rather than raw asset data.
- [ ] A sticker-only assistant reply cannot become a memory fact by itself.
- [ ] Existing ordinary image attachments remain provider-visible and continue using their established upload/base64 fallback behavior.

Focused verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*ProviderAttachmentSerializationTest" --tests "*ChatRepositoryImplTest" --tests "*Sticker*"
.\gradlew.bat :app:compileDebugKotlin
```

## Task 5: Add Settings-Only User Management And Dedicated Chat Rendering

**Goal:** Let users curate local static assets while giving assistant stickers an intentional visual treatment.

Likely additions/updates:

- a `StickerLibraryScreen` and `StickerLibraryViewModel` under settings;
- a route and `SettingScreen` entry;
- static-photo picker wiring using an independent import service;
- sticker metadata edit/delete/enable UI;
- `StickerMessageBlock` or equivalent in the assistant bubble;
- localized strings in both `values/strings.xml` and `values-zh-rCN/strings.xml`.

Implementation requirements:

- [ ] Add a Settings entry for the sticker library and a clear automatic-sticker enable/disable control. Do not add a composer button or an outgoing-user sticker renderer.
- [ ] The "My stickers" page must support multi-select add, import progress/error state, thumbnail preview, edit title/alt text/tags, enable/disable, and delete confirmation. The three built-ins may be visible but are not editable/deletable as user assets.
- [ ] Use a familiar add icon/button and the Android photo picker. Do not request broad storage permissions.
- [ ] Favor concise Chinese labels and tags. Initial user metadata may default from a safe filename-derived draft, but the UI must make title/alt/tags editable; all model-facing values are length-limited and sanitized.
- [ ] Render assistant stickers after text and before sources/actions in `OpponentChatBubble`. A sticker-only assistant response must render without a perpetual loading indicator.
- [ ] Give the sticker block stable constrained dimensions (roughly 128-160dp maximum for one asset), preserve aspect ratio, support transparent PNG/WebP, and show `altText` fallback when the asset is unavailable. Do not reuse the 48dp attachment-thumbnail row or its RGB_565 decoding behavior.
- [ ] Resolve assets off the main thread and cache only bounded decoded previews. Ensure renderer state and labels cannot change message layout unexpectedly during streaming.
- [ ] Unknown/non-renderable `mediaKind` must render a localized unavailable placeholder and must not be offered as an LLM candidate in this release.

Acceptance criteria:

- [ ] A user can add multiple static images through Settings, edit their semantic labels, and see them in the library without a chat-composer affordance.
- [ ] Disabled custom stickers disappear from candidates but historical assistant messages still render.
- [ ] The three bundled stickers are visually distinct in an assistant chat bubble and have appropriate content descriptions.
- [ ] The assistant response remains usable when the underlying asset is missing or corrupted.
- [ ] Dark and light themes remain legible, and no chat action/source UI overlaps the sticker block.

Focused verification:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*Sticker*" --tests "*ChatViewModel*"
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
```

If a device/emulator is available, manually verify: Settings entry, bulk import, metadata edit, automatic sticker reply with a tool-capable provider, disabled-item filtering, history/revision navigation, and restart persistence. Capture a screenshot/UI dump only after confirming the app actually reaches the intended screen.

## Task 6: Regression Coverage, Migration Proof, And Final Audit

**Goal:** Prove the feature is durable across storage, providers, retries, and UI states without regressing existing attachment behavior.

Required test coverage:

- [ ] Asset manifest parsing, bundled hash verification, user import success/failure, duplicate content, unsupported animation/MIME, oversized/decode failure, and interrupted promotion cleanup.
- [ ] Candidate ranking, enabled filtering, stable IDs, and bounded result text/structured content.
- [ ] `search_stickers -> send_sticker` success, forged/missing/disabled/hash-mismatch failure, single-send policy, and artifact de-duplication.
- [ ] Artifact event propagation through the JSON fallback and all native tool-loop paths.
- [ ] `MessageV2` / revision converter compatibility, schema 17 -> 18 migration, sticker-only persistence, retry history, and selected-revision rendering.
- [ ] Provider-context proof that stickers become semantic text only and ordinary attachments remain image inputs.
- [ ] Settings-only management logic and no composer/user-message sticker path.
- [ ] Missing-asset placeholder and unknown media-kind behavior.
- [ ] Localized strings and backup behavior: user assets may remain in normal app backup, but temporary import staging must live in cache or be excluded explicitly. Update exact backup-rule tests if rules change.

Run at minimum:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*Sticker*"
.\gradlew.bat :app:testDebugUnitTest --tests "*Tool*" --tests "*ChatRepositoryImplTest" --tests "*ChatViewModel*" --tests "*ChatDatabaseV2MigrationsTest"
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:assembleDebug
git diff --check
```

Run focused instrumentation migration/UI checks only with a usable connected device. Report exactly which runtime evidence was obtained and which behavior remains unit/compile verified only.

## Final Acceptance Matrix

- [ ] The app ships the three supplied static built-ins with stable IDs and validated local assets.
- [ ] A user can add/manage only their own static stickers from Settings; no user chat-send UI exists.
- [ ] The model automatically chooses stickers through bounded candidate IDs, never paths or binary media.
- [ ] A response emits at most one assistant sticker and normal text streaming still works.
- [ ] Sticker state survives save/restart, retry, and historical revision selection.
- [ ] Sticker assets never re-enter provider image uploads or future Base64 requests.
- [ ] Disabled/deleted catalog items are unavailable to the model but old messages remain understandable/renderable.
- [ ] Static renderer is complete; dynamic media has a safe typed extension boundary but no dynamic decoder is added.
- [ ] Existing attachment, edit, retry, export, memory, multi-provider, tool approval, and token-usage behavior remains covered and intact.

## Suggested Commit Sequence

Use small topical commits only if the user asks for commits:

1. `feat(stickers): add local catalog, bundled manifest, and static importer`
2. `feat(stickers): persist assistant sticker refs with Room migration`
3. `feat(stickers): add candidate tools and typed message event`
4. `feat(stickers): add settings management and chat rendering`
5. `test(stickers): cover provider, migration, and regression contracts`

Do not stage or commit unrelated changes encountered during implementation.

## Copy-Paste Handoff

```text
Implement docs/superpowers/plans/2026-07-26-llm-sticker-library-prompt.md end to end. Re-audit the live repo and AGENTS.md first, preserve unrelated work, then execute Tasks 0-6 in order. Treat the product contracts and non-goals as binding: only LLM-sent stickers, static assets in v1, settings-only user management, stable candidate IDs, no provider image upload, and dynamic-media extension points without a dynamic decoder. Verify each task before moving on and report exact runtime versus unit-test evidence at the end.
```
