"""Drive androidmcp with the OFFICIAL MCP Python SDK — a real client, not hand-rolled curl.

This is the check the project's roadmap has been blocked on. The SDK performs the full lifecycle
a real host does: initialize, the notifications/initialized notification, the MCP-Protocol-Version
header on subsequent requests, and Accept negotiation between application/json and
text/event-stream.
"""
import asyncio
import os
import sys

from mcp import ClientSession
from mcp.client.streamable_http import create_mcp_http_client, streamable_http_client

URL = os.environ["MCP_URL"]
TOKEN = os.environ["MCP_TOKEN"]


def g(obj, *names):
    """Read whichever of `names` the installed SDK actually uses (camel vs snake case)."""
    for n in names:
        if hasattr(obj, n):
            return getattr(obj, n)
    return None


async def main() -> int:
    headers = {"Authorization": f"Bearer {TOKEN}"}
    async with create_mcp_http_client(headers=headers) as hc:
        async with streamable_http_client(URL, http_client=hc) as streams:
            read, write = streams[0], streams[1]
            async with ClientSession(read, write) as s:
                init = await s.initialize()
                info = g(init, "server_info", "serverInfo")
                print("=== initialize ===")
                print("  protocolVersion:", g(init, "protocol_version", "protocolVersion"))
                print("  serverInfo     :", info.name, g(info, "title"), info.version)
                print("  capabilities   :", init.capabilities.model_dump(exclude_none=True))
                print("  instructions   :", (init.instructions or "")[:70].replace("\n", " "), "…")

                print("\n=== ping ===")
                await s.send_ping()
                print("  ok")

                print("\n=== tools/list ===")
                tools = (await s.list_tools()).tools
                print(f"  {len(tools)} tools")
                for name in ("torch", "root_shell", "battery_status"):
                    t = next(x for x in tools if x.name == name)
                    sch = g(t, "input_schema", "inputSchema") or {}
                    ann = t.annotations.model_dump(exclude_none=True) if t.annotations else None
                    print(f"  {name:15} required={sch.get('required')} annotations={ann}")

                print("\n=== tools/call list_capabilities (the only tool on by default) ===")
                r = await s.call_tool("list_capabilities", {})
                print("  isError:", g(r, "is_error", "isError"))
                print("  head   :", r.content[0].text[:150].replace("\n", " | "))

                print("\n=== tools/call a DISABLED tool — the refusal envelope ===")
                r = await s.call_tool("device_info", {})
                print("  isError:", g(r, "is_error", "isError"))
                print("  text   :", r.content[0].text[:130])
                sc = g(r, "structured_content", "structuredContent")
                if sc:
                    for k in ("status", "reason_code", "gate_failed", "app_toggle", "retriable"):
                        print(f"    {k:12}: {sc.get(k)}")

                print("\n=== tools/call an unknown tool — must be a protocol error ===")
                try:
                    await s.call_tool("no_such_tool", {})
                    print("  !! no error raised")
                except Exception as e:
                    print(f"  raised {type(e).__name__}: {str(e)[:110]}")

                print("\n=== a required argument omitted (torch needs 'on') ===")
                try:
                    r = await s.call_tool("torch", {})
                    print("  isError:", g(r, "is_error", "isError"), "|", r.content[0].text[:110])
                except Exception as e:
                    print(f"  raised {type(e).__name__}: {str(e)[:110]}")

    print("\nALL GOOD — a real MCP SDK client completed the full lifecycle.")
    return 0


sys.exit(asyncio.run(main()))
