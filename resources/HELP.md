# replsh

Bash runs commands. A REPL is where you think. Use it to verify before you write, not just to execute after. Timeouts return partial output, streaming gives real-time feedback, and background eval handles anything long-running — so the REPL works at every timescale.

## When to Use the REPL

**You should reach for the REPL proactively, not wait to be asked.** If you catch yourself guessing what code does — eval it instead.

### During investigation

- **Not sure what a function returns?** Eval it with sample inputs instead of tracing through the code mentally.
- **Reading unfamiliar code?** Require the namespace in the REPL and call functions to see their behavior.
- **Debugging?** Eval sub-expressions to isolate where values diverge from expectations.

```bash
# "What does this config resolution actually produce?"
replsh eval --name dev '(require (quote [replsh.config :as config])) (config/resolve-session (config/resolve-toolchains nil) {:config {:sessions {"x" {:toolchain "clojure.bb"}}} :dir "/tmp"} "x" {})'

# "What type does this return?"
replsh eval --name dev '(type (some-fn some-arg))'

# "Does this regex match?"
replsh eval --name dev '(re-matches #"v\d+\.\d+" "v1.2")'
```

### During planning

- **Before proposing a change**, eval the current behavior to establish a baseline.
- **Verify your mental model** — if you think X calls Y which returns Z, eval it and confirm.
- **Prototype in the REPL** before writing to files. It's faster and reversible.

```bash
# Verify current behavior before changing it
replsh eval --name dev '(my-fn current-args)'

# Prototype a new approach
replsh eval --name dev '(defn my-fn-v2 [x] (new-approach x))'
replsh eval --name dev '(my-fn-v2 test-input)'
```

### During implementation

- **Test each function as you write it** instead of waiting for the full test suite.
- **Run tests incrementally** through the REPL — faster feedback than cold-starting.
- **Check edge cases** immediately after writing a function.

```bash
# Test the function you just wrote
replsh eval --name dev '(require (quote [my.ns :reload :all])) (my.ns/new-fn edge-case-input)'

# Run specific tests
replsh eval --name dev '(clojure.test/run-tests (quote my.ns-test))'
```

### During debugging

- **Inspect state** in a running system.
- **Eval sub-expressions** from a failing function to find where it breaks.
- **Test fixes** before committing them.

```bash
# Python: inspect a DataFrame shape (use --file for multiline)
replsh eval --name ml --file /dev/stdin <<'EOF'
import pandas as pd
df = pd.read_csv('data.csv')
print(df.dtypes)
print(df.shape)
EOF

# Clojure: check what's in an atom/state
replsh eval --name dev '(deref my-app-state)'
```

## Installation

Check if replsh is available:

```bash
which bb && which replsh
```

If Babashka is not installed:

```bash
# macOS
brew install borkdude/brew/babashka

# Linux
curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
chmod +x install && sudo ./install
```

Install replsh:

```bash
# Via bbin (recommended)
bbin install io.github.danieltanfh95/replsh

# Or clone and install locally
git clone https://github.com/danieltanfh95/replsh.git
cd replsh && bbin install . --as replsh
```

## Getting Started

### 1. Check for project config

If `.replsh/config.edn` exists, sessions are pre-configured — just launch:

```bash
replsh launch --name dev
replsh eval --name dev '(+ 1 2)'
```

### 2. Launch without config

```bash
# Clojure (Babashka) — port auto-allocated
replsh launch nrepl --name dev --cmd "bb --nrepl-server {port}"

# Clojure (deps.edn)
replsh launch nrepl --name dev --cmd "clj -M:nrepl -m nrepl.cmdline --port {port}"

# Bash (persistent shell — env vars, cwd, functions persist)
replsh launch bash --name ops --cmd "python3 {bridge} --port {port} --backend bash"

# Python (lightweight — no Jupyter required)
replsh launch python --name py --cmd "python3 {bridge} --port {port}"

# Python (Poetry environment)
replsh launch python --name ml --cmd "poetry run python {bridge} --port {port}"

# Python (Jupyter — for images/HTML/widgets)
replsh launch jupyter --name ml --cmd "poetry run jupyter server --port {port}" --kernel python3

# Node.js
replsh launch node --name frontend --port 5001 \
  --cmd "node -e \"require('net').createServer(s=>require('repl').start({input:s,output:s})).listen(5001)\""
```

