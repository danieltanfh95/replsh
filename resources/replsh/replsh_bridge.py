#!/usr/bin/env python3
"""replsh bridge — lightweight Python REPL server with JSON-over-TCP protocol.

Stdlib only. Zero dependencies. Designed to be launched by the project's
Python interpreter (venv, poetry, conda) so all project packages are available.

Protocol: NDJSON over TCP. Each message is one JSON object + newline.
"""
import sys
import json
import os
import signal
import socket
import subprocess
import threading
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


class StdioConn:
    """Socket-like wrapper over stdin/stdout for stdio mode."""

    def sendall(self, data):
        sys.__stdout__.buffer.write(data)
        sys.__stdout__.buffer.flush()

    def makefile(self, mode, encoding='utf-8'):
        return sys.stdin


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

    def serve_stdio(self):
        """Serve a single connection over stdin/stdout."""
        print('replsh bridge stdio mode ready', file=sys.__stderr__)
        conn = StdioConn()
        self.handle_connection(conn)

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


class BashBridge(ReplBridge):
    """ReplBridge variant that evaluates code in a persistent bash subprocess.

    State (env vars, cwd, shell functions) persists across evals because
    the same bash process handles every eval request.
    """

    def __init__(self, host, port):
        super().__init__(host, port)
        self.bash_proc = subprocess.Popen(
            ['bash', '--norc', '--noprofile'],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
        )

    def eval_code(self, code, msg_id, conn):
        # Replace hyphens so the sentinel is a valid shell token
        sentinel = '__REPLSH_{}__'.format(msg_id.replace('-', '_'))
        # Wrap code; echo sentinel + exit code to both streams afterward
        script = (
            '{}\n'
            '__replsh_exit=$?\n'
            'printf "%s %d\\n" "{}" "$__replsh_exit" >&2\n'
            'printf "%s %d\\n" "{}" "$__replsh_exit"\n'
        ).format(code, sentinel, sentinel)

        exit_ref    = [0]
        interrupted = [False]
        done_out    = threading.Event()
        done_err    = threading.Event()

        def read_stream(stream, msg_type, done_event):
            for raw in iter(stream.readline, b''):
                line = raw.decode('utf-8', errors='replace')
                if line.startswith(sentinel):
                    try:
                        exit_ref[0] = int(line.split()[-1])
                    except (ValueError, IndexError):
                        pass
                    done_event.set()
                    return
                try:
                    send_msg(conn, {
                        'type': msg_type,
                        'content': line,
                        'stream': 'stdout' if msg_type == 'out' else 'stderr',
                        'msg_id': msg_id,
                        'done': False,
                    })
                except (BrokenPipeError, ConnectionResetError):
                    done_event.set()
                    return

        self._evaluating = True
        t_out = threading.Thread(target=read_stream,
                                 args=(self.bash_proc.stdout, 'out', done_out), daemon=True)
        t_err = threading.Thread(target=read_stream,
                                 args=(self.bash_proc.stderr, 'err', done_err), daemon=True)
        try:
            self.bash_proc.stdin.write(script.encode('utf-8'))
            self.bash_proc.stdin.flush()
            t_out.start()
            t_err.start()
            done_out.wait()
            done_err.wait()
        except KeyboardInterrupt:
            interrupted[0] = True
            # Forward interrupt to bash; write emergency sentinel to unblock threads
            try:
                os.kill(self.bash_proc.pid, signal.SIGINT)
            except ProcessLookupError:
                pass
            try:
                self.bash_proc.stdin.write(
                    '\nprintf "%s 130\\n" "{}" >&2\nprintf "%s 130\\n" "{}"\n'
                    .format(sentinel, sentinel).encode())
                self.bash_proc.stdin.flush()
            except Exception:
                pass
            done_out.wait(timeout=2.0)
            done_err.wait(timeout=2.0)
        finally:
            self._evaluating = False

        if interrupted[0]:
            send_msg(conn, {'type': 'error', 'content': 'KeyboardInterrupt',
                            'meta': {'ename': 'KeyboardInterrupt', 'traceback': []},
                            'msg_id': msg_id, 'done': False})
        elif exit_ref[0] != 0:
            send_msg(conn, {'type': 'error',
                            'content': 'exit code {}'.format(exit_ref[0]),
                            'meta': {'exit_code': exit_ref[0]},
                            'msg_id': msg_id, 'done': False})
        else:
            send_msg(conn, {'type': 'value', 'content': '0',
                            'meta': {}, 'msg_id': msg_id, 'done': False})

        send_msg(conn, {'type': 'status', 'content': '', 'meta': {},
                        'msg_id': msg_id, 'done': True})


def connect_proxy(host, port):
    """Connect to a running bridge via TCP, relay stdin<->TCP.

    Used as an ephemeral proxy: each eval starts one of these to talk
    to the persistent bridge server inside a container.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((host, port))

    def stdin_to_sock():
        for line in sys.stdin:
            try:
                sock.sendall(line.encode('utf-8'))
            except (BrokenPipeError, ConnectionResetError):
                break
        try:
            sock.shutdown(socket.SHUT_WR)
        except Exception:
            pass

    t = threading.Thread(target=stdin_to_sock, daemon=True)
    t.start()
    buf = sock.makefile('r', encoding='utf-8')
    for line in buf:
        sys.__stdout__.write(line)
        sys.__stdout__.flush()
    sock.close()


if __name__ == '__main__':
    args = sys.argv[1:]
    backend = args[args.index('--backend') + 1] if '--backend' in args else 'python'
    BridgeClass = BashBridge if backend == 'bash' else ReplBridge

    if '--stdio' in args:
        BridgeClass(None, None).serve_stdio()
    elif '--connect' in args:
        addr = args[args.index('--connect') + 1]
        h, p = addr.rsplit(':', 1)
        connect_proxy(h, int(p))          # proxy is backend-agnostic
    else:
        host = 'localhost'
        port = 9876
        if '--port' in args: port = int(args[args.index('--port') + 1])
        if '--host' in args: host = args[args.index('--host') + 1]
        BridgeClass(host, port).serve()
