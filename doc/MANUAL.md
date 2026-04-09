# replsh Manual

## Synopsis

```
replsh <command> [options]
```

Unified CLI for REPL servers. Manages named sessions across nREPL, Python, Jupyter, and Node.js backends. All output is structured JSON.

## Commands

### launch

Spawn a REPL server process, wait for readiness, and connect.

```
replsh launch [backend] --name <name> [options]
```

**Arguments:**
- `backend` — `nrepl`, `python`, `jupyter`, or `node`. Optional when using project config (derived from toolchain).

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
| `--container` | `-c` | Exec into existing Docker container (exec mode) |
| `--exec-port` | | Bridge port inside container, exec mode (default: 9876) |
| `--kernel` | `-k` | Jupyter kernel name (default: `python3`) |
| `--token` | | Jupyter auth token |
| `--prompt-re` | | Node.js prompt string (default: `> `) |

**Mode detection:**

| Flags | Mode | Behavior |
|-------|------|----------|
| `--cmd` (with or without `--image`) | Port mode | Bridge replaces container entrypoint, port exposed to host |
| `--container` (no `--cmd`) | Exec mode | Inject bridge into existing container via `docker exec` |
| `--image` (no `--cmd`) | Exec mode | Spawn container with default entrypoint, inject bridge |

**Examples:**

```bash
# From project config
replsh launch --name backend

# Explicit nREPL
replsh launch nrepl --name dev --port 1667 --cmd "bb --nrepl-server 1667"

# Python (lightweight — no Jupyter required)
replsh launch python --name py --cmd "python3 {bridge} --port {port}"

# Python (Poetry environment)
replsh launch python --name ml --cmd "poetry run python {bridge} --port {port}"

# Jupyter with Poetry (for images/HTML/widgets)
replsh launch jupyter --name ml --port 8888 \
  --cmd "poetry run jupyter server --port 8888" --kernel python3

# With bootstrap code
replsh launch --name dev --init "(require '[my.app])"
```

#### Exec mode

Exec mode injects a Python REPL into an existing or project container without replacing its entrypoint. The bridge runs persistently inside the container; each eval opens an ephemeral proxy via `docker exec -i`.

```bash
# Inject REPL into an already-running container
replsh launch python --name api --container my-flask-app

# Start a container with its default entrypoint, then inject REPL
replsh launch python --name api --image myapp:latest

# State persists across evals
replsh eval --name api '1 + 2'        # → 3
replsh eval --name api 'x = 42'
replsh eval --name api 'print(x)'    # → 42

# Stop: kills bridge but leaves unowned container running
replsh stop api
```

**How it works:**

1. Bridge is deployed into the container via `docker cp`
2. A persistent bridge server starts inside the container (`python3 /tmp/replsh/replsh_bridge.py --port 9876`), listening on container-internal localhost only (no port exposure)
3. Each eval opens an ephemeral proxy (`docker exec -i ... --connect 127.0.0.1:9876`) that relays stdin↔TCP
4. `replsh stop` kills the bridge; if the container was spawned by replsh (`--image`), it also stops the container

**Owned vs unowned containers:**

- `--container <name>`: Container is **unowned** — replsh will not stop it on `replsh stop`
- `--image <name>`: Container is **owned** — replsh spawned it and will stop+remove it on `replsh stop`

### start

Connect to an already-running REPL server.

```
replsh start [backend] --name <name> <address|url>
```

**Arguments:**
- `backend` — `nrepl`, `python`, `jupyter`, or `node`. Optional when using project config.
- `address` — `host:port` for nREPL/Python/Node, or URL for Jupyter (when not using config).

Options are the same as `launch` minus `--cmd` and `--timeout`.

**Examples:**

```bash
# From config
replsh start --name backend

# Connect to nREPL
replsh start nrepl --name dev --port 1667

# Connect to Python bridge
replsh start python --name py --port 9876

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

- replsh sends an interrupt to the backend (nREPL interrupt op, Jupyter REST interrupt, SIGINT for Python/Node).
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
replsh eval --name py 'len(my_list)'
replsh eval --name py --file /dev/stdin <<< 'import os; print(os.getcwd())'
```

