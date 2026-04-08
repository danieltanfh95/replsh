# replsh Manual

## Synopsis

```
replsh <command> [options]
```

Unified CLI for REPL servers. Manages named sessions across nREPL, Jupyter, and Node.js backends. All output is structured JSON.

## Commands

### launch

Spawn a REPL server process, wait for readiness, and connect.

```
replsh launch [backend] --name <name> [options]
```

**Arguments:**
- `backend` — `nrepl`, `jupyter`, or `node`. Optional when using project config (derived from toolchain).

**Options:**

| Flag | Alias | Description |
|------|-------|-------------|
| `--name` | `-n` | Session name (required) |
| `--port` | `-p` | Port number (auto-allocated if omitted) |
| `--cmd` | | Command to spawn the server |
| `--cwd` | | Working directory |
| `--env` | `-e` | Environment variable (`K=V`, repeatable) |
| `--init` | `-i` | Bootstrap code to run after connecting |
| `--timeout` | `-t` | Port readiness timeout in ms (default: 30000) |
| `--kernel` | `-k` | Jupyter kernel name (default: `python3`) |
| `--token` | | Jupyter auth token |
| `--prompt-re` | | Node.js prompt string (default: `> `) |

**Examples:**

```bash
# From project config
replsh launch --name backend

# Explicit nREPL
replsh launch nrepl --name dev --port 1667 --cmd "bb --nrepl-server 1667"

# Jupyter with Poetry
replsh launch jupyter --name ml --port 8888 \
  --cmd "poetry run jupyter server --port 8888" --kernel python3

# With bootstrap code
replsh launch --name dev --init "(require '[my.app])"
```

### start

Connect to an already-running REPL server.

```
replsh start [backend] --name <name> <address|url>
```

**Arguments:**
- `backend` — `nrepl`, `jupyter`, or `node`. Optional when using project config.
- `address` — `host:port` for nREPL/Node, or URL for Jupyter (when not using config).

Options are the same as `launch` minus `--cmd` and `--timeout`.

**Examples:**

```bash
# From config
replsh start --name backend

# Connect to nREPL
replsh start nrepl --name dev --port 1667

# With init code
replsh start --name dev --init "(in-ns 'my.ns)"
```

### eval

Evaluate code in a named session. Code can be provided as a positional argument, read from a file with `--file`, or piped via stdin (when neither argument nor file is given).

```
replsh eval --name <name> '<code>'
replsh eval --name <name> --file <path>
echo '<code>' | replsh eval --name <name>
```

| Flag | Alias | Description |
|------|-------|-------------|
| `--name` | `-n` | Session name |
| `--file` | `-f` | Read code from file |
| `--timeout` | `-t` | Soft timeout in ms (default: 30000). Returns partial output on expiry. `0` = no soft timeout. |
| `--hard-timeout` | | Hard timeout in ms. Interrupts the eval server-side on expiry. Exit code 3. |
| `--stream` | `-s` | Stream output as NDJSON (one JSON line per chunk). |
| `--bg` | | Run eval in a background process. Returns an eval-id immediately. |

#### Timeout behavior

By default, `--timeout` is 30000ms (30 seconds). When the soft timeout fires:

- Accumulated output (stdout, values, errors) is returned as a **partial success**: `"partial": true`, exit code 0.
- The eval may still be running server-side — replsh simply stops listening.

When `--hard-timeout` fires:

- replsh sends an interrupt to the backend (nREPL interrupt op, Jupyter REST interrupt).
- Returns as a **timeout error**: exit code 3.
- Guaranteed to stop the eval.

They compose: `--timeout 5000 --hard-timeout 60000` returns partial output at 5s, kills the eval at 60s. `--timeout 0 --hard-timeout 300000` waits for completion up to 5 minutes, then kills.

#### Streaming

With `--stream`, output is emitted as NDJSON — one JSON line per chunk as it arrives from the backend. The final line is the summary envelope with `"final": true`.

