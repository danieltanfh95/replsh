# replsh — Development Guide for Claude Code

For replsh command reference, see [skills/replsh/SKILL.md](skills/replsh/SKILL.md).

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
