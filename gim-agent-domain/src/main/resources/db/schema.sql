-- Gim Agent 桌面版 SQLite schema（本地优先，数据自持）
-- 时间列以 ISO-8601 字符串（TEXT）存储，由 SqlRunner 约定读写

CREATE TABLE IF NOT EXISTS agents (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_code       TEXT NOT NULL UNIQUE,
    agent_name       TEXT NOT NULL,
    agent_type       TEXT NOT NULL DEFAULT 'SIMPLE',
    description      TEXT,
    system_prompt    TEXT,
    model_code       TEXT,
    provider_code    TEXT,
    model_name       TEXT,
    skills_json      TEXT,
    mcp_servers_json TEXT,
    workspace_dir    TEXT,
    temperature      REAL DEFAULT 0.7,
    max_tokens       INTEGER DEFAULT 2000,
    enabled          INTEGER DEFAULT 1,
    created_at       TEXT,
    updated_at       TEXT
);

CREATE TABLE IF NOT EXISTS sessions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    TEXT NOT NULL UNIQUE,
    agent_code    TEXT,
    title         TEXT,
    model_code    TEXT,
    message_count INTEGER DEFAULT 0,
    status        TEXT DEFAULT 'ACTIVE',
    created_at    TEXT,
    updated_at    TEXT
);

CREATE TABLE IF NOT EXISTS conversations (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id     TEXT NOT NULL,
    agent_code     TEXT,
    role           TEXT NOT NULL,
    content        TEXT,
    tokens_input   INTEGER,
    tokens_output  INTEGER,
    tokens_cached  INTEGER,
    llm_calls      INTEGER,
    llm_elapsed_ms INTEGER,
    model_code     TEXT,
    tool_calls     TEXT,
    metadata       TEXT,
    created_at     TEXT
);
CREATE INDEX IF NOT EXISTS idx_conversations_session ON conversations (session_id);
CREATE INDEX IF NOT EXISTS idx_conversations_agent ON conversations (agent_code);
CREATE INDEX IF NOT EXISTS idx_conversations_model ON conversations (model_code);

CREATE TABLE IF NOT EXISTS model_providers (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_code TEXT NOT NULL UNIQUE,
    provider_name TEXT NOT NULL,
    api_protocol  TEXT NOT NULL,
    base_url      TEXT,
    api_key       TEXT,
    auth_method   TEXT,
    enabled       INTEGER DEFAULT 1,
    remark        TEXT,
    created_at    TEXT,
    updated_at    TEXT
);

CREATE TABLE IF NOT EXISTS models (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_code      TEXT NOT NULL,
    model_code         TEXT NOT NULL UNIQUE,
    model_name         TEXT NOT NULL,
    priority           INTEGER DEFAULT 100,
    cost_per_1k_input  REAL,
    cost_per_1k_output REAL,
    rate_limit_rpm     INTEGER,
    rate_limit_tpm     INTEGER,
    enabled            INTEGER DEFAULT 1,
    status             TEXT,
    last_test_at       TEXT,
    last_test_result   TEXT,
    supports_vision    INTEGER,
    created_at         TEXT,
    updated_at         TEXT
);

CREATE TABLE IF NOT EXISTS mcp_servers (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    server_code    TEXT NOT NULL UNIQUE,
    server_name    TEXT NOT NULL,
    transport_type TEXT NOT NULL DEFAULT 'STDIO',
    command        TEXT,
    args_json      TEXT,
    env_json       TEXT,
    base_url       TEXT,
    headers_json   TEXT,
    enabled        INTEGER DEFAULT 1,
    timeout        INTEGER DEFAULT 30,
    status         TEXT,
    created_at     TEXT,
    updated_at     TEXT
);

CREATE TABLE IF NOT EXISTS skills (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    skill_code    TEXT NOT NULL UNIQUE,
    skill_name    TEXT NOT NULL,
    description   TEXT,
    version       TEXT,
    entry_path    TEXT,
    source        TEXT,
    requires_json TEXT,
    enabled       INTEGER DEFAULT 1,
    created_at    TEXT,
    updated_at    TEXT
);

CREATE TABLE IF NOT EXISTS prompt_templates (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    template_code TEXT NOT NULL UNIQUE,
    template_name TEXT NOT NULL,
    content       TEXT,
    description   TEXT,
    enabled       INTEGER DEFAULT 1,
    created_at    TEXT,
    updated_at    TEXT
);

CREATE TABLE IF NOT EXISTS tasks (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    task_code      TEXT NOT NULL UNIQUE,
    task_name      TEXT NOT NULL,
    agent_code     TEXT,
    prompt         TEXT,
    cron_expression TEXT,
    status         TEXT DEFAULT 'READY',
    once           INTEGER DEFAULT 0,
    last_run_at    TEXT,
    next_run_at    TEXT,
    created_at     TEXT,
    updated_at     TEXT
);

CREATE TABLE IF NOT EXISTS settings (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    setting_key   TEXT NOT NULL UNIQUE,
    setting_value TEXT,
    description   TEXT,
    updated_at    TEXT
);

CREATE TABLE IF NOT EXISTS tool_permission_rules (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    tool_name   TEXT NOT NULL,
    rule_content TEXT,
    decision    TEXT NOT NULL,
    created_at  TEXT,
    UNIQUE (tool_name, rule_content)
);
