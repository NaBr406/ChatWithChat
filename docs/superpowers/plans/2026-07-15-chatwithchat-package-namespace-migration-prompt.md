# ChatWithChat Complete Identity And Internal Branding Migration Prompt

> **用途：** 本文是一份可直接交给新的实现 Agent 执行的仓库提示词。它要求把 Android 应用身份和全部自有 Kotlin/Java 类型命名空间从 `dev.chungjungsoo.gptmobile` 完整迁移到 `cn.nabr.chatwithchat`，并清理 active code/resource/tooling 中残留的 `GPTMobile*` / `gpt_mobile*` 内部品牌标识。不要只改 `applicationId`，不要停在分析或再写一份计划；执行 Agent 应按本文完成实现、验证、分片提交和推送。

> **当前快照：** 本文写于 2026-07-15。当前 `main`/`origin/main` 为 `fb810a6`，`namespace` 与 `applicationId` 都是 `dev.chungjungsoo.gptmobile`，`versionName = "0.7.5"`、`versionCode = 21`，Room 主库 schema = 17。源工作区已有用户未提交改动 `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/ModelConstants.kt`，把 `DEFAULT_PROMPT` 改为空字符串。执行时必须重新核对 live HEAD、远端和工作区，不得盲信快照或覆盖这项改动。

## Goal

在首次正式发布前，把项目自有 Android/Kotlin/Java 身份完整迁移为：

```text
cn.nabr.chatwithchat
```

完成后必须同时满足：

1. Android `applicationId` 是 `cn.nabr.chatwithchat`。
2. Android Gradle `namespace` 是 `cn.nabr.chatwithchat`。
3. 主源码、debug 源码、单元测试、设备测试和 Java 兼容测试的 `package`、`import`、内联全限定类名与物理目录全部迁到 `cn/nabr/chatwithchat`。
4. 默认 instrumentation test package 是 `cn.nabr.chatwithchat.test`，自定义 release instrumentation runner 和测试类均使用新全限定类名。
5. Manifest 组件、debug 广播 Action、应用自有 Intent extra、FileProvider authority、ProGuard/R8 keep 规则和所有运行脚本统一使用新身份。
6. Room schema 导出目录随数据库类的新全限定名移动，但 1-17 历史 JSON 内容、identity hash、数据库文件名、表结构和 migration 链不改变。
7. ObjectBox model UID、ONNX/Tokenizer 资产、记忆文件路径、DataStore 文件名、WorkManager 业务标识和附件目录不改变。
8. `GPTMobileApp`、`GPTMobileTheme`、`Theme.GPTMobile*`、`ic_gpt_mobile*`、`gpt_mobile_introduction_logo` 和根项目内部名称迁为对应的 ChatWithChat 命名。
9. 从 clean build 重新生成 Hilt、Room、KSP、ObjectBox 和 Android resources 后，JVM 测试、AndroidTest 编译、debug、release R8 和关键设备脚本通过。
10. APK manifest、DEX、resources、已安装 package、启动组件、test package 和 provider authority 均有独立证据证明不再混用旧身份/内部品牌。

完成标准不是“Gradle 中两行字符串改了”或“debug 编译通过”，而是 active source/test/build/script/docs、生成代码、Room schema 资产、release artifact 和真实运行时形成一致的新身份。

## User Authorization And Data Boundary

用户已明确确认：

- 应用尚未正式发布。
- 不需要保留旧 APK `dev.chungjungsoo.gptmobile` 沙箱中的聊天、设置、token、记忆、附件、WorkManager 或 ObjectBox 数据。
- 接受新旧包在 Android 看来是两个独立应用；旧应用不会被新 APK 原地升级。
- 接受在 disposable emulator/test device 上卸载旧包、卸载新包和清理测试数据，以完成 fresh-install 验证。
- 明确要求完整迁移，不是只修改 `applicationId`。
- 明确要求同时清理 active code/resource/tooling 中的 GPT Mobile 品牌类名、主题名、图标资源 ID 和工程名。
- 目标字符串精确为 `cn.nabr.chatwithchat`；用户句末标点不属于包名。

以上授权意味着本任务不需要：

- 跨 application sandbox 搬运数据；
- 从旧包导出/向新包导入数据库或 DataStore；
- 为旧 Worker 全限定类名保留 wrapper/alias；
- 保留旧 debug Action、Intent extra key 或 FileProvider authority；
- 做“旧 APK -> 新 APK”的 `adb install -r` 升级测试；
- 新增 package migration marker、双写、兼容 ContentProvider、`sharedUserId` 或 backup restore 桥。

以上授权不允许：

- 删除或改写源工作区现有 `ModelConstants.kt` 用户改动；
- 改变 Room schema 17、数据库表、migration SQL 或业务数据模型；
- 使用 `fallbackToDestructiveMigration()` 掩盖 schema 目录/测试错误；
- 重建或清零 ObjectBox `default.json` UID；
- 将 GPL-3.0 改成 MIT，或删除上游来源/许可证说明；
- 顺手改 UI、聊天逻辑、记忆逻辑、provider 行为、模型资产或依赖版本。

## Verified Current State

执行时重新验证以下事实；数值变化时以 live 仓库为准并在完成报告解释。

### Android And Source Identity

| Surface | Current | Target |
|---|---|---|
| Gradle namespace | `dev.chungjungsoo.gptmobile` | `cn.nabr.chatwithchat` |
| Android application ID | `dev.chungjungsoo.gptmobile` | `cn.nabr.chatwithchat` |
| Default test application ID | `dev.chungjungsoo.gptmobile.test` | `cn.nabr.chatwithchat.test` |
| FileProvider authority | `dev.chungjungsoo.gptmobile.fileprovider` | `cn.nabr.chatwithchat.fileprovider` |
| Debug seed Action | `dev.chungjungsoo.gptmobile.DEBUG_SEED_MEMORY_CHAT` | `cn.nabr.chatwithchat.DEBUG_SEED_MEMORY_CHAT` |
| Start-route extra | `dev.chungjungsoo.gptmobile.extra.START_ROUTE` | `cn.nabr.chatwithchat.extra.START_ROUTE` |
| Main activity class | `dev.chungjungsoo.gptmobile.presentation.ui.main.MainActivity` | `cn.nabr.chatwithchat.presentation.ui.main.MainActivity` |
| Application class | `dev.chungjungsoo.gptmobile.presentation.GPTMobileApp` | `cn.nabr.chatwithchat.presentation.ChatWithChatApp` |

### Internal Branding Identity

