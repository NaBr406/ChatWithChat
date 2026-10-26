# OpenUI 整理与提交验证

日期：2026-10-26。分支：`codex/intelligent-ui-rewrite`。

## 整理范围

- 富 UI 集成：本地 React + OpenUI Lang 55 组件、原生宿主桥、语义投影、模型提示、原生交互扩展层、富文本连贯性与配色，以及 DeepSeek final-answer 正文增量解码与显示。保留普通 Markdown 和旧 XML 内容兼容。
- 原生崩溃修复：检测到选中回答包含 OpenUI 起始围栏时，在组合子 WebView 之前同步关闭页面背景采样；当前页面会话保持关闭，保留 WebView 硬件加速。
- 验证与交接：保留各阶段报告，明确区分组件注册、渲染回放、真实模型生成和逐项交互验收。

Web 源码、锁文件、上游许可证和 Android 离线资源一起提交。离线资源由 `web/openui` 的 `npm run build` 生成；不能只提交宿主 Kotlin 代码而遗漏配套资源。

## 提交前审查修正

HtmlView 虽已禁止 fetch，但脚本原本仍可借远程图片 URL 发送输入内容。现将两层沙箱 CSP 的图片来源收紧为 `data:` / `blob:`，同步组件描述、生成规格及模型提示。新增浏览器回归实际向输入框填值，确认脚本运行并更改图片 URL，但无远程请求；本地 data 图片仍正常解码。普通 Image、SearchImage 与地图组件继续按各自宿主规则运行。

请求基线随完整组件目录更新，继续精确校验七种供应商 DTO 的序列化数据、调用轮数、工具候选与目录不重复；token 预算按最终实测值保留小幅余量。未修改记忆录制测试的固定哈希预期。

完整目录目前随每个可回答用户的工具轮次发送。七种供应商的三轮基线累计估算为 40,561–44,398 input tokens；每组 ceiling 仅保留 48–55 token 余量。这是特定三轮工具用例的测量，不是每次普通对话的固定消耗。目录按需加载与进一步压缩尚未实现。

## 本轮检查

- Web：Vitest 323/323、Playwright 12/12 通过，TypeScript/Vite 生产构建与许可证生成通过。新增远程图片外传回归包含实际输入事件。打包仍有较大 chunk 提示，未作为性能已验收的证明。
- Kotlin：52 个变更文件的 ktlint 1.3.1 检查通过；ChatBubble、RichInteractionHostController 以及 ChatViewModel 的既有格式问题已统一修正。`git diff --check` 通过；上游许可证原文由 `.gitattributes` 免除空白格式检查。
- Android：`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --continue --console=plain` 完成。1995 项单测中 1992 通过、2 跳过、1 失败；两个 assemble 任务均成功产出 APK。组合命令因单测失败返回非零，未声称全量绿灯。
- 唯一失败：已有 `MemoryBatchConsolidationServiceTest.provider generation recordings pass production parsing validation and commit`，位置 `MemoryBatchConsolidationServiceTest.kt:51`。预期哈希 `B5804E107955010CF87576203416075FA5BF08096B761E3F2C036A94313F6D1F`，实际 `5D6FBB849963CD81C8C6C3B0F9516B5EB3D15EF74F554FA299A2E197B84FB8ED`。保留失败，未用更新预期掩盖。
- 请求基线：首次全量检查发现新版目录使旧基线失效；按最终实际序列化结果更新后，ChatRepositoryImplTest 定向 98/98 及最终全量中的该类均通过。
- APK 资源：最终 debug APK 的 1769 个 OpenUI 资源与工作区逐文件 SHA256 一致，无遗漏或多余文件。
- 暂存检查：源代码、测试、文档与离线资源检查 API key / 签名密码字面值 / 私钥标记，未发现匹配；本机诊断与构建产物排除。

初次定向基线复验遇到本地 KSP lookup cache 损坏，通过 Gradle clean 后重编译恢复；未执行应用数据操作。原始失败与复验日志均保存在本机 outputs。

最终证据：`outputs/precommit-web-test-final.txt`、`precommit-web-build-final.txt`、`precommit-web-browser-final.txt`、`precommit-ktlint-final.txt`、`precommit-android-final.txt`、`precommit-apk-resource-parity.json`。

本轮未重新执行设备测试。此前的实机 55/55 崩溃回归、440 个流式快照和真实 DeepSeek 生成结果详见 [实机崩溃修复报告](2026-10-26-openui-native-crash-fix.md)，不据此宣称 55 种组件的全部交互已验收。早期模拟器 MemoryInfra 原生退出与实机 Vulkan/backdrop 路径分别记录，未合并为同一个已证实根因。

## 本地保留

`.idea/vcs.xml`、`.codex-remote-attachments/`、`outputs/` 保留本机，不纳入提交。诊断日志、截图、发布 APK、签名材料及供应商反馈 ZIP 均不随源代码提交。没有卸载应用、清除数据、重置数据库、删除会话或推送远端。
