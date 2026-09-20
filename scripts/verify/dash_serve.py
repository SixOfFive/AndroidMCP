"""Serve dashboard/index.html from localhost and proxy /mcp + /media to the device.

The in-app preview pane refuses LAN requests, but localhost may be allowed — and serving the page
from http://localhost:PORT also makes its Origin one the server's allowlist accepts by default,
so this exercises the real CORS path rather than the file:// (Origin: null) one.
"""
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import os

PAGE = os.environ.get(
    "DASH_PAGE",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "dashboard", "index.html"),
)
UPSTREAM = os.environ.get("MCP_UPSTREAM", "http://192.168.1.50:8765")
PORT = int(os.environ.get("DASH_PORT", "9912"))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _page(self):
        body = open(PAGE, "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _proxy(self, method):
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else None
        hdrs = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length")}
        req = urllib.request.Request(UPSTREAM + self.path, data=body, method=method, headers=hdrs)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                status, out, ct = r.status, r.read(), r.headers.get("Content-Type", "application/json")
        except urllib.error.HTTPError as e:
            status, out, ct = e.code, e.read(), e.headers.get("Content-Type", "text/plain")
        except Exception as e:
            status, out, ct = 502, str(e).encode(), "text/plain"
        self.send_response(status)
        self.send_header("Content-Type", ct)
        # The page is same-origin with this proxy, so no CORS headers are needed here — the point
        # is to exercise the dashboard's rendering, not the device's CORS (verified separately).
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def do_GET(self):
        if self.path.startswith("/media") or self.path.startswith("/mcp"):
            self._proxy("GET")
        else:
            self._page()

    def do_POST(self):
        self._proxy("POST")

    def do_OPTIONS(self):
        self._proxy("OPTIONS")

    def log_message(self, *a):
        pass


ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
