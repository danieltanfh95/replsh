# replsh Architecture

## Layers

```
┌─────────────────────────────────────────────────────┐
│  main.clj          Entry point, exit codes          │
├─────────────────────────────────────────────────────┤
│  cli.clj           Arg parsing, config resolution,  │
│                    mode detection, handler routing  │
├─────────────────────────────────────────────────────┤
│  command.clj       Orchestration: launch, eval,     │
│                    stop, restart, status, interrupt │
├──────────────┬──────────────┬───────────────────────┤
│  backend/*   │  runtime.clj │  state.clj            │
│  Protocol    │  Process &   │  Session              │
│  adapters    │  container   │  persistence          │
│              │  lifecycle   │                       │
├──────────────┤              ├───────────────────────┤
│  transport/* │  process.clj │  output.clj           │
│  Wire-level  │  Local PID   │  JSON envelope        │
│  I/O         │  management  │  formatting           │
├──────────────┴──────────────┴───────────────────────┤
│  config.clj        Toolchain resolution             │
│  bridge.clj        Python bridge deployment         │
│  util.clj          IDs, ports, parsing              │
└─────────────────────────────────────────────────────┘
```

## Data Flow: eval

The most common operation. Shows how data flows through all layers.

```
CLI: replsh eval --name dev '(+ 1 2)'

 args ["eval" "--name" "dev" "(+ 1 2)"]
  │
  ▼
 main.clj: cli/dispatch(args) ───────────────────────────────────────┐
  │                                                                  │
  ▼                                                                  │
 cli.clj: babashka.cli/dispatch ──match──► eval-handler              │
  │  parse opts: {:name "dev", :timeout 30000, :stream false}        │
  │  resolve code from args / --file / stdin                         │
  │                                                                  │
  ▼                                                                  │
 command.clj: eval-cmd                                               │
  │                                                                  │
  │  ┌─ state/load-state ──► read ~/.replsh/state.edn                │
  │  │  state/get-session ──► session-config map                     │
  │  │                                                               │
  │  ├─ backend/open! ──► live-state (ephemeral handles)             │
  │  │    dispatches on (:backend session-config)                    │
  │  │    :nrepl   → TCP socket + bencode + clone session            │
  │  │    :python  → TCP socket or exec proxy + ping/pong            │
  │  │    :jupyter → REST create kernel + WebSocket connect          │
  │  │    :node    → TCP socket + drain prompt                       │
  │  │                                                               │
  │  ├─ backend/eval! ──► chunk vector                               │
  │  │    sends code over transport, reads responses                 │
  │  │    each chunk: {:type :value :content "3" :done? true ...}    │
  │  │    on-chunk callback fires per chunk if --stream              │
  │  │                                                               │
  │  ├─ backend/close! ──► nil                                       │
  │  │    close socket / destroy proxy process                       │
  │  │                                                               │
  │  └─ state/put-session! ──► write back :internal state            │
  │                                                                  │
  ▼                                                                  │
 output/success or output/failure                                    │
  {:ok true :command "eval" :data {:value "3" :chunks [...]}}        │
  │                                                                  │
  ▼                                                              ◄───┘
 main.clj: output/emit! ──► JSON to stdout ──► System/exit 0
```

## Data Flow: launch (port mode)

```
CLI: replsh launch nrepl --name dev --cmd "bb --nrepl-server {port}"

 cli.clj: launch-handler
  │  resolve-config ──► merge toolchain defaults + session spec + CLI
  │  detect mode: --cmd present → port mode
  │  allocate port (util/find-free-port)
  │
  ▼
 command.clj: launch-cmd
  │
  │  ┌─ Template substitution: {port}, {host}, {cwd}, {bridge}
  │  │
  │  ├─ runtime/spawn!
  │  │    :local  → process/spawn! → /bin/sh -c "exec bb --nrepl-server 1667"
  │  │              logs to ~/.replsh/logs/dev.log
  │  │              returns {:runtime :local :pid 12345 :process Process}
  │  │    :docker → docker run -d --entrypoint sh --name replsh-dev -p 1667 ...
  │  │              returns {:runtime :docker :container-id "abc..." ...}
  │  │
  │  ├─ runtime/mapped-port ──► resolve host-side port
  │  │    :local  → same port
  │  │    :docker → docker port <cid> <port> → parse host port
  │  │
  │  ├─ runtime/wait-ready! ──► poll TCP/HTTP until responsive
  │  │    :local  → process/wait-for-port! (checks Process.isAlive)
  │  │    :docker → TCP poll loop (checks docker inspect)
  │  │
  │  ├─ backend/open! ──► verify connectivity (ping/pong or session clone)
  │  ├─ run-init! ──► execute --init code if present
  │  ├─ backend/close!
  │  └─ state/put-session! ──► persist session-config with :launch info
  │
  ▼
 output/success {:name "dev" :backend "nrepl" :runtime "local" :pid 12345}
```

