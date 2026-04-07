# replsh — Unified CLI Client for REPL Servers

## Context

LLMs need to run and test code in interactive REPL environments — evaluating expressions, checking outputs, iterating. But each REPL server (nREPL, Jupyter, Node.js) uses a different wire protocol, session model, and transport. replsh provides a single CLI with structured JSON output that LLMs can reliably parse. Sessions must be environment-aware (classpath, venv, node_modules) since LLMs need to test code against real project dependencies. Supporting three radically different backends (bencode, REST+WS, raw text) from the start ensures the abstraction is honest.

Built in Babashka. All three backends work in pure Babashka — no JVM fallback.

**Key design principle**: To the user (LLM), there is only one concept: a **named session**. Connections, sockets, kernels, and wire protocols are internal details. `replsh eval --name backend '(+ 1 2)'` — that's it.

---

## 1. Data Structure Shapes

### Session Config (persisted to `~/.replsh/state.edn`)

The **session** is the only user-facing entity. Four concerns, cleanly separated:
- **Identity + dispatch**: `:backend`, `:name`, `:created-at`
- **Transport**: standardized `:transport` map — "where to connect" (internal detail)
- **Environment**: universal `:env` map — "what context does this session have"
- **Backend-specific**: `:backend-opts` — anything only one backend needs

`:name` is the **primary key** — user-provided, used in all commands.

```clojure
;; nREPL session
{:name        "backend"
 :backend     :nrepl
 :created-at  "2026-04-07T10:00:00Z"
 :transport   {:type :tcp :host "localhost" :port 1667}
 :env         {:cwd "/repo/backend"
               :vars {"JAVA_HOME" "/usr/lib/jvm/java-21"}}
 :backend-opts {}
 :internal    {:session-id "nrepl-sess-uuid"}}   ;; backend's own session ID

;; Jupyter session
{:name        "ml"
 :backend     :jupyter
 :created-at  "2026-04-07T10:00:00Z"
 :transport   {:type :http :url "http://localhost:8888" :token "abc123"}
 :env         {:cwd "/repo/ml"
               :vars {"VIRTUAL_ENV" "/repo/ml/.venv"}}
 :backend-opts {:kernel-name "python3"}
 :internal    {:kernel-id "k-xyz"}}

;; Node session
{:name        "frontend"
 :backend     :node
 :created-at  "2026-04-07T10:00:00Z"
 :transport   {:type :tcp :host "localhost" :port 5001}
 :env         {:cwd "/repo/frontend" :vars {}}
 :backend-opts {:prompt-re "^> "}
 :internal    {}}

;; Node via Unix socket
{:name        "admin-repl"
 :backend     :node
 :created-at  "2026-04-07T10:00:00Z"
 :transport   {:type :unix :path "/tmp/node.sock"}
 :env         {:cwd "/repo/frontend" :vars {}}
 :backend-opts {:prompt-re "^> "}
 :internal    {}}
```

### Transport types (exhaustive)

```clojure
{:type :tcp  :host "localhost" :port 1667}    ;; nREPL, Node
{:type :unix :path "/tmp/node.sock"}          ;; Node (alternative)
{:type :http :url "http://localhost:8888"      ;; Jupyter (REST + WS upgrade)
             :token "abc123"}
```

### Live Session State (in-memory only — never persisted)

Opened from a Session Config when a command runs. Backend manages connection internals.

```clojure
{:config     {... session config ...}
 :backend    :nrepl                       ;; hoisted for dispatch
 :status     :connected | :error
 :handles    {;; backend-specific I/O handles
              ;; nrepl:   {:socket Socket :in PushbackInputStream :out OutputStream}
              ;; jupyter: {:http-client HttpClient :ws WebSocket}
              ;; node:    {:socket Socket :in BufferedReader :out PrintWriter}
              }
 :pending    {}    ;; {msg-id -> {:promise p :chunks-atom a}}
 :reader-thread nil}
```

Internally, backends may **share connections** across sessions (e.g., two nREPL sessions on the same server reuse one TCP socket). This is an optimization the backend manages transparently — a connection pool keyed by transport config. The user never sees it.

### Eval Request (internal, built by orchestration layer)

```clojure
{:code        "(+ 1 2)"
 :name        "backend"          ;; session name
 :backend     :nrepl
 :timeout-ms  30000
 :msg-id      "eval-7f3a"}
```

### Response Chunk (streaming output unit)

