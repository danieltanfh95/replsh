# replsh

Unified CLI for REPL servers. One tool to talk to nREPL, Jupyter, and Node.js REPLs.

Built for LLMs — all output is structured JSON. Named sessions abstract away connection details.

## Install

Requires [Babashka](https://babashka.org/).

```bash
# Clone and run directly
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

See [SKILL.md](SKILL.md) for the LLM-oriented reference, [doc/BACKENDS.md](doc/BACKENDS.md) for backend comparison.
