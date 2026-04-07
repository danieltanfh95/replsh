# `replsh launch` — Process Lifecycle, Toolchains, Config + `--init`

## Context

replsh currently only connects to *existing* REPL servers. For Poetry/venv workflows, this means the user must manually start servers inside the right environment. The `launch` command automates this: spawn a server process → wait for readiness → connect.

Two independent dimensions:
- **Protocol** (how to talk): nREPL, Jupyter, Node — the `:backend`
- **Toolchain** (how to start): lein, deps.edn, bb, poetry, pipenv — the `:cmd`

Also adds `--init` for post-connect bootstrap code (useful for both `launch` and `start`).

---

## Data Structure Shapes

Four distinct shapes at different layers:

### 1. Toolchain Preset — global, reusable across projects

Defines backend protocol + cmd template with `{placeholder}` substitution.

```clojure
;; ~/.replsh/config.edn
{:toolchains
 {"clojure.deps"   {:backend  :nrepl
                    :cmd      "clj -M:nrepl -m nrepl.cmdline --port {port}"
                    :defaults {:port 7888}}

  "clojure.lein"   {:backend  :nrepl
                    :cmd      "lein repl :headless :port {port}"
                    :defaults {:port 7888}}

  "clojure.bb"     {:backend  :nrepl
                    :cmd      "bb --nrepl-server {port}"
                    :defaults {:port 1667}}

  "python.poetry"  {:backend  :jupyter
                    :cmd      "poetry run jupyter server --port {port}"
                    :defaults {:port 8888 :kernel "python3"}}

  "python.venv"    {:backend  :jupyter
                    :cmd      "{cwd}/.venv/bin/jupyter server --port {port}"
                    :defaults {:port 8888 :kernel "python3"}}

  "node"           {:backend  :node
                    :cmd      "node -e \"require('net').createServer(s=>require('repl').start({input:s,output:s})).listen({port})\""
                    :defaults {:port 5001 :prompt-re "> "}}}}
```

Common toolchains ship built-in with replsh. Users extend/override in `~/.replsh/config.edn`.

### 2. Session Spec — project-level, references toolchain

Flat keys. References a toolchain by name. Any field overrides the toolchain.

```clojure
;; <project>/.replsh/config.edn
{:sessions
 {"backend"  {:toolchain "clojure.deps"
              :port      1667
              :init      "(require '[my.app])"}

  "ml"       {:toolchain "python.poetry"
              :port      8888
              :cwd       "ml/"
              :kernel    "python3"}

  "frontend" {:toolchain "node"
              :port      5001
              :cwd       "frontend/"}

  ;; Override toolchain cmd for a specific session
  "legacy"   {:toolchain "clojure.lein"
              :port      4567
              :cmd       "lein with-profile +dev repl :headless :port {port}"}}}
```

### 3. Resolved Spec — in-memory only (toolchain + session + CLI merged)

Flat, absolute paths, all values resolved, template vars substituted. Input to `launch-cmd`/`start-cmd`.

```clojure
{:backend-type :nrepl
 :name         "backend"
 :host         "localhost"
 :port         1667
 :cmd          "clj -M:nrepl -m nrepl.cmdline --port 1667"  ;; {port} substituted
 :cwd          "/repo"
 :init         "(require '[my.app])"
 :env          {}
 :kernel       nil :token nil :prompt-re nil}
```

### 4. Session Config — machine-generated, persisted to `~/.replsh/state.edn`

Structured internal representation. What backends consume. Self-contained.

```clojure
{:name         "backend"
 :backend      :nrepl
 :created-at   "2026-04-07T10:00:00Z"
 :transport    {:type :tcp :host "localhost" :port 1667}
 :env          {:cwd "/repo" :vars {}}
 :launch       {:cmd "clj -M:nrepl -m nrepl.cmdline --port 1667"
                :cwd "/repo" :pid 12345}
 :init-code    "(require '[my.app])"
 :backend-opts {}
 :internal     {:session-id "nrepl-sess-uuid"}}
```

---

## Data Flow

