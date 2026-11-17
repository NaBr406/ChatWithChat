# OpenUI 与聊天玻璃共存修复

## 结论

原来的修复在任意选中回答出现 OpenUI 后把 `LocalChatGlass` 设为 `null`。输入框、顶栏按钮和选择面板因此全部退回实体背景，这正是 OpenUI 一出现玻璃就消失的直接原因。

现在只关闭不安全的“录制整个聊天内容层”这一项，不再关闭玻璃本身：

- `ChatGlassPolicy.shouldCaptureLiveContent` 仍在 WebView 组合前检查历史消息、当前平台和回答版本。
- 检测到 OpenUI 后，`ChatGlassScope` 提供 `AmbientChatBackdrop`，不再提供用于列表录制的 `LayerBackdrop`。
- 输入框、顶栏按钮和选择面板继续使用同一套 `vibrancy()`、`blur(6.dp)`、`lens(10.dp, 16.dp)` 和半透明 tint。
- `ChatContent` 仅在 `captureBackdrop != null` 时挂载 `Modifier.layerBackdrop`，所以 WebView 不会进入 `GraphicsLayer.record`。
- 设备被判定为低内存或省电模式时，原有实体背景降级保持不变。

OpenUI WebView 仍保留硬件加速和透明底色。曾临时改成不透明画布色做过 A/B，也通过了同样数量的循环，但会遮住整块深色画布光晕，视觉上明显变平，最终没有保留这项改动。

## 调用链

1. `ChatScreen` 在组合 WebView 前读取所有轮次选中回答的 `effectiveContent`。
2. `ChatGlassPolicy` 发现 OpenUI 起始围栏后，将当前页面的 live capture 永久关闭；后续回滚、删除或切换回答版本都不会重新开启。
3. `ChatGlassScope` 根据结果选择 live `LayerBackdrop` 或安全的 `AmbientChatBackdrop`。
4. `LocalChatGlass` 始终保留非空上下文，玻璃表面继续绘制；只有列表记录入口变成 `null`。
5. `ChatContent` 在安全模式下不挂 `layerBackdrop`，但继续把环境 backdrop 传给输入框和其他玻璃控件。

## 诊断过程

历史 55 项实机回放已经证明完整生产聊天列表被 `layerBackdrop` 录制时，WebView 可以触发 RenderThread 的 Vulkan/Skia 原生崩溃。独立诊断壳复用了 Backdrop、WebView 和动态高度，却没有完整 `Scaffold`、真实输入区、选择菜单和多轮消息，所以未复现，不能据此推翻生产页的录制触发条件。

本轮又检查了：

- 生产聊天树中只有 `OpenUiWebView` 一个 `AndroidView`。
- MathJax 使用独立后台 WebView，不挂在聊天列表，也没有进入 `layerBackdrop`。
- 抽屉和空聊天页没有 OpenUI WebView，仍可安全使用 live capture。
- 多个 OpenUI 程序块仍会各自创建 WebView，内存和渲染进程压力没有因此消失，这是另一个仍需真机长批观察的风险。

4 GiB / API 35 / 16 KB 页模拟器上仍会偶发 Chromium `MemoryInfra` 的 `libmonochrome` SIGTRAP。最终源码的第一次 12 项批次复现过一次；关闭玻璃的生产壳、只保留 blur、以及 blur + vibrancy 各跑 12 项都通过，随后恢复完整 blur + vibrancy + lens 又连续通过 4 轮 12 项。现有数据不足以把这次 MemoryInfra 归因于某一个玻璃 effect，它与此前记录中的模拟器 Chromium 路径一致，不能算作本轮已经解决的实机 Vulkan 问题。

## 验证

- `ChatGlassPolicyTest` 8 项通过。
- `compileDebugAndroidTestKotlin`、`assembleDebug`、`assembleDebugAndroidTest` 通过。
- API 35 / 16 KB / 4 GiB x86_64 模拟器，WebView 124.0.6367.219：最终源码连续 4 轮，每轮 12 个流式 OpenUI 建立/销毁循环，共 48 项通过；批次开始前另有一次 MemoryInfra 崩溃，单独保留为模拟器风险。
- 每项都确认 `liveBackdropCaptureEnabled=false`、当前设备支持玻璃时 `glassSurfaceEnabled=true`，且 WebView 保持硬件加速。
- 单轮原始记录：`outputs/openui-glass-coexistence-20261107/emulator-production-final-12case-evidence.jsonl`。72 次内容确认里 live capture 开启 0 次、玻璃缺失 0 次、硬件加速关闭 0 次。
- 浅色与深色生产壳截图确认 OpenUI 内容、顶栏圆形玻璃和输入区玻璃同时存在：
  - `outputs/openui-glass-coexistence-20261107/emulator-production-openui-glass-light-final.png`
  - `outputs/openui-glass-coexistence-20261107/emulator-production-openui-glass-dark-final.png`

全量 JVM 单测执行了 2030 项，2027 项通过、2 项跳过、1 项失败。失败项是 `MemoryBatchConsolidationServiceTest` 的 provider 录制请求 hash 校验，当前分支的 provider/memory 改动已经改变请求结构；它不在本轮玻璃与 OpenUI 改动路径内。

## 边界

当前没有连接 Xiaomi dijun / Android 17 / WebView 155 的原崩溃设备，因此没有完成同一台机器上的 Vulkan 原生崩溃回归。模拟器通过只能证明新路径可以组合和持续渲染，不能替代真机结论。低内存设备仍按原策略使用实体背景。
