# replsh

[![License: EPL 2.0](https://img.shields.io/badge/License-EPL%202.0-blue.svg)](https://www.eclipse.org/legal/epl-2.0/)

A thinking medium for LLM agents. Bash gives agents hands — they can move files, invoke tools, run commands. replsh gives them a scratchpad — a persistent, stateful environment where they eval expressions, inspect runtime state, and test hypotheses before committing to code.

Supports Clojure (deps.edn, Leiningen, Babashka), Python (Poetry, venv), and Node.js over their native protocols. All output is structured JSON. Sessions persist across invocations.

## Why a REPL?

LLMs today write code blind. They produce a function, run the test suite, read the failure, and iterate. A REPL changes the loop — instead of *write then verify*, agents can *verify then write*:

- **Think before you commit** — eval an expression to check your assumption before writing it into a file
- **Build up context** — imports, variables, and state persist across evals, so you can explore incrementally
- **Never lose output** — timeouts return partial results, not errors. Even an interrupted eval teaches you something
- **Work at any timescale** — fast checks (sync), progressive output (streaming), long-running tasks (background)

The difference: bash runs commands. A REPL is where you think.

## Install

Requires [Babashka](https://babashka.org/).

```bash
# Via bbin (recommended)
bbin install io.github.g-daniel/replsh

# Or clone and run directly
git clone https://github.com/g-daniel/replsh.git
cd replsh
bb -m replsh.main --help
```

## Quick Start

```bash
# Launch a REPL (from project config or explicit)
replsh launch --name dev
replsh launch nrepl --name dev --cmd "bb --nrepl-server {port}"

# Eval — the core loop
replsh eval --name dev '(+ 1 2)'
# → {"ok":true,"data":{"value":"3","ns":"user",...}}

# Stream output in real time
replsh eval --name dev --stream '(run-tests)'

# Background eval for long-running work
replsh eval --name dev --bg '(train-model data)'
# → {"ok":true,"data":{"eval-id":"eval-a1b2c3d4",...}}
replsh output --eval-id eval-a1b2c3d4
```

## Eval Modes

### Default: sync with graceful timeout

```bash
replsh eval --name dev '(my-fn input)'           # 30s default timeout
replsh eval --name dev '(slow-fn)' --timeout 60000
```

If it times out, you get **partial output** (whatever accumulated) as a success with `"partial": true` — not an error. The eval may still run server-side. You never lose output to a timeout.

### Streaming

```bash
replsh eval --name dev --stream '(doseq [i (range 10)] (println i) (Thread/sleep 500))'
```

NDJSON — one JSON line per chunk as it arrives. Use for test suites, data processing, anything where progressive output matters.

### Background

```bash
replsh eval --name dev --bg '(train-model data)' --timeout 0 --hard-timeout 600000
replsh evals                              # list background evals
replsh output --eval-id <id>              # read output
replsh output --eval-id <id> --follow     # tail output live
```

Forks to a background process. Returns an eval-id immediately. Check results later.

### Hard timeout

```bash
replsh eval --name dev '(risky-fn)' --timeout 5000 --hard-timeout 60000
```

Soft timeout (partial output at 5s) + hard timeout (interrupt eval at 60s). `--hard-timeout` sends a backend interrupt — guaranteed to stop the eval.

## Session Management

```bash
replsh ls                    # list all sessions
replsh status --name dev     # reachability + process info
replsh restart dev           # restart server, re-run init
replsh stop dev              # kill and remove
replsh interrupt --name dev  # cancel running eval
replsh logs --name dev       # read server process logs
```

## Config

### Project config (`.replsh/config.edn`)

```clojure
{:sessions
 {"backend"  {:toolchain "clojure.bb"
              :init      "(require '[my.app])"}
  "ml"       {:toolchain "python.poetry"
              :cwd       "ml/"}}}
```

### Built-in toolchains

| Name | Backend | Command template |
|------|---------|-----------------|
| `clojure.deps` | nrepl | `clj -M:nrepl -m nrepl.cmdline --port {port}` |
| `clojure.lein` | nrepl | `lein repl :headless :port {port}` |
| `clojure.bb` | nrepl | `bb --nrepl-server {port}` |
| `python.poetry` | jupyter | `poetry run jupyter server --port {port}` |
| `python.venv` | jupyter | `{cwd}/.venv/bin/jupyter server --port {port}` |
| `node` | node | `node -e "require('net').createServer(...)..."` |

Custom toolchains go in `~/.replsh/config.edn` under `:toolchains`.

## Output Format

All sync commands emit one JSON object. Streaming commands emit NDJSON.

```json
{"ok": true, "command": "eval", "data": {"value": "3", "ns": "user", "chunks": [...]}}
{"ok": true, "command": "eval", "data": {"chunks": [...]}, "partial": true}
```

Exit codes: `0` success (including partial), `1` eval error, `2` client error, `3` hard timeout.

## Architecture

```
CLI args + config → Session Config → state.edn
                         ↓
                    backend/open! → eval! → close!
                         ↓
              sync / stream (NDJSON) / background (fork)
```

- **Backends** (nREPL, Jupyter, Node) handle wire protocols via multimethods
- **Eval modes** — sync (default), streaming (`--stream`), background (`--bg`)
- **Timeout** — soft (partial output, exit 0) and hard (interrupt, exit 3)
- **Process management** spawns/kills servers, tracks PIDs
- **State** persists to `~/.replsh/state.edn` — no daemon

## Development

replsh is developed using replsh:

```bash
replsh launch --name dev
replsh eval --name dev --stream \
  '(require (quote [replsh.test-runner])) (replsh.test-runner/run-all :unit-only? true)'
replsh stop dev
```

## Documentation

- [Full manual](doc/MANUAL.md) — complete command reference
- [Backend comparison](doc/BACKENDS.md) — protocol capabilities
- [Prior art](doc/PRIOR_ART.md) — landscape survey
- [LLM skill document](skills/replsh/SKILL.md) — agent-oriented reference ([skills.sh](https://skills.sh/) compatible)

## License

[Eclipse Public License 2.0](LICENSE)
