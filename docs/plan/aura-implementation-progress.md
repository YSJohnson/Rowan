# Rowan 二开实施进度

依据根目录 `aura-handoff.md`（上游交接文档，未随改名重写）。源码起点：`gqy20/Aura`，`5d815b3`（v0.1.6 代码线）。

## T1：原版构建基线

2026-10-15，在未修改业务源码的 Rowan 工作区执行：

```text
:app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL in 10m 41s
694 tests, 0 failures, 0 errors, 0 skipped
```

原版 APK：`app/build/outputs/apk/debug/app-debug.apk`。原始输出保存在本机 `build/baseline.log` 和 `app/build/test-results/testDebugUnitTest/`；这些是生成文件，不提交。

已在本机安装 Microsoft OpenJDK 21.0.12.1、Android SDK Platform 36、Build Tools 37.0.0、Platform Tools 37.0.1、NDK 28.2.13676358、CMake 3.22.1；Gradle Wrapper 自动取得 9.4.1。SDK 位于 `%LOCALAPPDATA%/Android/Sdk`，JDK 位于 `%USERPROFILE%/.local/share/rowan-tools/`。`local.properties` 和 `.env` 均为本机配置，未提交。

下载和 Gradle 的 HTTP/HTTPS 请求使用 `http://127.0.0.1:7890`，未修改全局 Java 17 环境。JDK 的 Adoptium 源曾返回 403，改用微软官方发行版；最新 SDK CLI 的 `--licenses` 已失效，补装 Command-line Tools 19.0 后完成许可证处理。实际 AGP 默认需要 NDK 28.2，与交接文档记录的 27 不同，未修改项目版本以迁就环境。

同一台机器的 PowerShell 复现命令（仅修改当前进程环境）：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.local\share\rowan-tools\jdk-21.0.12.1+1"
$env:JAVA_TOOL_OPTIONS = '-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.nonProxyHosts=localhost|127.*|[::1] -Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2'
./gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

没有连接 ADB 设备，也没有填入云端 API Key，因此设备端聊天、MCP、洞察和通知的人工冒烟验收尚未执行。单元测试通过不等于这些真实服务链路已验收。

## 实施顺序与状态

- T1 原版测试与 APK 构建：已通过；设备/真实服务冒烟待执行。
- T3 OpenAI 兼容：已完成并合回主目录，提交 `48d8c67`。
- T2 移除本地模型并改接云端洞察：**已完成**（见下）。
- T5 双模式对话：**已完成**（见下）。
- T6 情绪触发主动关怀（Pulse）：**已完成**（见下）。
- T7 敏感内容记忆策略：**已完成**（见下）。
- T4 接通云端并端到端验证：**待 owner 提供 API Key**（代码侧已就绪，见下）。
- T8 危机策略 / T9 补完洞察 / T10 加固：未实施（T8 需 owner 先拍板边界，见 rowan-handoff §7 第 2 条）。

原交接文档未改写，未向远程仓库推送。

## T2：移除本地模型（D5）

### 云端洞察执行器

新增 `core/insight/CloudInsightExecutor.kt`，取代 `LocalQwenExecutor`。Dream Loop（`patternDetect`）与
POST_CHAT（`conversationReflection`）两条后台链路现在共用它，因此与主对话走同一份 Provider 配置
（`KoogPromptExecutorFactory` + `LlmConfig.toKoogModel()`），Anthropic 与 OpenAI 兼容两种协议行为一致。

- 输入/输出预算：`maxTokens = 512`、`temperature = 0.3`、单次调用上限 90s（`Request.timeoutMs` 可覆盖）。
  超时按失败处理，交给 WorkManager 退避重试。
- 失败分类：`API Key` / `Base URL` 缺失 → `Result.failure`（重试不会自愈）；网络与超时 → `Result.retry`。
- 原 `LocalQwenExecutor.parsePatternDetectOutput` 的三层 JSON 容错迁到 `core/insight/InsightOutputParser.kt`
  （标准 JSON → 归一化修复 → 正则兜底），行为等价，迁移后另有 13 项测试覆盖。
- `DreamDataCollector` 的 prompt 结构未改（仍是 7 天窗口 + 词频 + 视觉 metadata），
  `base64 不进 prompt` 的性能护栏保留，对应测试未破坏。

### 删除清单

