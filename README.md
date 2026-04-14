# replsh

[![License: EPL 2.0](https://img.shields.io/badge/License-EPL%202.0-blue.svg)](https://www.eclipse.org/legal/epl-2.0/)

A thinking medium for LLM agents. Bash gives agents hands — they can move files, invoke tools, run commands. replsh gives them a scratchpad — a persistent, stateful environment where they eval expressions, inspect runtime state, and test hypotheses before committing to code.

Supports Clojure (deps.edn, Leiningen, Babashka), Python (native bridge — zero deps, or Jupyter for rich output), Node.js, and Bash (persistent shell — env vars, cwd, and functions survive across evals). All output is structured JSON. Sessions persist across invocations.

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
bbin install io.github.danieltanfh95/replsh

# Or clone and install locally
git clone https://github.com/danieltanfh95/replsh.git
cd replsh
bbin install . --as replsh
```

## Add as a Skill

Generate a [skills.sh](https://skills.sh/)-compatible skill document that teaches LLM agents how to use the REPL proactively:

```bash
replsh --install-skill                  # writes to skills/replsh/SKILL.md
replsh --install-skill --path my.md    # custom path
```

Or just point agents at the built-in help directly in your `CLAUDE.md` or `AGENTS.md`:

```markdown
Run `replsh --help` and read the output to learn the replsh skill. Follow it.
```

## Quick Start

```bash
# Launch a REPL (from project config or explicit)
replsh launch --name dev
replsh launch nrepl --name dev --cmd "bb --nrepl-server {port}"
replsh launch python --name py --cmd "python3 {bridge} --port {port}"

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

If it times out, you get **partial output** (whatever accumulated) as a success with `"status": "partial"` — not an error. The eval may still run server-side. You never lose output to a timeout.

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

## Exec Mode (Inject REPL via a Via Chain)

Inject a REPL bridge into any target reachable through an ordered chain of SSH, Docker, and environment layers — without touching the container entrypoint or exposing a port.

The chain is described left-to-right after `--`, with `--runtime` opening each layer:

```bash
# Into an already-running container
replsh launch --name api -- \
  --runtime docker --container my-flask-app \
  --toolchain python

# SSH → Docker (local → remote Docker host)
replsh launch --name remote -- \
  --runtime ssh --host my-machine \
  --runtime docker --container my-app \
  --toolchain python

# Docker → SSH (reversed — from inside a container, reach an internal host)
replsh launch --name dind -- \
  --runtime docker --container outer \
  --runtime ssh --host internal.host \
  --toolchain python

# SSH → Docker → Bash (environment setup: rbenv, conda, nvm, ...)
replsh launch --name rbenv -- \
  --runtime ssh --host my-machine \
  --runtime docker --container my-app \
  --runtime bash --setup "source /etc/profile.d/rbenv.sh" \
  --toolchain python

# State persists across evals
replsh eval --name api 'import sys; print(sys.version)'
replsh eval --name api 'x = 42'
replsh eval --name api 'print(x)'   # → 42

replsh stop api  # kills bridge; leaves unowned containers running
```

Chains can also live in config:

```clojure
;; .replsh/config.edn
{:sessions
 {"remote"  {:via [{:type :ssh :host "my-machine"}
                   {:type :docker :container "my-app"}]
             :toolchain "python"}
  "rbenv"   {:via [{:type :ssh :host "my-machine"}
                   {:type :docker :container "my-app"}
                   {:type :bash :setup ["source /etc/profile.d/rbenv.sh"]}]
             :toolchain "python"}}}
```

Then just `replsh launch --name remote`.

The bridge deploys via stdin piping through the chain and runs persistently on localhost inside the target (no port exposure). Each eval opens an ephemeral proxy through the same chain. `--host` accepts any `~/.ssh/config` alias — ProxyJump, user, port, and identity file are all inherited.

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
| `python` | python | `python3 {bridge} --port {port}` |
| `python.poetry` | python | `poetry run python {bridge} --port {port}` |
| `python.venv` | python | `{cwd}/.venv/bin/python {bridge} --port {port}` |
| `python.poetry.jupyter` | jupyter | `poetry run jupyter server --port {port}` |
| `python.venv.jupyter` | jupyter | `{cwd}/.venv/bin/jupyter server --port {port}` |
| `bash` | bash | `python3 {bridge} --port {port} --backend bash` |
| `node` | node | `node -e "require('net').createServer(...)..."` |

Run `replsh toolchains` for the full list including Docker port-mode variants. Custom toolchains go in `~/.replsh/config.edn` under `:toolchains`.

## Output Format

All sync commands emit one JSON object. Streaming commands emit NDJSON.

```json
{"ok": true, "command": "eval", "status": "complete", "data": {"value": "3", "ns": "user", "chunks": [...]}}
{"ok": true, "command": "eval", "status": "partial", "data": {"chunks": [...]}}
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

- **Backends** (nREPL, Python, Jupyter, Node, Bash) handle wire protocols via multimethods
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
