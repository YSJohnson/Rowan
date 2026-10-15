<div align="center">

<img src="docs/brand/rowan-logo.svg" width="120" alt="Rowan · 奥拉" />

# Rowan · 奥拉

**一个有情绪、有记性的 Android AI 陪伴 App。**

会记住几个月前你说过的烦心事，会感知到你今天语气不太好，
会隔一段时间主动找你聊聊。不是聊天机器人，更像一个老朋友。

[Releases](https://github.com/YSJohnson/Rowan/releases) · [架构文档](docs/architecture.md) · [Roadmap](docs/roadmap.md)

</div>

<div align="center">

[![Release](https://img.shields.io/github/v/release/YSJohnson/Rowan?include_prereleases&style=flat-square&label=%E7%89%88%E6%9C%AC&color=blue)](https://github.com/YSJohnson/Rowan/releases)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?style=flat-square&logo=kotlin&logoColor=white)]()
[![License](https://img.shields.io/badge/License-Private-red?style=flat-square)]()

</div>

> **来源说明**：Rowan 是 [gqy20/Aura](https://github.com/gqy20/Aura) 的二次开发（fork 后改名）。
> 上游的官网、Releases 与 CI 徽标不适用于本仓库，故不再引用。


---

## TL;DR

Rowan 是 Android 上的 AI 陪伴 App，主线跑通三件事：

- **跨会话长期记忆** — 事实 / 时刻 / 习惯自动归档，回复前自动召回相关条目
- **后台 Dream Loop + 对话后即时洞察** — 6h 周期模式识别 + 每次对话结束 3 分钟后即时分析，生成 Insight 卡片
- **图 + 工具** — 发图能记住画面；Health Connect 步数 / 心率 / 睡眠；定时提醒；MCP server 可配置

全部推理走云端：Anthropic Messages（GLM / Kimi / ModelScope）与 OpenAI 兼容 Chat Completions 双协议。
**不含设备端模型**——本地 Qwen / MNN 链路已整体移除（见 `docs/plan/aura-implementation-progress.md` T2）。

语音 I/O、动画角色 仍在路上。

---

## 目录

1. [它能做什么](#它能做什么)
2. [当前状态](#当前状态)
3. [怎么用](#怎么用)
4. [给开发者](#给开发者)
5. [文档](#文档)

---

## 它能做什么

按"使用频次"从高到低排序，6 项核心能力，每项都已在代码里跑通：

1. **长期记忆** — 跨会话记住你的偏好、习惯、重要的人和事
   - Room 存储记忆 + 摘要，每次回复都从 LTM 拉上下文；reflection 机制让 LLM 自己判断"什么值得记住"
2. **情绪感知** — 识别你当下的心情，对话风格随之调整
   - 情绪状态机持续更新，注入到 Prompt 让回复风格匹配你的状态（HAPPY / TIRED / SEARCHING …）
3. **关系模型** — 认识得越久，越懂你的说话方式
   - 关系亲密度 / 熟悉度随对话累积，影响称呼、语气、建议倾向
4. **图片理解** — 可以发图，能记住画面里的内容
   - Photo Picker 选图 → GLM-5v-turbo vision → 图像以 base64 落库，Dream Loop 把视觉证据注入下一轮上下文
5. **主动陪伴** — 到点提醒喝水、休息；情绪持续不好时会主动关心
   - Reminder（AlarmManager + Worker）+ **Pulse**（读 Mood Trend 序列，连续负向才发，带冷却与退避）
6. **只在情绪不好时才打扰** — 不是定时无差别推送
   - Pulse 判定连续 ≥2 天负向或单次极端负向；同一原因 7 天冷却、每周上限 2 次、连续不理则逐步静音

---

## 当前状态

**最新版本：v0.1.6** · 817 个单元测试通过 · 0 失败

| 模块 | 状态 | 说明 |
|------|------|------|
| 文本聊天闭环 | ✅ 完整 | Compose UI + ChatViewModel + CompanionRuntime + Koog Agent |
| 流式输出 | ✅ 完整 | Anthropic Messages 兼容 SSE、批量增量渲染、停止/重试、离底新消息提示 |
| 双协议 LLM | ✅ 完整 | Anthropic Messages + OpenAI 兼容 Chat Completions，按 Provider 分派 |
| 长期记忆 | ✅ 完整 | MemoryRepository 统一保存 / 搜索 / prompt selection / 访问时间；敏感分层 + 只记原话 |
| 情绪 / 关系 | ✅ 完整 | 状态机 + 关系模型已接入 Agent 主循环 |
| 双模式对话 | ✅ 完整 | LISTEN / GUIDE 判定器，情境偏好写入长期记忆 |
| 图片理解 | ✅ MVP | Photo Picker 选图，CameraX 拍摄 UI 未做 |
| 设置 / 可观测性 | ✅ 完整 | Provider、API Key、连通性检查、数据导出 |
| Insight / Onboarding / Presence | ✅ 完整 | Dream Loop 周期 + POST_CHAT 即时洞察、Mood Trend、引导流程、Presence 反应策略 |
| Reminder | ✅ 完整 | AlarmManager + Worker + 通知 |
| Pulse 主动关怀 | ✅ 完整 | 情绪触发 + 冷却/退避 + 通知；设置页可开关 |
| 对话操作 | ✅ 完整 | 新建/切换对话、消息复制、编辑后重发、重新生成、代码块复制 |
| Health Connect | ✅ 完整 | HealthDataSection + HealthSyncManager |
| 设备端 LLM | ➖ 已移除 | 本地 Qwen / MNN 链路整体删除，只走云端 |
| 语音 I/O (STT/TTS) | ❌ 未做 | 规划中 |
| 动画角色 (Rive/Lottie) | ❌ 未做 | 主页用 Compose Canvas 临时替代 |
| 远程 Agent Server | ❌ 未做 | 规划中 |

详细进度见 [Roadmap](docs/roadmap.md)。

---

## 怎么用

### 前置条件

- Android 6.0+ 设备（minSdk 26）
- 一项云端 LLM 接入：任选
  - **智谱 GLM** / **Kimi** / **魔搭 ModelScope**（Anthropic Messages 协议）
  - 或任意 **OpenAI 兼容**端点（自填 Base URL + 模型 ID），例如 DeepSeek

### 1. 安装

```bash
# 克隆并构建
git clone https://github.com/YSJohnson/Rowan.git
cd Rowan
make run    # 构建 + 安装 + 启动
```

### 2. 第一次启动

1. 打开 App → 自动进入主页（首次会跑 Onboarding 5 问，用来建初始人设）
2. **设置页**（右上角齿轮）→ 选 Provider（GLM / Kimi / ModelScope / OpenAI 兼容）→ 填 API Key → 点“保存”或“测试连接”
3. 回到主页 → 点底部 **+** 开始聊天
4. 发图：点输入框旁的图片按钮，从相册选

选择 **OpenAI 兼容** 时，可填写服务商的模型 ID 和 Base URL（例如 `https://api.deepseek.com` 或 `https://example.com/proxy/v1/`）。请求保留路径前缀并使用 Bearer 鉴权；模型的图片和工具能力取决于服务商。Debug 预填可使用 `.env` 中的 `LLM_PROVIDER=OPENAI_COMPATIBLE`、`LLM_MODEL`、`LLM_BASE_URL` 和 `OPENAI_API_KEY`。

### 3. 进阶用法

- **查看情绪趋势**：主页 Insight 卡片短按弹层
- **管理记忆**：主页 → 记忆室，可查看/搜索所有 LTM 条目
- **配置 Reminder**：设置页 → 提醒，添加定时任务
- **主动关怀开关**：设置页 → 情绪不好时主动关心（含间隔档位）
- **数据导出 / 清空**：设置页 → 数据透明，支持 JSON 全量导出

---

## 给开发者

<details>
<summary><b>展开开发者文档（环境 / 命令 / 技术栈 / 流程 / 项目结构）</b></summary>

### 环境

- JDK 21+
- Android SDK（compileSdk 36, minSdk 26）
- Android Studio（推荐）或 Gradle 命令行

### 常用命令

```bash
./gradlew assembleDebug              # 构建 Debug APK
./gradlew testDebugUnitTest          # 跑 817 个单元测试
./gradlew connectedDebugAndroidTest  # 仪器测试（需连真机）
make run                              # 构建 + 安装 + 启动
make logcat                           # 查看应用日志
make help                             # 查看所有 make 命令
```

### 技术栈

| 层 | 选型 | 选它的理由 |
|----|------|----------|
| UI | Jetpack Compose + Material 3 | 声明式 UI + 状态驱动，和 LLM 流式输出天然契合 |
| 架构 | MVVM + Repository | ViewModel + Flow + 单向数据流，单 Activity / 6 路由 NavHost |
| 并发 | Coroutines + Flow | 全异步；`Dispatchers.setMain` 在测试里手动替换 |
| Agent | [Koog](https://github.com/JetBrains/koog) 0.8.0 | JetBrains 官方 Agent 框架，原生支持工具调用 + 流式 |
| LLM | GLM / Kimi / ModelScope / 任意 OpenAI 兼容端点 | Anthropic Messages 与 OpenAI Chat Completions 双协议，按 Provider 分派 client |
| 数据 | Room + DataStore | 关系数据（消息/记忆/情绪）走 Room，配置项走 DataStore |
| DI | Hilt | 编译期依赖注入，Scope 划分清晰（Singleton / ViewModelScoped） |
| 后台 | WorkManager | Dream Loop / Pulse / Reminder / Health 拉取都用 Worker |
| 相机 | CameraX | 规划中（当前走 Photo Picker） |
| 动画 | Lottie / Coil | 规划中（当前用 Compose Canvas） |

### 核心流程（一次对话）

```
用户输入（文本 / 图片）
  → MemoryRepository 选相关记忆 / 摘要
  → PromptBuilder 组装（注入情绪 + 关系 + 记忆）
  → Koog Agent 调用 LLM（流式，可用工具）
  → OutputParser 解析响应
  → 保存 assistant 消息
  → 情绪状态机更新
  → 关系模型更新
  → reflection 写入值得保留的新记忆
  → UI 渲染（气泡 / 表情 / "已记住" 提示）
```

### 项目结构

```
app/src/main/java/com/xiaoqi/companion/
├── feature/        # UI 层（chat / settings / home / memory / onboarding）
├── core/           # 核心逻辑
│   ├── companion/  #   CompanionRuntime 主循环 + Koog 集成
│   ├── llm/        #   Anthropic Messages 兼容 LLM client
│   ├── prompt/     #   Prompt 组装引擎 + 模板
│   └── tools/      #   Agent tools
├── data/           # 数据层（Room DAO/Entity、DataStore、Repository）
└── di/             # Hilt 模块
```

更详细的分层 / 数据流 / 模块依赖见 [docs/architecture.md](docs/architecture.md)。

</details>

---

## 文档

- [技术架构](docs/architecture.md) — 分层设计、Agent Core、数据流、模块清单
- [二开实施进度](docs/plan/aura-implementation-progress.md) — 云端化、双模式、Pulse、敏感记忆策略的实施与验证记录
- [Roadmap](docs/roadmap.md) — M0-M6 里程碑 + 当前进度
- [工程化规范](docs/engineering-standards.md) — CI/CD、测试策略、代码规范、质量门禁
- [Koog API 参考](docs/koog-api-reference.md) — Koog 0.8.0 完整 API 签名（从 Gradle 缓存 JAR 提取）
- [Agent ↔ Android 集成](docs/koog-android-integration.md) — Koog 在 Android 上的集成状态、线程规则、生命周期
- [Agent 编排层](docs/agent-architecture.md) — 云端 Agent 编排层（Provider 路由、Graph Strategy、Tool 系统、Memory Reflection、流式 UX 节流）

---

## License

Private