```
{"type":"out","content":"line 1\n","stream":"stdout","meta":{}}
{"type":"value","content":":done","meta":{"ns":"user"}}
{"ok":true,"command":"eval","data":{...},"final":true}
```

#### Background eval

With `--bg`, the eval runs in a forked child process. The parent returns immediately with an eval-id:

```json
{"ok":true,"command":"eval-bg","data":{"eval-id":"eval-a1b2c3d4","session":"dev","pid":12345}}
```

Use `replsh output` to read results and `replsh evals` to list background evals.

**Examples:**

```bash
replsh eval --name dev '(+ 1 2)'
replsh eval --name ml 'import pandas; print(pandas.__version__)'
replsh eval --name dev --file script.clj
echo '(+ 1 2)' | replsh eval --name dev
replsh eval --name dev '(long-running-fn)' --timeout 60000
replsh eval --name dev --stream '(run-tests)'
replsh eval --name dev --bg '(train-model data)' --timeout 0 --hard-timeout 600000
```

### ls

List all sessions.

```
replsh ls
```

### status

Show detailed status for a session, including reachability and process info.

```
replsh status --name <name>
```

### stop

Stop and remove a session. Kills the server process if it was launched by replsh.

```
replsh stop <name>
```

### restart

Restart a session. If launched by replsh, kills the old process and spawns a new one. Re-runs init code if configured.

```
replsh restart <name>
```

### interrupt

Cancel a running evaluation (nREPL and Jupyter only).

```
replsh interrupt --name <name>
```

### evals

List all background evals.

```
replsh evals
```

Returns eval-id, session, status (`running`, `completed`, `failed`, `timeout`), timestamps, and PID for each background eval.

### output

Read output from a background eval.

```
replsh output --eval-id <id>
replsh output --eval-id <id> --follow
```

| Flag | Alias | Description |
|------|-------|-------------|
| `--eval-id` | `-e` | Background eval ID (required) |
| `--follow` | `-f` | Tail output until eval completes |

Without `--follow`, returns all output as a single JSON object with `data.chunks` and `data.summary`.

With `--follow`, streams chunks as NDJSON (same format as `eval --stream`) until the eval finishes.

### logs

Read server process logs for a launched session.

```
replsh logs --name <name>
replsh logs --name <name> --tail 20
replsh logs --name <name> --follow
```

| Flag | Alias | Description |
|------|-------|-------------|
| `--name` | `-n` | Session name |
| `--tail` | `-t` | Show last N lines |
| `--follow` | `-f` | Tail log until process exits |

Reads from `~/.replsh/logs/<name>.log`. Only available for sessions started with `launch`.

## Configuration

### Project config — `.replsh/config.edn`

Searched from the current working directory upward to the filesystem root (like `.gitignore`). Override with `REPLSH_CONFIG` environment variable.

```clojure
{:sessions
 {"backend"  {:toolchain "clojure.bb"
              :port      1667
              :init      "(require '[my.app])"}
  "ml"       {:toolchain "python.poetry"
              :port      8888
              :cwd       "ml/"
              :kernel    "python3"}}}
```

**Session fields:**

| Field | Description |
|-------|-------------|
| `:toolchain` | Name of a toolchain preset |
| `:port` | Port number (overrides toolchain default) |
| `:cmd` | Command (overrides toolchain default) |
| `:cwd` | Working directory (relative to config dir, or absolute) |
| `:init` | Bootstrap code run after connecting |
| `:kernel` | Jupyter kernel name |
| `:token` | Jupyter auth token |
| `:prompt-re` | Node.js prompt string |
| `:env` | Environment variables map |

### Global config — `~/.replsh/config.edn`

Define or override toolchain presets. Override path with `REPLSH_CONFIG_GLOBAL`.

