<p align="center">
  <img src="jwork-agent-ui/src/main/resources/icons/brand-mark.png" width="104" alt="Jwork logo">
</p>

# Jwork

English | [简体中文](README.zh-CN.md)

**Your local-first AI workbench for Windows — it does the chores, you keep the decisions.**

Jwork is a desktop AI assistant that actually gets work done on your machine: it reads and writes real files, runs commands under your approval, answers from your own documents, searches the web, executes scheduled jobs, and remembers your context across sessions. Everything lives in `~/.jwork/` on your disk — no account, no cloud dependency. Plug in any OpenAI-compatible provider, or a local model server (Ollama / LM Studio), and start delegating.

## What it does for you

### 1. Takes file & code chores off your plate

- *"Organize these files", "find every place that uses X", "write a script and run it"* — the agent works in a real per-agent workspace with line-numbered reads, regex grep, mtime-ordered glob and overwrite-safe writes, and executes shell commands behind a **guard chain** (dangerous-command policy, executable allowlist, path whitelist) with **your confirmation per call**; optional Docker sandbox for riskier jobs.
- Results land as **artifacts**: a workspace panel (artifacts-first file tree) and an in-app preview window show what was produced; an **integrated terminal** is right there when you'd rather do it yourself.
- **Problem solved:** no more copy-pasting between a chat window, Explorer and your editor — the work happens where the files are, and you approve anything risky.

### 2. Turns your documents into answers

- Drop **PDF / Word / Excel** attachments into chat — text is extracted and answered directly, no manual copy-out.
- Build a per-agent **knowledge base** with vector search (RAG, configurable embedding provider): ask across manuals, notes and specs instead of re-reading them.
- **Long-term memory** (`MEMORY.md`, sedimentation tiers, per-scope management) keeps your preferences and project context between sessions — stop repeating yourself every Monday.
- **Problem solved:** institutional knowledge stops living only in your head or in unread folders.

### 3. Answers without breaking your flow

- **Global hotkey quick-ask**, **selection assistant** (select text in any app → ask about it), **screenshot capture** straight into the composer, **voice input** (16 kHz PCM), tray-resident with autostart.
- Finished answers can be **read aloud** via configurable OpenAI-compatible TTS — useful when your eyes are busy.
- **Problem solved:** the question gets asked at the moment it appears, without switching windows or typing long prompts.

### 4. Automates the repetitive

- **Scheduled tasks** (cron or one-shot, persisted and recovered on startup): daily summaries, periodic checks, reminders — the agent runs them and reports back.
- **Skill packages** and **MCP servers**, both with **marketplaces** (grid browsing, cursor pagination, direct GitHub fetch): extend what the agent can do without writing code.
- **Sub-agent delegation, plan mode and a built-in task list** for multi-step jobs; the agent can even **ask you multiple-choice questions mid-turn** instead of guessing wrong.
- **Problem solved:** recurring work runs itself; one-off complex work gets structured instead of improvised.

### 5. Makes writing & delivery faster

- Streaming **Markdown** with syntax highlighting and **Mermaid diagrams**; stop / regenerate / edit-resend; **fork a new session from any message**; multi-agent conversations with per-agent persona, model and capability whitelist.
- **Model compare mode**: one prompt across several models side by side — pick the best answer and the best price.
- Export sessions to **Markdown / JSON / HTML / long image with secret redaction** — share results safely.
- **Problem solved:** draft → review → deliver stays in one place, and sharing never leaks your API keys.

### 6. Keeps long sessions reliable and affordable

- **Context window popover** with live usage breakdown and one-click compaction (automatic local-archive fallback if remote compaction fails) — long conversations don't die at the limit.
- **Fallback-model failover**, retry policy with human-readable failure messages (permanent quota errors are surfaced, not blindly retried).
- **Token usage & cost estimates** with monthly budget alerts; per-model rate limits and price tables; connectivity probing; auto-detection of local Ollama `:11434` / LM Studio `:1234`.
- **In-app update check**: daily silent check, progress download, silent install, forced-update dialog for critical releases.
- **Problem solved:** no silent failures, no surprise bills, no "context full" dead ends.

### 7. Keeps your data yours

