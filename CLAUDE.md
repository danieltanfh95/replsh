# replsh — Development Guide for Claude Code

This project uses replsh (its own tool) for development. You have a live REPL available — use it.

## Setup

The project has `.replsh/config.edn` with a `dev` session. Launch it:

```bash
bb -m replsh.main launch --name dev
```

Port is auto-allocated. The session persists across eval calls.

## Workflow

**Evaluate code interactively** instead of restarting processes:

```bash
# Inline
bb -m replsh.main eval --name dev '(+ 1 2)'

# From file
bb -m replsh.main eval --name dev --file src/replsh/util.clj

# From stdin (pipe)
echo '(require (quote [replsh.util :as util])) (util/find-free-port)' | bb -m replsh.main eval --name dev
```

**Run tests through the REPL** (faster than cold-starting bb each time):

```bash
# Unit tests only
bb -m replsh.main eval --name dev '(require (quote [replsh.test-runner])) (replsh.test-runner/run-all :unit-only? true)' --timeout 60000

# All tests (unit + integration)
bb -m replsh.main eval --name dev '(require (quote [replsh.test-runner])) (replsh.test-runner/run-all)' --timeout 120000
```

**Or run tests directly** (cold start, simpler):

```bash
bb -m replsh.test-runner          # all tests
bb -m replsh.test-runner --unit   # unit only
```

## Session Management

```bash
bb -m replsh.main ls                    # list sessions
bb -m replsh.main status --name dev     # check reachability
bb -m replsh.main restart dev           # restart server + re-run init
bb -m replsh.main stop dev              # kill server, remove session
```

## Project Structure

```
src/replsh/
  main.clj          Entry point, error handling
  cli.clj           CLI dispatch (babashka.cli)
  command.clj       Orchestration (launch, eval, stop, etc.)
  config.clj        Config loading + toolchain resolution
  process.clj       Process lifecycle (spawn, kill, wait-for-port)
  state.clj         Session persistence (~/.replsh/state.edn)
  output.clj        JSON envelope (success/failure)
  util.clj          Helpers (gen-id, parse-address, find-free-port)
  backend.clj       Multimethod declarations (open!, eval!, close!, etc.)
  backend/
    nrepl.clj       nREPL backend (bencode over TCP)
    jupyter.clj     Jupyter backend (REST + WebSocket)
    node.clj        Node.js backend (raw TCP)
  transport/
    tcp.clj         TCP + bencode codec
    http.clj        HTTP client wrapper
    ws.clj          WebSocket client

test/replsh/
  test_runner.clj   Test runner (run-all for REPL, -main for CLI)
  util_test.clj     Unit tests
  output_test.clj   Unit tests
  config_test.clj   Unit tests
  process_test.clj  Integration tests
  integration_test.clj  End-to-end tests (launches bb nREPL)
```

## Key Design Decisions

- **Babashka only** — no JVM fallback. ProcessBuilder and java.net are in BB's allowlist.
- **Stateless CLI** — each invocation reconnects from `~/.replsh/state.edn`. No daemon.
- **4 data shapes**: Toolchain Preset → Session Spec → Resolved Spec → Session Config
- **Config resolution**: builtin toolchains → `~/.replsh/config.edn` → `<project>/.replsh/config.edn` → CLI args
- **Backends are multimethods** dispatching on `:backend` keyword. They don't know about processes or config.
- **`exec` prefix** in spawn! so PID tracks the server, not the shell wrapper.
