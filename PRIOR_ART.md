# Prior Art & Landscape

Survey of existing tools in the "unified REPL client / multi-language REPL manager" space, conducted 2026-04-07.

## The Gap

No tool unifies nREPL + Jupyter kernel protocol + raw socket REPL under a single named-session abstraction with a clean CLI interface and structured output for LLMs. The closest tools either screen-scrape prompts (brittle), are cloud-dependent, or are editor-only.

## MCP-Based REPL Servers

**[takafu/repl-mcp](https://github.com/takafu/repl-mcp)** — Most direct overlap. MCP server managing named REPL sessions across Python, IPython, Node.js, Ruby, bash, zsh. Wraps processes via PTY, learns prompt patterns per session.
- Right: named sessions, multi-backend, MCP-native
- Wrong: PTY/pexpect screen-scraping is fragile. No nREPL/Jupyter protocol awareness. Essentially tmux over MCP.

**[MCPRepl.jl](https://discourse.julialang.org/t/ann-mcprepl-jl-share-your-repl-with-your-ai-agent/130536)** — Exposes a running Julia REPL via MCP so agent and developer share identical state. Clever shared-state model. Single-language.

**[Clojure REPL MCP server (hugoduncan)](https://playbooks.com/mcp/hugoduncan-clojure-repl)** — SSE-transport MCP server wrapping an nREPL connection. Single-language.

**[Anthropic: Code Execution with MCP](https://www.anthropic.com/engineering/code-execution-with-mcp)** — Anthropic's blog post on using Jupyter kernel as execution backend for agents via MCP. Treats the kernel as an implementation detail.

## Cloud Code Execution Sandboxes

**[E2B](https://github.com/e2b-dev/E2B)** (~60k stars) — Firecracker microVM sandbox with persistent IPython kernel. Best-in-class for secure cloud execution with LLM SDK integration. Used by Hugging Face, Groq.
- Right: persistent kernel state, streaming output, multi-language kernels, open source
- Wrong: cloud-only, requires their infrastructure or self-hosting, overkill for local dev

**[microsandbox](https://github.com/nicholasgasior/microsandbox)** — Self-hosted, libkrun microVM-based, sub-200ms startup.

**[alibaba/OpenSandbox](https://github.com/alibaba/OpenSandbox)** — General-purpose sandbox with multi-language SDKs, Docker/Kubernetes runtimes.

**[vndee/llm-sandbox](https://github.com/vndee/llm-sandbox)** — Lightweight Python library, Docker/Podman backends, IPython kernel inside the container.

## Jupyter as Headless Execution Backend

**[Jupyter Kernel Gateway](https://github.com/jupyter-server/kernel_gateway)** — The official "headless Jupyter." REST + WebSocket API for kernel lifecycle. Language-agnostic via kernel spec.
- Right: language-agnostic, well-documented protocol, mature
- Wrong: heavyweight (Python/Tornado), notebook-heritage UX, complex WebSocket framing

## LLM-First Execution

**[Open Interpreter](https://github.com/openinterpreter/open-interpreter)** (~60k stars) — LLM given an `exec()` function that accepts language name and code. Runs locally, full computer access. Supports Python, JS, shell.
- Right: natural language → code → execution loop, multi-language
- Wrong: not a REPL manager — it's an agent UI. No named sessions, no protocol awareness.

**[Simon Willison's WASM REPL CLI](https://simonwillison.net/2026/Feb/4/wasm-repl-cli/)** — Go binaries wrapping QuickJS-WASI and CPython-WASI. JSONL mode for programmatic submission with persistent state. Research-grade.

## Editor-Embedded REPL Managers

**[yarepl.nvim](https://github.com/milanglacier/yarepl.nvim)** — Neovim plugin managing multiple named REPL processes. Supports sending regions from any buffer to any running REPL, parallel sessions, mixed-language documents.
- Right: named sessions, buffer-REPL attachment model, mixed-language support
- Wrong: Neovim-only, UI-first, not programmatic

## Protocol-Level

**[nREPL](https://github.com/nrepl/nrepl)** — Clojure's network REPL. Message-oriented, async, middleware-extensible. Has clients in Python, Node.js, R. The nREPL docs note: *"the main issue with the plain socket REPL is it's hard to tell apart values from output — that's fine interactively, but a big issue programmatically."*

## What AI Coding Tools Do

| Tool | Approach |
|---|---|
| Claude Code | Shell tool (Bash). No REPL protocol awareness. |
| Aider | Terminal-native, shell commands. No REPL protocol. |
| Cursor | Agent mode runs terminal commands. No session management. |
| Cline | Shell command execution via tool calls. No named sessions. |

None have first-class REPL session management. All treat code execution as fire-and-forget shell commands with no persistent interpreter state.

## Where replsh Fits

replsh is a protocol-aware CLI that speaks each backend's native wire format (bencode for nREPL, REST+WS for Jupyter, raw TCP for Node) rather than screen-scraping. Named sessions, structured JSON output for LLMs, environment-aware. This combination does not exist as a finished tool.