| 位置 | 内容 |
|---|---|
| `core/local/` | 整个目录（含 `LocalQwenEngine` / `MnnLocalQwenEngine` / `NativeMnnLlmBridge` / 下载器 / catalog / locator / `ReactiveCompanion` / `LocalModelPreloader`） |
| `core/presence/runtime/` | `LocalQwenExecutor.kt` |
| `app/src/main/cpp/` + `app/src/main/jniLibs` | native 源码目录 |
| 构建脚本 | `externalNativeBuild {}`、`ndk {}`、`abiFilters`、`CMake 3.22.1`、`AURA_MNN_HOME`/`auraMnnHome`、`syncAuraMnnNativeLibs`、`jniLibs` sourceSets、`useLegacyPackaging`、`packaging.jniLibs` |
| 配置 | `.env.example` 的 `LOCAL_QWEN_MODEL`、`gradle.properties` 的 MNN 说明块、`BuildConfig.ENV_LOCAL_QWEN_MODEL` |
| 基准脚本 | `scripts/mnn_benchmark.py`、`scripts/mnn_benchmark.yml`、`Makefile` 的 `benchmark-mnn` / `benchmark-aura`、`MainActivity` 的 benchmark intent 入口 |
| 纯文本残留 | `LogTags.LocalModel` 常量、各文件里指向 `LocalQwenExecutor` / MNN 的注释 |

`LlmProvider` 删掉 `LOCAL_QWEN` 后穷尽 `when` 强制补齐了 `ProviderCapabilityRegistry`、`DefaultLlmValues`、
`LlmConfigStatus` 三处，编译器帮助确认无遗漏分支。

### 持久化值迁移（避免升级即崩）

删除枚举值后 `LlmProvider.valueOf()` 会在存过 `LOCAL_QWEN` 的设备上抛异常。已处理：

- `LlmProvider.fromStoredName(stored, fallback)`，`AppPreferences.llmProvider` 改用它 —— 未知值落到默认 Provider。
- `AppPreferences.dreamLoopModelName` 整套删除（UI 消费方已不存在；该键现在被忽略而不是回退成云端模型 ID）。
- 覆盖测试：`ConvertersTest` 的 `fromStoredName` 三条；`ConfigRepositoryTest` 的真 `AppPreferences` + 内存 DataStore
  端到端升级用例（写入 `llm_provider="LOCAL_QWEN"` + `Qwen3.5-0.8B-MNN`，读出 GLM 且不崩）。

## T3：OpenAI 兼容接口

实现内容：

- 新增 `OPENAI_COMPATIBLE` Provider，支持自由填写模型 ID、Base URL、API Key；UI 明确改为保存或测试连接时持久化。
- `CloudLlmModels.kt` 统一按协议创建 Koog `LLModel`；OpenAI 明确使用 Chat Completions，现有云端 Provider 保留 Anthropic 路由。
- URL 接受根地址、`/v1`、`/v1/` 和带路径前缀的 `/proxy/v1/`，保留前缀；探测请求使用相同地址规则和对应鉴权头。
- 复用 Koog 0.8.0 的 OpenAI 请求构造、工具序列化和 HTTP 实现。回归测试证实其原生流式归并会截断交错工具参数，因此 `OpenAiCompatibleLLMClient` 仅覆盖流式帧归并：按 index 累积 ID、名称、参数，拒绝截断或过滤后的工具调用。
- 自定义服务商密钥只写 per-provider map；双向隔离旧字段回退。切换设置 Provider 时，异步加载旧密钥不会覆盖新 Provider 或用户已编辑的草稿。
- Debug 预填新增 `LLM_BASE_URL`、`OPENAI_API_KEY`；切换 Provider 同步重置地址和模型，新增密钥保留其他 Provider 的已有配置；删除 `.env.example` 重复区块。
- 未新增依赖。新增真实本机 HTTP 测试，覆盖地址和头部、工具历史、交错分片、截断结束；配置测试覆盖模型/地址持久化、密钥隔离及启动预填。

2026-10-15 验证：独立工作区执行 **705 项测试，0 失败、0 错误、0 跳过**；新增 11 项回归测试。
`assembleDebug` 成功，Lint 0 Fatal、0 Error、84 Warning。原版 APK 与测试 XML 保留为
`build/baseline-app-debug.apk`、`build/baseline-test-results/`。

## T5：双模式对话（D4）

