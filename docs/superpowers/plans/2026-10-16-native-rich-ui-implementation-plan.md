# 原生富内容 UI v1 执行计划

日期：2026-10-16。基线：`main` / `878f383c`。
状态：尚未开始实现。用户已决定 B 标签、L2 交互、全局默认开启；其余默认行为见[设计文档](../specs/2026-10-16-native-rich-ui-design.md)。

## 工作边界

本轮只调查和写规划，未修改生产代码。调查时 tracked 工作区干净，`.codex-remote-attachments/` 与 `outputs/` 为现有未跟踪目录，保持原样。实施前重新检查分支和差异，不假定基线不变。

不新增 JS 引擎、网络请求型 UI 组件、Room 状态表、图表或 HTML 沙箱。不把所有工作塞进 ChatBubble/ChatRepositoryImpl；用纯 Kotlin 树、解析、投影、状态逻辑支撑 Compose。

## 依赖顺序

P0 正文增量地基 → P1 协议/投影 → P2 静态渲染与系统提示开关 → P3 本地交互 → P4 L2 与完整验收。

P1 必须先于对真实模型开放目录。P2–P4 开发期间不要把未实现的组件写入实际目录；v1 发布条件是全部阶段完成。已有全局默认开启决定保持不变，不能用“默认关闭实验功能”替代完成验收。

## P0：接通正文增量渲染

### 修改位置

- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/ChatBubble.kt`
- `app/src/main/kotlin/cn/nabr/chatwithchat/presentation/ui/chat/IncrementalThinkingMarkdown.kt`
- 必要时新增正文专用 accumulator/renderer，避免改坏思考链行为。
- `ChatScreen.kt` 和 `ChatScrollIntent` 对应策略文件：只调整跟随触发所必需部分。
- 现有 `IncrementalThinkingMarkdownTest`、`ChatMarkdownParserTest`、滚动策略测试。

### 任务

- [ ] 录制相同累计文本 chunk 的回放 fixture：短文、10k/50k 文本、长 fence、表格、列表、公式、工具轮次。
- [ ] 当前版本记录解析耗时/调用次数、首段显示延迟、已提交对象复用、尾块大小；有设备时记录帧时间和滚动。
- [ ] 正文路径直接按真实 isLoading/isStreaming 控制增量渲染，不启用逐字 smoothing。
- [ ] 分离正文尾块策略和思考链 4000 字符裁尾；正文完整可见，长单段不丢头部。
- [ ] 完成、取消、错误时 flush；流式与完成态保留块身份，不重新装载整篇。
- [ ] 对缩短/替换/StreamRollback 重置；已提交块只在有效追加时复用。
- [ ] 覆盖 Markdown 跨段语义：reference link、列表续段、表格分隔行、跨 chunk fence/公式；明确组件边界才结束作用域。
- [ ] 仅在 FollowingLatest 时合并跟随更新；用户上滚后不抢滚动，不给所有内容加整体高度动画。

### 通过标准

纯 Markdown 外观和完整性不回退；最终内容不截断；已提交块实例在正常追加时不变；回滚不残留前次内容；历史阅读不跳到底。保留 50ms 网络发布节奏。

P0 不要求先重构 LazyColumn item。若真机显示长 item 测量仍是主因，再独立提出分块列表方案，先测量与修复 anchor/版本切换后实施。

## P1：协议树、容错解析和语义投影

### 新增/修改位置

- 新建 `app/src/main/kotlin/cn/nabr/chatwithchat/data/richcontent/` 中的 document/catalog/parser/accumulator/projection。
- `data/context/StickerSemanticProjection.kt`：组合投影。
- `data/memory/MemoryTurnBatchCoordinator.kt`：成功回答筛选和 assistantContent 快照。
- `presentation/ui/chat/ChatTurnCoordinator.kt`：标题 transcript。
- `presentation/ui/chat/ChatScreen.kt`：复制和选取与原文显示分开。
- 审查 `data/context/`、`data/history/`、`ChatRepositoryImpl.kt` 历史序列化和 `ChatViewModel.exportChat`，补充遗漏入口。

### 任务

- [ ] 按设计固定标签、白名单属性、默认值、诊断码、限额和 source ranges。
- [ ] 状态机处理 Markdown code 保护、跨 chunk 标签/引号/entity、嵌套和非追加 reset。
- [ ] 组件闭合后提交；未闭合时占位；完成/取消后原文降级；未知/坏节点局部恢复。
- [ ] 同一 AST 同时服务渲染与投影，不实现第二个靠正则去标签的语义解析器。
- [ ] 建立 content 读路径清单：原文/语义/展示三类，检查每个 provider 实际出站历史。
- [ ] 确认派生历史索引是否需要投影版本升级及重建；保留原文 hash 失效规则，不重写已完成记忆。

### 必需测试

- [ ] 对协议样例在每一个字符边界切分输入，最终树/投影与一次性解析一致；多 chunk、重复相同快照也一致。
- [ ] 随机小 chunk、中文、emoji 代理对、CRLF、双引号与实体、fence 中伪标签、缩进代码、普通 HTML/泛型不误识别。
- [ ] 缺闭合/错配/超深/超限/重复 key/非法数值/未知标签都不崩溃，不丢后续正文。
- [ ] 截短、同长度替换、失败后重连、取消 finalize 的树和 generation 符合约定。
- [ ] 投影 golden cases：tabs 全面板、折叠内容、metric、按钮、默认状态和非法 ref；纯 Markdown 字符保持。
- [ ] 复制/导出/标题/记忆/历史/上下文/压缩/token counter 的实际输入不再携带有效 UI 控制标签，代码示例除外。

### 通过标准

关闭富内容时也能读到完整投影；损坏组件可读，节点不执行任意逻辑；跨 provider 历史回传语义一致；存储与修订保持原文。

## P2：静态组件、设置与提示词

### 修改位置

- 新建 `presentation/ui/chat/richcontent/` renderer 和静态组件。
- `ChatBubble.kt` 接入 RichChatContent，Markdown 叶子复用原有 ChatMarkdown。
- `data/datastore/SettingDataSource(.Impl).kt`、`data/repository/SettingRepository(.Impl).kt`。
- `SettingViewModelV2.kt`、`SettingScreen.kt`、中英文 `strings.xml`。
- 新建 `data/prompt/RichContentPromptBuilder.kt`，接入 `ChatRepositoryImpl` 的请求参数和 chatSystemPrompt。

### 任务

- [ ] 实现 card、row/col、metric、callout、steps；窄屏竖排、主题 token、语义与趋势分离。
- [ ] 为不可交互 AST 和不可识别节点提供独立静态投影 renderer。
- [ ] 用固定渲染 fixture 检查正文和组件前后穿插、组件内部 Markdown/公式/代码。
- [ ] 全局开关默认 true；关闭去目录 + 语义显示，重开恢复原文渲染；运行中的请求固定快照。
- [ ] 审查所有 chatSystemPrompt 调用点：普通/工具循环/JSON 回退各自的 answer 阶段覆盖，决策阶段保持结构协议。
- [ ] 标题、记忆维护和压缩等后台调用无组件目录；prompt trace 对比开/关及 token 成本。

### 通过标准

纯 Markdown 无需标签仍正常；静态组件支持流式与历史加载；小屏、大字体、深浅色均可读；关闭开关无需迁移正文数据。提示词只包含已实现 catalog，所有回答路径开关一致。

## P3：L1 本地交互

### 任务

- [ ] 实现 segmented、tabs、collapse、slider、ui-value；局部状态更新不触发 provider。
- [ ] ViewModel 持有有界状态 map；稳定轮次、模型、版本、generation、node key 联合身份；保存后映射 messageId。
- [ ] 离屏/重新组合/切换版本回来保留状态；重试/编辑/回滚使对应状态失效；进程恢复按默认值重建。
- [ ] slider 拒绝非有限值/非法范围/超步数，选项默认值不在集合时确定性回退并诊断。
- [ ] refs 只读合法本版本状态，无模板表达式、无跨消息引用。
- [ ] TalkBack 标签、范围宣读、选中态和触控区域验收；折叠动画遵循系统设置。

### 通过标准

改变状态只重组有关区域，不重解析全文；两个模型同名 key、两个版本同名 key 不串状态；未提交状态不泄露进后台记忆/上下文。历史/实时渲染共用组件和状态规则。

## P4：L2 回传与 v1 发布验收

### 任务

- [ ] ui-button 生成 prompt + refs 的明确文本快照，带来源标识；点击填入输入框，由正常发送键发起新轮。
- [ ] 处理已有草稿/附件/技能：不覆盖草稿、不清文件、不提前消费技能、不误带待上传附件。
- [ ] ViewModel 二次验证来源、当前版本、请求完成且已保存、会话空闲、状态值合法；防重和拒绝过期事件。
- [ ] 保留已有 AskUserQuestionBroker 通道并回归工具暂停/回答/取消；不把新轮按钮接到 broker.submit。
- [ ] 本地控件在不完整/错误/中断消息中退为静态，重新连接的旧 generation 控件失效。
- [ ] 验证发送快照进入持久化对话及下轮上下文，模型可以看见用户已提交的选择。
- [ ] 对比模式按应用原有模型选择发送，草稿可看清来源；历史按钮不复用过期模型路由。

### 检查命令与证据

实施时按实际新增测试类补齐命令；不是要求在仅改文档的本轮执行构建。

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.presentation.ui.chat.IncrementalThinkingMarkdownTest" --tests "cn.nabr.chatwithchat.presentation.ui.chat.ChatMarkdownParserTest"
.\gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.richcontent.*"
.\gradlew.bat :app:testDebugUnitTest --tests "cn.nabr.chatwithchat.data.context.*" --tests "cn.nabr.chatwithchat.data.history.*" --tests "cn.nabr.chatwithchat.data.title.*"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

另回归 MemoryTurnBatchCoordinator、网络重连和 provider 请求测试；Kotlin 变更按仓库 ktlint 1.3.1 配置检查。仪器测试命令需在设备就绪后运行，失败要区分设备/依赖与生产行为。

| 验收面 | 最低样本与记录 |
| --- | --- |
| 流式回放 | 与 P0 同一 fixture；parser 次数/耗时、已提交复用、终态完整性、回滚隔离 |
| 真机视觉 | 窄屏/横屏、深浅色、大字体、6+ 组件、长代码和公式、截图/录屏 |
| 滚动 | 跟随最新、主动上滚、组件整块出现、展开折叠、结束保存；记录帧时间/跳变，不能只凭肉眼宣称提升 |
| 状态和动作 | 离屏、版本/模型切换、重试、停止、重连、草稿保护、重复点击、进程恢复 |
| 真实模型 | 覆盖实际配置的 OpenAI 兼容、Anthropic、Google，以及 DeepSeek/Ollama 等大小模型；分别记录实际 model/endpoint 类型、合法率、损坏回退和提示词 token |
| 数据语义 | 复制/选取/导出、标题、记忆、压缩、历史检索和跨模型上下文；UI 控制标签不会污染正文语义 |

没有可用设备或 provider 凭据时，完成代码与离线检查并明确留下该项未验收；不能把离线 fixtures 当成真实模型或设备通过。

## 完成条件与交付

所有阶段逐项更新勾选，验证报告放入 `docs/superpowers/reports/`。报告分别列代码、离线、设备、真实模型证据，以及未完成项。达到 P4 才能称“B + L2 + 全局默认开启的 v1 已实现”；P0 完成只能称正文流式地基完成。

建议按阶段形成可独立审查的变更，每阶段提交前运行相关检查；不自动提交或部署，不改现有未跟踪产物。
