# 原生富内容 UI v1 设计

日期：2026-10-16。调查基线：`main` / `878f383c`。
状态：设计稿，尚未实施。配套执行计划：[执行计划](../plans/2026-10-16-native-rich-ui-implementation-plan.md)。

## 1. 已确定的范围

- 输入采用 B：XML 式标签；不执行 JavaScript，不使用 HTML 渲染整条回答。
- 第一版交互做到 L2：本地状态和用户主动回传模型都在范围内。
- 一个全局开关，默认开启，覆盖所有聊天模型；不做模型白名单。
- Kotlin 解析、Compose 原生渲染，正文原文仍存在 `MessageV2.content` 和版本记录中。
- 第一版包含 card、row/col、metric、callout、steps，以及 segmented、tabs、collapse、slider、button。
- 图表、任意计算表达式、网页沙箱、远程图片组件不进入 v1；A 格式兼容适配器推迟，不同时维护两套生成协议。

本文设计依据是当前仓库。用户提供的 ChatGPT UI 描述仅作为需求参考，本文不把第三方逆向细节当作已核实的官方协议。

## 2. 当前调用链与差距

以下路径均相对于 `app/src/main/kotlin/cn/nabr/chatwithchat/`。

| 实际入口 | 当前行为 | 设计影响 |
| --- | --- | --- |
| `util/ApiStateFlowExtensions.kt` | `StreamingMessageBuffer` 以 50ms 窗口发布累计文本，并处理 checkpoint/rollback | 保留发布节奏，解析器接受累计快照；不得假设永远只追加 |
| `presentation/ui/chat/StreamingText.kt`、`ChatBubble.kt` | smoothing=false；正文只有 isTextAnimating 才进入 IncrementalChatMarkdown，实际继续全文 ChatMarkdown | P0 单独接通正文增量路径，不靠打开逐字动画开关实现 |
| `presentation/ui/chat/IncrementalThinkingMarkdown.kt` | 已提交段落与 fence 可复用；尾部为最多 4000 字符的纯文本预览 | 可复用边界策略和测试；不可原样用于正文，否则长代码/单段落被截断 |
| `presentation/ui/chat/ChatMarkdownParser.kt`、`ChatMarkdown.kt` | Markdown/DisplayMath + 行内公式占位；最终组合给现有 Markdown 引擎 | 在外层引入 RichDocument，不直接把组件塞入现有公式占位机制 |
| `presentation/ui/chat/ChatScreen.kt` | 一轮一个 LazyColumn item；FollowingLatest/ReadingHistory 区分滚动意图 | 先保留现有列表，按实际可见内容/高度合并跟随请求；拆 item 需后续实测依据 |
| `presentation/ui/chat/ChatScreen.kt` | assistantContent 来自 effectiveContent；复制、选取直接用此文本 | 显示读原文，复制/选取改用投影，编辑仍用原文 |
| `data/context/StickerSemanticProjection.kt` | semanticAssistantContent 已供上下文、压缩、token 估算、历史和导出使用 | 在此语义入口组合富内容投影，继续保留贴图语义 |
| `data/memory/MemoryTurnBatchCoordinator.kt` | 筛选和快照直接 effectiveContent | 必须另改，不能认为替换 semanticAssistantContent 已覆盖记忆 |
| `presentation/ui/chat/ChatTurnCoordinator.kt`、`data/title/ChatTitlePrompt.kt` | coordinator 组装 ChatTitleTurn，标题服务有独立 system prompt | 在组装 transcript 前投影；不要给标题服务注入组件目录 |
| `data/repository/ChatRepositoryImpl.kt` | 多个 provider/tool 路径调用 chatSystemPrompt；CoreSystemPrompt.TEXT 为固定基础 | 请求开始读取开关快照，所有回答路径使用同一富 UI prompt section |
| `data/tool/AskUserQuestionBroker.kt` | submit 只完成队列中已有 requestId 对应的 CompletableDeferred | 不是 issueNewTurn；富卡片新一轮必须经正常发送入口 |
| `data/database/entity/MessageV2.kt` | 有有效版本、修订内容，流式对象保存前 id 可能为 0 | 状态身份不能只用 messageId 或数组下标 |
| `data/repository/ChatNetworkReconnect.kt` | 断流后 StreamRollback 重放一次请求 | 错误尝试的组件和状态必须失效，不允许旧前缀混入新结果 |

这些是静态代码事实。当前没有本次真机帧时间、闪烁频次或多模型标签合规率测量，不能据此宣布性能提升。

## 3. 分层与目录

建议新增纯 Kotlin 包 `data/richcontent/`：