#### Python: expression vs statement

The Python backend tries to compile code as an expression first (to return a value). If that fails, it falls back to statement mode (no return value). This means:

- **Single expressions** return values: `1 + 1` → `"2"`, `len(xs)` → `"42"`
- **Multi-statement code** (separated by `;` or newlines) compiles as statements — no return value. Use `print()` or split into separate evals.
- **For complex multiline code**, use `--file` or pipe via stdin to avoid shell quoting issues:

```bash
replsh eval --name py --file /dev/stdin <<'EOF'
import pandas as pd
df = pd.read_csv('data.csv')
print(df.dtypes)
print(df.shape)
EOF
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

Cancel a running evaluation.

```
replsh interrupt --name <name>
```

Supported by all backends: nREPL (interrupt op), Jupyter (REST interrupt), Python (SIGINT), Node (SIGINT).

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
              :cwd       "ml/"}}}
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
| `:container` | Existing Docker container name (exec mode) |

**Exec-mode config example:**

```clojure
{:sessions
 {"api" {:toolchain "python.container"
         :container "di-contracts"}}}
```

When `:container` is set (or `:image` is set without `:cmd`), replsh uses exec mode — injecting the bridge into the container instead of replacing its entrypoint.

### Global config — `~/.replsh/config.edn`

Define or override toolchain presets. Override path with `REPLSH_CONFIG_GLOBAL`.

```clojure
{:toolchains
 {"python.conda" {:backend  :python
                  :cmd      "conda run python {bridge} --port {port}"
                  :defaults {:port 9876}}}}
```

### Resolution order

```
toolchain :defaults → toolchain top-level → session spec → CLI args
```

Each layer overrides the previous. CLI args always win.

### Template variables

Command strings support `{port}`, `{cwd}`, `{host}`, and `{bridge}` placeholders, substituted at resolution time.

```clojure
:cmd "{cwd}/.venv/bin/python {bridge} --port {port}"
;; becomes: /project/ml/.venv/bin/python /home/user/.replsh/bridge/replsh_bridge.py --port 9876
```

`{bridge}` resolves to the path of the Python bridge script, automatically deployed to `~/.replsh/bridge/replsh_bridge.py`.

## Toolchains

### Built-in toolchains

| Name | Backend | Command | Defaults |
|------|---------|---------|----------|
| `clojure.deps` | nrepl | `clj -M:nrepl -m nrepl.cmdline --port {port}` | port 7888 |
| `clojure.lein` | nrepl | `lein repl :headless :port {port}` | port 7888 |
| `clojure.bb` | nrepl | `bb --nrepl-server {port}` | port 1667 |
| `python` | python | `python3 {bridge} --port {port}` | port 9876 |
| `python.poetry` | python | `poetry run python {bridge} --port {port}` | port 9876 |
| `python.venv` | python | `{cwd}/.venv/bin/python {bridge} --port {port}` | port 9876 |
| `python.poetry.jupyter` | jupyter | `poetry run jupyter server --port {port}` | port 8888, kernel python3 |
| `python.venv.jupyter` | jupyter | `{cwd}/.venv/bin/jupyter server --port {port}` | port 8888, kernel python3 |
| `node` | node | `node -e "require('net')..."` | port 5001, prompt "> " |
| `clojure.bb.container` | nrepl | `bb --nrepl-server 0.0.0.0:{port}` | port 1667, Docker |
| `clojure.deps.container` | nrepl | `clj -M:nrepl ... --bind 0.0.0.0` | port 7888, Docker |
| `python.container` | python | `python3 {bridge} --host 0.0.0.0 --port {port}` | port 9876, Docker |
| `node.container` | node | `node -e "require('net')..."` | port 5001, Docker |

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
| `~/.replsh/bridge/replsh_bridge.py` | Python bridge script (auto-deployed) |
| `<project>/.replsh/config.edn` | Project session config |
