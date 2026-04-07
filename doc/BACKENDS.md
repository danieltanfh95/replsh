# Backend Capabilities

Comparison of what each replsh backend supports.

| Backend | Transport | Eval | Stdout/Stderr | Errors | State | Interrupt |
|---------|-----------|------|---------------|--------|-------|-----------|
| **nREPL** | TCP + bencode | structured | separate chunks | structured with stacktrace | persistent across invocations | supported |
| **Node.js** | TCP raw text | prompt detection | mixed (text heuristic) | text only | per-connection | unsupported (honest) |
| **Jupyter** | REST + WebSocket | kernel protocol | separate stream msgs | structured with traceback | persistent kernel | supported |

## Notes

- **nREPL**: Sessions are server-side. `def` persists in the namespace across replsh invocations. Multiplexed over a single TCP socket per connection.
- **Node.js**: Each replsh invocation opens a new TCP connection (= new REPL context). No structured output — stdout and return values are separated by heuristic (last line = value). Errors arrive as text, not structured.
- **Jupyter**: Kernels are created via REST and persist server-side. Communication over WebSocket using the Jupyter messaging protocol. Avoids ZeroMQ by going through the Jupyter Server's HTTP+WS bridge. Environment vars are passed through to kernel creation.