- `RichDocument.kt`：不可变树、节点 ID、原文 source range、schemaVersion、诊断与投影结果。
- `RichContentParser.kt`：词法扫描、容错栈、组件白名单；无 Android/Compose 依赖。
- `RichContentAccumulator.kt`：累计快照、稳定前缀、活动尾部、generation/reset。
- `RichContentProjection.kt`：树到可读 Markdown，供语义入口和界面动作使用。
- `RichComponentCatalog.kt`：组件名、属性、默认值、枚举及限额的单一注册表。

建议新增 `presentation/ui/chat/richcontent/`：

- `RichChatContent.kt`：渲染 Markdown 叶子和原生组件，错误隔离到单个节点。
- `RichContentState.kt`：状态 reducer、状态身份、输入验证；纯 Kotlin 部分可做单元测试。
- `RichContentAction.kt`：本地状态动作、生成待发送文本；ViewModel 校验后接入输入框。
- 按静态布局、指标、交互拆组件文件，不建与现有应用无关的通用 UI 框架。

这是新增设计，不是现有类名。保留 `ChatMarkdownBlock` 的公式职责；RichNode.Markdown 叶子继续交给 ChatMarkdown。思考链继续只渲染 Markdown，用户消息和工具输出不自动激活富组件。

## 4. v1 标签约定

采用 `ui-` 前缀，避免把普通 HTML、泛型或讲解标签误识别成组件。不要求整条消息包根节点。模型只在需要组件的位置输出独立块。

~~~~xml
这里仍是普通 Markdown。

<ui-card key="example" title="示例">
这段 **Markdown** 在卡片内。

```kotlin
fun main() = println("hello")
```
</ui-card>

<ui-row key="overview">
<ui-col>
### 完成情况
指标是模型提供的展示数据，不代表客户端核验过。
</ui-col>
<ui-col>
<ui-metric label="完成率" value="86.4%" delta="+12.3%" trend="up" />
</ui-col>
</ui-row>
~~~~

实现文档中的代码围栏展示不影响实际协议：实际模型输出的标签不应再包在 Markdown 代码围栏里；代码示例中的 ui 标签只当代码。

### 词法规则

1. 大小写敏感；识别小写白名单标签；属性用双引号，允许换行。属性支持 `&amp; &lt; &gt; &quot; &apos;`，不支持 DTD、外部实体或脚本。
2. 组件标签独占逻辑行；自闭合叶子也独占行。顶层开始标签只在非代码的块边界识别，嵌套标签在容器内识别。
3. Markdown fenced code、inline code、缩进代码中的 ui 标签均为字面量；普通 `<div>`、泛型比较和 `<think>` 不属于富 UI 协议。
4. Markdown 叶子保留原文，继续处理现有公式/表格/列表；组件边界终止叶子的 Markdown 作用域，不支持跨组件 fence、列表或引用定义。
5. 栈扫描器识别引号和 fence，禁止用单个正则或严格 DOM XML parser 解析整条 Markdown 消息。
6. `key` 在消息当前版本内唯一；交互节点必须有显式 key。静态节点缺 key 时用 source offset + generation 派生。重复交互 key 降为静态投影，并记录诊断。
7. 不强制模型生成 version 根标签；v1 由 ui- 前缀和 catalog 确定。以后遇到未知标签不猜测新协议含义。

### 组件目录

| 标签 | 属性 / 子节点 | 固定语义 |
| --- | --- | --- |
| ui-card | key?, title?；Markdown/组件 | 标准表面、间距、圆角 |
| ui-row | key?；1–3 个 ui-col | 宽度足够并排；窄屏按顺序竖排 |
| ui-col | Markdown/组件 | 等宽分栏；v1 不开放任意宽度/CSS |
| ui-metric | label, value, delta?, trend=up/down/neutral | value 原样展示；trend 控制方向，up/down 不自动等于有利/不利 |
| ui-callout | tone=info/success/warning/error, title?；Markdown | 客户端语义色、图标和无障碍说明 |
| ui-steps | ui-step 子节点 | 有序列表 |
| ui-step | title?；Markdown/静态组件 | 编号由客户端生成 |
| ui-segmented | key, label, default?；ui-option | 单选，只更新本地状态 |
| ui-option | value, label；无正文 | 值和标签固定字符串 |
| ui-tabs | key, default?；ui-tab | 选中 tab 值是本地状态 |
| ui-tab | value, label；Markdown/组件 | 未选面板状态保留 |
| ui-collapse | key, title, default=closed/open；Markdown/组件 | 本地折叠 |
| ui-slider | key, label, min, max, step, default, unit? | 有限十进制数；客户端验证范围及步长，不执行计算表达式 |
| ui-value | ref；自闭合 | 在容器内单独成行显示指定状态值；仅引用当前消息版本，无模板表达式 |
| ui-button | key, label, prompt, refs?；自闭合 | refs 为逗号分隔状态 key；生成普通用户消息草稿 |

