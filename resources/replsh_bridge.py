#!/usr/bin/env python3
"""replsh bridge — lightweight Python REPL server with JSON-over-TCP protocol.

Stdlib only. Zero dependencies. Designed to be launched by the project's
Python interpreter (venv, poetry, conda) so all project packages are available.

Protocol: NDJSON over TCP. Each message is one JSON object + newline.
"""
import sys
import json
import signal
import socket
import traceback


def send_msg(conn, msg):
    """Send a JSON message as one NDJSON line."""
    conn.sendall((json.dumps(msg, ensure_ascii=False) + '\n').encode('utf-8'))


class StreamWriter:
    """File-like object that streams writes as JSON chunks over TCP."""

    def __init__(self, conn, msg_id, stream_name):
        self.conn = conn
        self.msg_id = msg_id
        self.stream_name = stream_name

    def write(self, data):
        if data:
            try:
                send_msg(self.conn, {
                    'type': 'out' if self.stream_name == 'stdout' else 'err',
                    'content': data,
                    'stream': self.stream_name,
                    'msg_id': self.msg_id,
                    'done': False,
                })
            except (BrokenPipeError, ConnectionResetError):
                pass
        return len(data) if data else 0

    def flush(self):
        pass

    def isatty(self):
        return False


class ReplBridge:
    def __init__(self, host, port):
        self.host = host
        self.port = port
        self.namespace = {'__name__': '__main__', '__builtins__': __builtins__}
        self._evaluating = False

    def eval_code(self, code, msg_id, conn):
        """Evaluate code with streaming stdout/stderr capture."""
        stdout_w = StreamWriter(conn, msg_id, 'stdout')
        stderr_w = StreamWriter(conn, msg_id, 'stderr')
        old_stdout, old_stderr = sys.stdout, sys.stderr
        self._evaluating = True
        try:
            sys.stdout = stdout_w
            sys.stderr = stderr_w
            # Try as expression first (to get a return value)
            try:
                compiled = compile(code, '<repl>', 'eval')
                result = eval(compiled, self.namespace)
                if result is not None:
                    send_msg(conn, {
                        'type': 'value',
                        'content': repr(result),
                        'meta': {},
                        'msg_id': msg_id,
                        'done': False,
                    })
            except SyntaxError:
                # Not an expression — compile as exec (statement/block)
                compiled = compile(code, '<repl>', 'exec')
                exec(compiled, self.namespace)
        except KeyboardInterrupt:
            send_msg(conn, {
                'type': 'error',
                'content': 'KeyboardInterrupt',
                'meta': {'ename': 'KeyboardInterrupt', 'traceback': []},
                'msg_id': msg_id,
                'done': False,
            })
        except Exception:
            etype, evalue, tb = sys.exc_info()
            tb_lines = traceback.format_exception(etype, evalue, tb)
            # Skip the first frame (this eval_code function)
            send_msg(conn, {
                'type': 'error',
                'content': f'{etype.__name__}: {evalue}',
                'meta': {
                    'ename': etype.__name__,
                    'evalue': str(evalue),
                    'traceback': tb_lines,
                },
                'msg_id': msg_id,
                'done': False,
            })
        finally:
            self._evaluating = False
            sys.stdout = old_stdout
            sys.stderr = old_stderr
            send_msg(conn, {
                'type': 'status',
                'content': '',
                'meta': {},
                'msg_id': msg_id,
                'done': True,
            })

    def handle_connection(self, conn):
        """Read NDJSON requests from a connection, dispatch ops."""
        buf = conn.makefile('r', encoding='utf-8')
        try:
            for line in buf:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    continue
                op = msg.get('op')
                msg_id = msg.get('msg_id', '')
                if op == 'eval':
                    self.eval_code(msg.get('code', ''), msg_id, conn)
                elif op == 'ping':
                    send_msg(conn, {
                        'type': 'pong',
                        'msg_id': msg_id,
                        'done': True,
                    })
                # Unknown ops are silently ignored
        except KeyboardInterrupt:
            pass  # SIGINT outside eval — just close this connection

    def serve(self):
        """Accept connections in a loop. State persists across connections."""
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((self.host, self.port))
        srv.listen(1)
        print(f'replsh bridge listening on {self.host}:{self.port}',
              file=sys.__stderr__)
        while True:
            try:
                conn, addr = srv.accept()
            except KeyboardInterrupt:
                continue  # SIGINT during accept — keep listening
            try:
                self.handle_connection(conn)
            except (ConnectionResetError, BrokenPipeError):
                pass
            except KeyboardInterrupt:
                pass  # SIGINT during connection handling — keep listening
            except Exception as e:
                print(f'replsh bridge error: {e}', file=sys.__stderr__)
            finally:
                try:
                    conn.close()
                except Exception:
                    pass


if __name__ == '__main__':
    args = sys.argv[1:]
    host = 'localhost'
    port = 9876
    if '--port' in args:
        port = int(args[args.index('--port') + 1])
    if '--host' in args:
        host = args[args.index('--host') + 1]
    ReplBridge(host, port).serve()
