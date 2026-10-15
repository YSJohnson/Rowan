# T5 双模式对话 · 接线记录

> 状态：**已全部接线完成**（2026-10 本轮）。判定器、偏好记忆写路径、PromptBuilder 两模式段落、
> Runtime 注入、ChatViewModel 逐轮判定、顶栏锚点都已落地并有单测；本文档保留为**接线落点索引**，
> 接手时按第 2 节核对即可，不需要再动手。
> 相关代码：`core/companion/ChatMode.kt`、`ChatModeHolder.kt`、`ChatModePreferenceStore.kt`、
> `core/prompt/templates/ChatModePrompts.kt`、`di/ChatModeModule.kt`。

## 1. 现状：已能用的部分

| 能力 | 位置 | 说明 |
|---|---|---|
| 模式枚举 | `ChatMode` (`ChatMode.DEFAULT == LISTEN`) | `storageKey` 用于持久化/日志，`displayName`（倾听/指引）用于 UI |
| 判定器 | `ChatModeResolver.decide(message, history, nowMillis, awaitingModeAnswer)` | 纯函数，不持状态；返回 `ChatModeDecision(mode, reason, explicit)` |
| 两模式 system prompt | `PromptBuilder.build(..., mode = ...)` | mode 段落紧跟人设进固定前缀；`BuiltPrompt.mode` 供 UI 读 |
| 偏好写入 | `ChatModePreferenceStore.record(...)` + `MemoryRepositoryChatModePreferenceSink` | 只写显式偏好，按情境 + sensitivity 分层，7 天冷却 |

判定器规则（优先级从高到低）：

1. `awaitingModeAnswer` 且命中倾听措辞 → `LISTEN / MODE_QUESTION_ANSWER`
2. 命中拒绝措辞（别给建议/我只想说说/听我说…）→ `LISTEN / EXPLICIT_DECLINE`
3. 命中索取措辞（我该怎么办/你觉得呢/给我点建议/帮我拿主意…）→ `GUIDE / EXPLICIT_REQUEST`
4. `awaitingModeAnswer` 且整句是短肯定（好/嗯/行/帮我吧…）→ `GUIDE / MODE_QUESTION_ANSWER`
5. 像提问 **且** 30 分钟内同话题已追问 ≥2 轮 → `GUIDE / SAME_TOPIC_FOLLOW_UP`
6. 其余 → `LISTEN / DEFAULT`

`explicit == true` 才写长期记忆：隐式追问是推断，按 D1「不记推断」不落库。

## 2. 待接线动作（按依赖顺序）

### 2.1 模式注入到 prompt（必须，否则两模式只是死代码）

`CompanionRuntime.send()` 里 `promptBuilder.build(...)` 增加一个 `mode` 实参。落点：

```kotlin
// CompanionRuntime.kt:100 附近
val prompt = promptBuilder.build(
    input = input,
    // ...现有参数...
    splitForCache = splitForCache,
    mode = chatModeHolder.current,   // 新增
)
```

模式来源二选一：

- **推荐**：新增 `@Singleton class ChatModeHolder { @Volatile var current: ChatMode = ChatMode.DEFAULT }`，
  `ChatViewModel.sendMessage()` 判定后写入、`CompanionRuntime` 读一行。侵入最小，也不用改 Runtime 的方法签名。
- 备选：`send()` 增加 `mode: ChatMode` 参数，由 `ChatViewModel` 逐轮传入（改动面更大）。

### 2.2 ChatViewModel 每轮判定

在 `sendMessage()`（以及编辑后重发/重新生成）里：

```kotlin
val decision = ChatModeResolver.decide(
    message = text,
    history = recentUserTurns,          // 最近若干轮用户发言 + 时间戳
    nowMillis = System.currentTimeMillis(),
    awaitingModeAnswer = uiState.value.awaitingModeAnswer,
)
chatModeHolder.current = decision.mode
```