| Surface | Current | Target |
|---|---|---|
| Application class/file | `GPTMobileApp` / `GPTMobileApp.kt` | `ChatWithChatApp` / `ChatWithChatApp.kt` |
| Compose theme function | `GPTMobileTheme` | `ChatWithChatTheme` |
| Base XML style | `Theme.GPTMobile` | `Theme.ChatWithChat` |
| Splash XML style | `Theme.GPTMobile.Starting` | `Theme.ChatWithChat.Starting` |
| Launcher mipmap | `ic_gpt_mobile` | `ic_chat_with_chat` |
| Icon foreground | `ic_gpt_mobile_foreground` | `ic_chat_with_chat_foreground` |
| Monochrome foreground | `ic_gpt_mobile_monochrome_foreground` | `ic_chat_with_chat_monochrome_foreground` |
| No-padding drawable | `ic_gpt_mobile_no_padding` | `ic_chat_with_chat_no_padding` |
| Icon background color | `ic_gpt_mobile_background` | `ic_chat_with_chat_background` |
| Introduction logo string key | `gpt_mobile_introduction_logo` | `chat_with_chat_introduction_logo` |
| Gradle root project | `GPTMobile` | `ChatWithChat` |
| Serena project name | `gpt_mobile` | `chat_with_chat` |

当前 app display name 已经是 `ChatWithChat`。图标矢量内容也已属于当前 ChatWithChat 资产；本任务只迁移残留的文件名/resource ID/reference，不重新设计或替换视觉内容。

当前旧包路径至少包括：

```text
app/src/main/kotlin/dev/chungjungsoo/gptmobile/
app/src/main/java/dev/chungjungsoo/gptmobile/
app/src/debug/kotlin/dev/chungjungsoo/gptmobile/
app/src/test/kotlin/dev/chungjungsoo/gptmobile/
app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/
app/src/androidTest/java/dev/chungjungsoo/gptmobile/
```

2026-07-15 只读盘点中，旧包名命中文件约为：

- `app/src/main`: 350 个；
- `app/src/test`: 97 个；
- `app/src/androidTest`: 15 个；
- `app/src/debug`: 2 个；
- 另有 Gradle、ProGuard、scripts、tools、README、AGENTS 和 active docs 引用。

不要把这些数值当固定断言；Task 0 必须重新统计。

### Current Database And Storage Names

这些名称不包含需要清理的旧包身份，必须保持不变：

| Store | Current definition | Required result |
|---|---|---|
| Legacy Room file | `chat` | 保留名称，不新增 schema migration |
| Legacy Room class | `ChatDatabase`, version 2 | 类名和 schema version 保持 |
| Active Room file | `chat_v2` | 保留名称，不新增 schema migration |
| Active Room class | `ChatDatabaseV2`, version 17 | 类名和 schema version 保持 |
| Preferences DataStore | logical name `token`, normally `filesDir/datastore/token.preferences_pb` | 保持 |
| Canonical memory root | `filesDir/memory_store` | 保持 |
| Long-term memory | `memory_store/MEMORY.md` | 保持 |
| Daily memory | `memory_store/memory/YYYY-MM-DD.md` | 保持 |
| Memory staging/backups | `memory_store/.staging`, `memory_store/.backups` | 保持 |
| Attachments | `filesDir/attachments` | 保持 |
| Camera captures | `externalFilesDir/Pictures/attachments` | 保持 |
| Embedding install root | `noBackupFilesDir/memory_models/bge-small-zh-v1.5` | 保持 |
| ObjectBox vector store | `noBackupFilesDir/memory_vector_index/v1-d512` | 保持 |
| ObjectBox model | `app/objectbox-models/default.json` | 文件内容和全部 UID 保持 |
| WorkManager unique names | `memory_maintenance_*` family | 保持，不新增旧 Worker alias |

`ChatDatabase` schema 2 当前只有 `chats`、`messages`。`ChatDatabaseV2` schema 17 当前有 13 张表：

```text
chats_v2
messages_v2
platform_v2
platform_model_v2
chat_platform_model_v2
memory_maintenance_job
memory_mutation_group
memory_mutation_receipt
memory_corpus_state
memory_distillation_checkpoint
memory_chat_checkpoint
memory_pending_turn
memory_activity_log
```

这些表名均不因 package/branding 迁移改变。

旧包和新包的数据根目录不同：

```text
/data/user/0/dev.chungjungsoo.gptmobile/
/data/user/0/cn.nabr.chatwithchat/
```

因此即使数据库文件继续叫 `chat_v2`，也不会与旧应用冲突。数据库名不需要为了包迁移改成 `chatwithchat`。

### Room Schema Export Paths

当前：

```text
app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabase/1.json
app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabase/2.json

app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/1.json
...
app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/17.json
```

目标：

```text
app/schemas/cn.nabr.chatwithchat.data.database.ChatDatabase/1.json
app/schemas/cn.nabr.chatwithchat.data.database.ChatDatabase/2.json

app/schemas/cn.nabr.chatwithchat.data.database.ChatDatabaseV2/1.json
...
app/schemas/cn.nabr.chatwithchat.data.database.ChatDatabaseV2/17.json
```

这是数据库类 canonical name 的路径迁移，不是 schema 18。移动前后对应 JSON 必须 byte-identical，identity hash 不得变化。

### Known Hard-Coded Anchors

至少重新审计：

- `app/build.gradle.kts`
  - `namespace`；
  - `applicationId`；
  - `Memory16KbReleaseCompatibilityInstrumentedTest` 自定义 runner FQCN。
- `app/src/main/AndroidManifest.xml`
  - 相对 Application/Activity/Receiver 类名；
  - `${applicationId}.fileprovider`。
- `app/src/debug/AndroidManifest.xml`
  - debug seed Action。
- `MainActivity.EXTRA_START_ROUTE`。
- `app/proguard-rules.pro`。
- `app/proguard-memory-shadow-rules.pro`。
- `run-on-emulator.ps1`。
- `scripts/seed-memory-chat.ps1` 里的默认 package、MainActivity、Action、Receiver。
- `tools/memory-vector/verify-release.ps1`。
- `tools/memory-vector/run-production-hybrid-shadow.ps1`。
- `tools/memory-vector/run-process-death-harness.ps1`。
- `tools/memory-vector/run-16kb-release-compatibility.ps1`。
- `AGENTS.md`、`README.md`、`.serena/memories/suggested_commands.md`、`docs/superpowers/tool-calling.md`。
- test 中对 `targetContext.packageName`、runner component、worker FQCN 或 package 字符串的断言。

这不是封闭列表。必须用 `rg` 重新生成完整命中清单。

## Naming Contract

### Must Change

对项目自有身份做精确映射：

```text
dev.chungjungsoo.gptmobile -> cn.nabr.chatwithchat
dev/chungjungsoo/gptmobile -> cn/nabr/chatwithchat
GPTMobileApp -> ChatWithChatApp
GPTMobileTheme -> ChatWithChatTheme
Theme.GPTMobile -> Theme.ChatWithChat
ic_gpt_mobile -> ic_chat_with_chat
gpt_mobile_introduction_logo -> chat_with_chat_introduction_logo
rootProject.name = "GPTMobile" -> rootProject.name = "ChatWithChat"
project_name: "gpt_mobile" -> project_name: "chat_with_chat"
```

