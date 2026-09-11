# ChatWithChat 上下文图片资产与 `view_image` 工具实施计划

日期：2026-09-11

## 实施状态

- 2026-09-11：已从 `main` 创建 `codex/context-visual-assets-view-image`。
- Task 0：审计完成；数据库基线为 26，`ToolResult` 仍为文本，managed context 仍清空旧附件。
- Task 1：视觉资产领域模型、Room 四表、DAO、文件存储、压缩器、Catalog、GC 和 `26 -> 27` migration 已落地。
- Task 2：折叠后在 fold maintenance job 内生成 `COMPACTED_V1`；压缩失败保留原图回退状态。
- Task 3：`ToolResult` 增加视觉附件、独立数量/字节/token 边界，scratchpad 和 audit 不写 base64。
- Task 4：`view_image` 与首版必需的 `list_visual_assets` 已加入默认启用工具集；不支持视觉的模型返回 `visual_asset_unsupported`。
- Task 5：OpenAI Responses、Anthropic 原生结果携带图片，OpenAI Chat Completions、Google 和 JSON fallback 走下一轮 synthetic user image。
- Task 6：raw turn attachments 不再被历史窗口清空；折叠 turn 生成 visual manifest；checkpoint hash 包含 visual_ref/asset_hash/profile。
- Task 7：保存/编辑/复制/删除 chat 与启动 GC 已接入；无引用原图、压缩图和 provider ref 会回收。
- Task 8：开发者诊断页增加只读视觉资产页，展示 catalog、文件存在性、profile、压缩错误和 provider ref 数量。
- Task 9：新增 JVM 契约/上下文测试，并在 `emulator-5554` 跑通真实 PNG/alpha 压缩、折叠解析、GC 和 `26 -> 27` migration。
- 验证：`:app:compileDebugKotlin`、工具/上下文单测、AndroidTest 编译、视觉资产 instrumented test 和 migration instrumented test 均通过。
- 说明：压缩当前在 fold maintenance job 内完成，没有单独排队 `COMPRESS_FOLDED_VISUAL_ASSETS` 任务；这样 checkpoint 已落库后压缩失败不会回滚折叠结果。

## Goal

将图片从纯文本上下文压缩中拆分为独立的视觉资产，并实现 Codex 式按需看图：

- 当前用户刚附带的图片自动作为 provider image part 发送。
- 仍在 raw 上下文窗口内的历史图片自动随对应 turn 发送。
- 已经折叠或超出 raw 窗口的图片不再自动注入上下文，由 agent 调用 `view_image` 按需查看。
- 原始图片保留到用户删除对应消息或附件。
- turn 被折叠后，在后台基于原图生成像素维度更小的本地压缩视图。
- `view_image` 默认使用压缩视图；需要时可显式请求原图。
- 所有 provider 共享同一个工具调用和结果契约，具体 wire format 按 provider 适配。

本计划不把图片像素写进 checkpoint，也不把文本摘要当作原图替代。OpenAI compaction item
是不可解释的派生状态，不能作为原图存储；本地 Visual Asset Store 才是真实来源。

参考：

