# androidmcp

An on-device **Model Context Protocol (MCP) server that runs on an Android phone or
tablet**, exposing the device's own capabilities — camera, files, sensors, location,
notifications and more — as MCP tools to LLM clients (Claude Code, opencode,
Claude Desktop, or any MCP-capable tooling).

Its whole reason for existing is the **permission model**: an LLM that can drive a
phone's camera, mic and files is a remotely-controllable surveillance surface, so
every capability ships **off** and is unlocked only by explicit, layered consent —
and when something is blocked, the server tells the model *exactly* what to turn on.

> **Status: early, actively being built.** The project scaffolds and builds; the
> gate engine, MCP server and MVP capabilities are landing incrementally (see
> [Roadmap](#roadmap)). Nothing here is production-hardened yet.

---

## The double gate (default-deny, checked at every call)

A tool call succeeds only when **all** applicable gates pass, re-evaluated on every
call — so flipping a toggle off, or revoking an OS permission, fails the very next
call with a precise reason.

1. **In-app toggle** — you explicitly enable the capability in the app. Off by default.
2. **OS runtime permission** — the backing `android.permission.*` is currently granted.
3. **Situational** — special access (Notification Listener, all-files), a live
   MediaProjection session, the app being foregrounded, or **per-call approval** for
   high-impact tools.

> **Per-call approval is not optional for high-impact tools.** The toggle is a
> *setup-time* control; it does nothing to stop a prompt-injected LLM abusing an
> *already-enabled* capability (the classic confused-deputy problem). Camera, mic,
> screenshot, location, SMS/call-log, whole-filesystem and run-shortcut therefore
> require either a per-call Approve/Deny prompt or a deliberate, time-boxed
> "armed for N minutes" window, plus rate limits.

### When a call is blocked

Tools are **always listed** (never hidden) so the model can discover a capability
and explain the fix. A blocked call returns a normal result with `isError: true`
and a machine-readable payload naming the exact toggle, the exact permission, and how
to fix it — never an opaque protocol error. Example (camera disabled):

```json
{
  "content": [{ "type": "text",
    "text": "Camera is disabled. Enable ‘Capabilities → Camera’ in the app and grant the Android CAMERA permission, then retry." }],
  "structuredContent": {
    "status": "capability_disabled",
    "capability": "camera",
    "gate_failed": "app_toggle",
    "app_toggle": "Capabilities → Camera", "app_toggle_enabled": false,
    "os_permission": "android.permission.CAMERA", "os_permission_granted": false,
    "retriable": true
  },
  "isError": true
}
```

Stable reason codes: `FEATURE_DISABLED_IN_APP`, `OS_PERMISSION_NOT_GRANTED`,
`OS_PERMISSION_PERMANENTLY_DENIED`, `SPECIAL_ACCESS_NOT_ENABLED`,
`RESTRICTED_SETTINGS_BLOCK`, `REQUIRES_FOREGROUND`, `REQUIRES_PER_SESSION_CONSENT`,
`REQUIRES_USER_APPROVAL`, `HARDWARE_UNAVAILABLE`, `NOT_SUPPORTED_WITHOUT_ROOT`.

---

## Capabilities

| Tool | Does | Android permission | Phase |
|---|---|---|---|
| `list_capabilities` | Report every capability's live gate state | none | MVP (always on) |
| `device_info` | Model, OS, RAM, CPU, uptime (no IMEI/serial) | none | MVP |
| `battery_status` | Level, charging, health, temperature | none | MVP |
| `read_sensors` | Accelerometer, magnetometer, light, proximity… | none / `ACTIVITY_RECOGNITION` | MVP |
| `get_location` | Current / last-known location | `ACCESS_FINE/COARSE_LOCATION` | MVP |
| `list_files` / `read_file` | Browse/fetch within granted folders (SAF) | none (SAF) | MVP |
| `post_notification` | Post to the shade | `POST_NOTIFICATIONS` | MVP |
| `read_notifications` | List / dismiss active notifications | Notification Listener access | MVP |
| `take_photo` | Headless still via Camera2 | `CAMERA` | v1.1 |
| `record_audio` | Bounded mic clip | `RECORD_AUDIO` | v1.1 |
| `capture_screenshot` | Screen grab via MediaProjection (not silent) | media-projection consent | v1.1 |
| `read/write_clipboard` | Get/set clipboard (foreground only) | none | v1.1 |
| `read_sms` / `read_call_log` | Read messages / call history | `READ_SMS` / `READ_CALL_LOG` | v1.1 |
| `send_sms` | Send (intent-based + confirm) | `SEND_SMS` / intent | v1.1 |
| `run_shortcut` | Fire an allow-listed Tasker task / intent | none | v1.1 |
| `read_screen_ui` | Read on-screen UI tree | Accessibility | deferred |

Capabilities the hardware lacks are auto-marked unsupported (e.g. no SMS on a
Wi-Fi-only tablet).

### The non-root ceiling (honest limits)

On a non-rooted device these are surfaced as first-class refusals, never faked:
silent screenshots, background camera/mic cold-start, background clipboard reads,
system-wide input injection, and toggling Wi-Fi/Bluetooth/mobile-data/airplane are
**not possible**; IMEI/serial are unavailable.

---

## Build & install

**Prerequisites:** JDK 17+, an Android SDK (platforms 34/35, build-tools), and a
device with USB debugging (or on the same Tailscale network). The Gradle wrapper is
committed; `local.properties` (with `sdk.dir=…`) is not — create it or set
`ANDROID_HOME`.

```bash
# build a debug APK
./gradlew assembleDebug

# install to a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or use the one-shot installer (build + install to whatever is connected):

```bash
./scripts/build-and-install.sh
```

---

## Connecting a client

The app hosts one Streamable-HTTP endpoint, bound to the device's **Tailscale**
interface, and every request needs a bearer token generated in-app.

```bash
# Claude Code
claude mcp add --transport http phone http://<tailnet-ip>:8765/mcp \
  --header "Authorization: Bearer <TOKEN>"
```

```jsonc
// opencode  (discriminator is "remote")
{ "mcp": { "phone": { "type": "remote", "url": "http://<tailnet-ip>:8765/mcp",
                      "headers": { "Authorization": "Bearer <TOKEN>" } } } }
```

Claude Desktop / claude.ai cloud connectors dial from Anthropic's servers and need a
public URL, so reach the phone through a **local `mcp-remote` stdio bridge** on a
tailnet-connected machine rather than exposing it publicly. The app prints ready-to-
paste snippets (and a QR) per token.

---

## Security notes

- **Default-deny**, gate re-checked at call time; config lives only in the local UI —
  no MCP tool can enable a capability, mint a token, or widen the bind interface.
- Bound to the **Tailscale interface only**, never `0.0.0.0`; WireGuard already
  encrypts the hop, and the bearer token is defence-in-depth + client attribution.
  Optional TLS is offered for direct-LAN use.
- Tokens stored **hashed** (never plaintext); `allowBackup=false`; CSPRNG-generated.
- Every tool call is written to an in-app **audit log**.

---

## Transports

Each is a default-off gated feature with a plain "why / why-not":

- **Tailscale** ★ — recommended; encrypted, works over Wi-Fi *and* cellular anywhere.
- **Wi-Fi / LAN** — local-only, fast; leave off on untrusted networks.
- **Cellular public IP** — discouraged/usually impossible (CGNAT + exposure); use Tailscale.
- **Bluetooth** — optional custom transport for offline/proximity only.

---

## Roadmap

- [x] Project scaffold + green build
- [x] Core: gate engine, config store, hashed token store, audit log
- [x] Server: Ktor foreground service, MCP JSON-RPC over Streamable HTTP, bearer auth
- [x] MVP capabilities: `list_capabilities`, `device_info`, `battery_status`,
      `read_sensors`, `get_location`, `post_notification` (working); `read_notifications`
      + `list_files` gated as not-yet-wired
- [x] Compose config UI (master switch, per-capability toggles + *why*, token manager, audit)
- [x] Install & verify against a real MCP client — auth, `tools/list`, an enabled
      call, and a gated structured refusal all confirmed on the device
- [x] Per-call approval manager for high-impact tools — Allow/Deny notification +
      "armed for N minutes" window; verified: `get_location` suspends until approved,
      then returns a real fix
- [ ] Onboarding wizard (restricted-settings, battery-optimization, Device Care)
- [ ] `read_notifications` (Notification Listener) + `list_files` (SAF)
- [x] v1.1 `take_photo` — Camera2 headless capture, returns an MCP image content
      block, approval-gated; verified on device (real 1080×1440 JPEG captured)
- [x] v1.1 `read_sms`, `read_call_log`, `read_clipboard`, `write_clipboard` —
      content-provider reads + clipboard; `read_sms` verified (real messages,
      approval-gated); clipboard read is honestly foreground-limited
- [ ] v1.1 remaining: `record_audio`, `capture_screenshot`, `run_shortcut`
- [x] Installer script (`scripts/build-and-install.sh`) — builds + installs to connected devices

---

## Scope

A personal, **sideloaded** app for the maintainer's own devices (a Samsung Galaxy
A03s and a tablet). Because it's sideloaded rather than Play-distributed, Play Store
policy is not a design constraint — but default-deny and honest disclosure are kept
as the actual safety story. Not intended for Play distribution as-is.