## Data Flow: launch (exec mode)

```
CLI: replsh launch python --name api --ssh-host my-machine --container my-app

 cli.clj: launch-handler
  │  exec-mode? → true (--ssh-host or --container present, no --cmd)
  │
  ▼
 command.clj: launch-exec-cmd!
  │
  │  ┌─ Verify container running: runtime/alive? {:runtime :exec :ssh-host ... :container-id ...}
  │  │    runs: ssh <host> docker inspect --format '{{.State.Running}}' <container>
  │  │    or spawn new container: runtime/spawn! with :image
  │  │
  │  ├─ runtime/deploy-bridge!  {:runtime :exec ...}
  │  │    bridge/ensure-bridge! → ~/.replsh/bridge/replsh_bridge.py
  │  │    pipes bridge via stdin: ssh <host> "docker exec -i <container> sh -c 'cat > /tmp/...'"
  │  │    (SSH-only: ssh <host> "mkdir -p /tmp/... && cat > /tmp/..."
  │  │     Docker-only: docker exec -i <container> sh -c 'cat > /tmp/...')
  │  │
  │  ├─ runtime/exec! ──► start persistent bridge server
  │  │    exec-args dispatches: ssh <host> "docker exec -i <container> sh -c python3 ..."
  │  │    returns {:in BufferedReader :out PrintWriter :process Process}
  │  │    host-side Process PID stored as :bridge-pid
  │  │
  │  ├─ Verify via temporary proxy:
  │  │    runtime/exec! → python3 bridge.py --connect 127.0.0.1:9876
  │  │    send ping → receive pong → destroy proxy
  │  │
  │  ├─ backend/open! ──► full eval connectivity check
  │  ├─ run-init! ──► execute --init code if present
  │  ├─ backend/close!
  │  └─ state/put-session! ──► persist with :runtime :exec :transport {:type :exec ...}
  │
  ▼
 output/success {:name "api" :runtime "exec" :container-id "my-app" :ssh-host "my-machine" :owned false}
```

## Data Shapes

### session-config (persisted in state.edn)

```clojure
{:name         "dev"
 :backend      :nrepl              ; dispatch key for backend/*
 :runtime      :local              ; dispatch key for runtime/*  (:local | :docker | :exec)
 :created-at   "2026-04-09T..."
 :transport    {:type :tcp         ; :tcp | :http | :exec
                :host "localhost"
                :port 1667}
 :env          {:cwd "/project" :vars {"KEY" "VAL"}}
 :launch       {:cmd "bb --nrepl-server 1667"    ; port/exec mode
                :cwd "/project"
                :pid 12345                       ; local only
                :container-id "abc..."           ; docker / exec runtime
                :ssh-host "my-machine"           ; exec runtime only (optional)
                :bridge-pid 54321                ; exec runtime only
                :bridge-path "/tmp/replsh/..."   ; exec runtime only
                :owned true}                     ; exec runtime only
 :backend-opts {:kernel-name "python3"}          ; jupyter only
 :internal     {:session-id "abc"}               ; backend-specific state
 :init-code    "(require '[my.app])"}            ; optional
```

### live-state (ephemeral, in-process only)

```clojure
{:config   <session-config>      ; the full persisted config
 :backend  :nrepl                ; redundant, for dispatch
 :status   :connected
 :handles  <transport-specific>  ; socket/ws/proxy process handles
 :internal {:session-id "abc"}}  ; updated by open!, written back to state
```

### chunk (eval output unit)

```clojure
{:type    :value       ; :out | :err | :value | :error | :status
 :content "3"          ; the payload
 :stream  nil          ; :stdout | :stderr (only for :out/:err)
 :meta    {:ns "user"} ; backend-specific metadata
 :done?   true         ; true on final chunk
 :msg-id  "eval-abc"   ; correlation ID
 :name    "dev"}       ; session name
```

### output envelope (emitted to stdout as JSON)

```clojure
;; Success
{:ok true :command "eval" :data {:name "dev" :value "3"}}

;; Failure
{:ok false :command "eval"
 :error {:code "eval_error" :message "ArithmeticException: Divide by zero"}
 :data {:chunks [...]}}

;; Streaming final line (same shape as sync output)
{:ok true :command "eval" :data {...}}
```

