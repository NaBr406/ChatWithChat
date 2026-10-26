# 富 UI 交互基础设施

> 阶段记录：本文描述原生交互基础设施完成时的范围。后续 55 组件采用 WebView + OpenUI Lang，见同日 OpenUI 集成报告及整理验证记录。

日期：2026-10-26。分支：`codex/intelligent-ui-rewrite`。

本轮实现可扩展的原生交互链路，暂不实现待提供的 55 个组件。生产交互注册表为空，模型提示仍只广告已有静态组件。

## 接入契约

`RichUiComponentDefinition` 将三部分放在同一份受信任的应用代码中：

- `spec`：组件标签、属性和子节点规则，供 `RichContentParser` 校验。
- `bind(node)`：声明有类型的本地字段、可导出字段和动作的显式 refs。
- `Content(scope)`：Compose 原生渲染；通过 scope 发送 SetValue / InvokeAction，不持有聊天发送器或工具执行器。

产品扩展统一注册到 `RichUiComponents.registry`。聊天宿主和渲染器使用同一注册表；仅覆盖 `LocalRichUiComponentRegistry` 适合测试渲染，不能替代宿主注册。注册表不允许替换已有静态渲染器，也不允许悄悄改写已有协议标签的 spec。

组件收到的输入是解析后的 AST，未注册、无效或未闭合的节点继续静态降级。没有脚本、表达式求值或远程 UI 执行器。

## 状态和事件

`RichInteractionRuntime` 由 ViewModel 的宿主 controller 持有，跨 Compose 离屏重建保留状态。身份包含聊天、稳定轮次、供应商、回答版本和请求 attempt；incarnation / generation token 防止旧回调跨重试、淘汰、回滚或声明变更生效。

字段支持文本、布尔、数值、单选、多选；校验类型、范围、选项白名单及长度。SetValue 只写本地 StateFlow，不改消息正文、上下文或记忆。动作仅导出同一文档中明确允许的 refs，产生不可变快照；快照含来源、文档指纹、token、事件 ID 和签发校验。

默认预算：32 个会话、每文档 128 个字段 / 64 个动作、4096 个节点、64 层 runtime 遍历、1048576 字符源文本、65536 字符本地值总量、32768 字符动作快照。parser 自身的较小嵌套限制仍有效。每代最多接受 4096 个不同事件 ID，达到上限后明确拒绝，不遗忘已处理事件。LRU 淘汰使旧 token 失效，重建恢复默认值。

追加普通文本保留值；缩短、替换前缀或 generation 变化重置。字段/动作声明变化更新 token，声明未变的已有字段值可保留。进程或 ViewModel 重建恢复默认值，不新增 Room 表。

## 聊天宿主

`handleStates` 明确报告 Success / Failed / Cancelled / Interrupted；`ChatTurnCoordinator` 只在持久化成功后携带 attempt 和终态发出 completion。宿主只为已知成功保存的来源开放交互，不从时间戳、非空正文或 idle 推断成功。

正常完成与保存证据暂存于当前 ViewModel。未知历史、取消、错误和未保存回答保持静态。当前 ViewModel 中已知的成功版本可以归档后再次绑定；稳定版本序号和内容指纹一起隔离重复文本版本。

事件在 ViewModel 中重新解析当前所选来源并复验，防止供应商/版本切换后延迟事件进入新来源。来源正文与界面实际显示内容不一致、流式进行中、忙碌、保存中或编辑中时不开放交互。

L2 动作只填入空输入框，附带来源信息。已有文本、附件、附件导入或排队问题会拒绝并提示；不覆盖草稿，不清除附件，不消费一次性技能，也不调用发送接口。用户仍通过原发送按钮确认发送。

## 后续 55 个组件

收到清单后，为每项确定标签/spec、字段类型/默认值、局部显示状态、可导出值、动作 refs、禁用态和无障碍语义。按组件族增加 definition 和契约/设备测试，再显式扩充模型提示白名单。布局、只读展示、局部交互与回填动作可共用本轮基础，不应通过一个万能动态组件绕过注册和校验。

## 验证

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 通过。
- 定向 JVM 测试 80 项通过，0 failure / error：runtime 19、组件注册 8、宿主 controller 7、流式终态矩阵 1、已有 ApiStateFlowExtensions 29、ChatTurnCoordinator 16。日志：`outputs/rich-interaction-final-build.txt`。
- 在 `emulator-5554` / `ChatWithChat_API35_16K_X86_64` 安装 debug 与测试 APK，9 项设备测试通过：交互 2、连续正文流式 3、正文/thinking 展示及原有交互回归 4。日志：`outputs/rich-interaction-final-device-tests.txt`。
- 新文件及本轮 UI 接线、coordinator、stream utility 的 ktlint 通过。ChatViewModel 全文件尚有 7 项既有格式问题；与 HEAD 基线逐项一致，本轮新增代码无 lint 错误。日志：`outputs/rich-interaction-final-lint.txt`、`outputs/rich-interaction-vm-lint-baseline.txt`。
- `git diff --check` 通过。未运行全量测试套件，未提交或推送。

设备 fixture 组件仅存在于 androidTest，不注册到产品或提示中。当前验证覆盖原生组件事件、局部状态在离屏重建后的保留、快照及旧 token 失效、流式/来源不匹配时禁用。输入框冲突和成功保存门槛由宿主单元测试覆盖；真实供应商生成的交互组件与完整聊天回填流程待具体组件接入后验收。
