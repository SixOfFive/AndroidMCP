# androidmcp

An on-device **Model Context Protocol (MCP) server that runs on an Android phone or
tablet**, exposing the device's own capabilities — camera, microphone, files, sensors,
location, notifications, screen, SMS and more — as MCP tools to LLM clients (Claude Code,
opencode, the MCP Inspector, or any MCP-capable tooling).

Its reason for existing is the **permission model**. An LLM that can drive a phone's camera,
mic and files is a remotely-controllable surveillance surface — so every capability ships
**off**, is unlocked only by explicit, layered consent, and high-impact actions ask for a tap
on the device *every time*. When something is blocked, the server tells the model exactly what
to turn on.

> **Status: feature-complete and device-verified.** 52 tools, a default-deny double gate,
> per-call approval, hashed bearer tokens with per-token capability scoping, optional
> self-signed TLS, optional remote approval via MCP elicitation, and a Compose config UI — all
> built and tested on real hardware (a Samsung phone and a Unisoc tablet) over **LAN** and
> **Tailscale**. The JSON-RPC and HTTP layers are covered by **185 JVM unit tests**, and the
> server is driven end to end by **three real MCP clients**: the official MCP Python SDK, Claude
> Code, and the MCP Inspector.

This is a **sideloaded** app for personal use — it is not on the Play Store (some capabilities
use permissions Play policy forbids). Full build, design and roadmap detail lives in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

---

## Install

**Download a build (no compiler needed).** Grab the latest signed APK from the
[Releases page](https://github.com/SixOfFive/AndroidMCP/releases/latest) and sideload it:

```bash
adb install -r androidmcp-0.3.0.apk
```

Optionally verify it carries the project's release signature before installing:

```bash
apksigner verify --print-certs androidmcp-0.3.0.apk
# SHA-256: D6:27:15:B5:E9:DB:AB:C6:7E:71:AC:D2:16:90:17:21:44:95:C8:89:7E:02:86:7B:93:E0:16:B5:83:32:89:EE
```

It runs on **Android 8.0+ (API 26)** and works fully on a stock, locked device — **no root
required**. (An optional elevated tier can use Shizuku or root if you want it; see below.)

**Or build from source** — see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md):

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## The double gate (default-deny, checked at every call)

A tool call succeeds only when **all** applicable gates pass, re-evaluated on every call — so
flipping a toggle off, or revoking an OS permission, fails the very next call.

1. **In-app toggle** — you explicitly enable the capability in the app. Off by default.
2. **OS runtime permission** — the backing `android.permission.*` is currently granted.
3. **Situational** — special access (Notification Listener, shared folders), a live
   MediaProjection session, the app being foregrounded, or **per-call approval**.

> **Per-call approval is required for high-impact tools.** The toggle is a *setup-time*
> control; it does nothing to stop a prompt-injected LLM abusing an *already-enabled*
> capability. Camera, mic, screenshot, location, SMS/call-log, clipboard-read and run-shortcut
> therefore raise an **Allow / Deny notification** a human must approve (25 s timeout → deny),
> or an "armed for N minutes" window.

