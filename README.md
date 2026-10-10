# Jwork

**A local-first personal AI assistant workbench for Windows desktop.**

Jwork (formerly Gim Agent) is a desktop application that puts a full-featured AI agent workspace on your machine: multi-agent chat with streaming Markdown rendering, long-term memory, a local knowledge base, scheduled tasks, tool execution under a security guard chain, and a pluggable model layer — all backed by an embedded SQLite database under `~/.jwork/`. No account, no cloud dependency; connect any OpenAI-compatible provider or a local model server (Ollama / LM Studio).

## Features

### Chat

- Multi-agent conversations with per-agent persona, model, workspace and capability whitelist
- Streaming responses with **stop / regenerate / edit-resend**, message fork (branch a new session from any message), delete and copy
- **Model compare mode** — run one prompt across multiple models side by side
- Full **Markdown rendering** (JCEF/Chromium embedded), syntax-highlighted code blocks and **Mermaid diagrams**
- File attachments (PDF / Word / Excel text extraction) and image inputs
- Read-aloud (TTS) on finished assistant messages

### Agents & Tools

- Sub-agent delegation, plan mode, built-in task list
- Shell execution behind a **guard chain** (dangerous-command policy, executable allowlist, path whitelist) with per-call **human-in-the-loop confirmation**
- Optional Docker sandbox mode
- Web search, image generation, skill packages, and **MCP** servers (with marketplace)
- Long-term memory (`MEMORY.md`) and a per-agent knowledge base with vector search (RAG)

### Productivity

- **Scheduled tasks** — cron or one-shot, persisted in SQLite with startup recovery
- Desktop integration (Windows): global hotkey quick-ask, selection assistant, screenshot capture, tray, autostart
- Voice input (16 kHz PCM) and system TTS playback
- Token usage & estimated cost statistics with monthly budget alerts
- Session search (titles + full message text), session groups, export to Markdown / JSON / HTML / long image

### Data & Security

- Everything stays local in `~/.jwork/` (SQLite with WAL, workspace files, logs, backups)
- Credentials encrypted with **Windows DPAPI + AES-GCM**
- Local login gate; WebDAV cloud sync with optional payload encryption
- Local database backup/restore with rolling retention

### Model Layer

- OpenAI-compatible providers, per-model rate limits and cost tables, connectivity probing
- Automatic **fallback model failover**
- Auto-detection of local model servers (Ollama `:11434`, LM Studio `:1234`)
- i18n: English / 简体中文 / 繁體中文 · light & dark themes · three font scales

## Architecture

| Module | Responsibility |
| --- | --- |
| `gim-agent-domain` | SQLite persistence (schema, DAOs, entities), backup/restore, security primitives |
| `gim-agent-core` | Agent runtime (AgentScope harness gateway), chat services, memory / knowledge / plan, capability resolution |
| `gim-agent-tools` | Tool guards, HITL confirmation, Docker sandbox, skills, web search, image generation |
| `gim-agent-scheduler` | Quartz-based scheduling service and agent-facing schedule tools |
| `gim-agent-ui` | JavaFX application: JCEF-rendered chat, settings, desktop integrations, sync, voice |

Module dependency direction: `ui → scheduler / tools / core → domain`.

## Tech Stack

- **Java 21** + **JavaFX 21** (Atlantafx) + **JCEF** (Chromium OSR rendering)
- **AgentScope Java 2.0.3** (HarnessAgent) for the agent runtime
- **Spring Boot 3.5** as the DI container (no web server)
- **SQLite** (xerial JDBC, WAL) — no ORM, plain DAOs
- **Quartz** for scheduling · **jpackage / WiX** for Windows packaging

## Getting Started

### Requirements

- Windows 10/11 (primary target; desktop integrations such as global hotkey, autostart and DPAPI are Windows-only)
- JDK 21 (e.g. Liberica, Temurin)
- Maven 3.9+

### Run from source

```powershell
# Build all modules (skip tests for a quick start)
mvn -q -DskipTests install

# Launch the application
mvn -pl gim-agent-ui javafx:run
```

Alternatively, open the project in IntelliJ IDEA and run `com.gettyio.gim.agent.ui.GimAgentApplication` (the module exports required by JCEF/OSR are preconfigured in `.run/`).

### Build a Windows package

```powershell
# app-image (no extra tooling) or msi (requires WiX Toolset v3 on PATH)
powershell -ExecutionPolicy Bypass -File .\scripts\package-windows.ps1 -Type msi
```

Artifacts are placed under `build/installer/`.

### First launch

1. Open **Settings → Models**, add a provider (any OpenAI-compatible endpoint) and set a default model. Local Ollama / LM Studio instances are detected automatically.
2. Optional: configure global hotkey, TTS voice, knowledge base, MCP servers, WebDAV sync in Settings.

## Data & Privacy

All application data lives in `~/.jwork/`:

```
~/.jwork/
├── jwork.db        # SQLite database (sessions, messages, settings, tasks...)
├── secret.key      # local credential-encryption key
├── security/       # local auth state (auth.json)
├── workspace/      # shared agent workspace
│   ├── AGENTS.md   # default agent persona
│   ├── MEMORY.md   # curated long-term memory
│   ├── memory/     # daily memory notes
│   ├── knowledge/  # knowledge base files
│   ├── skills/     # installed skill packages
│   ├── attachments/  generated/
│   └── agents/<code>/  # per-agent workspaces
├── backups/        # rolling database backups (VACUUM INTO)
├── restore/        # pending cloud-restore snapshots
├── logs/           # rolling application logs (14 days / 500 MB cap)
└── cef/            # JCEF runtime (extracted on first launch)
```

Chat data never leaves your machine unless you explicitly configure a remote model provider, WebDAV sync or an HTTP proxy.

## License

[Apache-2.0](LICENSE)
