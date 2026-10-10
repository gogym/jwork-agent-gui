# Jwork

**Windows 桌面上的本地优先个人 AI 助理工作台。**

Jwork（原名 Gim Agent）把一整套 AI 智能体工作区放到你的机器上：多智能体对话（流式 Markdown 渲染）、长期记忆、本地知识库、定时任务、带安全守卫链的工具执行，以及可插拔的模型层——全部数据由内嵌 SQLite 支撑，存放于 `~/.jwork/`。无需账号、不依赖云服务；可接入任意 OpenAI 兼容提供商，或本地模型服务（Ollama / LM Studio）。

## 功能特性

### 对话

- 多智能体会话：每个智能体独立的系统提示词、模型、工作区与能力白名单
- 流式响应，支持**停止 / 重新生成 / 编辑重发**；消息支持分支（从任意一条消息派生新会话）、删除与复制
- **多模型对比模式**——同一提示词在多个模型上并排运行
- 完整 **Markdown 渲染**（JCEF/Chromium 内嵌）、代码语法高亮与 **Mermaid 图表**
- 文件附件（PDF / Word / Excel 正文抽取）与图片输入
- 助手消息朗读（TTS）

### 智能体与工具

- 子智能体委派、计划模式、内置任务清单
- Shell 执行置于**守卫链**之后（危险命令策略、可执行文件白名单、路径白名单），并支持逐次调用的人工确认（HITL）
- 可选的 Docker 沙箱模式
- 联网搜索、图像生成、技能包与 **MCP** 服务器（含市场）
- 长期记忆（`MEMORY.md`）与按智能体隔离的向量检索知识库（RAG）

### 效率

- **定时任务**——cron 或一次性，持久化于 SQLite，启动时自动恢复
- 桌面集成（Windows）：全局热键快速提问、划词助手、截图捕获、托盘、开机自启
- 语音输入（16 kHz PCM）与系统 TTS 播报
- Token 用量与成本估算统计，支持月度预算提醒
- 会话搜索（标题 + 消息全文）、会话分组、导出 Markdown / JSON / HTML / 长图

### 数据与安全

- 一切数据本地存放于 `~/.jwork/`（SQLite WAL、工作区文件、日志、备份）
- 凭据采用 **Windows DPAPI + AES-GCM** 加密
- 本地登录门槛；WebDAV 云同步（可选载荷加密）
- 本地数据库备份/恢复，滚动保留

### 模型层

- OpenAI 兼容提供商，支持逐模型限流与成本表、连通性探测
- 主模型故障自动 **备用模型 failover**
- 自动探测本地模型服务（Ollama `:11434`、LM Studio `:1234`）
- 界面语言：English / 简体中文 / 繁體中文 · 亮暗主题 · 三档字号

## 架构

| 模块 | 职责 |
| --- | --- |
| `gim-agent-domain` | SQLite 持久化（schema、DAO、实体）、备份恢复、安全原语 |
| `gim-agent-core` | 智能体运行时（AgentScope Harness 网关）、聊天服务、记忆/知识/计划、能力解析 |
| `gim-agent-tools` | 工具守卫、HITL 确认、Docker 沙箱、技能、联网搜索、图像生成 |
| `gim-agent-scheduler` | 基于 Quartz 的调度服务与面向智能体的定时任务工具 |
| `gim-agent-ui` | JavaFX 应用：JCEF 渲染的聊天区、设置、桌面集成、云同步、语音 |

模块依赖方向：`ui → scheduler / tools / core → domain`。

## 技术栈

- **Java 21** + **JavaFX 21**（Atlantafx）+ **JCEF**（Chromium OSR 渲染）
- **AgentScope Java 2.0.3**（HarnessAgent）作为智能体运行时
- **Spring Boot 3.5** 作为依赖注入容器（不启动 Web 服务）
- **SQLite**（xerial JDBC，WAL）——无 ORM，纯 DAO
- **Quartz** 调度 · **jpackage / WiX** Windows 打包

## 快速开始

### 环境要求

- Windows 10/11（主要目标平台；全局热键、开机自启、DPAPI 等桌面集成为 Windows 专属）
- JDK 21（如 Liberica、Temurin）
- Maven 3.9+

### 从源码运行

```powershell
# 构建全部模块（快速启动可跳过测试）
mvn -q -DskipTests install

# 启动应用
mvn -pl gim-agent-ui javafx:run
```

也可以用 IntelliJ IDEA 打开项目，直接运行 `com.gettyio.gim.agent.ui.GimAgentApplication`（JCEF/OSR 所需模块导出参数已在 `.run/` 中预置）。

### 构建 Windows 安装包

```powershell
# app-image（无额外依赖）或 msi（需 WiX Toolset v3 在 PATH 中）
powershell -ExecutionPolicy Bypass -File .\scripts\package-windows.ps1 -Type msi
```

产物输出到 `build/installer/`。

### 首次启动

1. 打开 **设置 → 模型**，添加提供商（任意 OpenAI 兼容端点）并设置默认模型。本地 Ollama / LM Studio 实例会被自动探测。
2. 可选：在设置中配置全局热键、TTS 音色、知识库、MCP 服务器、WebDAV 同步。

## 数据与隐私

全部应用数据位于 `~/.jwork/`：

```
~/.jwork/
├── jwork.db        # SQLite 数据库（会话、消息、设置、任务等）
├── secret.key      # 本地凭据加密密钥
├── security/       # 本地登录状态（auth.json）
├── workspace/      # 共享智能体工作区
│   ├── AGENTS.md   # 默认智能体人格
│   ├── MEMORY.md   # 精炼的长期记忆
│   ├── memory/     # 每日记忆台账
│   ├── knowledge/  # 知识库文件
│   ├── skills/     # 已安装技能包
│   ├── attachments/  generated/
│   └── agents/<code>/  # 各智能体专属工作区
├── backups/        # 滚动数据库备份（VACUUM INTO）
├── restore/        # 待应用的云端恢复快照
├── logs/           # 滚动应用日志（14 天 / 500 MB 上限）
└── cef/            # JCEF 运行时（首次启动解包）
```

除非你主动配置了远程模型提供商、WebDAV 同步或 HTTP 代理，聊天数据不会离开本机。

## 许可证

[Apache-2.0](LICENSE)