映射覆盖：

- Kotlin/Java `package`；
- 项目自有 Kotlin/Java `import`；
- 内联全限定类型引用；
- Gradle namespace/applicationId/runner string；
- source/test/debug 物理目录；
- Room schema canonical-name 目录；
- app-owned Intent Action/extra key；
- ProGuard/R8 patterns；
- ADB/instrumentation/test selectors；
- Application class/file、Compose theme function、XML styles；
- launcher/splash drawable、mipmap、color 和 string resource IDs/files；
- Gradle/IDE tooling project identity；
- active operational docs 和脚本默认值。

### Must Stay Unchanged

以下不是包名，不要机械替换：

```text
ChatWithChat
ChatDatabase
ChatDatabaseV2
chat
chat_v2
token
memory_store
memory_vector_index
memory_models
attachments
MEMORY.md
```

不要对通用字符串 `GPT`、provider/model 名称或 OpenAI API 概念做模糊替换。这里只清理明确属于旧应用内部品牌的 `GPTMobile*`、`gpt_mobile*` 和工程名。README/LICENSE/历史 plans 中对上游项目 **GPT Mobile** 的来源与 GPL 归属必须保留；品牌清理不能伪造 greenfield 来源。

以下第三方包也不得被替换：

- `com.google.android.aicore`；
- `com.google.android.as.oss`；
- AndroidX、Ktor、Hilt、ObjectBox、ONNX 和其他 dependency namespace。

### Historical Documentation Policy

`docs/superpowers/plans/*.md` 中早于本迁移的命令、schema 路径、哈希和完成证据是历史快照。不要全局机械改写这些文档，否则会把旧证据伪装成新包下执行的证据。

允许旧包名继续出现在：

- 本迁移提示词的 source/target 映射与历史说明；
- 迁移前的历史 `docs/superpowers/plans/*.md`；
- Git 历史。

active source/test/build/scripts 和非历史操作文档不得继续依赖旧包名。
active code/resources/settings/tooling 也不得继续定义或引用上述旧内部品牌标识；合法来源说明和历史快照除外。

## Target State

完成后的静态结构至少为：

```text
app/src/main/kotlin/cn/nabr/chatwithchat/
app/src/main/java/cn/nabr/chatwithchat/
app/src/debug/kotlin/cn/nabr/chatwithchat/
app/src/test/kotlin/cn/nabr/chatwithchat/
app/src/androidTest/kotlin/cn/nabr/chatwithchat/
app/src/androidTest/java/cn/nabr/chatwithchat/

app/schemas/cn.nabr.chatwithchat.data.database.ChatDatabase/
app/schemas/cn.nabr.chatwithchat.data.database.ChatDatabaseV2/

app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ChatWithChatApp.kt
app/src/main/res/mipmap-anydpi/ic_chat_with_chat.xml
app/src/main/res/drawable/ic_chat_with_chat_foreground.xml
app/src/main/res/drawable/ic_chat_with_chat_monochrome_foreground.xml
app/src/main/res/drawable/ic_chat_with_chat_no_padding.xml
app/src/main/res/values/ic_chat_with_chat_background.xml
```

以下旧目录必须不存在或为空且被删除：

```text
app/src/main/kotlin/dev/chungjungsoo/gptmobile/
app/src/main/java/dev/chungjungsoo/gptmobile/
app/src/debug/kotlin/dev/chungjungsoo/gptmobile/
app/src/test/kotlin/dev/chungjungsoo/gptmobile/
app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/
app/src/androidTest/java/dev/chungjungsoo/gptmobile/
app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabase/
app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/
```

最终 APK 应报告：

```text
applicationId: cn.nabr.chatwithchat
launcher: cn.nabr.chatwithchat.presentation.ui.main.MainActivity
application: cn.nabr.chatwithchat.presentation.ChatWithChatApp
file provider: cn.nabr.chatwithchat.fileprovider
test package: cn.nabr.chatwithchat.test
base theme: Theme.ChatWithChat
splash theme: Theme.ChatWithChat.Starting
launcher icon: @mipmap/ic_chat_with_chat
```

## Risk Matrix

| Risk | Required handling | Completion evidence |
|---|---|---|
| 只改 applicationId，内部 namespace 仍旧 | 完整迁移 Gradle、源码、测试和目录 | clean compile + active-surface zero reference |
| namespace 与 Manifest 相对组件错配 | 所有项目组件迁到新 package，检查 merged manifest | APK manifest + 启动成功 |
| Hilt/KSP/Room/ObjectBox 使用陈旧生成物 | `clean` 后从零生成 | clean build，不依赖旧 build 目录 |
| Room schema 被误当成 schema 18 | 只移动 canonical-name 目录 | version 17，JSON/hash byte-identical |
| KSP 又生成旧 schema 目录 | namespace 与 database class package 一致 | build 后只有新 schema 目录 |
| ObjectBox model UID 漂移 | 不编辑/重建 `default.json` | before/after SHA-256 相同 |
| R8 keep rule 留在旧 package | 更新精确 keep pattern | release/R8 与 compatibility probe 通过 |
| instrumentation class/package 不一致 | 更新 runner、test class 和 `.test` package | connected test discovery 通过 |
| WorkManager 旧 class name 兼容代码膨胀 | 新 applicationId 使用新沙箱，不保留 alias | fresh install scheduler/worker smoke |
| FileProvider authority 混用 | 保持 `${applicationId}`，检查 APK/运行时 | manifest/provider authority 为新值 |
| debug seed/通知 Intent 常量遗漏 | 更新 app-owned Action/extra 和脚本 | seed/notification navigation smoke |
| ADB/PowerShell harness 仍启动旧包 | 更新 package/component/test defaults | harness 实际执行新包 |
| Manifest/preview 仍引用 GPTMobile 类名 | 精确迁移 Application/theme function 和 imports | clean compile + manifest/preview scan |
| XML theme/resource ID 半迁移 | 用 `git mv` 保留资源历史并同步所有引用 | `processDebugResources` + resource scan |
| app icon 被误重绘或丢失 adaptive/monochrome layer | 只改文件名/ID，不改矢量内容 | before/after content hash + launcher smoke |
| 模糊替换破坏 GPT/provider 术语 | 仅使用列明的 exact mapping | diff 审计，无业务 DTO/model 改名 |
| 历史 plans 被机械改写 | 从 active-surface 检查中排除历史 plans | 历史 diff 无无意义重写 |
| 旧包字符串进入 release DEX | clean release 后分析 DEX | defined packages 不含旧根 |
| 用户 dirty 文件混入迁移 | 独立 branch/worktree，不复制 dirty 文件 | source worktree diff 原样、commit 文件清晰 |
| 顺手改数据库/许可证或重绘品牌资产 | 执行 Naming Contract 和 Non-Goals | schema/model/license/resource content diff 审计 |

