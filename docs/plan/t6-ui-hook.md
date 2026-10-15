# T6 · Pulse 主动关怀 — 接线记录

> 归属：T6（aura-handoff.md §3-T6 / 决定 D2「只在情绪不好时主动关心」）
> 状态：**已全部接线完成**（2026-10 本轮）。调度器启动、通知投放、用户回应标记、设置页开关与周期档位
> 都已落地；本文档保留为**接线落点索引**，接手时按第 2 节核对即可，不需要再动手。

---

## 1. 已完成的部分（不需要动）

| 文件 | 职责 |
|---|---|
| `core/pulse/PulseModels.kt` | 契约 + 触发历史 JSON 编解码（纯函数） |
| `core/pulse/PulsePolicy.kt` | **纯函数**判定：连续负向 / 极端负向 / 冷却 / 每周上限 / 退避 |
| `core/pulse/PulsePreferences.kt` | 独立 DataStore 读写（开关、周期、历史、待投放文案） |
| `core/pulse/PulseWorkScheduler.kt` | `PulseWorkScheduler` 接口 + WorkManager 实现（UPDATE/REPLACE） |
| `core/pulse/PulseScheduler.kt` | 周期调度器，结构照抄 `DreamLoopScheduler` |
| `core/pulse/PulseMessageComposer.kt` | 记忆素材筛选 + 确定性文案 |
| `core/pulse/PulseMemorySource.kt` | MemoryDao 只读口子 |
| `core/pulse/PulseWorker.kt` | `@HiltWorker` 周期检查 |
| `core/pulse/PulseModule.kt` | Hilt 绑定（调度器 / 记忆来源） |

跑起来的链路是：`PulseScheduler` → `PulseWorker`（周期）→ 判定 → 文案写进
`PulsePreferences.pendingMessage`。

**判定参数**（`PulseConfig` 默认值，全部可覆盖）：

| 参数 | 默认 | 含义 |
|---|---|---|
| `negativeIntensityThreshold` | 0.6 | 负向情绪强度下限（含等号） |
| `extremeIntensityThreshold` | 0.9 | 单次极端负向下限（含等号） |
| `minNegativeStreakDays` | 2 | 连续负向天数门槛 |
| `lookbackDays` | 7 | 情绪回看窗口 |
| `maxStaleDays` | 1 | 最新情绪记录超过几天就不再打扰 |
| `sameReasonCooldownMillis` | 7 天 | 同一原因冷却（handoff §7 第 3 条） |
| `weeklyWindowMillis` / `maxTriggersPerWeek` | 7 天 / 2 次 | 每周总量上限（同上） |
| `ignoredStreakThreshold` | 2 | 连续不理几次后开始拉长冷却 |
| `backoffStepMillis` / `maxBackoffMillis` | 7 天 / 28 天 | 每次加长步长 / 上限 |
| `muteAfterUnanswered` | 5 | 连续不理多少次后彻底停止 |
| `zoneOffsetMillis` | 0 | 本地日边界；由 `PulseWorker` 注入 `TimeZone.getDefault().getOffset(now)` |

---

## 2. 待接线点

### 2.1 启动调度器 —— `CompanionApplication.kt`（必须）

`PulseScheduler` 现在没人调 `start()`，所以周期任务不会被 enqueue。

```kotlin
// CompanionApplication.kt
@Inject lateinit var pulseScheduler: PulseScheduler

override fun onCreate() {
    // ...
    dreamLoopScheduler.start()
    pulseScheduler.start()   // ← 新增，紧跟 dreamLoopScheduler
}
```

`start()` 幂等：首次 collect 读当前值（默认开启 / 240 分钟）enqueue 一次，之后开关或周期变化自动
`UPDATE` / `cancel`。

### 2.2 设置页开关与周期 —— `SettingsScreen.kt`（建议）

不要改 `AppPreferences.kt`（多人并行改动热点）。Pulse 的偏好已在
`core/pulse/PulsePreferences.kt` 里独立提供，注入它即可：

```kotlin
val enabled by pulsePreferences.pulseEnabled.collectAsStateWithLifecycle(initialValue = true)
// Switch(onCheckedChange = { scope.launch { pulsePreferences.setPulseEnabled(it) } })
// 周期档位：pulsePreferences.pulseIntervalMinutes / setPulseIntervalMinutes(minutes)
```

要求（§3-T6 第 5 条）：**默认开启但可关**；关掉后 `PulseScheduler` 会立刻 `cancelPeriodic`。

周期输入下限由 `WorkManagerPulseScheduler.MIN_PERIODIC_MINUTES = 15` 兜底，UI 直接给几档建议值
（如 60 / 120 / **240 默认** / 480）即可，不需要写文案以外的逻辑。

