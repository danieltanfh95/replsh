# replsh — Development Guide for Claude Code

For replsh command reference, run `replsh --help`. To generate the skill file, run `replsh --install-skill`.

This project uses replsh (its own tool) for development. You have a live REPL available — use it.

## Setup

Install globally via [bbin](https://github.com/babashka/bbin):

```bash
# Recommended: stable install (self-contained uberjar)
bb install       # from the replsh project dir

# Dev install (picks up source changes, but flaky from other directories)
bbin install .
```

The stable install builds a self-contained uberjar that works reliably from any directory. The dev install couples invocations to the source tree via `local/root`, which can fail intermittently when called from directories with their own `bb.edn`.

The project has `.replsh/config.edn` with a `dev` session. Launch it:

```bash
replsh launch --name dev
```

Port is auto-allocated. The session persists across eval calls.

## Workflow

**Evaluate code interactively** instead of restarting processes:

```bash
# Inline
replsh eval --name dev '(+ 1 2)'

# From file
replsh eval --name dev --file src/replsh/util.clj

# From stdin (pipe)
echo '(require (quote [replsh.util :as util])) (util/find-free-port)' | replsh eval --name dev

# Stream output (test suites, long-running)
replsh eval --name dev --stream '(run-tests)'

# Background eval (returns immediately)
replsh eval --name dev --bg '(long-task)'
replsh output --eval-id <id>
```

**Run tests through the REPL** (faster than cold-starting bb each time):

```bash
# Unit tests only (streaming shows progress)
replsh eval --name dev --stream '(require (quote [replsh.test-runner])) (replsh.test-runner/run-all :unit-only? true :reload? true)' --timeout 60000

# All tests (unit + integration)
replsh eval --name dev --stream '(require (quote [replsh.test-runner])) (replsh.test-runner/run-all :reload? true)' --timeout 120000
```

**Or run tests directly** (cold start, simpler):

```bash
bb -m replsh.test-runner          # all tests
bb -m replsh.test-runner --unit   # unit only
```

## Session Management

```bash
replsh ls                    # list sessions
replsh status --name dev     # check reachability
replsh restart dev           # restart server + re-run init
replsh stop dev              # kill server, remove session
replsh logs --name dev       # read server process logs
replsh evals                 # list background evals
```
