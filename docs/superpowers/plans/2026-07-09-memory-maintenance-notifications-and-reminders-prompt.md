# ChatWithChat Memory Maintenance Notifications And Reminder Scheduler Prompt

> **For agentic workers:** This is an implementation handoff prompt for adding local push notifications around memory maintenance and preparing a reusable scheduler foundation for future cron/reminder tasks. Start with a read-only audit, implement one task at a time, and keep the change scoped to notification/scheduling behavior. Do not redesign the whole memory system.

## Goal

为 ChatWithChat 当前 OpenClaw-style Markdown-first 记忆系统补上用户可感知的维护提醒：

- 记忆维护开始时，可以对真正需要用户知道的重型维护发出本地系统通知。
- 记忆维护失败时，必须能对用户发出本地系统通知，并引导用户进入 Memory/诊断入口查看失败任务、重试或忽略。
- 通知内容不能泄露记忆正文、原始 provider token、完整 API 错误、或敏感上下文。
- 通知事件应来自持久化维护任务状态变化，而不是来自某个 Compose 页面生命周期。
- 同时抽出可复用的本地调度/通知底座，为后续 cron/定时提醒任务做准备。

第一阶段默认只做 Android 本地系统通知，不引入 FCM、云推送、服务端账号、后台长连接或外部任务队列。

## Current Repo State To Respect

开工前仍需重新 `rg`，因为行号可能随其它分支变化。当前已知锚点：

- 发送前记忆召回在 `ChatViewModel.prepareMemoryPrompt(...)`，文件：`app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatViewModel.kt`
- 聊天保存后记忆学习在 `ChatViewModel.learnFromSavedChat(...)`，并运行于 `@ApplicationScope`，不依赖页面生命周期。
- 应用启动会调用 `MemoryMaintenanceRepairer.repairAndEnqueue()`，文件：`app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/GPTMobileApp.kt`
- 开机接收器只重新 enqueue WorkManager，文件：`app/src/main/kotlin/dev/chungjungsoo/gptmobile/receiver/BootCompletedReceiver.kt`
- 维护 Worker 调用 `MemoryMaintenanceProcessor.processRunnableJobs()`，文件：`app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceWorker.kt`
- 维护任务状态转移集中在 `MemoryMaintenanceScheduler`，文件：`app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceScheduler.kt`
- 持久任务实体是 `MemoryMaintenanceJob`，字段包含 `type`、`status`、`attempts`、`lastError`、`nextRunAt`、`idempotencyKey`。
- 现有 Memory 页面会显示维护任务并提供 retry/dismiss，文件：`app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/memory/MemoryScreen.kt`
- 设置页已有 `memory_enabled` 开关，文件：`SettingDataSourceImpl.kt`、`SettingRepositoryImpl.kt`、`SettingViewModelV2.kt`、`SettingScreen.kt`
- `AndroidManifest.xml` 目前有 `RECEIVE_BOOT_COMPLETED`，但没有 `POST_NOTIFICATIONS`。
- 依赖里已有 WorkManager 和 AndroidX Core；如使用 `NotificationCompat`，优先复用 `androidx.core`，不要新增重型通知库。

## Product Defaults

除非用户在新会话中明确改口，按以下默认实现：

- 使用本地系统通知，不使用 FCM。
- 维护失败通知默认开启，但仍受 Android 系统通知权限控制。
- 维护开始通知只用于重型/用户可感知任务：`distill_daily_notes`、`promote_long_term_candidate`、`repair_markdown_metadata`、`compaction_flush`。不要对每次 `append_daily_note` 都弹通知。
- 维护成功默认不弹新通知；如果存在同一任务的失败通知，可以更新或取消该通知。
- 同一 `jobId` 或同一 `idempotencyKey` 的重复失败应更新同一条通知，不能刷屏。
- 通知标题使用笼统文案，例如“记忆维护需要处理”或“记忆维护失败”。
- 通知正文只显示安全摘要，例如“点按查看并重试维护任务”。不要展示完整 `lastError`，UI 诊断页可展示更详细但仍要截断的错误。
- 通知点击打开 app 并尽量导航到 `Route.MEMORY` 或后续诊断页；如果深链成本过高，第一版可以打开主界面并保持失败任务在 Memory 页面可见。
- 不要在 app 启动时无条件请求通知权限。应在用户开启维护通知设置、打开相关设置页、或首次需要显示维护失败提示时，用上下文式方式请求 `POST_NOTIFICATIONS`。
- Android 自启动/后台执行不可靠。不要向用户或代码注释承诺一定准时执行；必须依赖 WorkManager、开机 enqueue、app 启动补偿和手动重试共同兜底。