## Execution Discipline

1. 先完整阅读仓库根目录 `AGENTS.md` 和本文。
2. 不要在当前 dirty `main` 直接移动 package tree。以 live `HEAD` 创建 `codex/chatwithchat-identity-migration` 独立 branch/worktree；若名称存在，使用不冲突的 `codex/...` 名称。
3. 源 worktree 的 `ModelConstants.kt` 空默认提示词改动属于用户工作。不得 reset、checkout、stash、覆盖、复制到 clean worktree 或纳入迁移提交。
4. 如果本文尚未进入 HEAD，只把本文复制到新 worktree，作为第一个 docs commit；不得复制其他 dirty 文件。
5. 使用 `git mv` 保留 source/schema/class/resource 路径历史。package 文本迁移只对精确 token `dev.chungjungsoo.gptmobile` 执行；品牌迁移只使用 Naming Contract 列出的 exact identifiers，禁止对 `GPT`、`mobile`、`chat` 或 `dev` 做模糊替换。
6. 大规模机械替换后逐类检查 diff；只允许移动/重命名本任务列出的 launcher/splash XML 资产，不要改模型二进制、第三方 bundle 或历史 plans。
7. 所有 `git add` 使用显式路径；禁止 `git add .` 和 `git add -A`。
8. 迁移生产源码是原子编译单元。不要为了提交很小而留下无法编译的半迁移 package tree。
9. 每个任务完成后先运行最小相关验证再提交，并及时推送 dedicated branch。
10. 不要 merge 到 `main`，除非用户另行明确要求。
11. 测试失败时修复真正的 package/生成/脚本回归；不要删除测试、放宽断言或改数据库/业务逻辑获得绿灯。
12. 所有删除/清数据只允许发生在明确的 disposable emulator/test device；不得清理源工作区或真实用户设备。

## Implementation Tasks

### Task 0: Freeze Baseline, Isolation And Hash Evidence

- [ ] 记录 `git rev-parse HEAD`、`git status --short --branch`、`git rev-list --left-right --count HEAD...origin/main`、remote refs。
- [ ] 记录源 worktree 的 `ModelConstants.kt` diff 和 SHA-256，证明独立 worktree 不包含该改动。
- [ ] 从 live HEAD 创建独立 branch/worktree；不要从落后的 remote ref 猜测基线。
- [ ] 重新统计旧包名命中的文件数、文本次数和六个 source roots 的文件数。
- [ ] 重新统计 active code/resources/settings/tooling 中 `GPTMobile*`、`gpt_mobile*` 和 `ic_gpt_mobile*` 的定义、引用与文件名。
- [ ] 记录 `namespace`、`applicationId`、test runner、Room versions、数据库文件名。
- [ ] 记录两套 Room schema 所有 JSON 的相对路径、SHA-256 和 identity hash。
- [ ] 记录 `app/objectbox-models/default.json` SHA-256。
- [ ] 记录三个品牌图标矢量 drawable 和 adaptive/monochrome 配置的路径与 SHA-256，确保本任务只迁移内部资源名、不重绘内容。
- [ ] 记录生产 embedding model/tokenizer 资产现有验证结果或 hashes；本任务不得重新 provision。
- [ ] 运行 baseline JVM test、Kotlin compile、AndroidTest compile、debug 和 release/R8；把既有失败与任务回归区分。
- [ ] 运行 `adb devices`。没有设备时记录事实，runtime 阶段启动 disposable emulator，不以“没有连接设备”为由停止。

建议的 baseline 命令：

```powershell
git status --short --branch
git rev-parse HEAD
git rev-list --left-right --count HEAD...origin/main
git remote -v

rg -l --hidden --glob '!**/build/**' --glob '!**/.git/**' 'dev\.chungjungsoo\.gptmobile' .
rg -n --hidden --glob '!**/build/**' --glob '!**/.git/**' 'dev\.chungjungsoo\.gptmobile' .

Get-ChildItem 'app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabase' -File |
    Sort-Object Name | Get-FileHash -Algorithm SHA256
Get-ChildItem 'app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2' -File |
    Sort-Object Name | Get-FileHash -Algorithm SHA256
Get-FileHash 'app/objectbox-models/default.json' -Algorithm SHA256

./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintVitalRelease
git diff --check
adb devices
```

如果 baseline 某项因环境失败，先确认是否为已存在问题。只要能在本任务边界内修复就继续；不得默默降低最终门禁。

### Task 1: Migrate Production Namespace, Build Identity And Room Schema Paths

- [ ] 创建目标 parent directories，然后用 `git mv` 移动 `main/kotlin`、`main/java` 和 `debug/kotlin` 的旧 package roots。
- [ ] 将上述源文件的 `package`、project import 和内联项目 FQCN 精确迁到 `cn.nabr.chatwithchat`。
- [ ] 把 `app/build.gradle.kts` 的 `namespace` 改为 `cn.nabr.chatwithchat`。
- [ ] 把 `applicationId` 改为 `cn.nabr.chatwithchat`。
- [ ] 更新 `memoryTestInstrumentationRunner` 的自定义 class FQCN。
- [ ] 保持 main Manifest 的相对组件名称；确认它们现在解析为新 namespace 下的类。
- [ ] 保持 FileProvider `${applicationId}.fileprovider` 表达式，不硬编码第二份 authority。
- [ ] 更新 debug seed Action 和 `MainActivity.EXTRA_START_ROUTE`。
- [ ] 更新 ProGuard/R8 中项目自有 class patterns。
- [ ] 用 `git mv` 把两套 Room schema 目录迁到新 database class canonical name。
- [ ] 证明所有 schema JSON 内容和 identity hash不变，Room version 仍分别为 2 和 17。
- [ ] 证明数据库文件名仍是 `chat`/`chat_v2`，没有新增 migration 或 destructive fallback。
- [ ] 保持 ObjectBox `default.json`、memory/model/attachment directory constants 原样。
- [ ] 删除 build outputs 后 clean compile，确认 Hilt、Room、KSP、ObjectBox 从新 package 生成。

目录移动目标：

```text
app/src/main/kotlin/cn/nabr/chatwithchat/
app/src/main/java/cn/nabr/chatwithchat/
app/src/debug/kotlin/cn/nabr/chatwithchat/
app/schemas/cn.nabr.chatwithchat.data.database.ChatDatabase/
app/schemas/cn.nabr.chatwithchat.data.database.ChatDatabaseV2/
```

Task 1 内部门禁：

```powershell
rg -n 'dev\.chungjungsoo\.gptmobile' app/src/main app/src/debug app/build.gradle.kts app/proguard-rules.pro app/proguard-memory-shadow-rules.pro
./gradlew.bat clean :app:compileDebugKotlin
./gradlew.bat :app:assembleDebug :app:assembleRelease
git diff --check
```

