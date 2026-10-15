# Aura 二开 · 接手指南

> 交接文档。目标读者：接手开发的工程师（不需要了解前序调研过程）。
> 最后更新：2026-10（已按 owner 的三项补充要求修订）
> 项目：[gqy20/Aura](https://github.com/gqy20/Aura) · 基于 v0.1.6 代码线

---

## 〇、一页摘要

**要做的事**：把开源项目 Aura 改造成一个**私人 AI 情绪陪伴 / 心理倾诉应用**，自用，不上架。

**为什么选 Aura**：它是 GitHub 上唯一一个已经把「长期记忆 + 情绪感知 + 关系模型 + 证据校验的洞察」四件事都实现出来的 Android 项目，README 第一句就是「会记住几个月前你说过的烦心事，会感知到你今天语气不太好」。其余候选都缺一块：LastChat / RikkaHub 缺情绪层，情绪日记类缺 LLM 和长期记忆，memex 的情绪只是个摘要字段。

**Aura 已经给了什么**：情绪状态机、关系模型、长期记忆（含 `sensitivity` 字段）、Mood 趋势图、后台 Dream Loop、**InsightValidator 四道证据校验**、672+ 单元测试、可用 APK。

**本项目的主要工作量**：
1. **砍掉本地模型**（硬需求）——同时能大幅简化构建、缩小 APK
2. **LLM 层加 OpenAI 兼容接口**（硬需求）——解锁任意 OpenAI 兼容供应商
3. **双模式对话**（纯倾听 ↔ 主动指引）——完全没有，需新做
4. **情绪触发的主动关怀**——情绪数据源已有，`core/pulse/` 是空的
5. **敏感内容的记忆策略**——有 `sensitivity` 字段，缺策略
6. **危机信号处理**——完全没有，且所有同类开源项目都没有

---

## 一、产品目标与已确认的决定

**使用者**：一位中文用户，用途是**向 AI 倾诉心事、把它当作情绪支持对象和人生指引者**。

### D1 · 敏感内容「要记，但要有保护」

工程含义：记忆需区分敏感层级；敏感条目独立存储、独立写路径、可单独查看/删除；**不得把"说过一次的症状"固化成"诊断"或人格标签**。

### D2 · 「只在情绪不好时」主动关心

需要：情绪检测（已有）→ 情绪状态序列（已有）→ **触发策略（缺）**。不允许定时无差别打扰。

### D3 · 洞察「必须有原话依据」

每条 AI 对用户的判断必须能溯源到真实消息 ID，用户可点开查看原文。**Aura 的 `InsightValidator` 已实现此机制，保留并加强。**

### D4 · 双模式：平时聊天/倾听 + 遇到问题能给指引

默认倾听；用户问"我该怎么办/你觉得呢"或显式请求时切到指引；**该偏好要写进长期记忆**，用几次后自动学会何时给建议。

### D5 · 只走云端，不要本地模型 ⭐ 新增

移除所有设备端推理能力。这既是需求，也**大幅降低构建门槛和 APK 体积**（详见 §3-T2）。

### D6 · LLM 层要支持 OpenAI 兼容接口，而不是只有 Anthropic ⭐ 新增

需要能配置任意 OpenAI 兼容端点。详见 §3-T3。

> **⚠️ 一个事实澄清（不影响执行，但影响优先级判断）**：DeepSeek **官方原生支持 Anthropic Messages 格式**（`base_url = https://api.deepseek.com/anthropic`，见 <https://api-docs.deepseek.com/guides/anthropic_api>），所以**不改 D6 也能用 DeepSeek**。改 D6 的真实收益是**解锁任意 OpenAI 兼容供应商**（OpenAI / 通义 / OpenRouter / 各类中转站），避免绑定单一协议——这是个合理的目标，只是要清楚它的成本（§3-T3 是四个 P0 任务里最重的一个）。

### 隐私立场（owner 已明确）

**owner 不担心隐私泄露，因为全部走官方 API。** 因此本项目**不做**以下加固：
- ❌ 云端洞察生成的知情同意门禁
- ❌ 通知内容脱敏 / 锁屏隐藏
- ❌ 记忆库加密（SQLCipher / Tink）
- ❌ 敏感字段的额外访问控制

**唯一仍建议保留的相关护栏是 `base64 不进 prompt`** —— 但请注意它的性质已经变了：原本是为了隐私 + 本地 Qwen 的小上下文，现在**它是一条纯性能护栏**（避免把几十 KB 的图片 base64 塞进每次请求，浪费 token 和带宽）。保留成本极低，建议留。

### 明确不做

- 不做危机干预的**自动化处置**（但需要识别并升级提示，见 §3-T8）
- 不做本地 LLM 推理
- 不做多角色 / 角色扮演
- 不上架、不分发（受 License 约束，见 §5-R1）

---

## 二、Aura 现状

### 2.1 技术栈

| 项 | 值 | ⚠️ 注意 |
|---|---|---|
| 语言 | Kotlin 2.3.21 | |
| AGP | 9.2.0 | |
| **JDK** | **21**（`JavaVersion.VERSION_21`） | ⚠️ **必须 21**，17 编译不过 |
| compileSdk / targetSdk | **36** / 36 | 相对友好 |
| minSdk | 26（Android 8.0+） | |
| **buildToolsVersion** | **"37.0.0"**（`app/build.gradle.kts` 硬编码） | ⚠️ 需确认 SDK 源是否提供；拿不到就改 36.0.0 并验证 |
| NDK / CMake | 27.0.12077973 / 3.22.1 | ⚠️ **做完 T2 后应完全移除** |
| UI | Jetpack Compose + Material 3 | |
| 架构 | MVVM + Repository + UseCase，Hilt | |
| **Agent 框架** | **Koog 0.8.0（JetBrains）** | 关键依赖；Koog 本身原生支持 OpenAI/DeepSeek，见 §3-T3 |
| 网络 | OkHttp 4.12.0（自研 `AnthropicMessagesLLMClient` + SSE） | |
| 数据库 | Room 2.8.4，**DB schema v10** | |
| SQLite | `androidx.sqlite:sqlite-bundled` 2.6.2 | 已含 FTS5（trigram） |
| 测试 | JUnit4 + Turbine + MockK + Robolectric；**672~694 单测全绿，CI passing** | 改动请保持全绿 |

### 2.2 架构骨架

设计原则是「**自研"灵魂"，外包"器官"**」。

```
app/src/main/java/com/xiaoqi/companion/
├─ feature/         # UI：chat / settings / memory / onboarding / insight
│   └─ chat/        #   ChatViewModel（编排器，8-9 个 collector）
├─ core/
│   ├─ companion/   # ★ CompanionRuntime 主循环（自研核心）
│   │   ├─ EmotionStateMachine(.Impl) / RelationshipModel
│   │   ├─ KoogAgentFactory(.Impl)
│   │   └─ ProviderCapabilities.kt        ← D6 要改这里
│   ├─ llm/         # ★ LLM 层                        ← D6 主战场
│   │   ├─ AnthropicMessagesLLMClient.kt  （自研 client + Factory）
│   │   ├─ KoogPromptExecutorFactory.kt
│   │   └─ LlmConnectivityChecker.kt
│   ├─ prompt/      # ★ PromptBuilder 组装引擎        ← D4 改这里
│   ├─ insight/     # ★ InsightPrompts / InsightValidator / EvidenceResolver
│   ├─ presence/    #   PresenceController / PresenceReactionPolicy
│   │   └─ runtime/ #   DreamLoopWorker / DreamDataCollector
│   │                 + LocalQwenExecutor.kt          ← D5 要删
│   ├─ local/       #   本地 Qwen（MNN）全套           ← D5 要删
│   ├─ pulse/       # ⚠️ PulsePolicy / PulseScheduler —— 规划中，空  ← D2 做这里
│   ├─ memory/      #   MemoryManager / MemorySelector
│   ├─ reminder/    #   提醒（AlarmManager + Worker）已实现
│   └─ tools/       #   Agent tools / ToolPolicy / KoogResultKindPatcher
├─ data/            # Room（9 Entity / 9 DAO）、DataStore、Repository
└─ platform/        # speech / camera / notification / widget / permissions
```

Room 表：`messages`、`memories`、`memory_summaries`、`agent_state`、`mood_snapshots`、`tool_calls`、`reminders`、`insights`、`health_snapshots`，另有 FTS5 `message_search_docs(_fts)`。

### 2.3 已实现（可直接用，不要重写）

- ✅ **情绪状态机** `EmotionStateMachine`：`record(mood, intensity, reason)` / `currentMood` / `getContext()`，状态含 HAPPY / TIRED / SEARCHING 等，已注入 Prompt
- ✅ **关系模型** `RelationshipModel`：亲密度 / 熟悉度累积，影响称呼、语气、建议倾向
- ✅ **长期记忆**：`MemoryEntity` 含 `type / importance / confidence / sourceMessageIds / expiresAt / sensitivity / lastAccessed / pinned / archived / imageBase64`；LLM reflection 后置写入；支持搜索、置顶、归档、删除；有 `MemoryRoomScreen` 可浏览
- ✅ **Mood 快照 + 趋势图**：`MoodSnapshotEntity` + `MoodTrendChart`（Compose Canvas 周柱状图，近 28 天）
- ✅ **Insight 系统（D3 的核心已做完）**：
  - `insights` 表 14 字段 + 3 索引
  - `InsightValidator` **四道校验**：缺 evidence / evidence 50% 真实存在 / confidence < 0.6 / 30 天内标题 Jaccard > 0.8
  - `InsightRepository.saveIfValid` —— **写路径必须过 Validator**
  - `EvidenceResolver` —— 把证据 ID 兜底解析回真实消息
  - UI：`InsightCard` / `InsightCardList` / 长按弹层（本周不再说 X 类 / 知道了 / **查看依据** / 和 Aura 聊聊）
  - 已实机验证：本地 Qwen 4B 生成真实 insight 并落库（**这条链路 T2 会换成云端**）
- ✅ **后台 Dream Loop**：`DreamLoopScheduler` 支持 7 档周期（OFF / 15min / 30min / 1h / 3h / **6h 默认** / 12h）+ 立即跑一次按钮；有电量约束
- ✅ **Vision → Memory 闭环**：用户发图自动写 `type=FACT, source=reflection:vision` 记忆（含 base64）；Dream 只拿 metadata
- ✅ **隐私面板** `DataTransparencySection`：条数展示 + JSON 全量导出（SAF）+ 3 个清空入口 + 二次确认
- ✅ **Onboarding 5 问**：最近挂心事 / 未来重要日期 / 称呼 / 关系人 / 作息（全部可选可跳过）
- ✅ **健康数据**：Health Connect + SensorManager 双源，`QueryHealthDataTool` 可查
- ✅ **提醒** Reminder：AlarmManager + Worker + 通知
- ✅ **聊天体验**：流式（33ms ticker 匀速出字）、停止/重试/重新生成、编辑后重发、跨会话 FTS5 消息搜索、跨天日期分隔、AI 头像、关系阶段徽标、空态建议 prompt、Markdown 链接可点

### 2.4 未实现（与本项目相关）

- ❌ **Pulse / 主动关怀**：`core/pulse/` 只有规划。M5 明确列「离线衰减 / 回归反应 / 主动通知 Worker 未做」
- ❌ **双模式对话**：无任何模式概念
- ❌ **危机干预**：无任何代码（grep crisis / 自杀 = 0）
- ❌ **OpenAI 协议支持**：只有 Anthropic 格式
- ❌ **Weekly Insight 自动汇总**、**InsightLog 反馈回路（👍/👎）**
- ❌ **Connection 类 insight 端到端**（prompt 字面量已定义，未跑通）
- ❌ **AnniversaryScannerWorker**（重要日期扫描）
- ❌ CameraX 拍照 UI（当前用 Photo Picker）
- ❌ 语音 I/O（STT/TTS）、Rive/Lottie 角色动画（当前 Compose Canvas 替代）

---

## 三、待开发功能清单

### P0 —— 必须先做

#### T1. 让项目跑起来

- 装 **JDK 21**
- 处理 `buildToolsVersion = "37.0.0"`（拿不到就改 36.0.0 并验证）
- 配 `ANDROID_HOME` + `local.properties`
- `.env.example` → `.env`（已 gitignore）

**验证命令**：`./gradlew.bat testDebugUnitTest`（应 672+ 全绿）→ `./gradlew.bat assembleDebug` → 真机安装

**验收**：单测全绿、APK 装上、能发消息收到回复。

---

#### T2. 移除本地模型（D5）

**要删的东西**（清单已核实）：

| 位置 | 内容 |
|---|---|
| `core/local/` | `LocalQwenEngine` / `MnnLocalQwenEngine` / `NativeMnnLlmBridge` / `LocalQwenModelDownloader` / `LocalQwenModelCatalog` / `LocalQwenModelLocator` |
| `core/presence/runtime/` | `LocalQwenExecutor.kt`（+ 其测试） |
| `core/companion/` | `ReactiveCompanion`（原 `LocalQwenAgentWrapper`）及其分支 |
| 枚举 | `LlmProvider.LOCAL_QWEN`（**删枚举值后编译器会强制你补 `ProviderCapabilityRegistry`**，很好） |
| 构建 | `app/src/main/cpp/`、`externalNativeBuild {}`、CMake 3.22.1、`ndk {}`、`AURA_MNN_HOME`、`syncAuraMnnNativeLibs` task、`abis` 相关 |
| 配置 | `.env` 里的 `ENV_LOCAL_QWEN_MODEL` / `ENV_MODELSCOPE_API_KEY`、设置页的本地模型下载入口、`LlmProvider.MODELSCOPE`（若仅用于模型下载） |
| UI | `Local Qwen` provider 选项、模型下载进度 UI |

**必做的连带改造**：`DreamLoopWorker` 的 **patternDetect 目前跑在 `LocalQwenExecutor` 上**（roadmap 原文：「用户在真机触发 Qwen 模型下载 → DreamLoop 跑出第一条 LLM 真实生成的 insight」）。删掉本地 executor 后，**必须把这条链路改接到云端 provider**，否则洞察功能整体失效。

**注意**：`DreamDataCollector` 生成的洞察 prompt 长度原先受本地 4B 模型的小上下文限制。改成云端后可以放宽，但要复核 token 成本和实际效果——**不要一次塞太多**（原设计是 7 天窗口 + 词频 top 10 + 最近 5 张图 metadata）。

**验收**：
- 项目里搜不到 `mnn` / `MNN` / `qwen` / `LocalQwen` 的残留引用
- `./gradlew assembleDebug` 在**未安装 NDK / 未设 `AURA_MNN_HOME`** 的环境下通过
- APK 体积明显下降（原先含 MNN native libs）
- DreamLoop 能在纯云端下跑出 insight 并落库

---

#### T3. LLM 层加 OpenAI 兼容接口（D6）⚠️ 四个 P0 里最重

**现状（已核实）**：

```kotlin
// data/db/converter/LlmProvider.kt
enum class LlmProvider { GLM, KIMI, MODELSCOPE, LOCAL_QWEN }

// core/companion/ProviderCapabilities.kt
object ProviderCapabilityRegistry {
    fun forProvider(provider: LlmProvider): ProviderCapabilities = when (provider) {
        LlmProvider.GLM -> ...        // supportsStreaming/Vision/Tools/Thinking, maxContextTokens, defaultToolPolicy
        LlmProvider.KIMI -> ...
        LlmProvider.MODELSCOPE -> ...
        LlmProvider.LOCAL_QWEN -> ...  // ← T2 会删掉
    }   // 穷尽 when，没有 else
}

// core/llm/KoogPromptExecutorFactory.kt
class DefaultKoogPromptExecutorFactory @Inject constructor(
    private val clientFactory: AnthropicMessagesLLMClientFactory,   // ← 写死 Anthropic
) : KoogPromptExecutorFactory {
    override fun create(config: LlmConfig): PromptExecutor =
        MultiLLMPromptExecutor(clientFactory.create(apiKey = config.apiKey, baseUrl = config.baseUrl))
}
```

**关键发现**：`DefaultKoogPromptExecutorFactory` **不按 provider 选 client**——它永远创建 Anthropic client，"GLM vs Kimi" 只是 `base_url` + `model name` 的差别，协议全是 Anthropic。所以 D6 的改造核心就是**在这里引入按 provider 分派 client 的逻辑**。

**要做的**：

1. `LlmProvider` 加一个 `OPENAI_COMPAT`（或 `OPENAI`）枚举值 → 编译器会强制你补 `ProviderCapabilityRegistry` 的 `when` 分支
2. **选一条路线实现 OpenAI client**：
   - **方案 A（推荐，改动最小）**：用 **Koog 内置的 OpenAI client**。Koog 官方 README 明确列出支持的 provider 含 **OpenAI** 和 **DeepSeek**，`MultiLLMPromptExecutor` 天然支持多 client 路由。不用自己写 HTTP/SSE。
     - ⚠️ 需确认 Koog **0.8.0**（Aura 当前版本）是否已含 OpenAI client；若不足，可考虑升 Koog（但升版本会波及 `KoogAgentFactoryImpl` / `KoogResultKindPatcher` 等，要评估）
   - **方案 B**：仿照 `AnthropicMessagesLLMClient`（+ 其 Factory）自写一个 `OpenAiChatLLMClient`。控制力最强、与现有代码风格一致、不动 Koog 版本，但 SSE 与 tool_calls 组装要自己写。
3. `DefaultKoogPromptExecutorFactory.create()` 改成**按 `config.provider` 分派 client**（这是结构性改动，别只加分支）
4. `LlmConnectivityChecker` 支持 OpenAI 模式探测（现有测试 `LlmConnectivityCheckerTest` 是回归基线）
5. 设置页 + `.env` 增加 provider 选项与 `base_url` / `model` 输入（`base_url` 字段已存在，可直接复用）
6. **重写测试**：`AnthropicMessagesLLMClientTest` 是 Anthropic 的基线；OpenAI client 需要**同等覆盖的测试**

**最难的部分：工具调用与流式事件映射差异**

| | Anthropic | OpenAI |
|---|---|---|
| 流式事件 | `content_block_start` / `content_block_delta` / `content_block_stop` | `choices[].delta.content` / `choices[].delta.tool_calls[]` 增量拼装 |
| 工具调用 | `tool_use` content block（JSON 整块） | `tool_calls[]` 分片累积（`index` 对齐）+ `finish_reason: "tool_calls"` |
| 结束信号 | `message_delta` / `message_stop` | `finish_reason` |

**必须做的兜底**：Aura 修过一个真实 bug —— GLM 偶发在 `content_block_stop` 前结束 SSE，导致整轮失败（commit `a930b5f`，已有"流干净结束时补齐 pending 工具"的逻辑）。**OpenAI 流式有自己的边界情况**（`finish_reason` 缺失、`tool_calls` 参数被截断成非法 JSON），要写同等级的兜底 + 单测：
- 收到 `finish_reason` 但 `tool_calls` 参数不完整 → 尝试补全，失败则交给 Koog 的工具参数校验
- 流在 `finish_reason` 前中断 → 按已有 pending 工具补齐

**验收**：
- 设置页可切换 Anthropic / OpenAI 兼容两种模式
- OpenAI 模式下能正常流式对话
- OpenAI 模式下**工具调用完整可用**（至少覆盖：`search_memory`、`save_memory`、一个 MCP 工具）
- 断流 / 参数截断的边界用例有单测
- Anthropic 模式无回归

---

#### T4. 接通你选的云端 provider 并做端到端验证

以 DeepSeek 为例（走 OpenAI 兼容格式，因为 D6 之后你会用 OpenAI 通路）：

```
base_url : https://api.deepseek.com        # OpenAI 兼容
          # 或 https://api.deepseek.com/anthropic  （Anthropic 通路，D6 之前的方式）
api_key  : <DeepSeek API Key>
model    : deepseek-flash                  # 或 deepseek-flash[1m] 长上下文
```

**验收**：设置页填完点「连通性检查」返回 OK；正常流式对话；工具调用可用；长会话（>20 轮）不断流、上下文正常裁剪。

---

### P1 —— 核心功能（本项目的实际价值）

#### T5. 双模式对话（D4）—— 体验提升最大，实现最简单

**现状**：`core/prompt/PromptBuilder` 只有单一 persona，无模式概念。

**要做**：
1. 定义两种模式：`LISTEN`（倾听）/ `GUIDE`（指引）
2. **默认 LISTEN**：不安慰、不分析、不急着给方案、不追问
3. **切到 GUIDE 的触发**：
   - 显式：用户说"我该怎么办""你觉得呢""给我点建议"
   - 显式询问：Aura 偶尔问一句「你想让我听着，还是帮你想办法？」
   - 隐式：用户连续追问同一问题 ≥2 轮
4. **模式写进 `PromptBuilder`**，两种模式用不同 system prompt 段落（用现成的模板机制）
5. **D4 的关键**：把该偏好写进长期记忆——记录「用户在什么情境下要建议 / 什么情境下只想被听」，用几次后自动学会，不再每次问
6. **UI 锚点**：当前模式要有轻量可见提示（不要打扰）

**验收**：说心事时它不急着给方案；问"我该怎么办"能切到指引；用几次后同类情境不再问第二遍。

---

#### T6. 情绪触发的主动关怀（D2）

**现状**：
- ✅ 数据源齐了：`EmotionStateMachine`（含 intensity）+ `MoodSnapshotEntity`（历史序列）+ `MoodTrendChart`
- ❌ `core/pulse/PulsePolicy` / `PulseScheduler` 是空的
- ❌ M5 的 Pulse Worker 未实现
- ❌ `POST_NOTIFICATIONS` 权限已声明但无运行时 UX

**要做**：
1. 实现 `PulsePolicy`：从 `mood_snapshots` 读序列，**建议判定条件**
   - 连续 ≥2 天负向情绪（`intensity` 超阈值）
   - 或单次极端负向
   - **加冷却**：同一触发原因 N 天内不重复（避免变成骚扰）
2. 实现 `PulseScheduler`（WorkManager 周期任务，**直接复用 `DreamLoopScheduler` 的 `WorkScheduler` 接口 + `ExistingPeriodicWorkPolicy.UPDATE` 模式**，不要另起一套）
3. 生成主动消息：**必须基于记忆**（"上次你说那件事，还好吗"），不要空泛问候
4. 通知运行时权限 UX
5. **开关默认开启但可关**；提供"多久没理它就别再来"的退避

**验收**：模拟 3 天负向 mood → 收到一条基于真实记忆的关心；模拟正向 mood → 不打扰；连续触发有冷却。

---

#### T7. 敏感内容记忆策略（D1）

**现状**：`MemoryEntity.sensitivity` 字段已存在，但未见策略层使用。

**要做**：
1. **明确分层**：普通 / 敏感（健康、财务、关系、情绪困扰）/ 禁止入库
2. **敏感条目隔离**：独立存储或独立查询路径；**不进 memory 的 `description` / 摘要**（避免在列表里暴露）；默认在 `MemoryRoomScreen` 折叠
3. **只记用户原话，不记推断**（关键）：参考 RikkaHub 的 `[stated]` 规则——每条记忆必须是用户**亲口说过的**
   - ❌ 不记 AI 的推断（"喜欢 X" → "喜欢整个 X 类"）
   - ❌ 不记 AI 的建议/计划（哪怕用户回"好的"）
   - ❌ 不记搜索结果/外部数据
   - ❌ **不记 AI 对用户人格的推断**
4. **症状 ≠ 诊断**：提到的症状或用药**不得**变成存储的诊断
5. **可单独遗忘**：用户说"忘掉这件事" → 删除该条**及其派生条目**，不是改写成"曾经…"
6. **独立写路径**：敏感信息单独一次写操作，在普通写入之后执行

**验收**：边界用例单测（推断不入库、症状不成诊断、敏感不外露、删除连带派生条目）。

---

### P2 —— 补完与加固

#### T8. 危机信号处理策略（全新，无参考）

**重要**：调研过的**所有同类开源项目都没有这个功能**（memex grep crisis/自杀 = 0，LettuceAI 同样没有）。这是唯一的空白，必须自己做。

**不是要写医疗软件**，而是要明确回答几个问题并把答案变成代码：
1. **识别**：要不要识别危机表达？（建议：是，轻量判定，只在本地打标记）
2. **记忆策略**：这类内容入不入记忆？
   - 入：能纵向追踪，但可能把痛苦固化成标签
   - 不入：更安全，但用户每次要重新说
   - 建议：入，但独立最高密级 + 永不进摘要 + **永不被 AI 主动提起**，除非用户当轮主动谈起
3. **回应策略**：识别到之后换成什么回应方式？（不评判、不惊慌、不机械念热线）
4. **升级提示**：什么条件下提示线下专业支持？文案是什么？（克制、不说教）
5. **AI 边界**：system prompt 明确它是情绪支持者而非治疗师

**验收**：设计决策写成文档存档；边界用例单测；**人工走查一遍真实对话**。

---

#### T9. 补完洞察流水线

- **Connection insight 端到端**：`InsightPrompts.connectionDetect` 已定义（跨 90 天找两条数据的潜在联系，`confidence` 必须 < 0.5 宁缺毋滥），未接通
- **AnniversaryScannerWorker**：`InsightPrompts.anniversaryScan` 已定义（未来 14 天：生日/纪念日/deadline/"想做但没做的事"），未接 worker
- **Weekly Insight 汇总**：建议周日 21:00 推 1 条，同类去重
- **InsightLog 反馈回路**：👍/👎/文字 → 落表
- **加强 D3**：确保 UI 上**每条洞察都能点开看到原始对话原文**（`EvidenceResolver` + 长按"查看依据"已有，补全链路）

#### T10. 稳定性

- 单测保持全绿（当前 672~694）
- 补 instrumented tests
- 长会话回归：跨天/跨周/跨月后记忆与洞察是否正常
- 数据迁移：改 schema 必须写 Room migration + migration test

---

## 四、建议的推进顺序

```
T1 跑起来
   ↓
T2 砍本地模型  ──┐（可并行，砍完构建立刻变轻）
   ↓            │
T3 LLM 加 OpenAI 兼容  ← 最重，单独排期
   ↓
T4 接通云端并端到端验证
   ↓
T5 双模式（体验提升最大，先做）
   ↓
T6 情绪触发主动  +  T7 敏感策略（可并行）
   ↓
T8 危机策略  +  T9 补完洞察
   ↓
T10 加固
```

**先做 T2 的理由**：它是纯删除，没有设计难度，做完立刻让构建从"要 JDK21 + NDK + CMake"降到"只要 JDK21"，后续所有人的效率都受益。

**先做 T5 的理由**：改动局限在 `PromptBuilder` + 一处记忆写入，风险低、体验提升最直接，而且能立刻用真实对话验证方向——**用它确认产品方向对不对，再投入后面更重的活**。

---

## 五、关键约束与已知坑

### R1. License ⚠️

**仓库没有 LICENSE 文件，README 标注 `License: Private`。**
→ 自用、自行修改没问题；**不得分发、不得上架**。若将来想开源，必须先联系作者取得授权。

### R2. 协议与 API

- 当前 LLM 层是 **Anthropic Messages API 兼容格式**（自研 `AnthropicMessagesLLMClient` + OkHttp SSE）
- **DeepSeek 原生兼容 Anthropic 格式**（`https://api.deepseek.com/anthropic`），所以 T3 完成前也能用
- D6 之后将同时支持 OpenAI 兼容格式
- ⚠️ **tool use / SSE 事件序列必须实测**，两种协议的边界情况不同，见 T3
- 联网搜索走 MCP（`AppPreferences.mcpProviderId` / `mcpApiKey` 已有），不是内置

### R3. 构建环境

| 要求 | 说明 |
|---|---|
| JDK **21** | 必须，17 不行 |
| compileSdk / targetSdk **36** | |
| buildToolsVersion **37.0.0** | ⚠️ 硬编码在 `app/build.gradle.kts`，拿不到就改 36.0.0 |
| NDK 27 / CMake 3.22.1 / `AURA_MNN_HOME` | ⚠️ **T2 完成后全部不再需要** |

### R4. 代码里的既有陷阱（前人踩过，已记录在 `AGENTS.md` 和 roadmap）

- GLM 偶发在 `content_block_stop` 前结束 SSE → 已有补齐逻辑（`a930b5f`）。**换协议/换 provider 必须复测工具调用链路**
- FTS5 androidTest 需补 `SQLiteConnection` 回调变体（`setDriver(BundledSQLiteDriver)` 下老 `onCreate` 不触发，FTS 表建不出来）
- `MoodSnapshotEntity` 有外键依赖，插入前需先有 `agent_state` parent row
- 模拟器 ADB：`input text` 空格会被切分；Git Bash 重定向会损坏二进制
- 项目有中文注释规范（`AGENTS.md` / `CLAUDE.md`），改动请遵守既有风格

### R5. 数据

- 记忆、情绪、洞察全部本地存储（Room + DataStore）
- **owner 已确认不做隐私加固**（见 §1），无需为隐私增加门禁或加密
- ⚠️ **纯技术注意**：`memories.imageBase64` 存的是图片原图 base64，**导出 JSON 时会把它们全带上**，文件可能非常大。若导出功能体验有问题，可考虑导出时剥离图片或单独打包
- `base64 不进 Dream prompt` 的护栏建议保留（现在是纯性能考量），改动时**不要破坏它的测试**

---

## 六、可直接参考的实现（省时间）

| 要解决的问题 | 参考项目 | 具体位置 |
|---|---|---|
| **OpenAI 协议的 Kotlin 实现** | [JetBrains/koog](https://github.com/JetBrains/koog) | 官方支持 OpenAI / DeepSeek provider；`OpenAILLMClient` + `MultiLLMPromptExecutor`。**先查 Koog 0.8.0 是否已有，或评估升级成本** |
| **情绪检测（端侧分类器）** | [LettuceAI/app](https://github.com/LettuceAI/app) | `src-tauri/src/embedding/emotion.rs` —— GoEmotions 28 标签 ONNX int8 分类器；`storage_manager/companion_turn_effects.rs` 的 `emotion_delta` / `relationship_delta` 闭环 |
| **敏感标记 + 证据溯源** | [Cocolalilal/LastChat](https://github.com/Cocolalilal/LastChat) | `TemporalMemoryModels.kt` —— 记忆条目带 `sensitive` / `source_message_id` / `valid_from` / `valid_until`；`op: add\|reinforce\|supersede\|close` 状态演化 |
| **敏感内容的伦理护栏（写得很细）** | [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) | `data/ai/prompts/MemoryPrompts.kt` —— `[stated]` 测试、症状不成诊断、敏感信息单独写操作、不记人格推断；`data/ai/tools/MemoryContentGuard.kt` —— 身份证（校验位）+ 银行卡（Luhn）正则兜底 |
| **"什么时候该回忆"的提示词设计** | 同上 | `MemoryPrompts.kt` 的 `MEMORY_SYSTEM_RULES` —— 何时读/写、如何自然表达（"Do not say 'according to my memory'"）、不要为了展示记忆而硬加个人化 |
| **时间线 / 情绪轨迹摘要** | [memex-lab/memex](https://github.com/memex-lab/memex) | `lib/agent/memory/character_memory_service.dart` —— `timeline.jsonl` 跨场景事件流 + `checkpoints.jsonl` 滚动摘要（模板含 Emotional Trajectory 字段）；`character_context_compressor.dart` 按真实 `promptTokens` 0.55/0.70 阈值触发压缩 |
| **情绪 + 记忆的完整设计文档** | Aura 自己 | `docs/plan/insight-driven-product.md`、`docs/plan/dual-mind-architecture.md` |

---

## 七、未决问题（需要产品 owner 拍板）

1. **T3 路线选择**：用 Koog 内置 OpenAI client（省事，但可能要升 Koog 版本）还是自写 client（可控，但要自己处理 SSE/tool_calls）？**建议先花半天验证 Koog 0.8.0 的 OpenAI 支持程度再定。**
2. **T8 危机策略的具体边界**：识别到什么程度？提示线下支持的条件和文案？**需要 owner 亲自定，不宜由工程师自行决定。**
3. **主动关怀的频率上限**：多久算骚扰？（建议：同一原因 7 天内最多 1 次，总体每周最多 2 次）
4. **洞察的"敢说程度"**：`InsightValidator` 当前阈值是 `confidence ≥ 0.6` + evidence 50% 真实存在。要不要更严？（越严越不容易说错，但也会显得"没什么洞察"）
5. **数据保留期**：`memories` 有 `expiresAt` 字段但未见策略。情绪快照、敏感记忆要不要定期清理？

---

## 八、上手第一天

```bash
# 1. 环境
#    装 JDK 21；确认 Android SDK 有 platform 36 + build-tools 36.0.0（或 37.0.0）
#    设 ANDROID_HOME
#    注意：T2 完成后不再需要 NDK / CMake

git clone https://github.com/gqy20/Aura.git
cd Aura
cp .env.example .env      # 填 provider / API Key（已 gitignore）

# 2. 先确认现状可用（不要跳过）
./gradlew.bat testDebugUnitTest     # 期望 672+ 全绿
./gradlew.bat assembleDebug         # 期望出 APK

# 3. 装到真机，用几天，只观察三件事：
#    - 它记得住你说过的心事吗？
#    - 它有没有在你情绪不好时察觉到？（看顶栏情绪状态 + 主页 Mood Trend）
#    - 主页 Insight 卡片里说的，是不是你真的说过的话？（点"查看依据"核对）
```

**第 3 步是这份文档里最重要的动作。** 在写任何代码之前，先用真实使用确认这个 App 的方向对不对——这比 T3 的技术选型重要得多。如果连 Aura 现有的表现都让你觉得"不过如此"，就先回来重新讨论产品方向，不要急着投入 T5 之后的工作量。

---

## 附录：本次修订记录

相对上一版，按 owner 补充要求做了三处修改：

1. **新增 D5「只走云端，不要本地模型」** → T2 从"优化建议"升级为**硬需求**，并补全了完整的删除清单（含必须连带的 `DreamLoopWorker` 改接云端）
2. **新增 D6「LLM 层支持 OpenAI 兼容接口」** → 新增 T3，含 Aura 现有 LLM 层的确切结构、两条实现路线、以及工具调用/流式事件的协议差异对照表
3. **隐私立场更新** → owner 明确不担心隐私泄露（全部走官方 API），因此**移除**了原版中的知情同意门禁、通知脱敏、记忆加密等要求，并把 `base64 不进 prompt` 重新定性为**性能护栏**（而非隐私护栏）
