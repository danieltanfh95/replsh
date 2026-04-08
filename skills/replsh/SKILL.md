---
name: replsh
description: >
  Use replsh to manage and evaluate code in REPL sessions across Clojure
  (deps.edn, Leiningen, Babashka), Python (Poetry, venv), and Node.js.
  Activate when the user wants to eval code interactively, run tests in a
  live REPL, or when the project has a .replsh/config.edn file.
license: EPL-2.0
compatibility: Requires Babashka (bb) installed. REPL servers started by replsh or running externally.
metadata:
  author: Daniel Tan
  repository: https://github.com/g-daniel/replsh
---

# replsh

Unified CLI for REPL servers. Manages named sessions across nREPL (Clojure), Jupyter (Python), and Node.js backends. All output is structured JSON — built for LLMs.

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

## Quick Reference

```bash
# Launch a REPL server + connect (port auto-allocated)
replsh launch --name <session>

# Launch from project config
replsh launch --name <session>

# Launch with explicit args
replsh launch nrepl --name <session> --port <port> --cmd "<shell command>"

# Connect to an already-running server
replsh start nrepl --name <session> --port <port>

# Evaluate code
replsh eval --name <session> '<code>'
replsh eval --name <session> --file <path>
echo '<code>' | replsh eval --name <session>

# Session lifecycle
replsh ls
replsh status --name <session>
replsh restart <session>
replsh stop <session>
replsh interrupt --name <session>
```

## Output Format

All commands emit exactly one JSON object on stdout:

```json
{"ok": true, "command": "eval", "data": {"value": "3", "ns": "user", ...}}
{"ok": false, "command": "eval", "error": {"code": "eval_error", "message": "..."}}
```

Exit codes: 0=success, 1=eval error, 2=client error, 3=timeout.

## Typical Workflow

### 1. Check for project config

Look for `.replsh/config.edn` in the project root. If it exists, sessions are pre-configured:

```bash
replsh launch --name backend
replsh eval --name backend '(+ 1 2)'
```

### 2. Launch without config

```bash
# Clojure (Babashka nREPL) — port auto-allocated
replsh launch nrepl --name dev --cmd "bb --nrepl-server {port}"

# Clojure (deps.edn) — explicit port
replsh launch nrepl --name dev --port 7888 \
  --cmd "clj -M:nrepl -m nrepl.cmdline --port 7888"

# Python (Jupyter via Poetry)
replsh launch jupyter --name ml --port 8888 \
  --cmd "poetry run jupyter server --port 8888" --kernel python3

# Node.js
replsh launch node --name frontend --port 5001 \
  --cmd "node -e \"require('net').createServer(s=>require('repl').start({input:s,output:s})).listen(5001)\""
```

### 3. Evaluate and iterate

```bash
# Inline
replsh eval --name dev '(defn greet [name] (str "Hello, " name))'
replsh eval --name dev '(greet "world")'

# From a file
replsh eval --name dev --file src/my/script.clj

# From stdin
echo '(+ 1 2)' | replsh eval --name dev
cat script.clj | replsh eval --name dev
```

### 4. Bootstrap with --init

Run setup code when creating a session:

```bash
replsh launch nrepl --name dev --cmd "bb --nrepl-server {port}" \
  --init "(require '[my.app :as app])"
```

### 5. Clean up

```bash
replsh stop dev
replsh ls             # verify
```

## Config File Format

### Project config (`.replsh/config.edn`)

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

### Global config (`~/.replsh/config.edn`)

```clojure
{:toolchains
 {"python.conda" {:backend  :jupyter
                  :cmd      "conda run jupyter server --port {port}"
                  :defaults {:port 8888 :kernel "python3"}}}}
```

## Built-in Toolchains

| Name | Backend | Default command |
|------|---------|----------------|
| `clojure.deps` | nrepl | `clj -M:nrepl -m nrepl.cmdline --port {port}` |
| `clojure.lein` | nrepl | `lein repl :headless :port {port}` |
| `clojure.bb` | nrepl | `bb --nrepl-server {port}` |
| `python.poetry` | jupyter | `poetry run jupyter server --port {port}` |
| `python.venv` | jupyter | `{cwd}/.venv/bin/jupyter server --port {port}` |
| `node` | node | `node -e "require('net')..."` |

## Eval Response Shape

```json
{
  "ok": true,
  "command": "eval",
  "data": {
    "name": "dev",
    "chunks": [
      {"type": "out", "content": "hello\n", "stream": "stdout"},
      {"type": "value", "content": "42", "meta": {"ns": "user"}}
    ],
    "value": "42",
    "ns": "user"
  }
}
```

- `data.value`: the return value (string)
- `data.ns`: current namespace (nREPL only)
- `data.chunks`: all output including stdout, stderr, values, and errors
- Chunk types: `out`, `err`, `value`, `error`, `status`

## Error Response

```json
{
  "ok": false,
  "command": "eval",
  "error": {"code": "eval_error", "message": "ArithmeticException: Divide by zero"},
  "data": {"chunks": [{"type": "error", "content": "..."}]}
}
```

## Tips

- Port is auto-allocated if not specified — no need for `--port` in most cases
- Use `--timeout` on eval for long-running operations (default 30s)
- `replsh restart <name>` re-launches the server and re-runs init code
- `replsh status --name <name>` shows reachability and process info
- Sessions persist across CLI invocations — `replsh eval` reconnects each time
- For multi-project setups, create multiple named sessions
- Config files use `{port}`, `{cwd}`, `{host}` template variables in commands