第一条在 production/debug/build/proguard active surface 应为零。它不扫描本文和历史 plans。

### Task 2: Migrate Unit And Instrumentation Test Packages

- [ ] 用 `git mv` 移动 `test/kotlin`、`androidTest/kotlin` 和 `androidTest/java` package roots。
- [ ] 更新所有 test package/import/inline FQCN。
- [ ] 更新 `ExampleInstrumentedTest` 或其他 package identity 断言。
- [ ] 更新 MigrationTestHelper/schema lookup 所依赖的数据库 canonical name。
- [ ] 更新 worker class name、instrumentation component 和 custom runner 断言。
- [ ] 确认 Android Gradle 生成的 test application ID 是 `cn.nabr.chatwithchat.test`，不手工添加重复 `testApplicationId`，除非 live 配置确实要求。
- [ ] 运行完整 JVM tests 和 AndroidTest compile。
- [ ] 有 emulator 时运行 database migration、memory recovery、ObjectBox canary 和核心 connected tests。

Task 2 内部门禁：

```powershell
rg -n 'dev\.chungjungsoo\.gptmobile' app/src/test app/src/androidTest
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:compileDebugAndroidTestKotlin
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.nabr.chatwithchat.data.database.ChatDatabaseV2MigrationInstrumentedTest
git diff --check
```

无设备时先完成 compile；connected gate 延后到 Task 6 的 disposable emulator，不得永久跳过。

### Task 3: Rename Internal GPT Mobile Classes, Themes And Resources

- [ ] 用 `git mv` 把 `presentation/GPTMobileApp.kt` 改为 `presentation/ChatWithChatApp.kt`，类名改为 `ChatWithChatApp`，Manifest application reference 同步更新。
- [ ] 把 Compose `GPTMobileTheme` 精确改为 `ChatWithChatTheme`，更新 MainActivity 和全部 preview/import/call site；不要改 Theme.kt 的配色、动态主题或暗色逻辑。
- [ ] 把 XML styles `Theme.GPTMobile` / `Theme.GPTMobile.Starting` 改为 `Theme.ChatWithChat` / `Theme.ChatWithChat.Starting`，同步 main/night themes 和 Manifest。
- [ ] 用 `git mv` 迁移以下资源文件，并同步所有 resource references：
  - `mipmap-anydpi/ic_gpt_mobile.xml` -> `ic_chat_with_chat.xml`；
  - `drawable/ic_gpt_mobile_foreground.xml` -> `ic_chat_with_chat_foreground.xml`；
  - `drawable/ic_gpt_mobile_monochrome_foreground.xml` -> `ic_chat_with_chat_monochrome_foreground.xml`；
  - `drawable/ic_gpt_mobile_no_padding.xml` -> `ic_chat_with_chat_no_padding.xml`；
  - `values/ic_gpt_mobile_background.xml` -> `ic_chat_with_chat_background.xml`，内部 color name 同步迁移。
- [ ] 更新 `splash_icon_inset.xml`、adaptive icon、themes 和 Manifest 的 icon/color/drawable references。
- [ ] 把所有 locale 中的 string key `gpt_mobile_introduction_logo` 迁为 `chat_with_chat_introduction_logo`；保留当前翻译 value，不借机改多语言文案。
- [ ] 把 `settings.gradle.kts` 的 `rootProject.name` 改为 `ChatWithChat`。
- [ ] 把 `.serena/project.yml` 的 active project name 从 `gpt_mobile` 改为 `chat_with_chat`（若仍为 tracked/live 配置）。
- [ ] 不重绘图标 vector path，不删除 adaptive/monochrome layer，不修改 splash timing/颜色值。
- [ ] 不替换 provider/model/API 中合法的 `GPT` 术语，不删除 README/LICENSE 的上游 GPT Mobile 来源。
- [ ] 运行 resource processing、clean compile、debug assemble 和 preview-related compile coverage。

Task 3 内部门禁：

```powershell
rg -n 'GPTMobile|GptMobile|gpt_mobile|ic_gpt_mobile|Theme\.GPTMobile' app/src/main app/src/debug settings.gradle.kts .serena/project.yml
rg --files app/src/main app/src/debug | rg 'GPTMobile|GptMobile|gpt_mobile|ic_gpt_mobile'
./gradlew.bat :app:processDebugResources :app:compileDebugKotlin :app:assembleDebug
git diff --check
```

第一条 active surface 应为零。`gptmobile` 旧 package 已由 Task 1/2 的独立检查负责；不要用宽泛的 `GPT` 搜索误伤模型名。

### Task 4: Update Package-Bound Automation And Harnesses

- [ ] `run-on-emulator.ps1` 默认 package 改为 `cn.nabr.chatwithchat`；相对 ActivityName 可保持。
- [ ] `scripts/seed-memory-chat.ps1` 默认 package、MainActivity FQCN、debug Action 和 Receiver FQCN 改为新值。
- [ ] 保持 seed script 的数据库路径 `databases/chat_v2` 和 canonical memory path 不变。
- [ ] 更新 `tools/memory-vector/verify-release.ps1` 的 instrumentation class filters。
- [ ] 更新 `run-production-hybrid-shadow.ps1` 的 app package、test package 和 test class。
- [ ] 更新 `run-process-death-harness.ps1` 的 app package、instrumentation component 和 test class。
- [ ] 更新 `run-16kb-release-compatibility.ps1` 的 app package、test package 和 runner class。
- [ ] 重新审计 `build-apk.ps1`、GitHub Actions、Gradle properties 和其他脚本；没有旧硬编码时不要制造无意义 diff。
- [ ] 运行现有 Pester tests；没有 Pester 时至少完成 PowerShell parse/help/static validation并如实记录。
- [ ] 在 Task 6 对每个有环境前置条件的 harness 做真实运行，不以字符串替换代替验证。

静态门禁：

```powershell
rg -n 'dev\.chungjungsoo\.gptmobile' run-on-emulator.ps1 build-apk.ps1 scripts tools .github
```

active automation 应为零命中。

### Task 5: Update Active Operational Documentation

- [ ] 更新 `AGENTS.md` 中 test class、instrumented class 和 project import 示例。
- [ ] 更新 `README.md` 中“内部仍保留旧包名”的现状说明；继续保留 GPT Mobile 来源和 GPL-3.0 声明。
- [ ] 更新 README/active docs 中仍把 `GPTMobileApp`、旧 Theme/resource ID 或 `rootProject.name` 描述为当前值的操作性说明；上游来源语句保持。
- [ ] 更新 `.serena/memories/suggested_commands.md` 中 active test selector（若该文件仍 tracked/live）。
- [ ] 更新 `docs/superpowers/tool-calling.md` 中 active test commands。
- [ ] 审计其他非历史 operational docs；只改会被当前开发流程实际执行的命令。
- [ ] 不批量改写本迁移前的 `docs/superpowers/plans/*.md`、已记录 hashes 或完成报告。
- [ ] 不改 `LICENSE`，不把项目描述为 MIT 或独立 greenfield 项目。