### 3. Exec mode (inject REPL into containers)

```bash
# Into an already-running container (unowned — won't stop it)
replsh launch python --name api --container my-flask-app

# Start container with its default entrypoint, inject REPL (owned — will stop it)
replsh launch python --name api --image myapp:latest

# SSH only (remote machine — jump hosts, keys, and ports from ~/.ssh/config)
replsh launch python --name remote --ssh-host my-machine

# SSH + Docker (e.g. local → jumphost → remote Docker host)
replsh launch python --name remote --ssh-host my-machine --container my-app

# State persists across evals
replsh eval --name api 'import flask; print(flask.__version__)'
replsh eval --name api 'x = 42'
replsh eval --name api 'print(x)'   # → 42

replsh stop api
```

`--ssh-host` accepts any host alias from `~/.ssh/config`. ProxyJump, user, port, and identity file are all read from SSH config — replsh just passes the name through.

### 4. Eval

```bash
# Inline
replsh eval --name dev '(+ 1 2)'

# From file
replsh eval --name dev --file script.clj

# From stdin
echo '(+ 1 2)' | replsh eval --name dev
```

### 5. Session lifecycle

```bash
replsh ls                    # list all sessions
replsh status --name dev     # reachability + process info
replsh restart dev           # restart server, re-run init
replsh stop dev              # kill and remove
replsh interrupt --name dev  # cancel running eval
```

## Eval Modes

replsh eval has three modes. Choose based on the task:

### Default (sync with partial output)

```bash
replsh eval --name dev '(+ 1 2)'
replsh eval --name dev '(long-running-fn)' --timeout 60000
```

If the eval completes within `--timeout` (default 30s), you get the full result with `"status": "complete"`. If it times out, you get **partial output** — whatever stdout/values accumulated before the deadline — as a success with `"status": "partial"`. The eval may still be running server-side. This means you never lose output to a timeout.

Use `--timeout 0` to wait indefinitely (until completion or hard timeout).

### Streaming (`--stream`)

```bash
replsh eval --name dev --stream '(doseq [i (range 10)] (println i) (Thread/sleep 500))'
```

Output arrives as NDJSON — one JSON line per chunk, streamed in real time. Each chunk line has `"status": "streaming"`. The final line is the summary envelope with `"status": "complete"` (or `"partial"` on timeout). Use this when you want to see output as it's produced (test suites, data processing, iterative output).

### Background (`--bg`)

```bash
replsh eval --name dev --bg '(train-model data)'
# → {"ok":true,"command":"eval-bg","data":{"eval-id":"eval-a1b2c3d4","session":"dev","pid":12345}}

# Check status
replsh evals

# Read output later
replsh output --eval-id eval-a1b2c3d4

# Or tail output in real time
replsh output --eval-id eval-a1b2c3d4 --follow
```

The eval runs in a background process. You get an eval-id immediately and can retrieve output later. Use this for long-running work (training, migrations, servers) where you don't want to block.

### Hard timeout (`--hard-timeout`)

```bash
# Soft timeout at 5s (return partial), hard kill at 60s
replsh eval --name dev '(expensive-fn)' --timeout 5000 --hard-timeout 60000

# No soft timeout, hard kill at 5 minutes
replsh eval --name dev '(train-model)' --timeout 0 --hard-timeout 300000
```

`--hard-timeout` interrupts the eval server-side (nREPL interrupt, Jupyter interrupt, SIGINT for Python/Node). Returns `"status": "partial"` and exit code 3. Use when you need to guarantee the eval stops.

## Command Reference