> **Remote approval (optional, off by default).** Turning on **Remote approval (elicitation)**
> lets you answer that Allow / Deny in your MCP client (via MCP
> [elicitation](https://modelcontextprotocol.io/specification/2025-06-18/client/elicitation))
> instead of reaching for the phone — the on-device prompt still appears, and whichever you
> answer first wins. The trade-off is deliberate: it means a *connected client* can approve its
> own calls, so the phone stops being the only thing that can. Leave it off unless you trust the
> client, and only clients that advertise the elicitation capability are ever asked.

Tools are **always listed** (never hidden), so a model can discover a capability and explain
the fix. A blocked call returns a normal result with `isError: true` plus machine-readable
`structuredContent` naming the exact toggle, permission and remediation — with a stable
`reason_code` (`FEATURE_DISABLED_IN_APP`, `OS_PERMISSION_NOT_GRANTED`,
`SPECIAL_ACCESS_NOT_ENABLED`, `REQUIRES_USER_APPROVAL`, `HARDWARE_UNAVAILABLE`,
`NOT_SUPPORTED_WITHOUT_ROOT`, and more).

---

## Capabilities

All default-OFF except `list_capabilities`. The 47 below need **no root**; five optional
**elevated** tools (Shizuku *or* root) are covered under [Root vs non-root](#root-vs-non-root).
All device-verified. Tools whose hardware is absent (e.g. `dial` on a Wi-Fi-only tablet) are
auto-marked unavailable and refuse with `HARDWARE_UNAVAILABLE`.

| Tool | Does | Backing permission / access | High-impact |
|---|---|---|:---:|
| `list_capabilities` | Report every capability's gate state | none | |
| `device_info` | Model, OS, RAM, uptime (no IMEI/serial) | none | |
| `battery_status` | Level, charging, health, temperature | none | |
| `read_sensors` | Accelerometer, light, proximity, magnetometer | none | |
| `get_location` | Current / last-known location (optional reverse-geocode) | `ACCESS_FINE/COARSE_LOCATION` | ✓ |
| `post_notification` | Post to the shade | `POST_NOTIFICATIONS` | |
| `read_notifications` | List active notifications (with keys + action buttons) | Notification Listener access | ✓ |
| `notification_action` | Reply to / tap an action button on a notification | Notification Listener access | ✓ |
| `list_files` | Browse + read within granted folders (list, or read by URI) | SAF grant (read-only) | ✓ |
| `write_file` | Create / overwrite a file in a granted **writable** folder | SAF read+write grant | ✓ |
| `take_photo` | Headless still, front/rear (Camera2) | `CAMERA` | ✓ |
| `record_audio` | Short mic clip (MediaRecorder) | `RECORD_AUDIO` | ✓ |
| `capture_screenshot` | Screen frame (MediaProjection) | screen-share consent | ✓ |
| `read_screen` | Structured on-screen content (accessibility node tree, no root) | Accessibility access | ✓ |
| `global_action` | Navigate: back / home / recents / notifications / quick-settings / lock / screenshot | Accessibility access | ✓ |
| `tap` | Tap a screen coordinate (no root) | Accessibility access | ✓ |
| `swipe` | Swipe / scroll between two points (no root) | Accessibility access | ✓ |
| `type_text` | Type into the focused field (no root) | Accessibility access | ✓ |
| `read_sms` | Recent received texts | `READ_SMS` | ✓ |
| `read_call_log` | Recent call history | `READ_CALL_LOG` | ✓ |
| `read_clipboard` / `write_clipboard` | Get / set clipboard | none | read ✓ |
| `run_shortcut` | Launch an app by package | none | ✓ |
| `wifi_info` | Wi‑Fi signal (RSSI/level), link speed, frequency, SSID | `ACCESS_WIFI_STATE` (install-time) | |
| `network_info` | Active transport, connected/metered, carrier | none | |
| `telephony_info` | Operator, SIM state, roaming, data state, signal level, country | none | |
| `bluetooth_info` | Adapter presence, on/off, BLE support (no paired-device list) | none | |
| `storage_info` | Internal/external total, free, used | none | |
| `thermal_status` | Thermal status + headroom | none | |
| `screen_info` | Resolution, density, refresh, rotation, timeout | none | |
| `volume_info` | Per-stream volumes + ringer mode | none | |
| `locale_info` | Language, region, timezone, 24h setting, local time | none | |
| `dnd_status` | Do Not Disturb / interruption filter + policy access | none | |
| `torch` | Toggle the camera flash LED | none | |
| `vibrate` | Buzz for N ms | `VIBRATE` (install-time) | |
| `list_packages` | Installed apps (label + package) | `QUERY_ALL_PACKAGES` | |
| `launch_url` | Open a URL (ACTION_VIEW) | none | ✓ |
| `dial` | Pre-fill the dialer (does not call) | none | ✓ |
| `get_contacts` | Look up contacts (name + numbers) | `READ_CONTACTS` | ✓ |
| `read_calendar` | Upcoming calendar events | `READ_CALENDAR` | ✓ |
| `create_calendar_event` | Insert a calendar event | `READ_CALENDAR` + `WRITE_CALENDAR` | ✓ |
| `set_volume` | Set a stream's volume | `MODIFY_AUDIO_SETTINGS` (install-time) | ✓ |
| `media_control` | Send a media key (play/pause/next…) | none | ✓ |
| `toast` | Show a toast on screen | none | |
| `speak` | Read text aloud (text-to-speech) | none | |
| `share_text` | Open the share sheet with text | none | ✓ |
| `open_settings` | Open a Settings screen | none | |

Photos/audio/screenshots return proper MCP `image`/`audio` content blocks — or, with **Media as
links** on, a short-lived `resource_link` URL the client fetches (an unguessable one-time key,
10-minute TTL) instead of multi-MB inline base64.

> **Approvals need notifications.** The high-impact tools (✓) prompt for per-call approval via a
> notification. On Android 13+ that needs the `POST_NOTIFICATIONS` runtime permission — the app
> requests it on launch; if you decline, high-impact calls block until they time out (deny).

---

## Root vs non-root

**Every capability above is non-root** and works on a stock, locked device. A non-rooted app
hits a hard ceiling — surfaced as honest refusals, never faked: truly silent screenshots,
background camera/mic cold-start, background clipboard reads, system-wide input injection, and
IMEI/serial are all unavailable to a normal app.

An **optional elevated tier** adds five tools — `root_screenshot` (silent capture),
`root_shell` (arbitrary shell command), `elevated_input` (system-wide tap/swipe/text/key),
`elevated_current_app` (true foreground app), and `elevated_settings` (read/write
system/secure/global settings). They run on **either** backend, checked live (Shizuku
preferred):

- **Shizuku** — non-destructive, no root, no wipe. A privileged process running as uid 2000
  (`shell`, the same identity `adb shell` has) that you start once over ADB. Gives the app
  shell-level power without unlocking or rooting.
- **Magisk root** — full uid 0, if the device is actually rooted.

On a device with neither, these appear in `tools/list` but refuse with
`NOT_SUPPORTED_WITHOUT_ROOT`. Same model as everything else: default-off, in-app toggle +
elevated-access detection + per-call approval. Full detail in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

---

## Connecting a client

In the app: flip **Server** on, pick a **bind** (loopback / lan / tailscale), press **Generate
token**, and enable the capabilities you want. Then point a client at `http://<host>:8765/mcp`
with the bearer token.

```bash
# Local — device on USB, via adb forward
adb forward tcp:8765 tcp:8765
claude mcp add --transport http phone http://127.0.0.1:8765/mcp --header "Authorization: Bearer <TOKEN>"

# LAN — client and device on the same network (app shows the address)
claude mcp add --transport http phone http://<device-lan-ip>:8765/mcp --header "Authorization: Bearer <TOKEN>"

# Tailscale — from anywhere, WireGuard-encrypted (recommended; app shows the 100.x address)
claude mcp add --transport http phone http://<device-tailnet-ip>:8765/mcp --header "Authorization: Bearer <TOKEN>"
```

```jsonc
// opencode (discriminator is "remote")
{ "mcp": { "phone": { "type": "remote", "url": "http://<host>:8765/mcp",
                      "headers": { "Authorization": "Bearer <TOKEN>" } } } }
```

**MCP Inspector** (the reference conformance client) needs Node ≥ 22.19:

```bash
npx @modelcontextprotocol/inspector --cli http://<host>:8765/mcp --transport http \
  --header "Authorization: Bearer <TOKEN>" --method tools/list
```

Claude Desktop needs the `mcp-remote` stdio bridge (its config accepts only stdio servers) —
see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

---

## Security notes

- **Default-deny**, gate re-checked at call time. Config lives only in the local UI — no MCP
  tool can enable a capability, mint a token, or widen the bind interface.
- **`list_files` is confined to the folders you shared.** `file://` and every other scheme are
  refused outright; for a `content://` document the owning provider decides containment
  (`DocumentsContract.isChildDocument`), and the grant is re-checked on every read, so revoking
  it in Settings takes effect immediately.
- **Bind to loopback or Tailscale**; `lan` binds `0.0.0.0` and is the warned option. On
  Tailscale the hop is already WireGuard-encrypted. **Optional HTTPS** (self-signed, persistent
  RSA-2048/SHA-256, 10-year cert with an IP SAN per reachable address) can be toggled on — pin
  the SHA-256 the app shows.
- **Tokens are stored hashed** (SHA-256, never plaintext, CSPRNG-generated), can be scoped to a
  subset of capabilities and given an expiry, and `allowBackup=false`. Every call is written to
  an in-app **audit log**.

---

## Requirements

- **To run:** Android 8.0+ (API 26). Sideloaded; built against compileSdk 35, targetSdk 33.
- **To connect remotely:** Tailscale on both ends (recommended), the same LAN, or `adb forward`
  for a purely local test.
- **To build:** JDK 17+ (built with 21), an Android SDK with platforms 34/35, and `adb`. The
  Gradle wrapper is committed. See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

---

## Scope

A personal, sideloaded app for the maintainer's own devices. Because it isn't Play-distributed,
Play policy isn't a design constraint — but default-deny and honest disclosure are kept as the
real safety story. The full engineering log, design decisions (including what was deliberately
*not* done), and the v1/v2/v3 roadmap are in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).