active-surface 零引用检查：

```powershell
rg -n --hidden --glob '!**/build/**' --glob '!**/.git/**' --glob '!docs/superpowers/plans/**' 'dev\.chungjungsoo\.gptmobile' .
```

预期为零。随后单独运行：

```powershell
rg -n 'dev\.chungjungsoo\.gptmobile' docs/superpowers/plans
```

第二条允许命中本文和历史快照，不得据此改写历史证据。

### Task 6: Clean Build, Artifact Identity And Disposable-Emulator Verification

- [ ] 运行 clean 全量 JVM tests、AndroidTest compile、debug、release R8、lint vital。
- [ ] 运行生产模型 checksum/native packaging verification；不得替换已 pin 资产。
- [ ] 用 `apkanalyzer`（或等价 Android SDK 工具）检查 debug/release APK application ID、manifest components、provider authority、DEX packages、application class 和 theme/icon resources。
- [ ] 证明 release DEX defined packages 不含 `dev.chungjungsoo.gptmobile`。
- [ ] 启动 disposable emulator；记录 serial、API、ABI 和 page size。
- [ ] 仅在该 disposable target 上卸载旧包和任何旧的新包测试安装，安装 fresh debug APK。
- [ ] 验证 `pm path cn.nabr.chatwithchat`、launch、PID、foreground component、force-stop 和 cold start。
- [ ] 验证 `run-as cn.nabr.chatwithchat pwd` 指向新 sandbox。
- [ ] 运行更新后的 seed script，确认 `chat_v2`、`memory_store/MEMORY.md` 和记忆页面/导出基本路径可用。
- [ ] 验证 FileProvider authority 是 `cn.nabr.chatwithchat.fileprovider`。
- [ ] 运行 connected migration tests、ObjectBox canary/round-trip、Hybrid shadow、process-death 和 16 KB/release compatibility harness；按 live prerequisites 执行并报告。
- [ ] 强停、冷启动和再次启动，确认 Room、DataStore、WorkManager、ObjectBox/lexical fallback 没有 package migration 回归。

建议命令（SDK/tool 路径按 live 环境调整）：

```powershell
./gradlew.bat clean :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintVitalRelease
./tools/memory-vector/verify-release.ps1 -SkipBuild

$Apk = Resolve-Path 'app/build/outputs/apk/debug/app-debug.apk'
apkanalyzer manifest application-id $Apk
apkanalyzer manifest print $Apk | Select-String 'cn.nabr.chatwithchat|fileprovider|MainActivity|ChatWithChatApp|Theme.ChatWithChat|ic_chat_with_chat'
apkanalyzer dex packages --defined-only $Apk | Select-String 'dev.chungjungsoo.gptmobile|cn.nabr.chatwithchat'

adb devices
adb -s <serial> uninstall dev.chungjungsoo.gptmobile
adb -s <serial> uninstall cn.nabr.chatwithchat
adb -s <serial> install $Apk
adb -s <serial> shell pm path cn.nabr.chatwithchat
adb -s <serial> shell am start -W -n 'cn.nabr.chatwithchat/.presentation.ui.main.MainActivity'
adb -s <serial> shell pidof cn.nabr.chatwithchat
adb -s <serial> shell run-as cn.nabr.chatwithchat pwd
adb -s <serial> shell run-as cn.nabr.chatwithchat ls databases
adb -s <serial> shell run-as cn.nabr.chatwithchat ls files

./run-on-emulator.ps1 -SkipBuild
./scripts/seed-memory-chat.ps1
./tools/memory-vector/run-production-hybrid-shadow.ps1
./tools/memory-vector/run-process-death-harness.ps1
./tools/memory-vector/run-16kb-release-compatibility.ps1
```

`adb uninstall` 返回 package 不存在不是失败。禁止在真实用户设备上无确认执行这些卸载命令。

### Task 7: Final Audit, Evidence, Commits And Push

- [ ] 运行 active-surface old-package zero-reference 检查。
- [ ] 证明旧 source/schema directories 已删除，新 directories 完整。
- [ ] 比较 Room 1-17 JSON before/after SHA-256；只允许路径变化。
- [ ] 比较 ObjectBox `default.json` before/after SHA-256；必须相同。
- [ ] 确认 Room versions、DB_NAME、DataStore、memory/vector/model/attachment constants 未变。
- [ ] 确认 `LICENSE` 和 README GPL 来源说明未被删除。
- [ ] 确认 active code/resources/settings/tooling 中旧 `GPTMobile*`/`gpt_mobile*`/`ic_gpt_mobile*` 标识为零，并证明图标矢量内容未被重绘。
- [ ] 运行 `git diff --check`、ktlint（若仓库可用）和最终 status。
- [ ] 审计提交只包含 package migration、测试/脚本适配和必要 docs；不得包含源 worktree 用户改动。
- [ ] 逐片提交并推送 dedicated branch，报告准确 remote ref；不要 merge main。

## Required Verification Commands

执行 Agent可按 live SDK、测试类和脚本参数调整命令，但不得降低语义覆盖：

```powershell
# Static identity
rg -n 'dev\.chungjungsoo\.gptmobile' app/src/main app/src/debug app/src/test app/src/androidTest app/build.gradle.kts app/proguard-rules.pro app/proguard-memory-shadow-rules.pro
rg -n 'dev\.chungjungsoo\.gptmobile' run-on-emulator.ps1 build-apk.ps1 scripts tools .github AGENTS.md README.md .serena/memories/suggested_commands.md docs/superpowers/tool-calling.md

# Expected new identity
rg -n 'cn\.nabr\.chatwithchat' app/build.gradle.kts app/src/main app/src/debug app/src/test app/src/androidTest app/proguard-rules.pro app/proguard-memory-shadow-rules.pro scripts tools run-on-emulator.ps1

# Old internal branding must be absent from active surfaces
rg -n 'GPTMobile|GptMobile|gpt_mobile|ic_gpt_mobile|Theme\.GPTMobile' app/src/main app/src/debug settings.gradle.kts .serena/project.yml
rg --files app/src/main app/src/debug | rg 'GPTMobile|GptMobile|gpt_mobile|ic_gpt_mobile'
rg -n 'ChatWithChatApp|ChatWithChatTheme|Theme\.ChatWithChat|ic_chat_with_chat|chat_with_chat_introduction_logo' app/src/main settings.gradle.kts .serena/project.yml

# Unit / compile / artifacts
./gradlew.bat clean :app:testDebugUnitTest
./gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew.bat :app:assembleDebug :app:assembleRelease :app:lintVitalRelease

# Focused Room and memory coverage
./gradlew.bat :app:testDebugUnitTest --tests 'cn.nabr.chatwithchat.data.database.ChatDatabaseV2MigrationsTest'
./gradlew.bat :app:testDebugUnitTest --tests '*Memory*'
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=cn.nabr.chatwithchat.data.database.ChatDatabaseV2MigrationInstrumentedTest

# Release/harness
./tools/memory-vector/verify-release.ps1 -SkipBuild
./tools/memory-vector/run-production-hybrid-shadow.ps1
./tools/memory-vector/run-process-death-harness.ps1
./tools/memory-vector/run-16kb-release-compatibility.ps1

# Git quality
git diff --check
git status --short --branch
```

