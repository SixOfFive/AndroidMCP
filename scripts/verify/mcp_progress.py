"""Watch `notifications/progress` arrive DURING the human-approval wait.

22 of the 40 tools block on a human tapping "Allow" for up to 25 s. This calls one of them with a
progress callback and timestamps every notification, so the question "does the client hear anything
while it waits" gets an answer with numbers on it rather than a shrug.

    export MCP_TOKEN=<the token the app shows once, on mint>
    export MCP_URL=http://127.0.0.1:8765/mcp        # adb forward tcp:8765 tcp:8765
    uv run --quiet --with mcp python scripts/verify/mcp_progress.py [tool] [seconds]

Leave the prompt untouched to watch the deny-by-timeout path; tap Allow to watch the whole thing.
"""
import contextlib
import json
import os
import sys
import time

import anyio
from mcp import ClientSession
import mcp.client.streamable_http as sh

URL = os.environ.get("MCP_URL", "http://127.0.0.1:8765/mcp")
TOKEN = os.environ.get("MCP_TOKEN") or sys.exit("set MCP_TOKEN")
TOOL = sys.argv[1] if len(sys.argv) > 1 else "read_clipboard"

# The SDK renamed this and changed its signature between releases: the old `streamablehttp_client`
# took headers=, the new `streamable_http_client` takes a prebuilt http_client (on httpx2), and it
# yields 2 values instead of 3. Accept either so this keeps working across an SDK bump.
_new = getattr(sh, "streamable_http_client", None)


@contextlib.asynccontextmanager
async def connect(url, headers):
    if _new is not None:
        import httpx2
        async with httpx2.AsyncClient(headers=headers, timeout=120) as hc:
            async with _new(url, http_client=hc) as streams:
                yield streams
    else:
        async with sh.streamablehttp_client(url, headers=headers) as streams:
            yield streams


async def main():
    t0 = time.monotonic()

    def stamp():
        return f"{time.monotonic() - t0:6.2f}s"

    seen = []

    async def on_progress(progress, total, message):
        seen.append((time.monotonic() - t0, progress, message))
        print(f"[{stamp()}] progress={progress:<8.3f} total={total} message={message!r}", flush=True)

    async with connect(URL, {"Authorization": f"Bearer {TOKEN}"}) as streams:
        async with ClientSession(streams[0], streams[1]) as s:
            init = await s.initialize()
            # The SDK's result models are snake_case; older ones were camelCase.
            info = getattr(init, "server_info", None) or getattr(init, "serverInfo", None)
            proto = getattr(init, "protocol_version", None) or getattr(init, "protocolVersion", "?")
            print(f"[{stamp()}] initialized: {info.name} protocol={proto}", flush=True)
            print(f"[{stamp()}] calling {TOOL!r} — approve or ignore the prompt on the device",
                  flush=True)
            res = await s.call_tool(TOOL, {}, progress_callback=on_progress)
            text = "".join(getattr(c, "text", "") for c in res.content)
            is_error = getattr(res, "is_error", getattr(res, "isError", None))
            print(f"[{stamp()}] result isError={is_error} {text[:300]}", flush=True)

    print("\n--- summary ---")
    print(f"notifications: {len(seen)}")
    if seen:
        gaps = [round(b[0] - a[0], 2) for a, b in zip(seen, seen[1:])]
        print(f"first at {seen[0][0]:.2f}s, gaps between: {gaps}")
        print(f"messages: {sorted({m for _, _, m in seen})}")
        values = [p for _, p, _ in seen]
        print(f"progress strictly increasing: {all(b > a for a, b in zip(values, values[1:]))}")
    else:
        print("NONE — the client heard nothing during the wait")


anyio.run(main)