metric 可加 `tone=neutral/success/warning/error`，默认 neutral；方向与好坏分开，费用上升也不能自动染绿。所有颜色、字号、行距、圆角、动效由主题 token 决定。忽略未知展示属性并记录诊断；缺少必填或属性非法时不创建可交互节点。

row 以可用宽度 >= 600dp 且每列至少 240dp 为并排条件，否则竖排；大字体下按测量结果退回竖排。组件不能突破气泡宽度。slider 至多 1000 个步进；其余数值始终是展示文本，无客户端隐式求值。

### 交互示例

```xml
<ui-card key="choice" title="选择方案">
<ui-segmented key="plan" label="方案" default="A">
<ui-option value="A" label="方案 A" />
<ui-option value="B" label="方案 B" />
</ui-segmented>
当前选择：
<ui-value ref="plan" />
<ui-button key="continue" label="继续讨论" prompt="请按所选方案展开实施步骤。" refs="plan" />
</ui-card>
```

ui-value 显示选项 label；发送快照同时包含 key、value、label。禁止 ref 循环、路径遍历、任意函数调用或跨消息引用。refs 缺失时按钮只使用 prompt；refs 指向不存在/重复/非法状态时按钮禁用并给出可读说明。

## 5. 容错和流式一致性

- `RichContentAccumulator.update(fullText, isStreaming, generation)` 接受累计文本；只扫描新增后缀，保存 tokenizer 状态。检测是否追加仍可能需要前缀比较，不承诺整个更新过程 O(delta)。
- 已闭合顶层组件一次提交成不可变树；外层容器未闭合时只保留一个加载占位，不把其内部 Markdown 提前提交到外层。首次实现不做开放容器逐层填充。
- 普通 Markdown 在安全边界提交。末尾正文不可沿用思考链 4000 字符裁尾；过长活动尾部转为稳定纯文本分段，保留完整内容和 source ranges，段长策略须通过长 fence/列表/公式验收。
- v1 倾向“结构完整再出现”；新块短淡入约 150–200ms，历史加载不重播，遵循系统动画缩放。高度动画只用于独立折叠等局部交互，不对整条长回答持续 animateContentSize。
- 未闭合/引号未完标签在流式期间缓冲；停止、错误或取消时必须终结解析，将未完成部分显示为原文文本。最终快照不可停留在加载占位。
- 错配闭合、非法必填值、未知 ui 标签：当前顶层组件退回原文可读文本，后续明确块边界恢复扫描。复杂错配以局部原文为准，不猜测并执行交互。
- 单节点解析/渲染失败不删除邻居；不要吞掉错误节点后导致正文悄悄消失。展示异常文本时用普通 Text 或代码展示，不能再递归激活同一标签。
- 文本缩短、同长度替换、前缀不匹配或显式 generation 变化：清除该尝试的树和状态。不得在回滚后继续显示上一尝试的成功组件。
- 完成时保留同一块身份，避免把整个流式列表替换成另一个最终渲染器；需要合并 Markdown 作用域时提前明确支持边界并测试视觉一致性。

初始限额：单顶层组件原文 <= 64Ki UTF-16 code units、嵌套 <= 8 层、每消息 <= 128 个组件节点、交互状态 <= 32 个、单属性 <= 4096 code units。超限组件退回原文文本，普通正文不因此被截断。限额按测试与设备回放调整；诊断仅记录代码、范围和计数，不默认记录整条回答。

## 6. 本地状态与 L2

状态由聊天 ViewModel 持有，不能仅存在可被 LazyColumn 回收的 Composable remember 中。身份至少包含 chatId、稳定 turnKey、platformUid、消息逻辑身份、revisionIdentity、generation、nodeKey。保存前 id=0 的状态用请求/轮次身份，持久化后显式映射，不重置同一成功回答。

v1 状态生命周期：同一 ViewModel 内滚出屏幕、切换模型、切换版本后回来保留各自状态；退出并重建聊天或进程恢复后按文档默认值重建。不新增 Room 表。编辑正文、重试替换及回滚使对应版本状态失效。状态只记录用户选择，不修改 content 或记忆。

两个 L2 通道必须分开：

1. 已有工具提问：继续 AskUserQuestionDialog → submitUserQuestion → AskUserQuestionBroker.submit → 原工具流程恢复。富组件不伪造 requestId/callId。
2. 富 UI button：点击后以 prompt + 白名单 refs 的当前状态快照生成可编辑的普通用户消息，填入输入框；用户点应用的发送键后走原有发送、持久化、provider、记忆流程。按钮旁明确“填入输入框”。v1 不设计点击即静默发送。

