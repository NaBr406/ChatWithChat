# OpenUI 流式渲染实机原生崩溃修复

## 结论

在 Xiaomi dijun / Android 17 / WebView 155.0.8059.43 上，完整生产聊天列表被 `layerBackdrop` 录制时，OpenUI WebView 可稳定触发 RenderThread 的原生 SIGTRAP。修复在 OpenUI 首次出现前关闭当前聊天页面的背景采样，让输入区、顶部按钮和选择面板使用已有的实体背景降级，保留 WebView 硬件加速。

这是已复现的应用侧触发条件；没有确认 Chromium 内部断言的具体源码位置，也不据此断言所有系统版本都受影响。

## 因果证据

- 原应用让 DeepSeek 展示全部 55 组件，在 16:45:48.932 崩溃；首次 OpenUI WebView 加载后约 708 ms 触发。
- 栈包含 WebViewChromium → WebViewFunctor::drawVk → renderLayersImpl → SkiaVulkanPipeline::draw，与此前两次崩溃的 23 个帧偏移完全一致。没有 OOM / LMK 证据。
- 简化气泡布局没有复现；换成实际 ChatContent 后，普通 Card 和空 Col 均复现相同栈。无需地图、图片或复杂组件。
- 同一诊断 APK 仅切换列表背景采样：开启时复现，关闭时通过。长 thinking / 长正文对照也通过。

## 实现

- ChatGlassPolicy 在外层 ChatGlassScope 和子 WebView 组合前同步检查所有轮次选中版本的 effectiveContent，使用与正文一致的内部标记清理及 OpenUiEnvelope 识别。
- 空的 OpenUI 起始围栏即关闭采样；历史消息、候选模型、历史修订均纳入判断。
- 当前页面会话一旦关闭便保持关闭，避免回滚、移除或切换版本时重新录制尚未卸载的 WebView。
- 未改变的历史内容缓存，避免流式每个分片重复处理历史文本。
- ChatContent 不再自行创建后备 backdrop；不存在上下文时不挂 layerBackdrop。

## 验证

- ChatGlassPolicyTest 8/8，OpenUiEnvelopeTest 6/6；修改文件 ktlint、git diff --check 通过。未宣称全量单元测试通过。
- 正式自动策略、完整生产壳、同一实机：Card 7.935 s、Tiny 4.307 s、LongThoughtsCard 6.128 s 通过。
- 原包名 arm64 release 编译成功。签名证书与原安装一致，17:25:26 通过 adb install -r 更新成功，保留 2026-09-10 的首次安装时间。
- 原应用 17:26:51 再次发送全部 55 项请求；17:27:38 首次 WebView 加载后正常完成。正文 9,978 字符、thinking 47,081 字符，单个 OpenUI 块含全部 55 种组件调用。完成后的表单、计时器、HtmlView 和末尾回答操作均可见，滚动和重新打开历史会话未复现崩溃。
- UI 只读查看新回答的编辑对话框后取消，未修改回复。组件构造调用覆盖不等同于逐个交互行为验收。
- 首两轮批量回放为测试预期错误（围栏闭合后仅追加 Markdown 不递增 WebView revision；生产正文清理会 trimEnd）。失败原始证据保留，未计作通过，也未混同为原生崩溃。

## 产物和数据保留

- 发布包：outputs/openui-phone-crash-20261026/app-fixed-release-signed.apk
- SHA256：3542dd91f21a215859ea261a66b337ae511a99fc0780066871831b21e8263448
- 证书 SHA256：41b0b4625f36c4ba91cc07e42505137ba048c6576d6edc5e301a2a474abef374
- 因果对照：outputs/openui-phone-crash-20261026/live-20261026-164341/reproduction.md
- 更新后真实生成：outputs/openui-phone-crash-20261026/live-fixed/
- 原安装 APK 已备份；没有卸载、清除应用数据、清空日志、重置数据库或删除会话。测试新增会话保留。


## 最终逐项回归结果

修正 harness 后，完整生产聊天壳的 55 项回放全部通过（170.746 秒）：440 个流式快照、339 次 OpenUI 内容确认。每次确认均校验源码 hash 一致、视图可见、背景采样自动关闭及硬件加速保留。未发生渲染错误断言或原生崩溃。详见 `outputs/openui-phone-crash-20261026/production55-regression-summary.json` 及 v3 原始结果。

已恢复诊断应用临时 appop，停止本任务的日志采集，手机返回原应用新生成的全部 55 项会话。原应用 PID 16546 始终存活。此次为崩溃回归及渲染验证，不代表 55 项每一种交互操作的完整验收。