## Multimethod Dispatch

Two orthogonal dispatch axes — backend (protocol) and runtime (infrastructure):

```
backend/*  dispatches on (:backend ...)     →  :nrepl | :python | :jupyter | :node
runtime/*  dispatches on (:runtime ...)     →  :local | :docker
```

### backend/* (src/replsh/backend.clj)

| Method       | Dispatches on               | Purpose                                                          |
| ------------ | --------------------------- | ---------------------------------------------------------------- |
| `open!`      | `(:backend session-config)` | Open transport, return live-state                                |
| `close!`     | `(:backend live-state)`     | Close transport handles                                          |
| `destroy!`   | `(:backend session-config)` | Server-side cleanup (nREPL session close, Jupyter kernel delete) |
| `eval!`      | `(:backend request)`        | Send code, collect chunk vector                                  |
| `interrupt!` | `(:backend live-state)`     | Cancel running eval                                              |

### runtime/* (src/replsh/runtime.clj)

| Method           | Dispatches on | Purpose                                          |
| ---------------- | ------------- | ------------------------------------------------ |
| `spawn!`         | `:runtime`    | Start a process/container                        |
| `stop!`          | `:runtime`    | Kill process / stop+rm container                 |
| `alive?`         | `:runtime`    | Check liveness                                   |
| `logs`           | `:runtime`    | Read process/container logs                      |
| `send-signal!`   | `:runtime`    | Send Unix signal                                 |
| `mapped-port`    | `:runtime`    | Resolve host-side port                           |
| `wait-ready!`    | `:runtime`    | Poll until TCP/HTTP ready                        |
| `deploy-files!`  | `:runtime`    | Copy files into runtime                          |
| `exec!`          | `:runtime`    | Run command inside runtime, return stdio handles |
| `deploy-bridge!` | `:runtime`    | Deploy Python bridge into runtime                |

## Transport Modes

The Python backend supports two transport modes, selected by `:transport :type`:

```
Port mode (:tcp)                        Exec mode (:exec)
─────────────────                       ──────────────────
Host opens TCP socket to bridge         Host opens ephemeral proxy (ssh / docker exec -i / both)
  ┌──────┐    TCP    ┌────────┐          ┌──────┐  stdin/out  ┌────────────┐  TCP  ┌────────┐
  │Client├───────────┤ Bridge │          │Client├─────────────┤ ssh+docker ├───────┤ Bridge │
  └──────┘           └────────┘          └──────┘             └────────────┘       └────────┘
                                          host side           proxy (ephemeral)    container
Bridge IS the container entrypoint       Bridge runs alongside the service
Port exposed to host via -p              No port exposure; proxy is ephemeral

Exec command composition (exec-args):
  container only  → docker exec -i <container> sh -c <cmd>
  ssh only        → ssh <host> sh -c <cmd>
  ssh + container → ssh <host> docker exec -i <container> sh -c <cmd>
```

## File Layout

```
src/replsh/
├── main.clj             Entry point, exit code mapping
├── cli.clj              Arg parsing, dispatch table, config resolution
├── command.clj          Command orchestration (launch, eval, stop, ...)
├── config.clj           Toolchain presets, 4-layer merge resolution
├── state.clj            Flat-file session persistence (~/.replsh/state.edn)
├── output.clj           JSON envelope formatting, NDJSON streaming
├── runtime.clj          Runtime multimethods (:local, :docker, :exec)
├── process.clj          Local PID management (spawn, kill, wait)
├── bridge.clj           Python bridge deployment to ~/.replsh/bridge/
├── util.clj             IDs, port allocation, address parsing
├── watch.clj            Stale-file detection (idle-gated module introspection)
├── backend.clj          Backend multimethod definitions
└── backend/
    ├── nrepl.clj        nREPL protocol (bencode over TCP)
    ├── python.clj       Python bridge protocol (NDJSON over TCP or exec pipe)
    ├── jupyter.clj      Jupyter protocol (REST + WebSocket)
    ├── node.clj         Node.js protocol (raw TCP text)
    └── bash.clj         Bash protocol (delegates to python.clj runtime, bash subprocess)

resources/
└── replsh/
    ├── HELP.md           CLI help + skill body (classpath-namespaced to avoid collisions)
    └── replsh_bridge.py  Python bridge script (deployed into containers)

test/replsh/
├── test_runner.clj      Test harness
├── util_test.clj
├── output_test.clj
├── config_test.clj
├── runtime_test.clj
├── process_test.clj
└── integration_test.clj