## Non-Goals

- 不重写 OpenClaw-style Markdown memory 的召回/学习主链路。
- 不把 `searchChatsV2(...)` 当长期记忆搜索或提醒搜索。
- 不让模型直接控制通知发送。LLM 只负责记忆语义；代码负责状态、调度、通知、安全和去重。
- 不把 Memory 页面变成复杂管理台。现有失败任务列表可以保留或迁往设置/诊断，但用户-facing memory surface 仍应以 `MEMORY.md` 为主。
- 不引入 FCM、Firebase、云账号、服务端推送、Python daemon、Node sidecar、Milvus、QMD、memsearch 或 Mem0。
- 不添加用户可见的 cron/reminder UI，除非本轮任务明确要求并且数据层已经实现。
- 不为了通知权限把 `POST_NOTIFICATIONS` 混入 `ToolRegistry.requestedRuntimePermissions()` 的启动时批量权限请求。

## Target Architecture

```text
MemoryMaintenanceScheduler.markRunning/markFailed/markSucceeded(...)
  -> MemoryMaintenanceEventSink.onStatusChanged(event)
    -> MemoryMaintenanceNotificationPolicy.decide(event, settings, permission)
      -> AppNotificationManager.show/update/cancel(...)
        -> Android notification channel: memory_maintenance

MemoryMaintenanceWorker.doWork()
  -> MemoryMaintenanceProcessor.processRunnableJobs()
  -> schedule next delayed repair if pending retry jobs have future nextRunAt

Future reminder task
  -> ScheduledTaskRepository stores one-shot/daily/weekly/interval specs
  -> ScheduledTaskWorker claims due tasks
  -> AppNotificationManager posts notification on scheduled_reminders channel
  -> computes and persists nextRunAt
```

Core boundaries:

- `MemoryMaintenanceScheduler` owns persisted job state transitions.
- `MemoryMaintenanceEventSink` is a small interface for side effects triggered by state transitions.
- `MemoryMaintenanceNotificationPolicy` is pure Kotlin and unit-testable. It decides whether a status/type/attempt should notify.
- `AppNotificationManager` is the thin Android boundary for channels, permissions, `NotificationCompat`, and `PendingIntent`.
- `MemoryMaintenanceWorkScheduler` owns WorkManager enqueue policy, including immediate repair and delayed retry scheduling.
- Future reminder scheduling should share notification infrastructure but stay separate from memory-specific job semantics.

## Task 0: Read-Only Audit

**Goal:** Confirm current repo shape and avoid implementing against stale assumptions.

Run:

```powershell
git status --short --branch
rg -n "POST_NOTIFICATIONS|NotificationCompat|NotificationManager|createNotificationChannel|PendingIntent|AlarmManager|WorkManager|PeriodicWorkRequest|OneTimeWorkRequest" app\src\main
rg -n "MemoryMaintenanceScheduler|MemoryMaintenanceWorker|MemoryMaintenanceProcessor|MemoryMaintenanceRepairer|MemoryMaintenanceWorkScheduler|MemoryMaintenanceJobStatus|MemoryMaintenanceJobType" app\src\main app\src\test
rg -n "memory_enabled|SettingDataSource|SettingRepository|SettingViewModelV2|SettingScreen|MemoryScreen|Route.MEMORY" app\src\main
```

Also read:

- `docs/superpowers/plans/2026-07-09-openclaw-style-memory-prompt.md`
- Android official docs for notification runtime permission, notification channels, WorkManager delayed work, periodic work minimum interval, and exact alarm restrictions.

Output before editing:

- Current maintenance chain.
- Current notification/permission gaps.
- Whether there is already a diagnostics surface beyond Memory page.
- Any dirty worktree files that must not be touched.
- Decision points that require user confirmation.

## Task 1: Add Notification Settings, Permission, And Channels

**Goal:** Add a safe user-controlled notification foundation without firing notifications yet.

Files likely involved:

- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/datastore/SettingDataSource.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/datastore/SettingDataSourceImpl.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepository.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/repository/SettingRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingViewModelV2.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/SettingScreen.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/main/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`

Implementation:

- [ ] Add `android.permission.POST_NOTIFICATIONS` to manifest.
- [ ] Add DataStore boolean key such as `memory_maintenance_notifications_enabled`.
- [ ] Default to enabled for failure notifications, but do not show notifications unless system permission is granted.
- [ ] Add setting row under personalization or a diagnostics/settings section.
- [ ] Add a separate runtime permission launcher or explicit flow in `MainActivity`; do not include notification permission in the existing tool runtime permission auto-request.
- [ ] Create notification channels at app startup or before first notification:
  - `memory_maintenance`
  - `scheduled_reminders`
- [ ] Use localized strings for channel names and notification copy.

Acceptance criteria:

- [ ] Fresh install does not request notification permission on launch.
- [ ] User can enable/disable memory maintenance notifications from settings.
- [ ] Turning the setting on can request `POST_NOTIFICATIONS` on Android 13+.
- [ ] Denied permission does not crash or block memory maintenance.
- [ ] Existing memory enabled switch behavior is unchanged.

Verification:

```powershell
./gradlew.bat :app:compileDebugKotlin
```

## Task 2: Add Notification Manager And Pure Policy

**Goal:** Separate Android notification mechanics from testable policy decisions.

Files likely involved:

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/notification/AppNotificationManager.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/notification/AppNotificationChannels.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceNotificationPolicy.kt`
- Test: `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceNotificationPolicyTest.kt`

Implementation:

- [ ] `AppNotificationManager` should expose small methods, not raw builder access:
  - `ensureChannels()`
  - `showMemoryMaintenanceStarted(job)`
  - `showMemoryMaintenanceFailed(job, terminal: Boolean)`
  - `cancelMemoryMaintenance(job)`
  - `showScheduledReminder(...)` for future use
- [ ] Guard notification posting with both user setting and system permission.
- [ ] Build a `PendingIntent` that opens the app; route to Memory/diagnostics if cleanly supported.
- [ ] Use stable notification IDs from `jobId` or `idempotencyKey` hash.
- [ ] `MemoryMaintenanceNotificationPolicy` should be pure Kotlin and cover:
  - start notification allowed only for selected heavy job types
  - retryable failure notification
  - terminal failure notification
  - no notification for routine `append_daily_note` start
  - no notification when preference disabled
  - duplicate attempts update same notification identity

Acceptance criteria:

- [ ] Unit tests verify policy without Android framework or Robolectric.
- [ ] Notification manager contains Android-specific code only.
- [ ] No memory text or raw full error is placed in notification body.

Verification:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceNotificationPolicyTest"
./gradlew.bat :app:compileDebugKotlin
```

## Task 3: Emit Maintenance Events From Job State Transitions

**Goal:** Notify based on durable job state changes, not ad hoc call sites.

Files likely involved:

- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceScheduler.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceEventSink.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceNotificationEventSink.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/di/MemoryRepositoryModule.kt`
- Update tests: `MemoryMaintenanceSchedulerTest.kt`, `MemoryMaintenanceProcessorTest.kt`, `MemoryLearnerTest.kt`

Implementation:

- [ ] Add `MemoryMaintenanceEventSink` with a no-op default so existing unit tests remain easy to construct.
- [ ] Emit events after successful DAO updates in:
  - `markRunning`
  - `markSucceeded`
  - `markFailedRetryable`
  - `markFailedTerminal`
  - optional: `resetStaleRunningJobs`, if you can cheaply fetch affected jobs; otherwise document as future improvement.
- [ ] The event should contain old job if available, new job, status transition, and timestamp.
- [ ] `MemoryMaintenanceNotificationEventSink` should fetch settings/permission and call notification policy + manager.
- [ ] Keep event sink failures non-fatal. Notification failure must not fail maintenance jobs.
- [ ] Avoid recursion: notification sink must not update the same maintenance job row as part of notification posting.

Acceptance criteria:

- [ ] A failed job status transition triggers exactly one notification decision.
- [ ] Scheduler tests still prove idempotency and retry state.
- [ ] Processor tests still pass for rebuild and persisted markdown learning jobs.
- [ ] Notification side effects cannot make a job fail.