第一组旧身份检查必须在 active surface 为零。历史 plans 和本文中的 source/target 映射是显式例外。

## Required End-To-End Test Matrix

| Area | Case | Expected |
|---|---|---|
| Gradle | clean configuration | namespace/applicationId 都是 `cn.nabr.chatwithchat` |
| Source | production/debug/test trees | package、imports、目录都在 `cn/nabr/chatwithchat` |
| Generated | clean Hilt/Room/KSP/ObjectBox | 从零生成，无旧 FQCN 依赖 |
| Manifest | debug/release APK | application、launcher、receivers 属于新 namespace |
| Application | manifest/runtime | class 为 `cn.nabr.chatwithchat.presentation.ChatWithChatApp` |
| Theme | base/splash/Compose | 使用 `ChatWithChatTheme` 和 `Theme.ChatWithChat*` |
| Launcher | adaptive/monochrome/splash | `ic_chat_with_chat*` 引用完整，视觉内容不变 |
| Resources | active IDs/files | `gpt_mobile*`/`ic_gpt_mobile*` 定义与引用为零 |
| Project tooling | Gradle/Serena | `ChatWithChat` / `chat_with_chat` |
| Provider | FileProvider | authority 为 `cn.nabr.chatwithchat.fileprovider` |
| Test APK | instrumentation identity | package 为 `cn.nabr.chatwithchat.test`，classes 可发现 |
| Room | schema export dirs | 两套目录使用新 canonical name |
| Room | schema history | 1-2 和 1-17 JSON byte-identical，identity hash 不变 |
| Room | runtime open/reopen | `chat_v2` schema 17 正常，FK/integrity tests 通过 |
| ObjectBox | model and store | `default.json` UID/hash 不变，open/query/reopen 通过 |
| DataStore | preferences | logical name `token` 不变，设置可写/重启可读 |
| Memory files | seed/read/export | `memory_store/MEMORY.md` 路径和语义不变 |
| Embedding | artifact install | `memory_models/bge-small-zh-v1.5` 和 hashes 不变 |
| Vector | index build/reopen | `memory_vector_index/v1-d512` 正常 |
| WorkManager | enqueue/run/restart | 新 Worker FQCN 可执行，无旧 alias 需求 |
| Debug action | seed broadcast | 新 Action/Receiver 命中，脚本成功 |
| Notification | memory route Intent | 新 extra key 可导航到 Memory |
| Scripts | emulator/seed/hybrid/process-death/16 KB | 默认 app/test/class 全是新身份且真实运行 |
| APK | DEX package analysis | defined packages 有新根、无旧根 |
| Runtime | fresh install | `pm path`、PID、foreground、cold start 全部是新包 |
| Runtime | old/new relationship | 不做跨包升级；旧数据缺失/可共存是授权结果 |
| Release | R8/compatibility probe | release 构建和反射入口通过 |
| Docs | active commands | 使用新 FQCN；历史 plans 保留原证据 |
| Git | isolation | 用户 `ModelConstants.kt` dirty diff 未被吸收或丢失 |

## Non-Goals

- 不把 GPL-3.0 改成 MIT，不删除上游来源或第三方 notices。
- 不重写、clean-room 实现或重新设计 GPT Mobile/ChatWithChat 功能。
- 不删除 README/LICENSE/历史 plans 中对上游 **GPT Mobile** 的合法来源、版权和 GPL 说明。
- 不重绘 app icon/vector path、不更换视觉资产、不改截图；只迁移 `ic_gpt_mobile*` 文件名和 resource IDs。
- 不改 app display name（已经是 `ChatWithChat`）或多语言 value；只迁移明确列出的旧 string key。
- 不改 `versionName`、`versionCode`、release signing key、Play listing 或 CI release policy。
- 不改 `ChatDatabase`/`ChatDatabaseV2` 类名、`chat`/`chat_v2` 文件名、表名或 schema version。
- 不新增 Room migration 17->18，不 squash/rewrite 历史 migrations。
- 不迁移旧应用数据，不实现旧包到新包的 backup/export/import。
- 不保留旧 Worker wrapper、旧 receiver、旧 provider authority 或双 package runtime。
- 不改 ObjectBox entities/UID、embedding model、tokenizer、HNSW、RRF/MMR 或 Hybrid 策略。
- 不改 WorkManager unique names、notification channel IDs 或 memory job semantics。
- 不改 attachment、memory、DataStore、model install directory names。
- 不批量重写迁移前的历史 `docs/superpowers/plans/*.md`。
- 不借机升级 Gradle、AGP、Kotlin、Compose、Room、ObjectBox、ONNX 或其他依赖。
- 不修改聊天、设置、工具、搜索、记忆、token accounting 或 provider product behavior。
- 不 merge 到 `main`，除非用户另行明确要求。

## Stop Conditions

只有在合理修复和重试后仍出现以下情况，才停止并向用户报告：

- 无法建立独立 worktree，且继续会覆盖、丢弃或吸收源工作区 `ModelConstants.kt` 用户改动；
- clean build 后 Hilt、Room、KSP 或 ObjectBox 仍要求旧 FQCN，且无法在纯 package 边界内修复；
- Room schema JSON 内容或 identity hash 发生漂移，必须改变 schema/version 才能继续；
- ObjectBox `default.json` UID/hash 发生变化或生成器试图重建 model；
- APK application ID、Manifest 组件、provider authority 或 test package 仍混用新旧身份；
- release R8/compatibility probe 因 package keep/reflection 迁移在合理修复后仍失败；
- instrumentation discovery、seed、Hybrid、process-death 或 16 KB harness 仍依赖旧 package 且无法在脚本适配范围修复；
- active source/test/build/script 中仍有无法解释的旧包名硬编码；
- 完成任务必须扩大到数据库 schema、业务逻辑、依赖升级、视觉资产重绘或许可证重构。
- exact mapping 完成后 active code/resource/tooling 仍有旧 GPT Mobile 内部标识，且无法在不误伤合法 provider/model `GPT` 术语的情况下清理。

以下不是停止理由：

