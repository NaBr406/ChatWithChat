# OpenCode DeepSeek 正文流式修复

日期：2026-10-22。分支：`codex/intelligent-ui-rewrite`。

## 根因

用户反馈 thinking 结束后长时间没有正文，随后整段出现，并确认使用 OpenCode 的 DeepSeek。源码路由中，非官方 DeepSeek 地址的 CUSTOM 供应商在存在启用工具时走 JSON 工具兼容路径；自动表情工具也可能使该条件成立。

原有路径只实时转发 thinking，正文由 `collectProviderText` 累积，直到模型请求结束、`ToolLoopOrchestrator` 解析完整 `final_answer` 后才一次发出 `Success(content)`。因此即使 Markdown 及淡入层正常工作，正文也无法逐片出现。

## 修复范围

- 增量解码标准 `{"type":"final_answer","content":"…"}` 中的正文，处理跨分片转义、Unicode 和代理对；JSON 外壳和工具参数不进入正文。
- 由工具状态机明确本轮是否可以直接展示最终回答，避免把仍需综合的草稿提前显示。
- 完整输出仍由原协议解析器校验；未通过校验的预览撤回，网络重试恢复相应检查点，最终输出去重。
- 用户主动停止时保留已经显示的部分正文。不增加模型请求，不修改富文本配色或淡入实现。
- 非标准字段顺序等输出继续由完整协议解析器处理，可能仍需等该轮输出结束。

## 验证记录

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 构建成功，debug APK 与测试 APK 已安装到 API 35 模拟器 `emulator-5554`。
- 139 项定向 JVM 测试全部通过：repository 98、JSON 增量解码 5、工具编排 30、正文淡入 6。日志：`outputs/body-stream-verification.txt`。
- 本轮修改的 Kotlin 文件通过 ktlint 1.3.1；`git diff --check` 通过。
- 模拟器回归已通过：正文淡入及链接/复制/表格操作 2 项、表格 Markdown 1 项。日志：`outputs/body-stream-reveal-regression.txt`、`outputs/body-stream-table-regression.txt`。
- 持续流式设备测试 3 项通过：普通 Markdown、富文本中的 Markdown、thinking 后切换正文。测试每 32ms 发包，经过真实 `handleStates` 和消息 StateFlow，在 `Done` 前分别观察到 3 次已布局正文增长，已显示前缀保持不变；结束后检查最终标记。日志：`outputs/body-stream-device-test.txt`。
- 流中截图：`outputs/body-streaming-during-provider.png`。截图在 producer 和 collector 仍活跃时保存，已人工查看：thinking 已显示，正文可见，末尾处于淡入中。这是动态测试流的渲染证据，不是真实 OpenCode 请求截图。
- 测试环境使用主线程 collector 和受控 Compose 调度器。持续发包期间直接读取根视口内已布局的 `TextLayoutResult`，避免常规查询等待全局空闲，造成持续流测试自身超时。

真实 OpenCode DeepSeek 网络请求尚未端到端实测。先前的静态富文本截图不作为本次流式验收证据。本轮不是全量测试验收，既有全量测试中的 memory 固定哈希失败不在本次修复范围。