```
Built-in toolchain presets (hardcoded in replsh)
         │
         ▼  extended/overridden by
~/.replsh/config.edn :toolchains (Toolchain Preset)
         │
         ▼  referenced by :toolchain key
<project>/.replsh/config.edn :sessions (Session Spec)
         │
         ▼  overridden by
CLI args (--port, --cmd, --init, etc.)
         │
         ▼
┌─────────────────────────────────────────────────┐
│  config/resolve-session  (Config layer)         │
│  1. Lookup toolchain → get {:backend :cmd ...}  │
│  2. Merge toolchain defaults                    │
│  3. Merge session spec (overrides toolchain)    │
│  4. Merge CLI args (overrides everything)       │
│  5. Resolve relative :cwd to absolute           │
│  6. Substitute {port}, {cwd} in :cmd template   │
│  OUT: Resolved Spec                             │
└──────────────────┬──────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────┐
│  command/launch-cmd  (Orchestration)            │
│  1. process/spawn! with resolved :cmd → PID     │
│  2. process/wait-for-port!                      │
│  3. Transform Resolved Spec → Session Config    │
│     :host+:port → {:transport {:type :tcp ..}}  │
│     :cwd+:env   → {:env {:cwd .. :vars ..}}    │
│     :cmd+PID    → {:launch {:cmd .. :pid ..}}   │
│     :init       → {:init-code "..."}            │
│  4. backend/open! → :internal                   │
│  5. run-init! (if init-code)                    │
│  6. Persist Session Config to state.edn         │
└──────────────────┬──────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────┐
│  state.edn (Session Config)                     │
│  Self-contained — eval/stop/restart/status      │
│  never read config files                        │
└─────────────────────────────────────────────────┘
```

**Key**: Config files are consulted only during `launch`/`start`. Once persisted, `state.edn` is the source of truth.

**Backends don't change at all** — they connect to a running server. Process management + config resolution are separate layers above.

---

## Config Files

### `~/.replsh/config.edn` (global)

Toolchain presets. User-defined/overridden. Searched at fixed path (override with `REPLSH_CONFIG_GLOBAL`).

### `<project>/.replsh/config.edn` (project)

Session definitions. Searched from cwd walking up to filesystem root (like `.gitignore`). Override with `REPLSH_CONFIG` env var.

### Resolution order

```
toolchain :defaults → toolchain top-level → session spec → CLI args
```

Any field specified at a later stage overrides the earlier one. Flat merge, no nesting.

---

## CLI

```bash
# Launch from project config (reads toolchain + session)
replsh launch --name backend

# Launch with full CLI args (no config needed, backend as subcommand)
replsh launch nrepl --name backend --port 1667 \
  --cmd "bb --nrepl-server 1667" --cwd /repo

# Launch from config + CLI override
replsh launch --name backend --cmd "clj -M:dev:nrepl -m nrepl.cmdline --port {port}"

# Start (connect-only, no process spawn)
replsh start --name backend       # from config
replsh start nrepl --name backend --port 1667  # from CLI

# --init on both launch and start
replsh launch --name backend --init "(require '[my.app :as app])"
replsh start --name backend --init "(require '[my.app :as app])"
```

When using config: backend type comes from toolchain `:backend`. When using CLI only: backend type is the subcommand.

---

## File Changes

### 1. NEW: `src/replsh/process.clj`

Process lifecycle. No knowledge of backends or config.

- **`spawn!`** `[{:keys [cmd cwd env-vars name]}] -> {:pid long :process Process}`
  - `ProcessBuilder ["/bin/sh" "-c" cmd]`, set `.directory()`, merge env via `.environment()`
  - Redirect stdout+stderr to `~/.replsh/logs/<name>.log`
  - Check alive after 200ms — throws `:launch-failed` if dead

- **`wait-for-port!`** `[{:keys [host port timeout-ms process]}] -> true | throws`
  - Poll TCP connect every 250ms. Check `process.isAlive()` each iteration.
  - Throws `:port-timeout` or `:launch-failed`

- **`wait-for-http!`** `[{:keys [url timeout-ms process]}] -> true | throws`
  - Same pattern, polls HTTP GET to `<url>/api/status`. For Jupyter.

- **`kill!`** `[pid] -> :killed | :already-dead`
  - `ProcessHandle/of` → `.destroy()` (SIGTERM) → wait 3s → `.destroyForcibly()` (SIGKILL)

- **`alive?`** `[pid] -> boolean`

### 2. NEW: `src/replsh/config.clj`

Config loading + resolution. The bridge between user-authored config and internal data.

- **`builtin-toolchains`** — hardcoded map of common toolchains (clojure.deps, clojure.lein, clojure.bb, python.poetry, python.venv, node)

- **`load-global-config`** `[] -> map | nil`
  - Read `~/.replsh/config.edn` (or `REPLSH_CONFIG_GLOBAL`)

- **`load-project-config`** `[] -> {:config map :dir path} | nil`
  - Walk from cwd upward looking for `.replsh/config.edn` (or `REPLSH_CONFIG`)
  - Returns both the config and the directory it was found in (for resolving relative paths)

- **`resolve-toolchains`** `[global-config] -> merged-toolchains`
  - `(merge builtin-toolchains (:toolchains global-config))`

- **`resolve-session`** `[toolchains project-config session-name cli-opts] -> Resolved Spec`
  1. Lookup session in project config: `(get-in project-config [:config :sessions session-name])`
  2. Lookup toolchain: `(get toolchains (:toolchain session-spec))`
  3. Merge: `toolchain :defaults` → toolchain top-level → session spec → CLI opts
  4. Resolve relative `:cwd` against project config dir
  5. Substitute `{port}`, `{cwd}`, `{host}` in `:cmd` string
  6. Derive `:address` or `:url` from `:host` + `:port` if not explicitly set