```bash
# Session lifecycle
replsh launch [backend] --name <n> [--cmd <c>] [--port <p>] [--init <code>] [--timeout <ms>] [--force]
replsh launch [backend] --name <n> --container <c>                    # exec mode: inject into existing container
replsh launch [backend] --name <n> --image <img>                      # exec mode: spawn container, inject REPL
replsh launch [backend] --name <n> --ssh-host <host> [--container <c>]  # exec mode: via SSH
replsh start  [backend] --name <n> [--port <p>]
replsh ls
replsh status --name <n>
replsh stop   <n>
replsh restart <n> [--timeout <ms>]
replsh interrupt --name <n>

# Eval
replsh eval --name <n> '<code>' | --file <path> | stdin
    [--timeout <ms>]           # soft timeout, default 30s (0 = no limit)
    [--hard-timeout <ms>]      # interrupt eval after this
    [--stream]                 # NDJSON output
    [--chunked]                # include raw chunks array in output
    [--bg]                     # background eval

# Background eval management
replsh evals                           # list all background evals
replsh output --eval-id <id>           # read bg eval output
replsh output --eval-id <id> --follow  # tail bg eval output

# History and replay
replsh history --name <n>                       # recent eval history (JSON)
replsh history --name <n> --format script       # history as a script (one form per line)
replsh replay --name <n> '<code>'               # eval each top-level form sequentially
replsh replay --name <n> --file <path>          # replay from file

# Process logs
replsh logs --name <n>                 # full server log
replsh logs --name <n> --tail 20       # last 20 lines
replsh logs --name <n> --follow        # tail until process exits

# Discovery
replsh toolchains                      # list all available toolchains (JSON)
replsh --help                          # full reference
replsh --install-skill [--path <dest>] # generate skills/replsh/SKILL.md
```

Backends: `nrepl`, `python`, `jupyter`, `node`, `bash`. Optional when using project config.

### Global flags

- `--exit-on-error` — exit with non-zero codes on failure (default: always exit 0). Use this in scripts or CI. Without it, errors are reported in JSON but the process exits 0, which is friendlier for LLM agent tool-calling.

## Output Format

### Sync eval (default)

One JSON object on stdout:

```json
{"ok": true, "command": "eval", "status": "complete", "data": {"value": "3", "ns": "user"}}
{"ok": true, "command": "eval", "status": "complete", "data": {"value": "42", "output": "hello\n", "ns": "user"}}
{"ok": true, "command": "eval", "status": "partial", "data": {"value": "42", "output": "partial stdout..."}}
{"ok": false, "command": "eval", "status": "complete", "error": {"code": "eval_error", "message": "..."}}
{"ok": true, "command": "eval", "status": "complete", "data": {"value": "42", "stale": [{"ns": "replsh.util", "file": "src/replsh/util.clj"}]}}
```

### Streaming eval (`--stream`)

NDJSON — one JSON line per chunk, last line is the summary:

```
{"type":"out","content":"hello\n","stream":"stdout","meta":{},"status":"streaming"}
{"type":"value","content":"3","meta":{"ns":"user"},"status":"streaming"}
{"ok":true,"command":"eval","status":"complete","data":{"value":"3","ns":"user"}}
```

### Key fields

- `data.value` — return value (string)
- `data.ns` — current namespace (nREPL)
- `data.output` — joined stdout (only present when non-empty)
- `status` — `"complete"` (eval finished), `"partial"` (eval timed out), or `"streaming"` (live NDJSON chunk, more coming)
- `data.chunks` — raw chunk array, only present with `--chunked` flag
- `data.stale` — list of `{"ns": "...", "file": "..."}` objects for loaded modules whose source files changed on disk since the last eval; absent when nothing is stale. Only checked after the session has been idle for 30+ minutes.
- Exit codes: always 0 by default. With `--exit-on-error`: 0=success, 1=eval error, 2=client error, 3=hard timeout

## Config

### Project (`.replsh/config.edn`)

```clojure
{:sessions
 {"backend" {:toolchain "clojure.bb"
             :init "(require '[my.app])"}
  "ml"      {:toolchain "python.poetry"
             :cwd "ml/"}}}
```

### Built-in toolchains

