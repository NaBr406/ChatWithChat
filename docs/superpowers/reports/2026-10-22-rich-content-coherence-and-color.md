# 静态富文本连贯性与配色验证

日期：2026-10-22。分支：`codex/intelligent-ui-rewrite`，本轮基于 `0ee02f9c`。用户当前要求先完善显示，不实施交互。

## 最终行为

- 卡片使用透明的正文段落布局；比较项以细分隔线组织，步骤使用小编号和淡连接线，取消实心灰底编号。
- 提示块正文保持正常文字色；info 仅标题和细侧线用蓝色，浅色 `#356F9E`、深色 `#84B6D9`。success/warning/error 仅由明确 tone 选择，不根据趋势方向推断。
- row 保留原顺序中的说明文字和错误回退，不再只筛选列和指标。空白节点不增加布局间隙；字体缩放参与列数计算。
- 原生工具首轮可以直接回答，因此也带一份静态目录；最终综合继承这份目录，避免重复。JSON 工具回退仅在解码后的 `final_answer.content` 中使用富文本，保留外层 JSON 协议。
- 提示词说明比较、计划、多个指标的适用场景，要求引导、过渡和结论；简短回答继续使用 Markdown。没有新增模型请求，未广告交互组件。

## 实际模型证据

使用用户提供的 DeepSeek 配置进行直接 API 对照。请求 model 为 `deepseek-chat`，实际返回 `deepseek-flash`；问题只要求比较两个知识库方案并提出分阶段建议，没有要求组件标签。

| 样本 | 输入 token | 输出 token | 实际组件 |
| --- | ---: | ---: | --- |
| 原提示 | 2636 | 851 | 1 steps、4 step，比较使用 Markdown 表格 |
| 新提示 | 2827 | 812 | 3 card、1 row、2 col、2 steps、9 step、1 callout |

新提示在该样本增加 191 输入 token。新回答经生产 parser 解析，diagnostics 为空。这是单次真实样本及直接 API probe，不是触发率统计，也不等于 Android 内端到端 provider 调用验收。

本地证据在未跟踪的 `outputs/deepseek-rich-before.txt`、`deepseek-rich-after.txt` 和对应 report/projection；报告中的早期 step 计数使用前缀匹配，会包含 steps，正确计数以本表为准。配置凭据不进入代码、报告或提交。

## 模拟器证据

AVD `ChatWithChat_API35_16K_X86_64`，`emulator-5554`，Android API 35。通过已有 debug seed receiver 装载同一条真实模型返回，安装 debug APK 后在实际聊天页检查。

- 浅色：`outputs/emulator-rich-palette-light.png`。
- 深色：`outputs/emulator-rich-palette-dark.png`、`emulator-rich-palette-dark-steps.png`。
- 1.3 倍字体：`outputs/emulator-rich-palette-large-font.png`。显示尺寸为 945×2100、density 420，比较内容纵排并换行，没有看到横向裁切。检查后恢复字体 1.0 和浅色主题。
- 实际滑动检查了正文和步骤；本轮没有帧时间测量，不宣称所有闪烁或抖动已解决。截图中的 debug 后缀是 fixture 内容。

## 自动检查

`:app:assembleDebug` 成功。全量 `:app:testDebugUnitTest`：1920 项，1 失败、2 跳过。富文本/parser/prompt/layout 和 repository 共 114 项全部通过。

唯一失败是原有 `MemoryBatchConsolidationServiceTest` 的 `provider generation recordings pass production parsing validation and commit`：expected `B5804E107955010CF87576203416075FA5BF08096B761E3F2C036A94313F6D1F`，actual `A7363FA8BBED3349D35D2F2CF2AB87B567692635018150A1BB653A443A41387B`。本轮未修改该模块或测试。

七组 provider 请求 baseline 按实际序列化结果更新，包含新增目录成本，保留完整请求行、请求数、工具数和重复目录检查。旧的整体 30% token 节省说法不再用于本特性。

修改的 Kotlin 文件通过 ktlint 1.3.1，差异通过 `git diff --check`。独立审查发现长 delta 可能挤掉趋势箭头，已用有界权重保留箭头空间。

## 未完成边界

尚未实施 L1/L2 交互、全局富内容开关、全部语义入口审计、跨 provider 真实流式矩阵、TalkBack/RTL 设备验收和帧时间基准。当前是原生 Kotlin/Compose 白名单协议，没有集成 OpenUI TypeScript 运行时。
