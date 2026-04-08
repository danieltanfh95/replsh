# Async eval: soft/hard timeouts, streaming, background

## Context

Every `replsh eval` blocks until completion or timeout. Timeout is an error — all captured output is thrown away. This means:
- Starting a server via eval? 30s wait, then error, output lost.
- Running a training loop? Same — wait, error, nothing.
- Quick 100ms eval? Works fine, but the timeout behavior punishes everything else.

The core fix: **soft timeout returns partial output** (not an error). This changes the default experience without new flags. Streaming and background eval are additive power features.

---

## Design: soft vs hard timeout

**Soft timeout** (`--timeout`, existing flag, default 30s):
- Read loop stops at deadline
- Returns accumulated chunks as **partial success**: `{"ok": true, "partial": true, "data": {"chunks": [...]}}`
- Exit code **0** (not 3)
- Eval may still be running server-side — replsh just stops listening
- The LLM gets useful output without any new flags

**Hard timeout** (`--hard-timeout`, new flag, no default):
- When reached, sends **interrupt** to backend (nREPL `interrupt` op, Jupyter REST `/interrupt`)
- Returns accumulated chunks as **timeout error**: `{"ok": false, "error": {"code": "timeout"}, "data": {"chunks": [...]}}`
- Exit code **3**
- Actually kills the eval

They compose:

```bash
# Default: soft timeout at 30s — returns partial output
replsh eval --name dev '(start-server)'

# Sniff 5s of output, let eval continue
replsh eval --name dev '(start-server)' --timeout 5000

# Stream test output, hard-kill at 2 minutes
replsh eval --name dev --stream '(run-tests)' --hard-timeout 120000

# No soft timeout (wait for completion), hard-kill at 5 minutes
replsh eval --name dev '(train-model)' --timeout 0 --hard-timeout 300000
```

`--timeout 0` = no soft timeout (wait until eval completes or hard timeout).

---

## Changes

### Phase 1: Soft timeout (core change — no new flags needed)

The highest-impact, smallest change. Every eval immediately benefits.

**`src/replsh/backend/nrepl.clj`** — `read-responses` returns partial on timeout:
- Currently: throws `(ex-info "..." {:code :timeout})` when deadline exceeded
- Change: return accumulated chunks with a `:timed-out? true` metadata flag
- The loop already accumulates chunks — just stop looping and return them

**`src/replsh/backend/jupyter.clj`** — `collect-responses` same change:
- Currently: throws on timeout
- Change: return accumulated chunks with `:timed-out? true`

**`src/replsh/backend/node.clj`** — `read-until-prompt` same change:
- Currently: throws on timeout  
- Change: return whatever text was accumulated, parse to chunks, flag as timed-out

**`src/replsh/command.clj`** — `eval-cmd` handles partial results:
- Detect `:timed-out?` in the returned chunks metadata (or as a flag on the result)
- Build result with `"partial": true` in the envelope
- Exit code 0 (success), not 3 (timeout)

**`src/replsh/output.clj`** — Support `partial` field:
- `success` fn accepts optional `partial?` parameter
- Adds `"partial": true` to the JSON envelope when set

**No CLI changes needed** — `--timeout` already exists and defaults to 30000.

### Phase 2: Hard timeout + interrupt on deadline

**`src/replsh/cli.clj`** — Add `--hard-timeout` flag to eval spec:
- `{:hard-timeout {:desc "Hard timeout: interrupt eval (ms)" :coerce :long}}`

**`src/replsh/command.clj`** — `eval-cmd` implements hard timeout:
- Pass both `:timeout-ms` and `:hard-timeout-ms` in request
- If eval returns with `:timed-out?` and hard-timeout is set, check if hard deadline passed
- If hard deadline passed: call `backend/interrupt!`, return timeout error (exit 3)
- If hard deadline not yet passed: return partial success as in Phase 1

Alternative (simpler): handle hard timeout inside each backend's read loop:
- The read loop checks two deadlines: soft and hard
- On soft deadline: stop reading, return partial
- On hard deadline: throw timeout (existing behavior)

The simpler approach keeps the backend interface clean — backends already handle timeout, we just add a second tier.

### Phase 3: Streaming eval (`--stream`)

**`src/replsh/output.clj`** — Add NDJSON functions:
- `emit-chunk!` — print one chunk as JSON line to stdout, flush immediately
- `emit-summary!` — print final envelope with `"final": true`, return exit code

**`src/replsh/backend/nrepl.clj`** — `on-chunk` callback in `read-responses`:
- Extract `response->chunks` (single nREPL response → seq of replsh chunks)
- When `:on-chunk` is in request, call it per chunk as it arrives
- Still accumulate and return chunks (for the summary line)

**`src/replsh/backend/jupyter.clj`** — Same pattern:
- `on-chunk` callback in `collect-responses`

**`src/replsh/backend/node.clj`** — Line-buffered streaming:
- Emit `:out` chunk per newline when `on-chunk` is set
- Prompt detection still marks completion