Verification:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceSchedulerTest"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceProcessorTest"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.repository.MemoryLearnerTest"
./gradlew.bat :app:compileDebugKotlin
```

## Task 4: Schedule Future Retry Work From `nextRunAt`

**Goal:** Ensure retryable future jobs actually wake up later, not only on next app launch.

Files likely involved:

- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceWorkScheduler.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceWorker.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/memory/MemoryMaintenanceScheduler.kt`
- Modify: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/dao/MemoryMaintenanceJobDao.kt`
- Test: `MemoryMaintenanceSchedulerTest.kt`
- Optional test: `MemoryMaintenanceWorkSchedulerTest.kt` if it can be kept JVM-friendly

Implementation:

- [ ] Add DAO query for earliest pending/retryable `next_run_at`.
- [ ] Add scheduler helper like `nextScheduledRunAt(now)`.
- [ ] Extend `MemoryMaintenanceWorkEnqueuer` with delayed enqueue support, for example `enqueueRepairWork(delaySeconds: Long = 0)`.
- [ ] Avoid the unique-work trap: if delayed work and immediate work share the same unique name with `KEEP`, immediate work can be blocked by the delayed instance. Prefer separate unique names for immediate and delayed repair, or carefully use `REPLACE` where safe.
- [ ] After `MemoryMaintenanceWorker` processes runnable jobs, enqueue the next delayed repair for the earliest future `nextRunAt`.
- [ ] Keep `BootCompletedReceiver` tiny: it should only enqueue immediate repair.
- [ ] Document that WorkManager is not exact cron; it is a durable best-effort background scheduler.

Acceptance criteria:

- [ ] Retryable jobs with `nextRunAt` in the future are scheduled for future processing.
- [ ] Immediate repair still runs even if a delayed retry work item exists.
- [ ] App launch and boot enqueue still repair stale/runnable jobs.
- [ ] No duplicate job processing because job idempotency remains enforced by job rows and markdown entry IDs.

Verification:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.memory.MemoryMaintenanceSchedulerTest"
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryMaintenance*"
./gradlew.bat :app:compileDebugKotlin
```

## Task 5: Improve Memory/Diagnostics Surface For Failures

**Goal:** Make notification click-through useful without turning the Memory page into a noisy operations console.

Files likely involved:

- Modify: `MemoryScreen.kt`
- Modify: `MemoryViewModel.kt`
- Modify: `SettingScreen.kt` or create a small diagnostics route if needed
- Modify: `Route.kt` and `NavigationGraph.kt` only if a new diagnostics route is truly needed
- Strings in `values` and `values-zh-rCN`

Implementation:

- [ ] Keep `MEMORY.md` viewer as the main Memory page purpose.
- [ ] Keep failed/pending maintenance tasks visible in a compact diagnostics block or move them to settings diagnostics.
- [ ] Retry/dismiss actions should remain available for failed jobs.
- [ ] If notifications open Memory page, make sure failed tasks are visible without requiring several extra taps.
- [ ] If notification permission is denied, settings should make that state understandable without nagging.

Acceptance criteria:

- [ ] User receiving a failure notification can find the failed job and retry/dismiss it.
- [ ] Memory page does not expose daily notes or per-entry memory management.
- [ ] Existing memory disabled notice still appears when memory is off.

Verification:

```powershell
./gradlew.bat :app:compileDebugKotlin
```

## Task 6: Prepare Generic Reminder/Cron Scheduler Foundation

**Goal:** Add only the foundation needed for future reminder tasks; do not add a full reminder product UI in this slice.

Files likely involved:

- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/schedule/ScheduledTask.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/schedule/ScheduleSpec.kt`
- Create: `app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/schedule/ScheduleCalculator.kt`
- Create tests under `app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/schedule/`
- Optional later, not required in first pass: Room entity/DAO and Worker for actual reminder execution

Implementation:

- [ ] Define a small `ScheduleSpec` model that can later support:
  - one-shot at timestamp
  - interval
  - daily local time
  - weekly local day/time
  - future cron expression string, stored but not necessarily parsed in MVP
- [ ] Implement pure Kotlin next-run calculation for one-shot, interval, daily, and weekly.
- [ ] Do not import a heavy cron parser unless a real task requires it.
- [ ] Keep exact alarms out of MVP. If future reminders require exact user-visible timing, evaluate `AlarmManager` and Android exact alarm restrictions separately.
- [ ] Reuse `AppNotificationManager` channel `scheduled_reminders` for future notifications.

Acceptance criteria:

- [ ] Schedule calculation is unit-tested and timezone-aware enough for local user reminders.
- [ ] No visible reminder UI is added before persistence/execution exists.
- [ ] The scheduler foundation does not couple reminder tasks to memory maintenance jobs.

Verification:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.schedule.*"
./gradlew.bat :app:compileDebugKotlin
```

## Task 7: End-To-End Verification