有未发送文字、附件或待处理附件时保留草稿，提示先发送/清空，不能覆盖或误携带附件。按钮不消费一次性技能；真正发送时按原有规则处理技能和会话模型选择。对比模式下仍由正常发送规则决定接收模型，并在草稿中注明来源模型。

流式未完成、保存窗口、编辑中、当前选中版本与事件来源不符时禁用回传。ViewModel 二次校验身份、canSubmitOrEdit、节点合法性和状态快照，拒绝过期事件及重复点击。取消/断流终止的回答展示静态投影，不开放交互。完成的历史消息可以生成草稿，但仍需当前会话空闲且来源版本有效。

本地选项改变不发请求。context/记忆默认只看文档声明的默认状态；不把未提交交互当成模型可知事实。用户通过按钮发送的快照才进入对话记录。复制/导出如包含状态，必须明确标为“本地未提交选择”，不能改写模型原话；v1 默认只导出文档默认值。

## 7. 语义投影与存储

RichContentProjection 输出确定性的可读 Markdown，不要求模型再生成一份 fallbackMarkdown，也不双存正文。

- card/callout 保留标题和正文；row/col 按阅读顺序线性展开；metric 保留 label、value、delta 和趋势文字。
- steps 编号；tabs 导出所有面板并标注标题；collapse 导出全部内容，不能因折叠丢信息。
- segmented/slider 保留选项/范围和声明默认值；ui-value 解析默认值；button 显示操作标签和提示内容，不冒充已执行动作。
- 非协议正文保持原样；代码里的标签不剥离。损坏组件保留原文，不能用“删除所有尖括号”式正则投影。
- 原文用于存储、编辑、修订快照和必要的原始内容 hash；UI/语义文本用于复制、选取、导出、标题 transcript、历史索引、压缩、token 估算、记忆快照和发送给其他模型的历史内容。
- 在 semanticAssistantContent 中组合富 UI 投影和贴图处理；另外修正 MemoryTurnBatchCoordinator 和 ChatTurnCoordinator 的实际输入，逐个确认 provider 的历史序列化最终接收投影。
- 不把所有 effectiveContent 调用机械替换。正文显示、保存、编辑、空消息判定和缓存失效可能依赖原文。ContextTurnHasher 的原始内容 hash 保留，不能把不同原文强行合并。
- 已存在的压缩 checkpoint、记忆任务快照和历史索引不会因函数变更自动更新。第一版用投影版本使可重建的历史派生索引失效重建；不后台重跑已完成的记忆任务、不重写历史记忆。v1 前尚无该协议产生的正式历史消息。

## 8. 全局设置与提示词

设置名“富内容回答”，布尔值 rich_content_enabled，缺省 true；经 SettingDataSource → SettingRepository → SettingViewModelV2/SettingScreen 接入。

关闭含义：后续请求不带目录；已有消息显示语义投影，交互停用；重开后恢复原文渲染。同一进行中的请求固定使用开始时的开关快照，设置变化在下一请求生效；不导致流式中间切换解析器。

独立 RichContentPromptBuilder 从 catalog 生成简短提示段，不把动态开关塞进固定 CoreSystemPrompt.TEXT。所有 chatSystemPrompt 调用路径必须显式收到同一请求设置，包括普通回答、native 工具循环结束后的回答、JSON 工具回退及无工具路径；工具决策/结构化 JSON 阶段不混入要求输出标签的指令。

标题、记忆维护、上下文摘要等后台模型不注入目录。请求 trace 可验证实际是否注入；记录静态目录 token 大小作为成本基线。指令要求“适合时使用，普通回答继续 Markdown”，不强制每轮用卡片。

全局默认开启不代表每个模型都已验证合规。损坏输出靠容错保留，不能依赖为用户静默关闭某些平台来掩盖问题。

## 9. 验收边界

解析器、投影和状态 reducer 的离线测试证明协议一致性；Compose 测试证明组件事件和状态隔离；真机回放证明布局和滚动表现；真实 provider 回放证明该模型在指定提示词下的输出兼容性。四类证据分别记录。

最低设备场景：窄屏、横屏/宽屏、深浅色、大字体、TalkBack、长 Markdown/代码/表格/公式、6+ 静态组件、展开折叠、滑块拖动、用户向上阅读、停止、断流重连、版本切换和模型对比。所有动作应有明确 label，触控区域遵循现有 Material 规范，slider 宣读范围和当前值。

不得仅凭 assembleDebug 成功宣称流式更流畅或多模型可用。不能把模型展示的数值标为客户端校验结果。
