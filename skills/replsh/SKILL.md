---
name: replsh
description: >
  A thinking medium for LLM agents. Use the REPL to verify assumptions, inspect
  runtime state, and test hypotheses — think before you write. Timeouts return
  partial output (never lose work). Streaming and background eval handle any
  timescale. Supports Clojure (deps.edn, Leiningen, Babashka), Python (native
  bridge — zero deps, or Jupyter for rich output), and Node.js. Activate when
  working on any project with a .replsh/config.edn, or any Clojure/Python/Node
  project where a REPL would help you understand the code.
license: EPL-2.0
compatibility: Requires Babashka (bb) installed. REPL servers started by replsh or running externally.
metadata:
  author: Daniel Tan
  repository: https://github.com/g-daniel/replsh
---

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
bbin install io.github.g-daniel/replsh

# Or clone and run directly
git clone https://github.com/g-daniel/replsh.git
cd replsh && bb -m replsh.main --help
```

If running from a clone, substitute `replsh` with `bb -m replsh.main` in all commands below.

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

### 3. Eval

```bash
# Inline
replsh eval --name dev '(+ 1 2)'

# From file
replsh eval --name dev --file script.clj

# From stdin
echo '(+ 1 2)' | replsh eval --name dev
```

### 4. Session lifecycle

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

If the eval completes within `--timeout` (default 30s), you get the full result. If it times out, you get **partial output** — whatever stdout/values accumulated before the deadline — as a success with `"partial": true`. The eval may still be running server-side. This means you never lose output to a timeout.

Use `--timeout 0` to wait indefinitely (until completion or hard timeout).

### Streaming (`--stream`)

```bash
replsh eval --name dev --stream '(doseq [i (range 10)] (println i) (Thread/sleep 500))'
```

Output arrives as NDJSON — one JSON line per chunk, streamed in real time. Final line has `"final": true`. Use this when you want to see output as it's produced (test suites, data processing, iterative output).

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

`--hard-timeout` interrupts the eval server-side (nREPL interrupt, Jupyter interrupt, SIGINT for Python/Node). Returns exit code 3. Use when you need to guarantee the eval stops.

## Command Reference

```bash
# Session lifecycle
replsh launch [backend] --name <n> [--cmd <c>] [--port <p>] [--init <code>] [--timeout <ms>]
replsh start  [backend] --name <n> [--port <p>]
replsh ls
replsh status --name <n>
replsh stop   <n>
replsh restart <n>
replsh interrupt --name <n>

# Eval
replsh eval --name <n> '<code>' | --file <path> | stdin
    [--timeout <ms>]           # soft timeout, default 30s (0 = no limit)
    [--hard-timeout <ms>]      # interrupt eval after this
    [--stream]                 # NDJSON output
    [--bg]                     # background eval

# Background eval management
replsh evals                           # list all background evals
replsh output --eval-id <id>           # read bg eval output
replsh output --eval-id <id> --follow  # tail bg eval output

# Process logs
replsh logs --name <n>                 # full server log
replsh logs --name <n> --tail 20       # last 20 lines
replsh logs --name <n> --follow        # tail until process exits
```

Backends: `nrepl`, `python`, `jupyter`, `node`. Optional when using project config.

## Output Format

### Sync eval (default)

One JSON object on stdout:

```json
{"ok": true, "command": "eval", "data": {"value": "3", "ns": "user", "chunks": [...]}}
{"ok": true, "command": "eval", "data": {"chunks": [...]}, "partial": true}
{"ok": false, "command": "eval", "error": {"code": "eval_error", "message": "..."}}
```

### Streaming eval (`--stream`)

NDJSON — one JSON line per chunk, final line is the summary:

```
{"type":"out","content":"hello\n","stream":"stdout","meta":{}}
{"type":"value","content":"3","meta":{"ns":"user"}}
{"ok":true,"command":"eval","data":{...},"final":true}
```

### Key fields

- `data.value` — return value (string)
- `data.ns` — current namespace (nREPL)
- `data.chunks` — all output: `out`, `err`, `value`, `error`, `status`
- `partial` — true when eval timed out but returned partial output
- `final` — true on the last line of streaming output
- Exit codes: 0=success, 1=eval error, 2=client error, 3=hard timeout

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

Custom toolchains go in `~/.replsh/config.edn` under `:toolchains`.

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