- `core/companion/ChatMode.kt`：`LISTEN`/`GUIDE` 枚举 + 纯函数 `ChatModeResolver`（时间与历史全部注入）。
  判定优先级：模式提问回答 → 拒绝措辞 → 索取措辞 → 短肯定 → 同话题追问 ≥2 轮 → 默认 LISTEN。
  关键护栏：隐式追问必须同时是问句，所以连发同话题**倾诉**不会被切成 GUIDE。
- `core/prompt/PromptBuilder.build(..., mode = ...)`：两模式 system prompt 段落紧跟人设进固定前缀；
  `BuiltPrompt.mode` 供 UI 读。`core/prompt/templates/ChatModePrompts.kt` 提供文案（yml `mode_listen`/`mode_guide` 可覆盖）。
- `core/companion/ChatModeHolder.kt`：`@Singleton` 进程内共享位。`ChatViewModel` 每轮判定后写入，
  `CompanionRuntime` 组装 prompt 时读一行 —— 比给 `send()` 加参数波及面小得多。
- `core/companion/ChatModePreferenceStore.kt`：偏好按 8 类情境分层落长期记忆，
  只写**显式**信号（`ChatModeDecision.explicit`），隐式追问是推断、按 D1 不落库；同一情境同模式 7 天冷却。
  `di/ChatModeModule.kt` 提供绑定（必须单例，否则进程内冷却失效）。
- `ChatUiState.chatMode` / `awaitingModeAnswer`；UI 锚点在顶栏状态行。

## T6：情绪触发主动关怀（Pulse · D2）

`core/pulse/` 从空目录建起：

| 文件 | 职责 |
|---|---|
| `PulseModels.kt` | 契约 + 触发历史 JSON 编解码 |
| `PulsePolicy.kt` | **纯函数**判定：连续负向 / 极端负向 / 冷却 / 每周上限 / 退避 |
| `PulsePreferences.kt` | 独立 DataStore 读写（开关、周期、历史、待投放文案） |
| `PulseWorkScheduler.kt` / `PulseScheduler.kt` | 调度，结构照抄 `DreamLoopScheduler` |
| `PulseMessageComposer.kt` / `PulseMemorySource.kt` | 基于真实记忆的确定性文案 |
| `PulseWorker.kt` | `@HiltWorker` 周期检查 |
| `PulseNotificationPoster.kt` | 通知投放（独立 `rowan_pulse` 渠道 + 固定通知 id，新关心覆盖旧关心） |
| `PulseModule.kt` | Hilt 绑定 |

判定参数：负向强度 ≥ 0.6、极端负向 ≥ 0.9、连续 ≥ 2 天、回看 7 天、数据陈旧 > 1 天不打扰；
同一原因冷却 7 天、每周上限 2 次（对齐 rowan-handoff §7 第 3 条）；连续 2 次不理开始拉长间隔
（步长 7 天、上限 28 天），连续 5 次不理彻底静音；用户回一句即归零。

接线：`CompanionApplication` 调 `pulseScheduler.start()`；`PulseWorker` 生成文案后直接投通知
（只有 Worker 能在后台推通知，留给 UI 层收集会导致离开前台彻底静音）；
`ChatViewModel.sendMessage` 调 `pulsePreferences.markLatestAnswered()`（否则退避永不生效）；
设置页提供开关与周期档位。**不主动提起敏感记忆**：`PulseMemorySelector` 只接受 `normal`/空密级。

## T7：敏感内容记忆策略（D1）

- `core/memory/MemorySensitivity.kt`：三档密级（`normal` / `private` / `sensitive`，顺序即强度）
  + `MemorySourceKinds`（区分"用户原话"与"模型推断"）+ 密级信号词表（健康/财务/关系/创伤 → sensitive；
  关系人与角色 → private）+ 保密与遗忘意图识别。
- `core/memory/MemoryWritePolicy.kt`：写路径守卫。`MemoryRepository.saveMemory` 在落库前调用，
  `shouldStore == false` 直接丢弃：
  - 推断来源（跨轮综合、人格推断、外部工具结果）一律拒收；
  - 推断句式（"用户是一个…""用户喜欢整个 X 类"）一律拒收；
  - "忘掉这件事"类意图不落库，转由 `forgetMemory` 执行删除；
  - 症状/病情保持用户归属：`用户有抑郁症` → `用户说自己有抑郁症`（已是归属句式则不动）。
- 密级分层：内容命中敏感词表即升级，用户显式要求保密直接顶到 `sensitive`；
  视觉派生记忆至少 `private`（从图里读出来的不如用户原话可靠）。
