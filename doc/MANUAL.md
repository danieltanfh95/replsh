# replsh Manual

## Synopsis

```
replsh <command> [options]
```

Unified CLI for REPL servers. Manages named sessions across nREPL, Python, Jupyter, Node.js, and Bash backends. All output is structured JSON.

## Commands

### launch

Spawn a REPL server process, wait for readiness, and connect.

```
replsh launch [backend] --name <name> [options]
```

**Arguments:**
- `backend` — `nrepl`, `python`, `jupyter`, `node`, or `bash`. Optional when using project config (derived from toolchain).

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
| `--image` | | Docker image for port-mode containerized REPL (requires `--cmd`) |
| `--force` | `-f` | Replace an existing live session with the same name without error |
| `--volume` | `-v` | Docker volume mount (`host:container[:opts]`, repeatable). Requires `--image`. |
| `--platform` | | Docker platform override (e.g., `linux/amd64`). Requires `--image`. |
| `--exec-port` | | Bridge port inside the exec chain (default: 9876) |
| `--kernel` | `-k` | Jupyter kernel name (default: `python3`) |
| `--token` | | Jupyter auth token |
| `--prompt-re` | | Node.js prompt string (default: `> `) |

**Mode detection:**

| Invocation | Mode | Behavior |
|------------|------|----------|
| `--cmd` (with or without `--image`) | Port mode | Bridge replaces container entrypoint, port exposed to host |
| `-- --runtime ...` (after `--`) | Exec mode | Inject bridge through the ordered via chain |
| Config session has `:via` key | Exec mode | Via chain read from config |

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

Exec mode injects a REPL bridge into an existing or remote container without replacing its entrypoint. The execution chain is expressed as an ordered list of layers after `--`, left to right from outermost to innermost:

```bash
# Into an already-running container
replsh launch --name api -- \
  --runtime docker --container my-flask-app \
  --toolchain python

# Spawn a new container, inject REPL (owned — replsh will stop it)
replsh launch --name api -- \
  --runtime docker --image myapp:latest \
  --toolchain python

# SSH only (remote machine)
replsh launch --name remote -- \
  --runtime ssh --host my-machine \
  --toolchain python

# SSH → Docker (local → remote Docker host)
replsh launch --name remote -- \
  --runtime ssh --host my-machine \
  --runtime docker --container my-app \
  --toolchain python

# Docker → SSH (inside an outer container, SSH to internal host)
replsh launch --name dind -- \
  --runtime docker --container outer \
  --runtime ssh --host internal.host \
  --toolchain python

# SSH → Docker → Bash (environment setup, e.g. rbenv)
replsh launch --name rbenv -- \
  --runtime ssh --host my-machine \
  --runtime docker --container my-app \
  --runtime bash --setup "source /etc/profile.d/rbenv.sh" \
  --toolchain python

# State persists across evals
replsh eval --name api '1 + 2'        # → 3
replsh eval --name api 'x = 42'
replsh eval --name api 'print(x)'    # → 42

# Stop: kills bridge; if container was spawned (--image), also stops container
replsh stop api
```

**Pipeline flags (after `--`):**

| Flag | Layer | Description |
|------|-------|-------------|
| `--runtime ssh` | `:ssh` | SSH access layer |
| `--host user@host` | ssh | Remote host (or `~/.ssh/config` alias) |
| `--port 2222` | ssh | SSH port override |
| `--key ~/.ssh/work` | ssh | Identity file override |
| `--runtime docker` | `:docker` | Docker exec/run layer |
| `--container abc123` | docker | Attach to existing container |
| `--image python:3.12` | docker | Spawn new container |
| `--user root` | docker | `docker exec --user` override |
| `--runtime bash` | `:bash` | Environment setup layer |
| `--setup "cmd"` | bash | Setup command (repeatable, joined with `&&`) |
| `--toolchain python` | terminal | Backend toolchain (sets REPL type) |

**How it works:**

1. Bridge is deployed via stdin piping (`cat > /tmp/replsh/replsh_bridge.py`) through the via chain
2. A persistent bridge server starts at the end of the chain, listening on localhost (no port exposure)
3. Each eval opens an ephemeral proxy that relays stdin↔TCP through the same via chain
4. `replsh stop` kills the bridge; owned containers (spawned via `--image`) are also stopped

**Owned vs unowned containers:**

- `--container <name>`: Container is **unowned** — replsh will not stop it on `replsh stop`
- `--image <name>`: Container is **owned** — replsh spawned it and will stop+remove it on `replsh stop`

`--host` accepts any alias from `~/.ssh/config`. ProxyJump, user, port, and identity file are all read from SSH config — replsh just passes the alias through.

### start

Connect to an already-running REPL server.

```
replsh start [backend] --name <name> <address|url>
```

**Arguments:**
- `backend` — `nrepl`, `python`, `jupyter`, `node`, or `bash`. Optional when using project config.
- `address` — `host:port` for nREPL/Python/Node/Bash, or URL for Jupyter (when not using config).

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
| `--chunked` | | Include raw `data.chunks` array in the output envelope. Absent by default. |

#### Timeout behavior

By default, `--timeout` is 30000ms (30 seconds). When the soft timeout fires:

- Accumulated output (stdout, values, errors) is returned as a **partial success**: `"status": "partial"`, exit code 0.
- The eval may still be running server-side — replsh simply stops listening.

When `--hard-timeout` fires:

- replsh sends an interrupt to the backend (nREPL interrupt op, Jupyter REST interrupt, SIGINT for Python/Bash). Node interrupt is best-effort only — replsh sends SIGINT to the server process if locally launched; does not stop evals inside a remote or containerised Node session.
- Returns as a **timeout error**: exit code 3.
- Guaranteed to stop the eval.