```clojure
{:type       :out | :err | :value | :error | :status
 :content    "6"
 :stream     :stdout | :stderr | nil    ;; only for :out/:err
 :meta       {:ns "user"}               ;; backend-specific
 :done?      false
 :msg-id     "eval-7f3a"
 :name       "backend"}
```

### Error Shape (consistent across all operations)

```clojure
{:error    true
 :code     :connection-refused | :timeout | :session-not-found
           | :eval-error | :unsupported | :parse-error | :unknown
 :message  "Connection refused to localhost:1667"
 :backend  :nrepl
 :detail   {}}
```

### Command (parsed CLI input)

```clojure
{:command   [:start :nrepl]
 :args      {:address "localhost:1667" :name "backend" :cwd "/repo/backend"}
 :opts      {:timeout 30000}
 :raw-args  ["start" "nrepl" "localhost:1667" "--name" "backend" "--cwd" "/repo/backend"]}
```

---

## 2. Logic Layers & Data Flow

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 1: CLI Parsing  (replsh.cli)                         │
│  IN:  raw *command-line-args*                               │
│  OUT: Command map                                           │
│  Side effects: none                                         │
├─────────────────────────────────────────────────────────────┤
│  Layer 2: Orchestration  (replsh.command)                   │
│  IN:  Command map                                           │
│  OUT: Result map (success or error envelope)                │
│  Side effects: reads/writes ~/.replsh/state.edn,            │
│                calls backend layer, manages reconnection    │
├─────────────────────────────────────────────────────────────┤
│  Layer 3: Backend Dispatch  (replsh.backend + backend/*)    │
│  IN:  session config + request maps                         │
│  OUT: response chunks, live session state, errors           │
│  Side effects: delegates to transport layer                 │
├─────────────────────────────────────────────────────────────┤
│  Layer 4: Transport  (replsh.transport.*)                   │
│  IN:  backend-specific wire data                            │
│  OUT: backend-specific raw responses                        │
│  Side effects: all actual I/O (sockets, HTTP, WebSocket)    │
└─────────────────────────────────────────────────────────────┘
```

### Layer 1 → 2: CLI Parsing

`babashka.cli/dispatch` with a command table. Pure parsing, no I/O.

```clojure
[{:cmds ["start" "nrepl"]    :fn cmd/start    :args->opts [:address]}
 {:cmds ["start" "jupyter"]  :fn cmd/start    :args->opts [:url]}
 {:cmds ["start" "node"]     :fn cmd/start    :args->opts [:address]}
 {:cmds ["eval"]             :fn cmd/eval     :args->opts [:code]}
 {:cmds ["ls"]               :fn cmd/ls}
 {:cmds ["stop"]             :fn cmd/stop     :args->opts [:name]}
 {:cmds ["restart"]          :fn cmd/restart  :args->opts [:name]}
 {:cmds ["interrupt"]        :fn cmd/interrupt}
 {:cmds ["status"]           :fn cmd/status}]
```

All commands that target a session accept `--name <session-name>`. When omitted, uses the active session.

### Layer 2: Orchestration

Coordinates state, builds requests, wraps results in output envelopes.

Key responsibility: the CLI layer gives us raw args like `{:address "localhost:1667" :name "backend"}`. The orchestration layer assembles these into the structured Session Config before passing to backends.

```clojure
(defn eval-cmd [{:keys [args opts]}]
  (let [state        (state/load)
        session-name (or (:name args) (:active state))
        session-cfg  (get-in state [:sessions session-name])
        ;; open connection from persisted config (stateless CLI model)
        live-state   (backend/open! session-cfg)
        request      {:code       (:code args)
                      :name       session-name
                      :backend    (:backend session-cfg)
                      :timeout-ms (or (:timeout opts) 30000)
                      :msg-id     (util/gen-id "eval")}
        result       (backend/eval! request live-state)]
    (backend/close! live-state)
    result))
```

### Layer 3 → 4: Backend translates between generic maps and wire format

nREPL example:
```
Eval Request map                     bencode wire bytes
{:code "(+ 1 2)"        ───────►    {"op" "eval" "code" "(+ 1 2)"
 :name "backend"                     "session" "nrepl-sess-uuid"
 :msg-id "eval-9f2b"}                "id" "eval-9f2b"}

bencode response bytes               Response Chunk map
{"id" "eval-9f2b"        ◄───────   {:type :value :content "3"
 "value" "3" "ns" "user"              :meta {:ns "user"}
 "status" ["done"]}                    :done? true}
```

Note: the backend translates `:name` → the internal nREPL session ID from `:internal` in the Session Config.

---

## 3. Data Flow Traces

### `replsh eval --name backend '(+ 1 2)'`

```
["eval" "--name" "backend" "(+ 1 2)"]
        │
        ▼  [Layer 1: parse]
{:command [:eval] :args {:name "backend" :code "(+ 1 2)"}}
        │
        ▼  [Layer 2: orchestrate]
        ├─ state/load → get session config for "backend"
        ├─ backend/open! (open socket from persisted transport config)
        ├─ build eval request map
        │
        ▼  [Layer 3: defmethod eval! :nrepl]
        ├─ get nREPL session-id from config :internal
        ├─ translate to bencode msg, send via transport
        ├─ wait for response chunks matched by msg-id
        │
        ▼  [Layer 4: transport]
        ├─ tcp/send-bencode, tcp/read-bencode
        │
        ▼  [Layer 3: normalize response]
[{:type :value :content "3" :meta {:ns "user"} :done? true}]
        │
        ▼  [Layer 2: wrap in envelope]
{"ok": true, "command": "eval",
 "data": {"name": "backend",
          "chunks": [{"type": "value", "content": "3", "meta": {"ns": "user"}}],
          "value": "3", "ns": "user"}}
```

### `replsh start jupyter http://localhost:8888 --name ml --kernel python3 --cwd /repo/ml --env VIRTUAL_ENV=...`

```
["start" "jupyter" "http://localhost:8888" "--name" "ml" "--kernel" "python3"
 "--cwd" "/repo/ml" "--env" "VIRTUAL_ENV=..."]
        │
        ▼  [Layer 1: parse]
{:command [:start :jupyter]
 :args {:url "http://localhost:8888" :name "ml" :kernel "python3"
        :cwd "/repo/ml" :env {"VIRTUAL_ENV" "..."}}}
        │
        ▼  [Layer 2: orchestrate]
        ├─ build session config:
        │  {:name "ml" :backend :jupyter
        │   :transport {:type :http :url "http://..." :token nil}
        │   :env {:cwd "/repo/ml" :vars {"VIRTUAL_ENV" "..."}}
        │   :backend-opts {:kernel-name "python3"}}
        ├─ call backend/open! with config
        │
        ▼  [Layer 3: defmethod open! :jupyter]
        ├─ POST /api/kernels {"name": "python3", "env": {"VIRTUAL_ENV": "..."}}
        ├─ open WebSocket to /api/kernels/{kernel-id}/channels
        ├─ return live-state + update config :internal {:kernel-id "k-abc"}
        │
        ▼  [Layer 2: persist session config to state.edn, close, wrap]
{"ok": true, "command": "start",
 "data": {"name": "ml", "backend": "jupyter",
          "kernel_name": "python3",
          "env": {"cwd": "/repo/ml",
                  "vars": {"VIRTUAL_ENV": "..."}}}}
```

---

## 4. Multimethod Design

All dispatch on `:backend` keyword. Declarations in `replsh.backend`, implementations in `replsh.backend.*`:

```clojure
(ns replsh.backend)

;; Session lifecycle
(defmulti open!      (fn [session-config]    (:backend session-config)))  ;; → live-state
(defmulti close!     (fn [live-state]        (:backend live-state)))      ;; → nil
(defmulti destroy!   (fn [session-config]    (:backend session-config)))  ;; → nil (cleanup server-side resources)

;; Evaluation
(defmulti eval!      (fn [request live-state] (:backend request)))        ;; → [response-chunks]
(defmulti interrupt! (fn [live-state]         (:backend live-state)))     ;; → :ok | error
```

**5 multimethods, not 7.** The flattened session model removed `session-create`, `session-list`, `session-close` — those are now `start`, `ls`, `stop` at the orchestration layer.

- **`open!`**: Open transport handles for an existing session config. Called on each CLI invocation (stateless model).
- **`close!`**: Close transport handles. Called after each CLI invocation.
- **`destroy!`**: Clean up server-side resources (Jupyter: DELETE kernel, nREPL: send "close" op, Node: no-op). Called by `replsh stop`.
- **`eval!`**: Send code, collect response chunks.
- **`interrupt!`**: Cancel a running eval.

Each backend file requires `replsh.backend` and provides `defmethod` for all five.

---

## 5. Environment-Aware Sessions

| Backend | cwd/env handling |
|---------|-----------------|
| **nREPL** | Metadata only — server already running with baked-in classpath. Stored so LLM can query via `replsh status`. |
| **Jupyter** | Active — `POST /api/kernels` accepts `env` map. Kernel spawned with those env vars. `cwd` set via kernel `cwd` field or bootstrap `os.chdir()` eval. |
| **Node** | Metadata only — server already running. `require()` resolves from wherever the server was started. |

LLM workflow — polyglot project:
```bash
# 1. LLM starts servers from the right dirs
cd /repo/backend && bb --nrepl-server 1667
cd /repo/frontend && node -e "require('net').createServer(s=>require('repl').start({input:s,output:s})).listen(5001)"
jupyter server &

# 2. LLM creates named sessions
replsh start nrepl localhost:1667 --name backend --cwd /repo/backend
replsh start node localhost:5001 --name frontend --cwd /repo/frontend
replsh start jupyter http://localhost:8888 --name ml --kernel python3 --cwd /repo/ml --env VIRTUAL_ENV=/repo/ml/.venv

# 3. LLM evals against any session by name
replsh eval --name backend '(my-api/handler {:method :get :path "/users"})'
replsh eval --name frontend 'require("./src/api").fetchUsers()'
replsh eval --name ml 'model.predict(test_data)'

# 4. LLM lists all sessions
replsh ls
# → [{"name": "backend", "backend": "nrepl", "env": {"cwd": "/repo/backend"}},
#    {"name": "frontend", "backend": "node", "env": {"cwd": "/repo/frontend"}},
#    {"name": "ml", "backend": "jupyter", "env": {"cwd": "/repo/ml"}}]

# 5. Lifecycle
replsh stop frontend
replsh restart ml
```

---

## 6. Output Contract for LLMs

Every command emits exactly one JSON object on stdout. No decoration, no extra text.

### Envelope

```json
{"ok": true, "command": "eval", "data": { ... }}
{"ok": false, "command": "eval", "error": {"code": "timeout", "message": "...", "detail": {}}}
```

### Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Eval error (code threw) |
| 2 | Client error (bad args, connection refused, session not found) |
| 3 | Timeout |

### Command-specific `data` shapes

**start:**
```json
{"ok": true, "command": "start",
 "data": {"name": "backend", "backend": "nrepl",
          "env": {"cwd": "/repo/backend", "vars": {}}}}
```

**eval:**
```json
{"ok": true, "command": "eval",
 "data": {"name": "backend",
          "chunks": [
            {"type": "out", "content": "hello\n", "stream": "stdout"},
            {"type": "value", "content": "42", "meta": {"ns": "user"}}
          ],
          "value": "42",
          "ns": "user"}}
```

`value` and `ns` are hoisted from chunks for quick LLM access. Eval errors include both `error` and `data.chunks` so the LLM sees partial output before the failure.

**ls:**
```json
{"ok": true, "command": "ls",
 "data": {"sessions": [
   {"name": "backend", "backend": "nrepl", "env": {"cwd": "/repo/backend"}},
   {"name": "ml", "backend": "jupyter", "env": {"cwd": "/repo/ml"}},
   {"name": "frontend", "backend": "node", "env": {"cwd": "/repo/frontend"}}
 ], "active": "backend"}}
```

**status:**
```json
{"ok": true, "command": "status",
 "data": {"name": "backend", "backend": "nrepl",
          "transport": {"type": "tcp", "host": "localhost", "port": 1667},
          "env": {"cwd": "/repo/backend", "vars": {}},
          "reachable": true}}
```

**stop / restart / interrupt:**
```json
{"ok": true, "command": "stop", "data": {"name": "backend"}}
```

---

## 7. Stateless CLI Model

Each invocation: load state → open transport → operate → close transport → update state. No daemon, no background process. Ideal for LLM consumers.

nREPL sessions persist server-side across replsh invocations — we just reopen a TCP connection and specify the stored session ID from `:internal`.

### State location

**Global**: `~/.replsh/state.edn`. Sessions span projects — an LLM working on a polyglot codebase needs `backend`, `ml`, and `frontend` from anywhere.

Override with `REPLSH_STATE` env var.

### `~/.replsh/state.edn`

Keyed by session **name**:

```clojure
{:active "backend"
 :sessions
 {"backend"
  {:name        "backend"
   :backend     :nrepl
   :created-at  "2026-04-07T10:00:00Z"
   :transport   {:type :tcp :host "localhost" :port 1667}
   :env         {:cwd "/repo/backend" :vars {}}
   :backend-opts {}
   :internal    {:session-id "nrepl-sess-uuid"}}

  "ml"
  {:name        "ml"
   :backend     :jupyter
   :created-at  "2026-04-07T10:00:00Z"
   :transport   {:type :http :url "http://localhost:8888" :token "abc"}
   :env         {:cwd "/repo/ml" :vars {"VIRTUAL_ENV" "/repo/ml/.venv"}}
   :backend-opts {:kernel-name "python3"}
   :internal    {:kernel-id "k-abc"}}

  "frontend"
  {:name        "frontend"
   :backend     :node
   :created-at  "2026-04-07T10:00:00Z"
   :transport   {:type :tcp :host "localhost" :port 5001}
   :env         {:cwd "/repo/frontend" :vars {}}
   :backend-opts {:prompt-re "^> "}
   :internal    {}}}}
```

---

## 8. File Structure

```
replsh/
  bb.edn
  src/replsh/
    main.clj               ;; entry: parse → orchestrate → emit
    cli.clj                 ;; babashka.cli dispatch table → Command map
    command.clj             ;; orchestration: each command as fn
    backend.clj             ;; defmulti declarations (open!, close!, destroy!, eval!, interrupt!)
    backend/
      nrepl.clj             ;; defmethod * :nrepl
      jupyter.clj           ;; defmethod * :jupyter
      node.clj              ;; defmethod * :node
    transport/
      tcp.clj               ;; socket, bencode read/write, text read/write
      http.clj              ;; babashka.http-client wrapper
      ws.clj                ;; babashka.http-client.websocket wrapper
    state.clj               ;; ~/.replsh/state.edn load/save
    output.clj              ;; JSON envelope construction + emit
    util.clj                ;; gen-id, timestamp, parse-address, parse-env-arg
```

---

## 9. Implementation Order

### Phase 1: Skeleton + nREPL
1. `bb.edn`, directory structure, `main.clj` entry point
2. `cli.clj` — dispatch table, arg parsing
3. `output.clj` — JSON envelope construction
4. `state.clj` — load/save `~/.replsh/state.edn`
5. `backend.clj` — all 5 defmulti declarations
6. `transport/tcp.clj` — socket open/close, bencode read/write
7. `backend/nrepl.clj` — all defmethods for :nrepl
   - `open!`: TCP socket + bencode + clone op → get nREPL session-id, store in :internal
   - `eval!`: send eval op, background reader dispatches by msg-id to pending promises
   - `destroy!`: send close op for the nREPL session
8. `command.clj` — start, eval, ls, stop, restart, status, interrupt
9. Test against `bb --nrepl-server 1667`

### Phase 2: Node.js Backend
1. `backend/node.clj` — extend tcp.clj with text read/write
2. Prompt detection (configurable regex via `:backend-opts`, timeout fallback)
3. `open!` = open TCP socket, `destroy!` = close socket (no server-side session)
4. Validate abstraction still holds

### Phase 3: Jupyter Backend
1. `transport/http.clj`, `transport/ws.clj`
2. `backend/jupyter.clj` — REST lifecycle + WebSocket eval
3. `open!`: POST /api/kernels + open WS. Store kernel-id in :internal
4. `eval!`: execute_request on WS, match responses by parent_header.msg_id
5. `destroy!`: close WS + DELETE /api/kernels/{kernel-id}
6. Environment passthrough on kernel creation
7. Token auth via `--token` / `JUPYTER_TOKEN`

### Phase 4: Polish
1. `--timeout` for eval
2. Reconnection robustness (detect stale sessions)
3. Error messages and `--help`

---

## 10. Verification

```bash
# nREPL
bb --nrepl-server 1667 &
replsh start nrepl localhost:1667 --name backend --cwd .
replsh eval --name backend '(+ 1 2)'        # → {"ok":true,"data":{"value":"3"}}
replsh eval --name backend '(println "hi")'  # → chunks with :out and :value
replsh interrupt --name backend              # → :ok
replsh stop backend

# Node
node -e "require('net').createServer(s=>require('repl').start({input:s,output:s})).listen(5001)" &
replsh start node localhost:5001 --name frontend --cwd .
replsh eval --name frontend '1+1'            # → {"ok":true,"data":{"value":"2"}}
replsh stop frontend

# Jupyter
jupyter server &
replsh start jupyter http://localhost:8888 --token TOKEN --name ml --kernel python3 --cwd . --env VIRTUAL_ENV=/proj/.venv
replsh eval --name ml 'print(42)'            # → chunks with :out "42\n"
replsh interrupt --name ml
replsh restart ml
replsh stop ml

# Multi-session management
replsh ls                                     # → all sessions
replsh status --name backend                  # → detailed info + reachability
```