- 敏感条目**不特殊存储**（owner 已明确隐私立场，D2 不变），只体现为「不进摘要 / 列表默认折叠 /
  只在当轮原话相关时才进 prompt」。
- `MemoryRepository.forgetMemory(id)`：删除该条**及其派生条目**（派生 = 与目标共享任一 `sourceMessageIds`），
  不是改写成"曾经…"。配套 `MemoryDao.getAll()` 快照查询。
- 覆盖测试 30 项：`MemoryWritePolicyTest`（拒收各分支 / 症状归属 / 密级升级 / 摘要与折叠判定）
  与 `MemorySensitivityTest`（存储值往返 / 未知值降级 / 强度合并 / 词表分类 / 来源集合）。

### 与 T5 的耦合（已处理）

T5 的偏好记忆来源是 `chat_mode:preference`。T7 的守卫最初按"来源不在白名单即拒收"实现，
会把偏好写入全部拒掉；已把该来源显式加入 `MemorySourceKinds.USER_STATED`，
并有 `MemorySensitivityTest.inferred sources are exactly the three non stated kinds` 钉住这个契约。

## 本轮最终验证（T2 / T5 / T6 / T7）

```text
./gradlew.bat testDebugUnitTest assembleDebug
BUILD SUCCESSFUL in 29s
```

- **817 项测试，0 失败、0 错误、0 跳过**（97 个测试类）。基线是 T3 完成时的 705 项，本轮净增 **112 项**：

  | 任务 | 新增测试 | 类 |
  |---|---|---|
  | T5 | 35 | `ChatModeResolverTest` 20、`ChatModePreferenceStoreTest` 15 |
  | T6 | 66 | `PulsePolicyTest` 34、`PulseMessageComposerTest` 18、`PulseModelsTest` 7、`PulseSchedulerTest` 7 |
  | T7 | 36 | `MemoryWritePolicyTest` 20、`MemorySensitivityTest` 10，以及 `MemoryRepositoryTest` 新增 7 项仓库级集成（拒绝不落库 / 症状归属 / 密级 / 连带遗忘） |
  | T2 | 19 | `InsightOutputParserTest` 13、`AppPreferencesTest` 3、`ConvertersTest` 的 `fromStoredName` 3 |
  | T2 删除 | −44 | 只测本地模型行为的用例（引擎 / 下载器 / 软协议 / 本地 provider 能力） |

- Debug APK 构建通过：`app/build/outputs/apk/debug/app-debug.apk`（44.5 MB 量级；精确字节数与 SHA-256
  随最后一次改动变化，以本机 `app/build/outputs/apk/debug/` 为准）。
- **APK 体积口径说明**：`lib/arm64-v8a/librowan_mnn_llm.so`（333,296 字节）确实已从包里消失，
  原生库从 5 个减到 4 个。但**包总体没有变小**（基线 43,910,587 → 现约 44,543,977），
  因为本轮新增的 Pulse / 模式判定 / 记忆守卫代码量超过了省下的 native 体积 —— 交接文档
  §3-T2 预期"APK 体积明显下降"这一点**没有达成**，如实记录。
  真正的收益在构建侧：不再需要 NDK / CMake / `AURA_MNN_HOME`，干净环境即可 `assembleDebug`。
  另外 `abiFilters = arm64-v8a` 必须保留：去掉 CMake 后我一度删掉它，APK 反而涨到 45.6 MB，
  因为依赖自带的 `libsqliteJni.so` 等会按 4 个 ABI 各打一份（sqlite4 单个 ~1 MB）。已恢复并在
  `app/build.gradle.kts` 注释说明；代价是 x86 模拟器装不上，需要时本机临时改这一行。
- 仍未验收：真实云端 API 与设备端链路（需要 API Key 与设备），见下节。

## 下一步

1. **T4 端到端验收需要 owner 的 API Key**：设置页选「OpenAI 兼容」填
   `base_url = https://api.deepseek.com`、`model = deepseek-flash`、填 Key，点连通性检查。
   代码侧已就绪，但**未填 Key 无法验证**，所以设备端聊天 / 工具调用 / 长会话仍属未验收。
2. 设备冒烟待执行：T2 的 Dream Loop 云端产出洞察、T5 模式切换与偏好落库、T6 通知投递与冷却、
   T7 敏感分层在记忆库的实际展示。
3. T8 危机策略需 owner 先定边界（识别到什么程度、何时提示线下支持、文案），
   按 rowan-handoff §7 第 2 条不宜由工程师自行决定。