They compose: `--timeout 5000 --hard-timeout 60000` returns partial output at 5s, kills the eval at 60s. `--timeout 0 --hard-timeout 300000` waits for completion up to 5 minutes, then kills.

#### Streaming

With `--stream`, output is emitted as NDJSON — one JSON line per chunk as it arrives from the backend. The final line is the summary envelope with `"status": "complete"`.

```
{"type":"out","content":"line 1\n","stream":"stdout","meta":{}}
{"type":"value","content":":done","meta":{"ns":"user"}}
{"ok":true,"command":"eval","status":"complete","data":{...}}
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

Backend support:
- **nREPL**: interrupt op (protocol-level)
- **Jupyter**: REST interrupt (protocol-level)
- **Python / Bash**: SIGINT to process (reliable for locally-launched sessions)
- **Node**: best-effort — no protocol-level interrupt. replsh sends SIGINT to the server process if the session was launched by replsh; does not stop evals inside a remote or containerised Node session.

### evals

List all background evals.

```
replsh evals
```

Returns eval-id, session, status (`running`, `completed`, `failed`, `timeout`), timestamps, and PID for each background eval.

### history

Show recent eval history for a session.

```
replsh history --name <name>
replsh history --name <name> --format script
```

| Flag | Alias | Description |
|------|-------|-------------|
| `--name` | `-n` | Session name |
| `--format` | | `json` (default) or `script` (one form per line, blank-line separated) |

`--format script` emits code forms only — useful for piping back into `replay`.

### replay

Re-evaluate each top-level form from code or a file sequentially.

```
replsh replay --name <name> '<code>'
replsh replay --name <name> --file <path>
```

| Flag | Alias | Description |
|------|-------|-------------|
| `--name` | `-n` | Session name |
| `--file` | `-f` | Read code from file (use `/dev/stdin` for pipe) |
| `--timeout` | `-t` | Soft timeout per form in ms (default: 30000) |
| `--hard-timeout` | | Hard timeout per form in ms |

Form splitting is backend-aware: Clojure splits on top-level EDN forms; Python/Jupyter on `\n# ---\n`; Node on `\n// ---\n`; Bash treats the whole input as one form.

Returns `data.forms` (count) and `data.results` (per-form eval results array).

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

## Global Flags

| Flag | Description |
|------|-------------|
| `--exit-on-error` | Exit with non-zero codes on failure (default: always exit 0). Use in CI pipelines or scripts. |

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
| `:via` | Ordered via chain for exec mode (see below) |

**Exec-mode config example:**

```clojure
{:sessions
 {"api"      {:via [{:type :docker :container "my-flask-app"}]
              :toolchain "python"}

  "remote"   {:via [{:type :ssh :host "my-machine"}
                    {:type :docker :container "my-app"}]
              :toolchain "python"}

  "rbenv"    {:via [{:type :ssh :host "my-machine"}
                    {:type :docker :container "my-app"}
                    {:type :bash :setup ["source /etc/profile.d/rbenv.sh"]}]
              :toolchain "python"}}}
```

When `:via` is set, replsh uses exec mode — injecting the bridge through the ordered layer chain.

**Via layer specs:**

```clojure
;; SSH access layer
{:type :ssh  :host "user@host"  :port 22  :key "~/.ssh/id_rsa"}  ; :port and :key optional

;; Docker exec (attach to existing container)
{:type :docker  :container "abc123"  :user "root"}               ; :user optional

;; Docker run (spawn new container)
{:type :docker  :image "python:3.12"}

;; Bash environment wrapper
{:type :bash  :setup ["source /etc/profile.d/rbenv.sh" "rbenv shell 3.1.0"]}
```

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
| `bash` | bash | `python3 {bridge} --port {port} --backend bash` | — |
| `node` | node | `node -e "require('net')..."` | port 5001, prompt "> " |

**Port-mode Docker REPL** (spawn a fresh isolated container, expose a port to the host) — define as a custom toolchain:

```clojure
;; ~/.replsh/config.edn
{:toolchains
 {"python.myimage" {:backend  :python
                    :cmd      "python3 {bridge} --host 0.0.0.0 --port {port}"
                    :runtime  :docker
                    :image    "python:3.12-slim"
                    :defaults {:port 9876}}}}
```

This differs from exec mode: exec mode injects into an existing container with no port exposure; port-mode Docker spawns a fresh container and exposes a port to the host.

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
    "ns": "user"
  }
}
```

When `--chunked` is passed, `data.chunks` contains the raw chunk array:

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
  "status": "partial",
  "data": {"name": "dev", "output": "..."}
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
{"ok":true,"command":"eval","status":"complete","data":{...}}
```

### Chunk types

| Type | Description |
|------|-------------|
| `out` | Stdout output (has `stream` field) |
| `err` | Stderr output (has `stream` field) |
| `value` | Return value (has `meta` with `ns` for nREPL) |
| `error` | Evaluation error |
| `status` | Status message |

### Notable output fields

- `data.stale` — list of `{"ns": "...", "file": "..."}` objects for loaded modules whose source files changed on disk since the last eval. Only populated after the session has been idle for 30+ minutes (configurable via `REPLSH_WATCH_IDLE_MS`). Absent when nothing is stale.
- `data.chunks` — raw chunk array; only present when `--chunked` is passed.
- `data.output` — joined stdout string (present when stdout was captured).

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
| `REPLSH_WATCH_IDLE_MS` | Override idle threshold for stale-file detection in ms (default: `1800000` = 30 min). Set small (e.g., `100`) to force stale checks during testing. |

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
