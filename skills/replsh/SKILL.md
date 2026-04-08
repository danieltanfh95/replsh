---
name: replsh
description: >
  REPL-driven development tool. Use replsh proactively during investigation,
  planning, and debugging — eval expressions to verify assumptions, inspect
  runtime state, and test hypotheses before writing code. Supports Clojure
  (deps.edn, Leiningen, Babashka), Python (Poetry, venv), and Node.js.
  Activate when working on any project with a .replsh/config.edn, or any
  Clojure/Python/Node project where a REPL would help you understand the code.
license: EPL-2.0
compatibility: Requires Babashka (bb) installed. REPL servers started by replsh or running externally.
metadata:
  author: Daniel Tan
  repository: https://github.com/g-daniel/replsh
---

# replsh

A REPL gives you instant feedback. Use it to think, not just to execute.

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
# Python: inspect a DataFrame shape
replsh eval --name ml 'import pandas as pd; df = pd.read_csv("data.csv"); print(df.dtypes); print(df.shape)'

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

# Python (Poetry)
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

## Command Reference

```bash
replsh launch [backend] --name <n> [--cmd <c>] [--port <p>] [--init <code>] [--timeout <ms>]
replsh start  [backend] --name <n> [--port <p>]
replsh eval   --name <n> '<code>' | --file <path> | stdin  [--timeout <ms>]
replsh ls
replsh status --name <n>
replsh stop   <n>
replsh restart <n>
replsh interrupt --name <n>
```

Backends: `nrepl`, `jupyter`, `node`. Optional when using project config.

## Output Format

All commands emit one JSON object on stdout:

```json
{"ok": true, "command": "eval", "data": {"value": "3", "ns": "user", "chunks": [...]}}
{"ok": false, "command": "eval", "error": {"code": "eval_error", "message": "..."}}
```

- `data.value` — return value (string)
- `data.ns` — current namespace (nREPL)
- `data.chunks` — all output: `out`, `err`, `value`, `error`, `status`
- Exit codes: 0=success, 1=eval error, 2=client error, 3=timeout

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
| `python.poetry` | jupyter | `poetry run jupyter server --port {port}` |
| `python.venv` | jupyter | `{cwd}/.venv/bin/jupyter server --port {port}` |
| `node` | node | `node -e "require('net')..."` |

Custom toolchains go in `~/.replsh/config.edn` under `:toolchains`.

## Tips

- Port is auto-allocated if not specified — just omit `--port`
- Use `--init` to require namespaces on session start
- Use `--timeout` for long-running evals (default 30s)
- Sessions persist across invocations — no need to re-launch
- The REPL is your thinking tool. Use it early and often.