Run the smallest relevant checks after each task. Before final handoff, run:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*MemoryMaintenance*"
./gradlew.bat :app:testDebugUnitTest --tests "*Notification*"
./gradlew.bat :app:testDebugUnitTest --tests "dev.chungjungsoo.gptmobile.data.schedule.*"
./gradlew.bat :app:compileDebugKotlin
```

Manual or emulator checks, when available:

- Install on Android 13+ emulator/device.
- Enable memory maintenance notification setting.
- Grant notification permission.
- Force a retryable memory maintenance failure through a debug/test path.
- Confirm one notification appears and updates instead of duplicating.
- Tap notification and confirm app opens to Memory/diagnostics surface.
- Deny permission and confirm maintenance continues without crash.
- Reboot or simulate boot receiver if practical; confirm repair work is enqueued, not executed inside receiver.

## Copy-Paste Prompt For A Fresh Session

Use this exact prompt in another Codex conversation:

```text
你是接手 ChatWithChat Android 项目的资深 Kotlin/Jetpack Compose 工程师。当前仓库是 `E:\code\ChatWithChat`。请阅读：

- `AGENTS.md`
- `docs/superpowers/plans/2026-07-09-openclaw-style-memory-prompt.md`
- `docs/superpowers/plans/2026-07-09-memory-maintenance-notifications-and-reminders-prompt.md`

目标：为当前 OpenClaw-style Markdown-first 记忆维护系统添加本地系统通知提醒，并为后续 cron/定时提醒任务准备通用调度底座。

请先不要改代码。第一步只做 Task 0 Read-Only Audit：

1. 运行：
   - `git status --short --branch`
   - `rg -n "POST_NOTIFICATIONS|NotificationCompat|NotificationManager|createNotificationChannel|PendingIntent|AlarmManager|WorkManager|PeriodicWorkRequest|OneTimeWorkRequest" app\src\main`
   - `rg -n "MemoryMaintenanceScheduler|MemoryMaintenanceWorker|MemoryMaintenanceProcessor|MemoryMaintenanceRepairer|MemoryMaintenanceWorkScheduler|MemoryMaintenanceJobStatus|MemoryMaintenanceJobType" app\src\main app\src\test`
   - `rg -n "memory_enabled|SettingDataSource|SettingRepository|SettingViewModelV2|SettingScreen|MemoryScreen|Route.MEMORY" app\src\main`

2. 联网核对 Android 官方文档：
   - Android notification runtime permission / `POST_NOTIFICATIONS`
   - notification channels
   - WorkManager one-time delayed work and periodic work limits
   - Android exact alarm restrictions

3. 输出：
   - 当前记忆维护链路图。
   - 当前通知/权限/调度缺口。
   - 与计划文档相比，当前代码是否已有漂移。
   - 推荐的第一步实现范围。
   - 需要用户确认的决策点。

默认产品决策：

- 使用本地系统通知，不使用 FCM、Firebase、云推送或服务端任务。
- 维护失败通知默认开启，但仍受 Android 系统通知权限控制。
- 维护开始通知只用于重型任务：`distill_daily_notes`、`promote_long_term_candidate`、`repair_markdown_metadata`、`compaction_flush`。
- 不要对每次 `append_daily_note` 弹通知。
- 通知内容不能暴露记忆正文、完整 provider 错误、token、原始 prompt 或敏感上下文。
- 同一 job 重复失败应更新同一条通知，不能刷屏。
- 通知点击应尽量打开 Memory/诊断入口，让用户可以重试或忽略失败任务。
- 不要在 app 启动时无条件请求通知权限；用上下文式授权。
- 不要把 `POST_NOTIFICATIONS` 混入现有 tool runtime permission 的启动时批量请求。
- Android 后台/自启动不可靠；必须保留 WorkManager、开机 enqueue、app 启动补偿和手动重试。

实现约束：

- 不重写记忆召回/学习主链路。
- 不破坏附件、编辑、重试、导出、多 provider、per-chat model override、tool/web search、token usage 等现有聊天能力。
- 不把 `searchChatsV2(...)` 当长期记忆搜索。
- LLM 负责语义，代码负责存储、安全、去重、索引、调度、通知和失败兜底。
- 通知事件应来自 `MemoryMaintenanceJob` 的持久状态变化，而不是 UI 页面。
- `MemoryMaintenanceScheduler` 是优先挂接点；新增 event sink 时要保持测试可构造，提供 no-op 默认或测试 fake。
- 通知发送失败不能让维护任务失败。
- 不添加完整 reminder UI；第一阶段只准备可复用 schedule/notification 基础。

等我确认后，再按文档 Task 1 开始实现。每完成一个 task，运行对应 verification 命令，并报告 changed files、行为变化、验证结果、下一步建议。
```

