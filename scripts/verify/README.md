# Verification tools

Three throwaway-looking scripts that each caught something the unit tests could not. Kept because
the findings were only reachable by driving the running server, and re-deriving them took a while.

All of them read the bearer token from `MCP_TOKEN` — none stores a credential.

```bash
export MCP_URL=http://192.168.15.123:8765/mcp
export MCP_TOKEN=<the token the app shows once, on mint>
```

## `mcp_probe.py` — drive the server with the official MCP SDK

```bash
uv run --quiet --with mcp python scripts/verify/mcp_probe.py
```

A real client, not curl. Runs the full lifecycle: `initialize` (version negotiation, `title`,
`instructions`), `notifications/initialized`, `ping`, `tools/list` (checking `required` and
`annotations` deserialise into the SDK's own typed models), `tools/call`, an unknown tool surfacing
as a raised error, and a required argument omitted.

**Why it exists:** curl cannot send a notification, cannot probe version negotiation, and cannot
decline to dereference a `resource_link`. "Verified with curl" gave false confidence in exactly the
areas that were broken. This found two defects in ten minutes that 71 unit tests had not — argument
errors reported as `isError:false`, and `gate_failed` contradicting `retriable`.

Note the SDK renamed `streamablehttp_client` → `streamable_http_client`, and its result models are
snake_case.

## `mcp_tap.py` — log what a client actually sends

```bash
python3 scripts/verify/mcp_tap.py http://192.168.15.123:8765 tap.log
# then point a client at http://127.0.0.1:9911/mcp
```

A logging reverse proxy: request line, headers, body, response status. Neither side's logs show the
handshake, so this is the only way to see it.

**What it revealed:** Claude Code 2.1.251 does a *dual-era* handshake — `POST server/discover` with
`mcp-protocol-version: 2026-07-28` first, and on a 4xx falls back to `initialize` requesting
`2025-11-25` with **no** version header, because negotiation lives in the body. That is why the
server must answer the probe with **400, not 404**, and why the version-header check must never
apply to `initialize`.

## `dash_serve.py` — drive the browser dashboard against a device

```bash
python3 scripts/verify/dash_serve.py     # then open http://127.0.0.1:9912/
```

Serves `dashboard/index.html` from localhost and proxies `/mcp` and `/media` to the device.
Overridable via `MCP_UPSTREAM`, `DASH_PORT`, `DASH_PAGE`.

**Why it exists:** the in-app preview pane refuses LAN requests (`ERR_BLOCKED_BY_CLIENT`), but
localhost is allowed — so this is the only way to put the real page in front of the real server.
It confirmed the dashboard fixes after the protocol changes silently broke it (every capability
showing as disabled, refusals rendering three times over).

## A note on smoke tests

**One request always works.** The `@Sharable` failure that made minification unshippable only
appears on the *second* TLS request, and the app reports the server as running throughout. Any
"does it still work" check against this server needs at least two requests.
