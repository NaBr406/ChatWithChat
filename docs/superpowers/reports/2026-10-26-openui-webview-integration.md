# OpenUI WebView 集成与验证

> 阶段记录：本文保留当时的验证结果与阻塞。后续实机 Vulkan/backdrop 崩溃修复见同日 native-crash-fix 报告；本轮提交前的完整检查见整理验证记录。模拟器 MemoryInfra 崩溃与该实机路径分别记录。

工作分支：`codex/intelligent-ui-rewrite`。本次继续已有渲染、流式解码和原生交互基础设施工作；没有提交或推送。

## 实现范围

- 根据用户确认采用本地打包 React + OpenUI Lang，由 Compose 保留聊天列表、输入框和消息管理。仅显式 `openui` 围栏进入新渲染器，普通 Markdown 与旧 XML 保持兼容。
- 迁入固定版本的全部 55 个组件及配套函数、样式、KaTeX 字体、组件规格、示例库和许可证。上游为 Thesys 的独立实现，不是 OpenAI 官方源码：`thesysdev/open-intelligent-ui@03213439358bf3408ad61a46ec9e3230c5572e48`。
- 同一程序块复用 WebView；追加和结束状态更新保留局部输入，回退、替换和回答身份切换重建 React 状态。高度变化通知聊天列表，正文无需等整个 OpenUI 程序生成完才开始渲染。
- 表单、变量、派生计算、Set/Reset 在页面内执行。ToAssistant 只填入空白输入框，由用户确认发送；链接和复制经原生宿主校验。
- 原生桥限制本地 HTTPS 来源、顶层 frame、当前消息身份及版本。ViewModel 再核对已保存成功的当前回答，拒绝旧回调、生成中、编辑中或草稿冲突操作。
- HtmlView 独立页面置于不含 `allow-same-origin` 的沙箱，允许内联脚本及 data/blob 图片资源（提交前收紧，禁止远程图片），禁止网络 fetch、外部脚本、表单和原生桥。生命周期暂停局部计时器，恢复后保留状态。
- 模型提示词包含组件签名和宿主动作规则，并要求围栏之外提供完整语义说明。复制回答、导出、上下文、记忆和 assistant token 估算使用语义说明，过滤组件源码；用户原始消息不受此投影影响。

## 验证记录

- 本地 Web 生产构建通过；323 项 Vitest 和 11 项 Playwright 通过。覆盖全部 55 个注册名、23 个组件/组合程序、45 个示例，以及变量、动作、只读门槛、沙箱、生命周期和嵌入布局。组件 smoke 不等于逐项穷尽交互验收。最终日志：`outputs/openui-web-vitest-final.txt`、`outputs/openui-web-playwright-stable-final.txt`。
- Android debug 与测试 APK 构建通过，并以 `adb install -r` 覆盖安装，未清除应用数据。最后一次 Web 构建后重新打包安装，日志：`outputs/openui-final-stable-device-build.txt`。
- 定向 JVM 回归 37 项通过：上下文 token、记忆批次、OpenUI envelope、提示词、流式 final-answer 解码、宿主动作和宿主 controller。日志：`outputs/openui-unit-final.txt`。
- 旧 Android 回归连续 9 项通过：正文连续流式 3、正文/thinking 交互 4、原生交互基础 2。日志：`outputs/openui-regressions-final.txt`。此前 Markdown 动态节点销毁时出现 Compose SlotWriter 越界，调整节点 key 后独例和整组均通过。
- OpenUI Android 8 项在 4GB / API35 / 16KB 页大小模拟器整组通过：本地 Input/Slider、Popover 高度、桥接拒绝非法消息、深色与 1.3 倍字体/聊天列表滚动、追加/回退/替换/身份重置、沙箱内脚本与原生桥隔离、混排高度回传、约 26.4 万字内容尾部及延迟图片增高。最终资源重新安装后的日志：`outputs/openui-webview-device-stable-final.txt`。长文首次失败实际为测试标记 `_TAIL_` 被正常解析成 Markdown 斜体，使用无格式标记后通过，无内容截断。
- 已人工检查真实模拟器截图：`outputs/openui-dark-font130-narrative.png` 和 `outputs/openui-dark-font130-conversation-tail.png`。截图使用实际 OpponentChatBubble 与测试 LazyColumn，未冒充完整 ChatScreen 的供应商端到端验收。

- 最终 APK 的 1769 个 OpenUI 资源与工作区逐文件 SHA256 一致，记录：`outputs/openui-final-resource-parity.json`；Android 宿主与测试文件 ktlint、`git diff --check` 通过。ChatViewModel 仍有本轮之前的格式问题，未声称仓库全量 lint 或全量测试通过。

一次浏览器整组运行在最后一项的初始化阶段收到 `Not found`，发生于 Web 资源重建的同一时间段，未进入生命周期断言。资源稳定后该项单独通过、完整 11 项也通过；保留失败日志 `outputs/openui-web-playwright-final.txt`，不把它计作通过。

本报告不把构建成功、浏览器测试或外部服务 HTTP 可达等同于真实模型端到端生成验收。2GB 模拟器曾在系统内存压力下退出应用/后台进程，无新增 Java 崩溃栈；保留数据冷启动并临时增加至 4GB 后整组通过。此结果不证明低内存设备性能已验收。另有已记录并修复的 Compose 销毁异常，二者分开判断。

## 当前边界

- 局部 UI 状态尚未写入数据库；页面销毁后恢复初始状态。历史消息缺少当前屏幕持有的生成成功证据时为只读。
- Wikimedia 图片搜索、地图底图、OSRM 驾车路线及 YouTube 需要外部网络。错误状态与本地组件运行能力分别验证。
- HtmlView 不支持上游可选 CDN 脚本；复杂工具应使用内联实现或后续明确批准的离线依赖。
- 55 项是组件注册及协议覆盖数量，包含布局和 SVG 基础元素；不代表 55 个业务组件均已逐个完成手工交互验收。
- 已追加官方 DeepSeek `deepseek-flash` 的 55 项定向生成验收，逐项生成结果、重试、浏览器局部交互和 Android 回放结论见 [真实模型验收报告](../../../outputs/openui-live-acceptance/acceptance-report.md) 与 [55 项矩阵](../../../outputs/openui-live-acceptance/matrix.md)。该验证使用真实采集的 SSE 加速回放，不覆盖完整 ChatRepository 在线请求链路，也不证明模型在普通问题下会主动选择组件。

## 后续真实模型批跑发现的稳定性阻塞

55 个不同组件经跨批次补测，均取得至少一次 Android SSE 回放及目标渲染 pass，但不能据此报告连续整组稳定通过。full-v5 首次保存 40 pass、1 Card fail 后发生 MemoryInfra / libmonochrome 原生 SIGTRAP，余 14 项未运行；tail-v6 新进程又在保存 10 pass 后出现相同 native 栈，余 4 项未完成。两次连续批跑崩溃已复现，稳定性阻塞仍未解决。Card 定向复验、尾部 4 项补测及 MapMarker 单项复验通过，均不覆盖原始失败或关闭该风险。两次日志、15 帧 offset 一致的比较及各批次结果均见上述验收报告。这些新证据扩展了前述固定样例 8 项通过记录的边界；本轮未据此修改生产生命周期代码，也未认定 WebView 版本或 OOM 是根因。