**`src/replsh/command.clj`** — Wire up:
- When `stream?`, set `:on-chunk output/emit-chunk!` in request
- Return result with `:stream? true` for main.clj

**`src/replsh/cli.clj`** — Add `--stream` / `-s` flag to eval spec

**`src/replsh/main.clj`** — Branch on `:stream?`:
- Streaming: `output/emit-summary!` (chunks already emitted inline)
- Sync: `output/emit!` as before

### Phase 4: Background eval (`--bg`) + output reading

**File conventions:**
- `~/.replsh/evals/<eval-id>.jsonl` — NDJSON chunks from child process
- `~/.replsh/evals/<eval-id>.meta.edn` — `{:eval-id :session :pid :status :started-at :ended-at}`
- `~/.replsh/evals/<eval-id>.code` — code temp file (avoids shell quoting)

**`src/replsh/command.clj`** — `eval-bg-cmd`:
1. Write code to `.code` temp file
2. Write initial `.meta.edn` with `:status :running`
3. Fork child: `bb -m replsh.main eval --name <n> --stream --bg-child <eval-id> --file <code-file>` with stdout → `.jsonl`
4. Record PID, return immediately with eval-id

**`src/replsh/command.clj`** — Child mode (`--bg-child`):
- Streaming eval, does NOT write to `state.edn`
- On completion, writes final status to `.meta.edn`

**`src/replsh/command.clj`** — `output-cmd`:
- `replsh output --eval-id <id>` — read `.meta.edn` + `.jsonl`
- `--follow` — tail until done (checks PID liveness)

**`src/replsh/command.clj`** — `evals-cmd`:
- `replsh evals` — list all bg evals

**`src/replsh/cli.clj`** — New flags/commands:
- `--bg` on eval, `output` command, `evals` command

### Phase 5: Process log reading (`logs` command)

**`src/replsh/command.clj`** — `logs-cmd`:
- `replsh logs --name <name>` — reads `~/.replsh/logs/<name>.log`
- `--tail N`, `--follow`

**`src/replsh/cli.clj`** — `logs` command dispatch

### Phase 6: Eval output history / replay

When streaming or background evals produce stdout output, the user may want to retrieve the full output later in one go. Two aspects:

1. **Streaming eval output capture**: When `--stream` is used, chunks go to stdout. If the user also wants them persisted, `--stream` could optionally write to a log file alongside stdout. Or: `--bg` already captures to `.jsonl` — this is the persistent version of `--stream`.

2. **Session eval history**: Store eval results (or at least stdout chunks) per session so `replsh output --name <name>` can replay the last eval's output. Most useful when the output was captured to a variable in the REPL but sometimes it's just stdout that the user wants to see again. This could be as simple as writing the last eval's chunks to `~/.replsh/evals/<session>-last.jsonl` on every eval.

---

## Files

| File | Phase | Action |
|------|-------|--------|
| `src/replsh/backend/nrepl.clj` | 1, 3 | Return partial on timeout; add `on-chunk` callback |
| `src/replsh/backend/jupyter.clj` | 1, 3 | Return partial on timeout; add `on-chunk` callback |
| `src/replsh/backend/node.clj` | 1, 3 | Return partial on timeout; add line-buffered streaming |
| `src/replsh/command.clj` | 1-5 | Handle partial results, streaming, bg fork, output, logs |
| `src/replsh/output.clj` | 1, 3 | `partial` support; `emit-chunk!`, `emit-summary!` |
| `src/replsh/cli.clj` | 2-5 | `--hard-timeout`, `--stream`, `--bg`; `output`, `evals`, `logs` commands |
| `src/replsh/main.clj` | 3 | Branch on `stream?` for emit path |

---

## Implementation order

Each phase is independently shippable. Phase 1 alone is a major improvement.

1. **Phase 1** (soft timeout) — smallest change, biggest impact. ~30 min.
2. **Phase 2** (hard timeout) — adds interrupt-on-deadline. Small addition.
3. **Phase 3** (streaming) — NDJSON output, `on-chunk` callback. Moderate.
4. **Phase 4** (background) — fork child, output reading. Most complex.
5. **Phase 5** (logs) — simple file reading. Independent of everything.

---

## Verification

- **Phase 1**: `replsh eval --name dev '(Thread/sleep 60000) :done' --timeout 3000` → returns `{"ok": true, "partial": true, ...}` with exit 0, not timeout error
- **Phase 2**: Same with `--hard-timeout 5000` → interrupts at 5s, returns `{"ok": false, "error": {"code": "timeout"}}` with exit 3
- **Phase 3**: `replsh eval --name dev --stream '(doseq [i (range 5)] (println i) (Thread/sleep 500)) :done'` → 5 lines arrive incrementally, summary at end
- **Phase 4**: `replsh eval --name dev --bg '(Thread/sleep 5000) :done'` → immediate return. `replsh output --eval-id <id>` → shows status.
- **Phase 5**: `replsh logs --name dev --tail 10` → last 10 lines of server log
- **Regression**: `replsh eval --name dev '(+ 1 2)'` → identical single JSON as before, exit 0