- [OpenAI Compaction Guide](https://developers.openai.com/api/docs/guides/compaction)
- [OpenAI Images and Vision](https://developers.openai.com/api/docs/guides/images-vision)
- [OpenAI Responses API Reference](https://developers.openai.com/api/reference/resources/responses/methods/create)

## Product Contract

### 图片进入模型上下文的规则

| 图片来源 | 行为 |
| --- | --- |
| 当前用户消息 | 自动作为 image part 发送 |
| raw 窗口内历史 turn | 自动作为 image part 发送，不单独裁剪附件 |
| 已进入 checkpoint 的 turn | 只保留视觉 manifest；agent 可调用 `view_image` |
| raw 窗口外且尚未折叠的 turn | 视为待折叠/摘要内容；图片只保留资产引用 |
| 当前 provider/model 不支持图片 | 明确返回能力错误，不用文本描述假装已查看像素 |

### 保留与压缩规则

- 原图：从消息首次附件化开始保留，直到用户删除消息或附件。
- 压缩视图：仅在原 turn 成功进入 checkpoint 后生成。
- 压缩视图不覆盖原图；两者同时存在，使用 profile 区分。
- 默认 `view_image` 使用 `COMPACTED_V1`。
- `detail=original` 请求原图，并受 provider 能力与请求预算约束。
- 删除最后一条引用后，原图和所有派生视图一起回收。

### Provider 一致性

所有 provider 必须暴露相同的 `view_image` 工具定义、参数和结果语义。差异只允许出现在
底层请求装配：

1. 原生支持多模态工具结果的 provider，直接把图片放进 tool result。
2. 不支持多模态 tool result、但支持 vision user message 的 provider，在工具调用后的下一轮
   注入合成 user image message。
3. 当前模型完全不支持图片输入时，返回结构化 `visual_asset_unsupported`，不能把图片路径或
   caption 当作像素结果返回。

“全一致”指 agent 行为与工具结果契约一致，不表示各 provider wire format 相同。

### 图片寻址：Codex 式文件路径 vs ChatWithChat Catalog

Codex 当前可观察到的模式不是先给每张图片生成语义标签，而是把图片视为文件资产：

- 图片在 workspace 中有稳定路径。
- 路径、文件名、所在目录、用户消息和之前的工具输出共同构成“标签”。
- `view_image` 按路径读取图片，不要求预先 OCR、caption 或 embedding。
- 模型需要先从上文、文件列表或命令结果中发现路径，再决定是否查看。

ChatWithChat 不应允许模型读取任意路径，因此需要提供等价的内部寻址层：

- 每张图片必须有一个稳定的 `visual_ref`，但不需要人工或 LLM 生成的语义标签。
- `visual_ref` 使用 `v<messageId>-<attachmentIndex>`，例如 `v17-2`。
- `asset_hash` 负责内容身份和去重，`visual_ref` 负责在当前 chat 中引用。
- Catalog 保存来源消息、turn、角色、附件顺序、文件名、尺寸、MIME 和时间。
- 没有 caption 不影响 `view_image`；caption 只是未来用于搜索的可选增强。

因此，“标签”在本计划中定义为**确定性寻址索引**，不是语义标签。没有 Catalog，agent
只知道图片存在过，却无法可靠地告诉工具该查看哪一张。

## Non-Goals

- 不要求 OCR、图片 caption 或图像 embedding 才能使用 `view_image`。
- 不实现基于 caption/embedding 的跨图片语义检索。
- 不实现跨聊天图片检索。
- 不允许 `view_image` 读取任意本地路径。
- 不让长期记忆保存图片二进制。
- 不把图片写入 `MEMORY.md`、checkpoint body 或 OpenAI opaque compaction item。
- 不在折叠成功时删除原图。
- 不通过文件名、hash 或 regex 猜测图片内容。

## Task 0: Audit And Freeze Boundaries

实施前只读确认：

- `ChatDatabaseV2` 当前版本是否为 `26`，本轮 migration 应为 `26 -> 27`。
- `ToolResult.content` 仍是 `String`，provider tool adapters 当前只支持文本结果。
- `ContextBuilder.applyAttachmentWindow()` 仍会清空历史附件。
- `ContextCompactionPrompt` 和 `ConversationContextSummarizer` 仍只读取消息文本。
- `ContextTurnHasher` 仍不包含 attachments。
- 消息删除和聊天删除没有视觉文件 GC 钩子。
- 当前工作区未提交文件不得被修改或清理。

验证命令：

```powershell
git status --short --branch
rg -n "version = 26|MIGRATION_25_26|applyAttachmentWindow|data class ToolResult|sourceTurnsHash" app/src/main/kotlin
```

## Task 1: Visual Asset Domain And Storage

新增 `data/visual/`：

- `VisualAsset`
- `VisualAssetProfile`
- `VisualAssetStore`
- `VisualAssetRepository`
- `VisualAssetCompressor`
- `VisualAssetGarbageCollector`

建议数据模型：

```kotlin
enum class VisualAssetProfile {
    ORIGINAL,
    COMPACTED_V1
}

data class VisualAsset(
    val assetHash: String,
    val mimeType: String,
    val originalPath: String,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long,
    val createdAt: Long
)
```

Room 新增：

- `visual_asset`：以 `asset_hash` 为主键，保存原图和压缩视图元数据。
- `visual_asset_catalog`：当前 chat 的确定性寻址表。
- `message_visual_ref`：`message_id + attachment_index` 指向 `asset_hash`。
- `visual_asset_provider_ref`：按 `asset_hash + profile + platform_uid` 保存 provider 文件引用。

`visual_asset_catalog` 建议字段：

```kotlin
@Entity(
    tableName = "visual_asset_catalog",
    primaryKeys = ["chat_id", "visual_ref"]
)
data class VisualAssetCatalogEntity(
    val chatId: Int,
    val visualRef: String,
    val assetHash: String,
    val messageId: Int,
    val attachmentIndex: Int,
    val role: String,
    val turnOrdinal: Int,
    val displayName: String,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
    val createdAt: Long,
    val foldedAt: Long?,
    val compactedProfile: String?
)
```

`visual_ref` 生成规则必须稳定、可排序且不依赖当前 UI 排序：

```text
v<messageId>-<attachmentIndex>
```

例如 `v17-1` 表示 message id 17 的第一张图片。删除其他消息不会改写仍存在资产的 ref。
如果同一消息附件重排，message edit 必须使 checkpoint 失效并重建 Catalog 映射。

约束：

- 文件根目录为应用私有 `filesDir/visual_assets/`。
- 原图路径不可被压缩流程覆盖。
- 所有写入使用临时文件加原子 rename。
- provider remote ref 只是缓存，文件不存在时必须能从本地重新上传。
- `message_visual_ref` 对 `messages_v2.message_id` 使用级联删除。
- `visual_asset` 只有在没有 message ref 后才能删除。
- Catalog row 与 message ref 在同一事务内增删。
- 所有 Catalog 查询都必须带 `chat_id`，禁止跨 chat 解析 `visual_ref`。

Migration `26 -> 27`：

- 新增四张表和索引。
- 不修改 `messages_v2` JSON 结构。
- 旧消息采用惰性索引：第一次折叠、发送或 `view_image` 时补建 asset/ref。
- 新增 instrumented migration 测试。

## Task 2: Local Compression Pipeline

在 checkpoint segment 成功落库后异步生成 `COMPACTED_V1`，不能在发送同步路径压缩。

压缩规则：

- 使用 `ImageDecoder` 读取源图并应用 EXIF orientation。
- 长边超过 `2048px` 时按比例缩小。
- 无 alpha 通道使用 WebP lossy，质量 `82`。
- 有 alpha 通道优先 WebP lossless，超阈值时回退 PNG。
- 不保留原始 EXIF、GPS 或 ICC 之外的非必要元数据。
- 派生文件命名为 `<assetHash>_compacted_v1.webp`。
- 记录压缩后宽高和字节数。
- 小于阈值的图片仍生成 profile 记录，但可复用原图路径，避免无意义转码。

触发方式：

- 复用现有 maintenance job 设施，新增 `COMPRESS_FOLDED_VISUAL_ASSETS`。
- idempotency key 使用 `assetHash + COMPACTED_V1 + compressorVersion`。
- 每个 asset 单独失败、单独重试，不阻塞 checkpoint。
- 压缩失败不改变折叠结果；`view_image` 回退原图并记录可见降级状态。

主要落点：

- [ContextFoldService.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/context/ContextFoldService.kt:144)
- [MemoryMaintenanceJobType.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/memory/MemoryMaintenanceJobType.kt)
- [AttachmentUploadCoordinator.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/AttachmentUploadCoordinator.kt:29)

## Task 3: Multimodal Tool Result Contract

扩展 `ToolResult`，保留现有文本字段，新增独立视觉附件：

```kotlin
data class ToolResult(
    val callId: String,
    val name: String,
    val content: String,
    val visualAttachments: List<ToolResultVisualAttachment> = emptyList(),
    val isError: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
    val structuredContent: JsonElement? = null,
    val sources: List<ToolSource> = emptyList(),
    @Transient
    val presentationArtifacts: List<ToolPresentationArtifact> = emptyList()
)

data class ToolResultVisualAttachment(
    val assetId: String,
    val profile: VisualAssetProfile,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
    val sizeBytes: Long,
    val detail: VisualDetail = VisualDetail.AUTO
)
```

要求：

- `ToolResultBounds` 增加独立图片数量、总字节数和 token 预算。
- 图片不能进入 `maxContentChars` 字符截断。
- scratchpad 只保存 asset ref，重复查看同一图片时复用已加载 bytes。
- tool audit 只记录 assetId、profile、大小和结果状态，不记录 base64。

主要落点：

- [ToolResult.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/ToolResult.kt:8)
- [ToolResultBounds.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/ToolResultBounds.kt:7)
- [NativeToolResultProjection.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/provider/NativeToolResultProjection.kt:14)

## Task 4: `view_image` Tool

新增 Built-in tool：

```text
name: view_image
parameters:
  visual_ref: string, required
  detail: enum(auto, compact, original), optional
```

输入接受当前 chat Catalog 中的 `visual_ref`，不接受本地路径。工具内部解析为
`asset_hash`，调用方不需要知道 SHA-256。

执行逻辑：

1. 使用 ToolExecutionContext 取得当前 `chatId`。
2. 验证 `visual_ref` 存在且被该 chat 的消息引用。
3. 按 detail 选择原图或 `COMPACTED_V1`。
4. 校验 MIME、文件存在性、hash 和大小。
5. 返回文本确认和 `ToolResultVisualAttachment`。
6. 越权、已删除、损坏和超限分别返回结构化错误。

策略：

- `ToolEffect.READ_ONLY_PRIVATE`
- 默认启用，不需要 Android 权限
- 不要求逐次审批
- 不允许 session grant 覆盖任意路径

加入轻量 `list_visual_assets`，它是首版必需能力，不是可选优化：

```text
name: list_visual_assets
parameters:
  query: string, optional
  limit: integer, optional
  include_raw: boolean, optional
  include_folded: boolean, optional
```

返回内容只包含 Catalog 索引：

```text
v17-1 | turn=17 | name=wiring-diagram.png | 2400x1600 | folded=true | profile=COMPACTED_V1
v18-3 | turn=18 | name=error-screenshot.png | 1080x2400 | folded=false | profile=ORIGINAL
```

没有语义 caption 时，agent 可以结合 turn 文本、文件名和尺寸决定查看哪张图。若图片很多，
再调用 `view_image` 查看候选，而不是为每张图片预先执行一次 vision 调用。

主要落点：

- [ToolDefinition.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/ToolDefinition.kt:16)
- [BuiltInTools.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/BuiltInTools.kt:11)
- [ToolModule.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/di/ToolModule.kt:31)
- [ToolEnablementResolver.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/ToolEnablementResolver.kt:50)

## Task 5: Provider Adapters

### OpenAI Responses

OpenAI API 的 `function_call_output.output` 支持字符串或
`input_text | input_image | input_file` 内容数组。将
`ResponseFunctionCallOutputItem.output` 升级为自定义 serializable 类型，直接返回图片。

### Anthropic

把 `ToolResultContent.content` 从 `String` 扩展为 text/image block 列表，`view_image` 使用现有
`ImageContent`/`ImageSource` 类型。

### Google

如果当前 function response 协议无法直接嵌入图片，使用通用 synthetic user image message
降级；不能把图片塞进 JSON 字符串。

### OpenAI-compatible、Groq、Ollama、OpenRouter、Custom

统一采用“工具返回 visual_ref + orchestrator 下一轮合成 user image message”：

- 工具结果先返回 visual_ref、asset_hash 和状态。
- 如果模型支持 vision，下一轮输入追加真实 image part。
- 如果模型不支持 vision，返回 `visual_asset_unsupported`。
- 文本 JSON fallback tool loop 同样走该策略。

主要落点：

- [OpenAIResponsesToolAdapter.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/provider/OpenAIResponsesToolAdapter.kt:158)
- [AnthropicNativeToolAdapter.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/provider/AnthropicNativeToolAdapter.kt:131)
- [GoogleNativeToolAdapter.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/provider/GoogleNativeToolAdapter.kt:116)
- [OpenAICompatibleJsonToolAdapter.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/tool/provider/OpenAICompatibleJsonToolAdapter.kt:46)

## Task 6: ContextBuilder And Checkpoint Integration

修改图片窗口规则：

- 删除 `applyAttachmentWindow()` 事后清空 attachments 的行为。
- 当前 turn 和 raw tail turn 的 attachments 完整随请求发送。
- 进入 checkpoint/摘要的 turn 不被自动注入图片。
- checkpoint 生成视觉 manifest：

```text
[visual asset ref=v17-2 hash=sha256:... turn=17 profile=COMPACTED_V1 name=diagram.png detail=auto]
```

manifest 至少包含：

- `visual_ref`
- `asset_hash`
- `turn`
- `role`
- `attachment_index`
- `profile`
- 文件名、MIME、宽高

manifest 不要求包含模型生成的 caption。其职责是让 agent 知道存在什么资产、如何引用，以及
是否已经折叠；具体内容由 `view_image` 获取。

- `ContextBuildResult` 增加 `visualManifest`。
- deterministic summary 与 LLM compaction prompt 都包含 manifest。
- compaction 完成后调用 Visual Asset Store 为相关 asset 标记 `folded_at` 并入队压缩。
- 可折叠 batch 的 hash 必须包含 attachment asset hash、顺序和 profile 版本。

主要落点：

- [ContextBuilder.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/context/ContextBuilder.kt:93)
- [ContextBuildResult.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/context/ContextBuildResult.kt:35)
- [ContextCompactionPrompt.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/context/ContextCompactionPrompt.kt:90)
- [ConversationContextSummarizer.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/context/ConversationContextSummarizer.kt:12)
- [ContextTurnHasher.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/context/ContextTurnHasher.kt:34)

## Task 7: Lifecycle And Garbage Collection

消息编辑和删除必须同步处理视觉引用：

- 编辑用户消息时比对旧/新 attachments，移除不再引用的 ref，保留仍被其他消息使用的 asset。
- 删除消息时依赖 `message_visual_ref` 级联删除。
- 删除 chat 时依赖 chat 外键级联删除 ref，并异步回收无引用文件。
- 压缩视图重生成前先检查 profile version。
- app 启动或维护任务清理孤立文件和孤儿 asset row。
- provider remote ref 不阻止本地文件 GC。

必须覆盖现有写路径：

- `saveChat()` 的 delete/update/add 分支。
- `saveChatWithTextSkillInvocation()` 的同一组分支。
- 消息编辑和重试产生的 message replacement。
- `deleteChatsV2()`。

主要落点：

- [ChatRepositoryImpl.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/data/repository/ChatRepositoryImpl.kt:3836)
- [ChatViewModel.kt](E:/code/ChatWithChat/app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatViewModel.kt:1792)

## Task 8: Diagnostics And UI

开发者诊断页增加只读视觉状态：

- visual_ref、asset_hash、来源 messageId、chatId
- original/compacted 是否存在
- 原图和压缩图尺寸、字节数
- 压缩 profile 和失败原因
- provider ref 是否存在
- 当前是否处于 raw 或 folded

不要在普通聊天 UI 暴露文件路径。工具执行可在现有 tool progress 中显示“查看图片”状态，
不需要显示 base64 或本地路径。

## Task 9: Tests

### Domain And Storage

- 相同图片按 SHA-256 去重。
- 原图和 compressed profile 路径互不覆盖。
- 损坏文件、删除文件和 hash 不匹配返回结构化错误。
- 压缩后尺寸不超过边界，alpha 图片不丢透明通道。

### Context

- 当前 turn 图片自动发送。
- raw tail 图片自动发送。
- folded 图片不再自动发送，只出现在 visual manifest。
- checkpoint 生成的 manifest 包含全 part 顺序、visual_ref 和 asset_hash。
- 附件修改会改变 checkpoint hash。
- 压缩视图生成失败时回退原图，不阻塞 checkpoint。
- `visual_ref` 在删除其他消息后保持不变。
- Catalog 查询严格隔离 chat。

### Tool And Provider

- `view_image` 拒绝其他 chat 的 asset。
- `view_image` 拒绝任意文件路径。
- `list_visual_assets` 能发现 raw 和 folded 图片，并返回稳定 `visual_ref`。
- `view_image(v17-2)` 能解析到正确 message、attachment 和 asset hash。
- OpenAI Responses 序列化 `function_call_output.output` 数组，包含 text 和 image。
- Anthropic tool result 包含 text 和 image block。
- 非原生 provider 在下一轮注入 synthetic user image。
- 不支持 vision 的模型返回 `visual_asset_unsupported`。
- tool result image 不受字符截断影响，但受图片数量、字节和 token 限制。

### Instrumented

- `26 -> 27` migration。
- message 删除级联删除 visual ref。
- chat 删除级联清理 visual ref。
- 无引用 asset 和压缩文件被 GC。
- 真实 `ImageDecoder` 压缩路径。

## Verification Commands

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.visual.*"
.\gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.tool.*"
.\gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.context.*"
.\gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.repository.ChatRepositoryImplTest"
.\gradlew.bat connectedAndroidTest
git diff --check
```

## Delivery Order

1. Visual asset schema、store、compression 和 migration。
2. ToolResult multimodal contract。
3. Visual Asset Catalog、`list_visual_assets` 和 `view_image` 寻址。
4. OpenAI Responses native multimodal tool result。
5. Anthropic、Google 适配。
6. OpenAI-compatible/Groq/Ollama/OpenRouter/Custom synthetic injection。
7. ContextBuilder manifest、hash 和折叠触发压缩。
8. 生命周期删除和 GC。
9. 诊断 UI。
10. 全量 provider contract tests 和真机图片测试。

## Acceptance Criteria

- 折叠前后原图始终存在，直到用户删除消息。
- 折叠完成后存在 `COMPACTED_V1`，`view_image` 默认返回压缩视图。
- agent 能根据 checkpoint manifest 找到并查看旧图。
- 每张仍存活的图片都有稳定 `visual_ref`，并可通过 `list_visual_assets` 在 fold 前后一致寻址。
- 所有 provider 都能执行同一个 `view_image` 调用；无法查看像素的模型明确报错。
- 原始上下文发送行为不变：当前图片和 raw 图片仍自动注入。
- 文本 compaction 不再承担像素保存职责。
- 删除消息后，无引用原图、压缩图和 provider ref 最终全部回收。