```clojure
{:toolchains
 {"python.conda" {:backend  :jupyter
                  :cmd      "conda run jupyter server --port {port}"
                  :defaults {:port 8888 :kernel "python3"}}}}
```

### Resolution order

```
toolchain :defaults → toolchain top-level → session spec → CLI args
```

Each layer overrides the previous. CLI args always win.

### Template variables

Command strings support `{port}`, `{cwd}`, and `{host}` placeholders, substituted at resolution time.

```clojure
:cmd "{cwd}/.venv/bin/jupyter server --port {port}"
;; becomes: /project/ml/.venv/bin/jupyter server --port 8888
```

## Toolchains

### Built-in toolchains

| Name | Backend | Command | Defaults |
|------|---------|---------|----------|
| `clojure.deps` | nrepl | `clj -M:nrepl -m nrepl.cmdline --port {port}` | port 7888 |
| `clojure.lein` | nrepl | `lein repl :headless :port {port}` | port 7888 |
| `clojure.bb` | nrepl | `bb --nrepl-server {port}` | port 1667 |
| `python.poetry` | jupyter | `poetry run jupyter server --port {port}` | port 8888, kernel python3 |
| `python.venv` | jupyter | `{cwd}/.venv/bin/jupyter server --port {port}` | port 8888, kernel python3 |
| `node` | node | `node -e "require('net')..."` | port 5001, prompt "> " |

## Output Format

### Sync mode (default)

All commands emit exactly one JSON object on stdout.

**Success:**

```json
{
  "ok": true,
  "command": "eval",
  "data": {
    "name": "dev",
    "value": "3",
    "ns": "user",
    "chunks": [
      {"type": "out", "content": "hello\n", "stream": "stdout"},
      {"type": "value", "content": "3", "meta": {"ns": "user"}}
    ]
  }
}
```

**Partial success (soft timeout):**

```json
{
  "ok": true,
  "command": "eval",
  "data": {"name": "dev", "chunks": [...]},
  "partial": true
}
```

**Error:**

```json
{
  "ok": false,
  "command": "eval",
  "error": {
    "code": "eval_error",
    "message": "ArithmeticException: Divide by zero"
  },
  "data": {
    "chunks": [{"type": "error", "content": "..."}]
  }
}
```

### Streaming mode (`--stream` or `--follow`)

NDJSON — one JSON line per chunk, final line is the summary envelope:

```
{"type":"out","content":"hello\n","stream":"stdout","meta":{}}
{"type":"value","content":"3","meta":{"ns":"user"}}
{"ok":true,"command":"eval","data":{...},"final":true}
```

### Chunk types

| Type | Description |
|------|-------------|
| `out` | Stdout output (has `stream` field) |
| `err` | Stderr output (has `stream` field) |
| `value` | Return value (has `meta` with `ns` for nREPL) |
| `error` | Evaluation error |
| `status` | Status message |

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success (including partial output from soft timeout) |
| 1 | Evaluation error (code ran but produced an error) |
| 2 | Client error (connection failed, session not found, etc.) |
| 3 | Hard timeout (eval was interrupted) |

## Environment Variables

| Variable | Description |
|----------|-------------|
| `REPLSH_CONFIG` | Override project config file path |
| `REPLSH_CONFIG_GLOBAL` | Override global config file path |
| `REPLSH_STATE` | Override state file path (default: `~/.replsh/state.edn`) |

## Files

| Path | Description |
|------|-------------|
| `~/.replsh/state.edn` | Persisted session state |
| `~/.replsh/config.edn` | Global toolchain config |
| `~/.replsh/logs/<name>.log` | Server process stdout/stderr logs |
| `~/.replsh/evals/<id>.jsonl` | Background eval NDJSON output |
| `~/.replsh/evals/<id>.meta.edn` | Background eval metadata (status, timestamps, PID) |
| `~/.replsh/evals/<id>.code` | Background eval source code |
| `<project>/.replsh/config.edn` | Project session config |
