# replsh

[![License: EPL 2.0](https://img.shields.io/badge/License-EPL%202.0-blue.svg)](https://www.eclipse.org/legal/epl-2.0/)

Unified CLI for REPL servers. One tool to talk to Clojure (deps.edn, Leiningen, Babashka), Python (Poetry, venv), and Node.js — over their native protocols.

Built for LLMs — all output is structured JSON. Named sessions abstract away connection details.

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

## Usage

### Launch a REPL server

```bash
# From project config (.replsh/config.edn)
replsh launch --name backend

# With explicit args
replsh launch nrepl --name dev --port 1667 --cmd "bb --nrepl-server 1667"

# With bootstrap code
replsh launch --name dev --init "(require '[my.app])"
```

### Connect to an existing server

```bash
replsh start nrepl --name dev --port 1667
```

### Evaluate code

```bash
replsh eval --name dev '(+ 1 2)'
# → {"ok":true,"command":"eval","data":{"value":"3","ns":"user",...}}
```

### Manage sessions

```bash
replsh ls                    # list all sessions
replsh status --name dev     # show details + reachability
replsh restart dev           # restart (re-launches if launched)
replsh stop dev              # stop and remove
replsh interrupt --name dev  # cancel running eval
```

## Config

### Project config (`.replsh/config.edn`)

```clojure
{:sessions
 {"backend"  {:toolchain "clojure.bb"
              :port      1667
              :init      "(require '[my.app])"}
  "ml"       {:toolchain "python.poetry"
              :port      8888
              :cwd       "ml/"}}}
```

### Global config (`~/.replsh/config.edn`)

Add or override toolchain presets:

```clojure
{:toolchains
 {"python.conda" {:backend  :jupyter
                  :cmd      "conda run jupyter server --port {port}"
                  :defaults {:port 8888 :kernel "python3"}}}}
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

## Output Format

All commands emit JSON to stdout:

```json
{"ok": true, "command": "eval", "data": {"value": "3", "ns": "user", ...}}
{"ok": false, "command": "eval", "error": {"code": "eval_error", "message": "..."}}
```

Exit codes: `0` success, `1` eval error, `2` client error, `3` timeout.

## Environment Variables

| Variable | Description |
|----------|-------------|
| `REPLSH_CONFIG` | Override project config path |
| `REPLSH_CONFIG_GLOBAL` | Override global config path |
| `REPLSH_STATE` | Override state file path |

## Architecture

```
CLI args + config → Resolved Spec → Session Config → state.edn
                                         ↓
                                    backend/open! → eval! → close!
```

- **Backends** (nREPL, Jupyter, Node) handle wire protocols via multimethods
- **Process management** spawns/kills server processes for `launch`
- **Config resolution** merges toolchain presets → project sessions → CLI args
- **State** persists sessions to `~/.replsh/state.edn` — no daemon needed

## Development

replsh is developed using replsh. The project includes `.replsh/config.edn` with a `dev` session:

```bash
# Launch a dev REPL (port auto-allocated)
replsh launch --name dev

# Run unit tests through the REPL
replsh eval --name dev \
  '(require (quote [replsh.test-runner])) (replsh.test-runner/run-all :unit-only? true)'

# Run all tests (unit + integration)
replsh eval --name dev \
  '(require (quote [replsh.test-runner])) (replsh.test-runner/run-all)' \
  --timeout 120000

# Or run tests directly (cold start)
bb -m replsh.test-runner          # all tests
bb -m replsh.test-runner --unit   # unit only

# Clean up
replsh stop dev
```

## Documentation

- [Full manual](doc/MANUAL.md) — complete command reference
- [Backend comparison](doc/BACKENDS.md) — protocol capabilities
- [Prior art](doc/PRIOR_ART.md) — landscape survey
- [LLM skill document](skills/replsh/SKILL.md) — agent-oriented reference ([skills.sh](https://skills.sh/) compatible)
- `man replsh` — Unix manual page

## License

[Eclipse Public License 2.0](LICENSE)
