# Backend Capabilities

Comparison of what each replsh backend supports.

| Backend | Transport | Eval | Stdout/Stderr | Errors | State | Interrupt |
|---------|-----------|------|---------------|--------|-------|-----------|
| **nREPL** | TCP + bencode | structured | separate chunks | structured with stacktrace | persistent across invocations | supported |
| **Python** | TCP + NDJSON | expression/statement detection | separate streaming chunks | structured with traceback | persistent namespace | supported (SIGINT) |
| **Jupyter** | REST + WebSocket | kernel protocol | separate stream msgs | structured with traceback | persistent kernel | supported |
| **Node.js** | TCP raw text | prompt detection | mixed (text heuristic) | text only | per-connection | supported (SIGINT) |

## Notes

- **nREPL**: Sessions are server-side. `def` persists in the namespace across replsh invocations. Multiplexed over a single TCP socket per connection.
- **Python**: Uses a stdlib-only bridge script (`replsh_bridge.py`) — zero dependencies, auto-deployed. Runs inside the project's Python interpreter (venv, Poetry, conda), so all project packages are available. State persists in a single namespace dict across all evals and connections. Single expressions (e.g., `1 + 1`, `len(xs)`) return values; multi-statement code (e.g., `import x; expr`) compiles as `exec` and does not return a value — use `print()` or split into separate evals.
- **Node.js**: Each replsh invocation opens a new TCP connection (= new REPL context). No structured output — stdout and return values are separated by heuristic (last line = value). Errors arrive as text, not structured.
- **Jupyter**: Kernels are created via REST and persist server-side. Communication over WebSocket using the Jupyter messaging protocol. Avoids ZeroMQ by going through the Jupyter Server's HTTP+WS bridge. Environment vars are passed through to kernel creation. Supports rich output (images, HTML, widgets) that the Python backend does not.

## Python backend: expression vs statement

The Python backend tries to compile code as an expression (`eval` mode) first. If that fails with `SyntaxError`, it falls back to statement (`exec` mode). This means:

```bash
# Single expression — returns value
replsh eval --name py '1 + 1'           # → "2"
replsh eval --name py 'len(my_list)'    # → "42"

# Multi-statement — no return value (use print instead)
replsh eval --name py 'import os; os.getcwd()'     # → (no value)
replsh eval --name py 'import os'                    # then:
replsh eval --name py 'os.getcwd()'                  # → "'/project'"

# For complex multiline code, use --file or stdin
replsh eval --name py --file script.py
replsh eval --name py --file /dev/stdin <<'EOF'
import os
result = os.getcwd()
print(result)
EOF
```

## Toolchains

Toolchains define **how to start** a backend server. They are independent from the backend protocol — multiple toolchains can target the same backend.

| Toolchain | Backend | Command | Default Port | Use Case |
|-----------|---------|---------|-------------|----------|
| `clojure.deps` | nREPL | `clj -M:nrepl -m nrepl.cmdline --port {port}` | 7888 | deps.edn projects with nREPL middleware |
| `clojure.lein` | nREPL | `lein repl :headless :port {port}` | 7888 | Leiningen projects |
| `clojure.bb` | nREPL | `bb --nrepl-server {port}` | 1667 | Babashka scripts and projects |
| `python` | Python | `python3 {bridge} --port {port}` | 9876 | System Python (no deps needed) |
| `python.poetry` | Python | `poetry run python {bridge} --port {port}` | 9876 | Poetry-managed Python projects |
| `python.venv` | Python | `{cwd}/.venv/bin/python {bridge} --port {port}` | 9876 | venv-based Python projects |
| `python.poetry.jupyter` | Jupyter | `poetry run jupyter server --port {port}` | 8888 | Poetry + rich output (images, HTML) |
| `python.venv.jupyter` | Jupyter | `{cwd}/.venv/bin/jupyter server --port {port}` | 8888 | venv + rich output (images, HTML) |
| `node` | Node.js | `node -e "require('net')..."` | 5001 | Node.js projects |

### Custom toolchains

Define custom toolchains in `~/.replsh/config.edn`:

```clojure
{:toolchains
 {"python.conda"   {:backend  :python
                    :cmd      "conda run python {bridge} --port {port}"
                    :defaults {:port 9876}}
  "clojure.shadow" {:backend  :nrepl
                    :cmd      "npx shadow-cljs server --nrepl-port {port}"
                    :defaults {:port 9000}}}}
```

### How toolchains relate to backends

```
Toolchain (how to start)         Backend (how to talk)
─────────────────────────────    ─────────────────────
clojure.deps  ─┐
clojure.lein  ─┼────────────►   nREPL (bencode/TCP)
clojure.bb    ─┘

python         ─┐
python.poetry  ─┼───────────►   Python (NDJSON/TCP)
python.venv    ─┘

python.poetry.jupyter ─┐
python.venv.jupyter   ─┼────►   Jupyter (REST+WS)
python.conda          ─┘

node          ──────────────►   Node.js (raw TCP)
```

A toolchain is just a command template + defaults. The backend protocol is determined by the toolchain's `:backend` field. Users can create toolchains for any server that speaks one of the four protocols.
