# replsh — LLM Skill Document

You have access to `replsh`, a CLI tool for interacting with REPL servers (nREPL, Jupyter, Node.js). Use it to evaluate code, test functions, and iterate in interactive environments.

## Quick Reference

```bash
# Launch a REPL server + connect (from project config)
replsh launch --name <session>

# Launch with explicit args
replsh launch nrepl --name <session> --port <port> --cmd "<shell command>"

# Connect to an already-running server
replsh start nrepl --name <session> --port <port>

# Evaluate code
replsh eval --name <session> '<code>'
replsh eval --name <session> --file <path>
echo '<code>' | replsh eval --name <session>

# List sessions
replsh ls

# Session lifecycle
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
replsh launch --name backend    # launch from config
replsh eval --name backend '(+ 1 2)'
```

### 2. Launch without config

```bash
# Clojure (Babashka nREPL)
replsh launch nrepl --name dev --port 1667 --cmd "bb --nrepl-server 1667"

# Python (Jupyter via Poetry)
replsh launch jupyter --name ml --port 8888 \
  --cmd "poetry run jupyter server --port 8888" --kernel python3

# Node.js
replsh launch node --name frontend --port 5001 \
  --cmd "node -e \"require('net').createServer(s=>require('repl').start({input:s,output:s})).listen(5001)\""
```

### 3. Evaluate and iterate

```bash
replsh eval --name dev '(defn greet [name] (str "Hello, " name))'
replsh eval --name dev '(greet "world")'
replsh eval --name ml 'import pandas as pd; df = pd.read_csv("data.csv"); print(df.shape)'

# From a file
replsh eval --name dev --file src/my/script.clj

# From stdin (pipe)
echo '(+ 1 2)' | replsh eval --name dev
cat script.clj | replsh eval --name dev
```

### 4. Bootstrap with --init

Run setup code when creating a session:

```bash
replsh launch nrepl --name dev --port 1667 \
  --cmd "bb --nrepl-server 1667" \
  --init "(require '[my.app :as app])"
```

### 5. Clean up

```bash
replsh stop dev       # stops server (if launched) and removes session
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

### Built-in toolchains

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