- Everything local: SQLite (WAL) + workspace + logs + rolling backups under `~/.jwork/`; credentials encrypted with **Windows DPAPI + AES-GCM**.
- Local login gate with configurable session expiry (1–90 days); optional **WebDAV sync with payload encryption**; granular tool / file / network guard settings and authorization management.
- Chat data never leaves your machine unless you explicitly configure a remote provider, sync or proxy.
- **Problem solved:** company documents and personal notes stay on your disk, not in someone else's cloud.

## Comfort & control

- Grouped settings — **Personal / App / Models / Extensions**, 18 pages — with in-menu search; branded About dialog with version, source repository and license links.
- i18n: English / 简体中文 / 繁體中文 · light & dark themes · three font scales.
- Session search command palette (titles + full text), session groups, workspace-aware session organization.

## Architecture

| Module | Responsibility |
| --- | --- |
| `jwork-agent-domain` | SQLite persistence (schema, DAOs, entities), backup/restore, security primitives |
| `jwork-agent-core` | Agent runtime (AgentScope harness gateway), chat services, memory / knowledge / plan, capability resolution |
| `jwork-agent-tools` | Tool guards, HITL confirmation, Docker sandbox, skills, web search, image generation |
| `jwork-agent-scheduler` | Quartz-based scheduling service and agent-facing schedule tools |
| `jwork-agent-ui` | JavaFX application: JCEF-rendered chat, workspace & terminal, settings, desktop integrations, sync, voice |

Module dependency direction: `ui → scheduler / tools / core → domain`.

## Tech Stack

- **Java 21** + **JavaFX 21** (AtlantaFX) + **JCEF** (Chromium OSR rendering) + **JediTermFX** (integrated terminal)
- **AgentScope Java 2.0.5** (HarnessAgent) for the agent runtime
- Hand-wired **composition root** (`AppContext`) — no DI framework
- **SQLite** (xerial JDBC, WAL) — no ORM, plain DAOs
- **Quartz** for scheduling · **jpackage / WiX** for Windows packaging

## Getting Started

### Requirements

- Windows 10/11 (primary target; global hotkey, autostart and DPAPI are Windows-only)
- JDK 21 (e.g. Liberica, Temurin) · Maven 3.9+

### Run from source

```powershell
# Build all modules (skip tests for a quick start)
mvn -q -DskipTests install

# Launch the application
mvn -pl jwork-agent-ui javafx:run
```

Alternatively, open the project in IntelliJ IDEA and run `com.gettyio.jwork.agent.ui.JworkAgentApplication` (the module exports required by JCEF/OSR are preconfigured in `.run/`).

### Build a Windows package

```powershell
# app-image (no extra tooling) or msi (requires WiX Toolset v3 on PATH)
powershell -ExecutionPolicy Bypass -File .\scripts\package-windows.ps1 -Type msi
```

Artifacts are placed under `build/installer/`.

### First launch

1. Open **Settings → Models → Providers / Models**, add a provider (any OpenAI-compatible endpoint) and set a default model. Local Ollama / LM Studio instances are detected automatically. The settings menu has a search box if you get lost.
2. Delegate your first chore: attach a document, select text anywhere and press the hotkey, or ask the agent to organize a folder — approve the guarded commands and watch the artifacts appear.

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
│   ├── attachments/  # uploaded attachments
│   ├── generated/    # agent-generated artifacts
│   └── agents/<code>/  # per-agent workspaces
├── backups/        # rolling database backups (VACUUM INTO)
├── restore/        # pending cloud-restore snapshots
├── logs/           # rolling application logs (14 days / 500 MB cap)
└── cef/            # JCEF runtime (extracted on first launch)
```

## License & Copyright

Copyright © 2026 gogym. All rights reserved.

- **Personal use — free.** You may install, run, copy and modify Jwork for personal, non-commercial purposes at no cost.
- **Commercial use — authorization required.** Any commercial use (including business operations inside for-profit organizations, embedding into commercial products or services, and paid redistribution) requires prior explicit written authorization from the copyright holder.

Full terms: [LICENSE](LICENSE) (Jwork Software License Agreement). For commercial licensing, get in touch via [Issues](https://github.com/gogym/jwork-agent-gui/issues).