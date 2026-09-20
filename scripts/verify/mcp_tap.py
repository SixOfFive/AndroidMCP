"""A logging reverse proxy in front of the androidmcp server.

Point an MCP client at this and it records exactly what goes over the wire — request line,
headers, body, and the response status/headers — so we can see what a real client actually
requires rather than what we assume it does.
"""
import json
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

UPSTREAM = sys.argv[1] if len(sys.argv) > 1 else "http://192.168.1.50:8765"  # override as argv[1]
LOG = open(sys.argv[2] if len(sys.argv) > 2 else "tap.log", "w", buffering=1)

# Headers a client sets that we want to see verbatim; everything else is still logged.
INTERESTING = ("accept", "content-type", "mcp-protocol-version", "mcp-session-id",
               "authorization", "origin", "user-agent", "last-event-id")


def log(*a):
    print(*a, file=LOG)


class Tap(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _relay(self, method: str):
        body = b""
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            body = self.rfile.read(n)

        log(f"\n===== {method} {self.path} =====")
        for k, v in self.headers.items():
            shown = "Bearer <redacted>" if k.lower() == "authorization" else v
            mark = "*" if k.lower() in INTERESTING else " "
            log(f"  {mark} {k}: {shown}")
        if body:
            try:
                log("  BODY: " + json.dumps(json.loads(body), separators=(",", ":"))[:600])
            except Exception:
                log(f"  BODY: <{len(body)} bytes, not json>")
        else:
            log("  BODY: <empty>")

        req = urllib.request.Request(
            UPSTREAM + self.path, data=body if body else None, method=method,
            headers={k: v for k, v in self.headers.items() if k.lower() != "host"},
        )
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, hdrs, payload = r.status, list(r.headers.items()), r.read()
        except urllib.error.HTTPError as e:
            status, hdrs, payload = e.code, list(e.headers.items()), e.read()
        except Exception as e:                                    # upstream unreachable
            log(f"  -> UPSTREAM ERROR {e}")
            self.send_response(502); self.end_headers(); return

        log(f"  -> {status}  ({len(payload)} bytes)")
        for k, v in hdrs:
            if k.lower() in ("content-type", "www-authenticate", "mcp-session-id", "retry-after"):
                log(f"     {k}: {v}")
        if payload[:1] in (b"{", b"["):
            log("     " + payload[:400].decode("utf-8", "replace"))

        self.send_response(status)
        for k, v in hdrs:
            if k.lower() not in ("transfer-encoding", "connection", "content-length"):
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_POST(self): self._relay("POST")
    def do_GET(self): self._relay("GET")
    def do_DELETE(self): self._relay("DELETE")
    def do_OPTIONS(self): self._relay("OPTIONS")
    def log_message(self, *a): pass          # our own logging is richer


if __name__ == "__main__":
    log(f"tapping {UPSTREAM}")
    ThreadingHTTPServer(("127.0.0.1", 9911), Tap).serve_forever()