- **`substitute-template`** `[cmd-template resolved-map] -> cmd-string`
  - Replace `{port}`, `{cwd}`, `{host}` etc. in the cmd string

### 3. MODIFY: `src/replsh/cli.clj`

- Add `launch-handler` — loads config, resolves session, delegates to `cmd/launch-cmd`
  - `replsh launch --name X`: resolve from config (no subcommand needed)
  - `replsh launch nrepl --name X ...`: use subcommand as backend, config provides defaults
- Add dispatch entries: `["launch" "nrepl"]`, `["launch" "jupyter"]`, `["launch" "node"]`, `["launch"]` (config-only)
- Add `--init`, `--port` to existing `start` and new `launch` specs
- Modify `start-handler` to also resolve from config when `--name` matches a config entry

### 4. MODIFY: `src/replsh/command.clj`

Add `(:require [replsh.process :as process] [clojure.string :as str])`.

**New `launch-cmd`**:
1. `process/spawn!` with cmd, cwd, env-vars
2. `process/wait-for-port!` or `wait-for-http!` (based on backend type)
3. Build Session Config from Resolved Spec + `:launch {:cmd .. :pid ..}`
4. `backend/open!` to verify connectivity
5. `run-init!` if init-code provided
6. `backend/close!`, persist session
7. On ANY failure post-spawn: `process/kill!` to prevent orphans, re-throw

**New `run-init!`** helper (shared by `launch-cmd` and `start-cmd`):
- Eval init code via `backend/eval!`, throws `:init-failed` on error

**Modified `start-cmd`**: Accept `:init`, call `run-init!` after open. On init failure: close, destroy, throw.

**Modified `stop-cmd`**: If session has `:launch`, `process/kill!` before `backend/destroy!`.

**Modified `restart-cmd`**: If session has `:launch`, kill → re-spawn → wait → re-connect. Else existing behavior.

**Modified `status-cmd`**: Show `{:launch {:pid N :alive bool :cmd "..."}}` when present.

### 5. No changes needed

- `src/replsh/backend.clj` and all backend implementations — unaware of process/config
- `src/replsh/state.clj` — `:launch` and `:init-code` are just EDN fields
- `src/replsh/main.clj` — error handling already generic
- `bb.edn` — `ProcessBuilder`/`ProcessHandle` are JVM stdlib

---

## Error Handling

| Failure | Behavior |
|---------|----------|
| Bad command / missing binary | `spawn!` detects death within 200ms, reads log, throws `:launch-failed` |
| Process starts but never listens | `wait-for-port!` checks liveness each poll; fails fast if dead, else times out. Catch block kills orphan |
| Port opens but `backend/open!` fails | Catch block kills spawned process |
| Init code fails | Throws `:init-failed`, kills process, session NOT persisted |
| Process dies later | `status-cmd` shows `{:alive false}`. `eval` fails with connection refused. User can `restart` or `stop` |
| Toolchain not found | Throws at config resolution time with clear message |
| No config file found | Fine for CLI-only usage. Error only if `--name` used without subcommand and no config |

---

## Verification

```bash
# 1. Launch from config
# .replsh/config.edn: {:sessions {"backend" {:toolchain "clojure.bb" :port 1667}}}
replsh launch --name backend
replsh eval --name backend '(+ 1 2)'       # → {"ok":true,"data":{"value":"3"}}
replsh status --name backend               # → {...,"launch":{"pid":...,"alive":true,...}}
replsh stop backend                        # → kills process, removes session

# 2. Launch with full CLI (no config)
replsh launch nrepl --name dev --port 1668 \
  --cmd "bb --nrepl-server 1668"
replsh eval --name dev '(+ 1 2)'

# 3. Launch with --init
replsh launch --name backend \
  --init "(require '[clojure.string :as str])"
replsh eval --name backend '(str/upper-case "hello")'  # → "HELLO"

# 4. Config override
replsh launch --name backend --cmd "clj -M:dev:nrepl -m nrepl.cmdline --port {port}"

# 5. Poetry/venv workflow
# .replsh/config.edn: {:sessions {"ml" {:toolchain "python.poetry" :port 8888 :cwd "ml/"}}}
replsh launch --name ml
replsh eval --name ml 'import pandas; print(pandas.__version__)'

# 6. Launch failure
replsh launch nrepl --name bad --port 9999 --cmd "nonexistent-binary"
# → {"ok":false,"error":{"code":"launch_failed",...}}

# 7. Restart re-launches
replsh restart --name backend
# → kills old process, spawns new one, reconnects

# 8. Start (connect-only, no spawn)
replsh start --name backend --init "(in-ns 'my.ns)"
```