- `recentUserTurns`：`ChatUiState.messages` 里 role=user 的最近 5 条，映射成 `ChatModeTurn(text, timestamp)` 即可，不需要新 DAO。
- 判定结果同时写进 `ChatUiState.chatMode`，供 UI 锚点读。
- 判定后把 `awaitingModeAnswer` 复位为 false。

### 2.3 「你要我听着，还是帮你想办法？」的显式询问

判定器已经支持 `awaitingModeAnswer`，但目前**没有任何地方把它置 true**。建议：

- 在 `PresenceController` 或 ChatViewModel 的后处理里，当同一话题第 2 次追问（`SAME_TOPIC_FOLLOW_UP`）**尚未**达到时，
  允许 Aura 在回复末尾问一次「你想让我听着，还是帮你想办法？」；
- 提问后把 `awaitingModeAnswer = true` 存进 `ChatUiState`（不落库；进程内即可），下一轮用户回答就会走规则 1 / 4；
- 一旦用户在**同一情境**已经写过偏好记忆，就不再问第二遍（这是 D4「用几次后自动学会」的关键：先查 `ChatModePreferenceStore`
  是否已有该情境的偏好，有就直接用）。

### 2.4 偏好记忆落库（Hilt 绑定）

`MemoryRepositoryChatModePreferenceSink` 已有 `@Inject` 构造，还差一层接口绑定：

```kotlin
// di/DataModule.kt（或 ChatModeModule）
@Provides
@Singleton
fun provideChatModePreferenceSink(impl: MemoryRepositoryChatModePreferenceSink): ChatModePreferenceSink = impl

@Provides
@Singleton
fun provideChatModePreferenceStore(sink: ChatModePreferenceSink): ChatModePreferenceStore =
    ChatModePreferenceStore(sink)
```

写入时机：每轮回复结束后（和现有 POST_CHAT 后处理同一批），仅当 `decision.explicit` 为真：

```kotlin
chatModePreferenceStore.record(
    turn = ChatModeTurn(text, System.currentTimeMillis()),
    decision = decision,
    sourceMessageIds = listOfNotNull(userMessageId),
)
```

### 2.5 UI 轻量锚点（不打扰）

- `ChatUiState` 增加 `chatMode: ChatMode = ChatMode.LISTEN`。
- `ChatScreen` 顶栏关系阶段徽标旁加一个小 chip：文案用 `ChatMode.displayName`（「倾听中」/「指引中」），
  字号小、低对比度、无动效，不弹 toast、不打断输入；点击可手动切换模式（写回 `ChatModeHolder` 与 `ChatUiState`）。
- 手动切换**不要**写长期记忆（用户随时会改主意）；只有判定器给出的显式信号才落库。

## 3. 已知限制 / 后续要补

1. **sensitive 偏好暂时只存不用**：`MemoryRepository.selectPromptContext` 用 `allowPrivateWhenRelevant=false` 过滤
   `private` / `sensitive` 记忆，因此健康/金钱/亲密关系情境的偏好进不了 prompt。要真正生效，需要在该过滤里放行
   `source == "chat_mode:preference"` 的条目（改 `MemoryRepository`，属 T7 范围，本轮未动）。
2. **冷却只在进程内**：`ChatModePreferenceStore` 的重启后可能重复写一次，靠 `MemoryRepository` 的 merge 兜底。
   若嫌脏，可把 `lastWrittenAt` 换成 DataStore —— 但这属于偏好持久化范畴，和 T7 的敏感策略一起做更合适。
3. **判定器是关键词匹配**：中文措辞多样，长句里的「怎么办」可能被误判成索取建议。目前用「倾听措辞优先」+
   「隐式追问必须是问句」压住误判；真实使用后如果发现漏判，往 `ChatModeResolver` 的词表加即可（词表集中在文件顶部）。
4. **多轮追问的窗口是 30 分钟**：跨天追问同一件事不会切 GUIDE，需要时可调 `FOLLOW_UP_WINDOW_MS`。