### 2.3 通知投放 —— 新增 `PulseNotificationPoster`（必须才能看到效果）

目前 Worker 只把文案落库，**不会弹通知**。三个消息出口，任选其一消费：

1. `PulsePreferences.pendingMessage: Flow<String?>` —— 前台/UI 或后台服务可以直接收；
2. Worker 的 `Result` outputData：`PulseWorker.KEY_MESSAGE`（文案）+ `PulseWorker.KEY_REASON`（原因名）；
3. 消费完调 `pulsePreferences.setPendingMessage(null)` 清空（`markLatestAnswered()` 也会顺带清）。

建议照 `core/reminder/ReminderNotificationPoster.kt` 的写法建一个 `PulseNotificationPoster`，
走同一条通知渠道；`POST_NOTIFICATIONS` 权限已声明，但**运行时申请 UX 尚缺**（§3-T6 第 4 条），
和 §3-T6 里「通知运行时权限 UX」是同一件事。

> 注意：`pendingMessage` 是「最近一条未消费文案」，不是消息队列。用户长时间不理它时，
> 下一条会覆盖上一条 —— 这正是想要的行为（不要攒一堆过期的关心）。

### 2.4 用户回应标记 —— `ChatViewModel.kt`（必须，否则退避永不生效）

`PulsePolicy` 靠 `PulseTriggerRecord.answered` 判断"用户有没有理它"。接线点：用户发出任意一条消息时
调一次。

```kotlin
// ChatViewModel 发送路径（或 SendMessageUseCase 入口）
pulsePreferences.markLatestAnswered()
```

语义：把最近一条未回应记录标为 `answered = true`，退避计数归零，并清掉 `pendingMessage`。
用户回一句就够，不需要精确匹配是哪条通知。

### 2.5（可选）文案润色

`PulseMessageComposer` 输出的是确定性中文文案（"这几天你好像一直不太好。上次你说「…」——现在还在为这件事烦吗？"）。
若想让它更口语化，接线方可以在这个文案之上再走一次云端 LLM —— 但这会引入延迟、成本和不确定性，
**默认不建议**，除非产品上明确要更自然的语气。

---

## 3. 明确不做的部分

- 不做危机干预的自动化处置（T8 的范围）。
- 不主动提起敏感记忆：`PulseMemorySelector` 只接受 `sensitivity` 为 `normal`/空 的记忆，`crisis`/`sensitive`
  一律不提 —— 与 T7 的分层、T8 的「永不被 AI 主动提起」一致。接线时**不要**放宽这个过滤。
- 不发空泛问候：记忆窗口内找不到可用素材时（30 天内、非敏感、非图片、内容 ≥ 6 字）直接静默，
  这是刻意的 —— 见 `PulseSkipReason.NO_MEMORY_EVIDENCE`。

---

## 4. 单测覆盖（`app/src/test/java/com/xiaoqi/companion/core/pulse/`）

| 类 | 覆盖 |
|---|---|
| `PulsePolicyTest` | 3 天负向 → 触发；2 天边界；正向 → 不触发；正向日打断连续性；阈值边界（正好 0.6 / 0.9）；极端负向优先于连续负向；空序列；窗口外；陈旧数据；开关关闭；同原因冷却（内 / 边界）；跨原因不互相阻塞；每周上限；每周窗口滚动；退避拉长 / 上限 / 回应后归零 / 5 次静音；未来时间脏数据；时区注入改变切天 |
| `PulseMessageComposerTest` | 素材价值排序（pinned > EPISODE > importance > 新近）；敏感 / 危机 / 归档 / 过期 / 图片 / 过旧 / 过短一律排除；无记忆不发消息；引用真实原话；base64 清洗；超长截断；措辞随天数变化 |
| `PulseSchedulerTest` | 默认周期 enqueue；关开关 cancel；改周期 UPDATE；关了再开重新 enqueue；`start()` 幂等；`triggerNow()` 不动周期任务 |
| `PulseModelsTest` | 历史 JSON 往返；非法 JSON 降级为空；未知 reason 跳过；裁剪；`MoodSnapshotEntity` 降维；默认值对齐 handoff §7 第 3 条 |

**未覆盖**：`PulseWorker` 本体（需要 Robolectric + WorkManager TestDriver + Hilt 测试组件）。
它的分支已被拆薄到只剩"取数 → 调策略 → 调筛选 → 落库"，逻辑风险都在 `PulsePolicy` /
`PulseMessageComposer` 里，两者已全量覆盖。若要补 Worker 测试，务必用 `TestListenableWorkerBuilder`
而不是真跑周期任务。