| Name | Backend | Command |
|------|---------|---------|
| `clojure.deps` | nrepl | `clj -M:nrepl -m nrepl.cmdline --port {port}` |
| `clojure.lein` | nrepl | `lein repl :headless :port {port}` |
| `clojure.bb` | nrepl | `bb --nrepl-server {port}` |
| `python` | python | `python3 {bridge} --port {port}` |
| `python.poetry` | python | `poetry run python {bridge} --port {port}` |
| `python.venv` | python | `{cwd}/.venv/bin/python {bridge} --port {port}` |
| `python.poetry.jupyter` | jupyter | `poetry run jupyter server --port {port}` |
| `python.venv.jupyter` | jupyter | `{cwd}/.venv/bin/jupyter server --port {port}` |
| `node` | node | `node -e "require('net')..."` |
| `bash` | bash | `python3 {bridge} --port {port} --backend bash` |
| `bash.container` | bash | Docker: `python3 {bridge} --host 0.0.0.0 --port {port} --backend bash` |
| `clojure.bb.container` | nrepl | Docker: `bb --nrepl-server 0.0.0.0:{port}` |
| `clojure.deps.container` | nrepl | Docker: `clj -M:nrepl -m nrepl.cmdline --port {port} --bind 0.0.0.0` |
| `python.container` | python | Docker: `python3 {bridge} --host 0.0.0.0 --port {port}` |
| `node.container` | node | Docker: `node -e "require('net')..."` |

Custom toolchains go in `~/.replsh/config.edn` under `:toolchains`.

Run `replsh toolchains` to list all available toolchains (built-in + custom) as JSON.

## Python Eval Notes

The Python backend tries `compile(code, 'eval')` first (returns a value), then falls back to `compile(code, 'exec')` (no return value). This means:

- **Single expressions** return values: `1 + 1` → `"2"`, `len(xs)` → `"42"`
- **Multi-statement code** (`import x; expr`, multiline blocks) compiles as `exec` — no return value. Use `print()` or split into separate evals.
- **For complex multiline code**, use `--file` or pipe via stdin to avoid shell quoting:

```bash
# Split imports and expressions into separate evals
replsh eval --name py 'import pandas as pd'
replsh eval --name py 'pd.read_csv("data.csv").shape'  # → returns value

# Or use stdin for multiline
replsh eval --name py --file /dev/stdin <<'EOF'
import pandas as pd
df = pd.read_csv('data.csv')
print(df.dtypes)
print(df.shape)
EOF
```

## Reasoning Cache

REPL state persists independently of the conversation context window. A stored `def` costs ~50 tokens to retrieve; re-reading the same file costs ~1,500. Use the REPL as working memory to keep context small and survive cache evictions.

### Pattern: compute → store → retrieve

Instead of reading a whole file, eval to extract what you need, then `def` the result:

```bash
# Clojure — extract and store
replsh eval --name dev '(def schema (keys (read-string (slurp "deps.edn"))))'
# later, retrieve cheaply
replsh eval --name dev 'schema'

# Python — compute and store
replsh eval --name py 'import json; schema = list(json.load(open("schema.json")).keys())'
replsh eval --name py 'schema'

# Node
replsh eval --name frontend 'global.routes = require("./src/routes").map(r => r.path)'
replsh eval --name frontend 'global.routes'
```

### Pattern: verify → store → recall

Verify an assumption once, store it, and recall instead of re-verifying:

```bash
replsh eval --name dev '(def uses-http? (some? (re-find #"http" (slurp "src/my/ns.clj"))))'
# many turns later — no need to re-read the file
replsh eval --name dev 'uses-http?'
```

### When to def

- After reading a complex file — store a summary, not the raw content
- After computing something expensive — test results, dependency graphs, schema shapes
- After making a decision — store the conclusion so you don't re-derive it

### What to store

Store **computed values and summaries**, not raw file contents. Good: `(def public-fns (filter :public (analyze-ns 'my.ns)))`. Bad: `(def src (slurp "big-file.clj"))`.

## Tips

- Port is auto-allocated if not specified — just omit `--port`
- Use `--init` to require namespaces or import modules on session start
- Sessions persist across invocations — no need to re-launch
- The REPL is your thinking tool. Use it early and often.
- Timeout is graceful — you always get partial output, never a bare error
- Use `--stream` for test suites and any eval where you want progressive output
- Use `--bg` for anything that might run longer than you want to wait
- Use `replsh logs` to inspect server-side output (startup messages, crash logs)
- `--timeout 0` disables the soft timeout — use with `--hard-timeout` for a single deadline
