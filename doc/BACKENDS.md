# Backend Capabilities

Comparison of what each replsh backend supports.

| Backend | Transport | Eval | Stdout/Stderr | Errors | State | Interrupt |
|---------|-----------|------|---------------|--------|-------|-----------|
| **nREPL** | TCP + bencode | structured | separate chunks | structured with stacktrace | persistent across invocations | supported |
| **Node.js** | TCP raw text | prompt detection | mixed (text heuristic) | text only | per-connection | unsupported (honest) |
| **Jupyter** | REST + WebSocket | kernel protocol | separate stream msgs | structured with traceback | persistent kernel | supported |

## Notes

- **nREPL**: Sessions are server-side. `def` persists in the namespace across replsh invocations. Multiplexed over a single TCP socket per connection.
- **Node.js**: Each replsh invocation opens a new TCP connection (= new REPL context). No structured output — stdout and return values are separated by heuristic (last line = value). Errors arrive as text, not structured.
- **Jupyter**: Kernels are created via REST and persist server-side. Communication over WebSocket using the Jupyter messaging protocol. Avoids ZeroMQ by going through the Jupyter Server's HTTP+WS bridge. Environment vars are passed through to kernel creation.

## Toolchains

Toolchains define **how to start** a backend server. They are independent from the backend protocol — multiple toolchains can target the same backend.

| Toolchain | Backend | Command | Default Port | Use Case |
|-----------|---------|---------|-------------|----------|
| `clojure.deps` | nREPL | `clj -M:nrepl -m nrepl.cmdline --port {port}` | 7888 | deps.edn projects with nREPL middleware |
| `clojure.lein` | nREPL | `lein repl :headless :port {port}` | 7888 | Leiningen projects |
| `clojure.bb` | nREPL | `bb --nrepl-server {port}` | 1667 | Babashka scripts and projects |
| `python.poetry` | Jupyter | `poetry run jupyter server --port {port}` | 8888 | Poetry-managed Python projects |
| `python.venv` | Jupyter | `{cwd}/.venv/bin/jupyter server --port {port}` | 8888 | venv-based Python projects |
| `node` | Node.js | `node -e "require('net')..."` | 5001 | Node.js projects |

### Custom toolchains

Define custom toolchains in `~/.replsh/config.edn`:

```clojure
{:toolchains
 {"python.conda"   {:backend  :jupyter
                    :cmd      "conda run jupyter server --port {port}"
                    :defaults {:port 8888 :kernel "python3"}}
  "clojure.shadow" {:backend  :nrepl
                    :cmd      "npx shadow-cljs server --nrepl-port {port}"
                    :defaults {:port 9000}}}}
```

### How toolchains relate to backends

```
Toolchain (how to start)     Backend (how to talk)
─────────────────────────    ─────────────────────
clojure.deps  ─┐
clojure.lein  ─┼──────────► nREPL (bencode/TCP)
clojure.bb    ─┘

python.poetry ─┐
python.venv   ─┼──────────► Jupyter (REST+WS)
python.conda  ─┘

node          ────────────► Node.js (raw TCP)
```

A toolchain is just a command template + defaults. The backend protocol is determined by the toolchain's `:backend` field. Users can create toolchains for any server that speaks one of the three protocols.