- 用户不保留旧 APK 数据；
- 旧包和新包可同时安装；
- 无法通过 `adb install -r` 从旧包升级到新包；
- 历史 plans 仍记录旧包名；
- Task 0 暂时没有连接设备；
- release APK 尚未正式签名；
- 真实用户升级链不存在。

无设备时应启动 disposable emulator 完成 fresh-install/runtime gate。正式签名不是包迁移代码门禁，但 release R8 和 artifact identity 仍必须验证。

## Commit Sequence

建议保持以下可回滚边界，每片提交前运行对应最小验证并及时推送：

1. `docs: add complete package migration execution prompt`
2. `refactor: migrate app namespace and production package`
   - Gradle identity、main/debug Kotlin/Java、Manifest constants、ProGuard、Room schema directories；
   - 这是一个必须保持编译一致的原子 slice。
3. `test: migrate unit and instrumentation packages`
   - test/androidTest directories、package/imports、runner/classes/assertions。
4. `refactor: replace legacy GPT Mobile internal branding`
   - Application class、Compose/XML themes、launcher/splash resource IDs、root project/tooling names；不重绘资产。
5. `build: update Android package automation`
   - emulator、seed、Hybrid shadow、process-death、release verify、16 KB scripts。
6. `docs: update active identity references`
   - AGENTS、README、active tool docs/commands；历史 plans 不重写。
7. `test: verify cn.nabr.chatwithchat artifacts and runtime`
   - 只包含必要测试/evidence/doc 状态，不混入产品功能。

如果 Task 2 必须与 Task 1 合并才能保持 build green，可以合并这两个相邻提交；不得为了形式拆出无法编译的中间状态，也不得把全部 docs/scripts/runtime evidence 压成一个不可审阅大提交。

## Completion Report

最终报告必须按以下结构给出真实值：

```text
Baseline and isolation:
- source HEAD / origin divergence:
- implementation branch/worktree:
- pre-existing ModelConstants.kt diff preserved:
- old-package baseline file/match counts:

Identity migration:
- namespace:
- applicationId:
- test package:
- launcher/application classes:
- FileProvider authority:
- debug Action / start-route extra:
- source/test directories moved:

Internal branding migration:
- Application class/file:
- Compose/XML theme names:
- launcher/splash drawable, mipmap, color and string IDs:
- root project / Serena name:
- old active-brand scan:
- proof that icon/vector content was not redesigned:

Storage invariants:
- Room file names and versions:
- Room schema directory mapping:
- schema JSON before/after hashes:
- ObjectBox default.json before/after hash:
- DataStore/memory/model/vector/attachment names:
- confirmation that no cross-package data migration was added:

Static and generated proof:
- active-surface old-package scan:
- historical-doc exceptions:
- clean Hilt/Room/KSP/ObjectBox generation:
- release DEX package scan:

Tests and artifacts:
- exact Gradle commands/results:
- debug/release APK paths and SHA-256:
- manifest/application ID evidence:
- instrumentation package/discovery:
- release R8/compatibility result:

Runtime proof:
- emulator/device serial, API, ABI, page size:
- fresh install / pm path / launch / PID / foreground:
- run-as sandbox path:
- chat_v2/DataStore/memory/ObjectBox smoke:
- seed/hybrid/process-death/16 KB harness results:
- intentionally unverified items:

Git:
- commits:
- remote branch/ref:
- final status:
- main merge status (must be not merged unless separately authorized):
```

不得在以下任一情况存在时宣布完成：旧 source tree 仍是生产输入、active scripts 仍默认旧包、Manifest/资源仍使用 `GPTMobile*`/`gpt_mobile*`、Room schema JSON 漂移、ObjectBox UID 改变、只验证 debug 未验证 release、只看 Gradle 配置未检查 APK、或只完成 fresh compile 未完成可用 emulator runtime gate。

## Copy-Paste Prompt For A Fresh Implementation Conversation

```text
在 E:\code\ChatWithChat 工作。

先完整阅读：
1. E:\code\ChatWithChat\AGENTS.md
2. E:\code\ChatWithChat\docs\superpowers\plans\2026-07-15-chatwithchat-package-namespace-migration-prompt.md

第二份文档是本次实现的权威范围。不要再停留在风险解释、只改 applicationId 或另写计划，直接按文档完成完整 package/namespace 迁移、GPT Mobile 内部品牌标识清理、clean build、artifact/runtime 验证、分片提交和 dedicated branch 推送。

用户已明确授权：应用尚未正式发布，不保留旧 APK dev.chungjungsoo.gptmobile 的任何数据，接受新包是独立应用。目标包名精确为 cn.nabr.chatwithchat。不要实现跨包数据库/DataStore/Worker/FileProvider 兼容桥，不要做旧包到新包的 adb install -r 升级测试。

完整迁移 applicationId、namespace、main/debug/test/androidTest Kotlin/Java package 与目录、Room schema canonical-name 目录、Manifest 自有 Action/extra、ProGuard/R8、instrumentation runner/classes、emulator/seed/memory-vector 脚本和 active operational docs。同时把 GPTMobileApp、GPTMobileTheme、Theme.GPTMobile*、ic_gpt_mobile*、gpt_mobile_introduction_logo、Gradle/Serena 项目名精确迁为 ChatWithChat 对应命名。

必须保持 chat、chat_v2、ChatDatabase/ChatDatabaseV2、token、memory_store、memory_models、memory_vector_index/v1-d512、attachments、MEMORY.md、Room schema 17、历史 migration SQL、ObjectBox default.json UID、模型资产和 WorkManager 业务标识不变。只重命名内部品牌资源标识，不重绘图标，不改 GPL-3.0、上游来源、版本号、UI 行为、业务逻辑或依赖。

当前源 main 有用户未提交的 ModelConstants.kt 改动。不得 reset、stash、覆盖或纳入提交。先从 live HEAD 创建 codex/chatwithchat-identity-migration 独立 branch/worktree；如果本文尚未进入 HEAD，只复制本文作为第一个 docs commit。显式逐文件 stage，禁止 git add . / git add -A，不要 merge main。

必须完成 active-surface 旧包名和旧内部品牌标识零引用、clean Hilt/Room/KSP/ObjectBox/resources 生成、Room 1-17 schema JSON byte-identical/hash、ObjectBox model hash、完整 JVM/AndroidTest compile、debug/release R8、APK manifest/DEX/theme/icon identity、新 test package、disposable emulator fresh install、pm path/launch/PID/foreground/run-as、FileProvider、seed、Room/DataStore/memory/ObjectBox、Hybrid/process-death/16 KB harness 验证。

历史 docs/superpowers/plans/*.md 是迁移前证据，不做全局机械重写。遇到问题按文档 Stop Conditions 修复和收敛；没有现成设备时启动 disposable emulator，不得以旧数据不保留或旧包无法原地升级为理由停止。最终按 Completion Report 给出 hashes、命令结果、APK/runtime 证据、提交和远端分支。
```
