# androidmcp

An on-device **Model Context Protocol (MCP) server that runs on an Android phone or
tablet**, exposing the device's own capabilities — camera, microphone, files,
sensors, location, notifications, screen, SMS and more — as MCP tools to LLM clients
(Claude Code, opencode, Claude Desktop, or any MCP-capable tooling).

Its reason for existing is the **permission model**: an LLM that can drive a phone's
camera, mic and files is a remotely-controllable surveillance surface, so every
capability ships **off** and is unlocked only by explicit, layered consent — and when
something is blocked, the server tells the model *exactly* what to turn on.

**Nothing is required to be on.** The server and every capability are OFF by default —
you enable only the features you want a client to have, and each one states exactly what
it exposes and the risk, right where you toggle it. The only things you must set up to
connect at all are the server switch and a client token.

> **Status: feature-complete and device-verified.** All 16 capabilities, the double
> gate, per-call approval, token auth, the config UI, and the installer are built and
> tested on real hardware (a Samsung Galaxy A03s and a Unisoc tablet), including live
> cross-machine connections over **LAN** and **Tailscale**. Not yet production-hardened
> — see [Caveats](#caveats).

---

## Requirements

**To build:**
- **JDK 17+** (JDK 21 is fine).
- An **Android SDK** with platforms 34/35 and build-tools (e.g. `~/Android/Sdk`). Set
  `ANDROID_HOME`, or put `sdk.dir=/path/to/Android/Sdk` in `local.properties`.
- **adb** on your `PATH` (to install / test).
- The Gradle wrapper is committed — no separate Gradle install needed.

**To run (on the device):**
- Android **8.0+ (API 26)**; built against compileSdk 35, **targetSdk 33**.
- **Sideloaded** (not from Play) — some capabilities use restricted permissions that
  Play policy would forbid; sideloading sidesteps that. On Android 13+ you may need to
  tap *App info → ⋮ → Allow restricted settings* before granting Notification/
  Accessibility access, and (Samsung) exclude the app from *Device Care → Sleeping apps*.

**To connect a client remotely:**
- **Tailscale** on both the device and the client machine (recommended), **or**
- both on the **same LAN**, **or**
- `adb forward` for a purely local test.

---

## The double gate (default-deny, checked at every call)

A tool call succeeds only when **all** applicable gates pass, re-evaluated on every call
— so flipping a toggle off, or revoking an OS permission, fails the very next call.

1. **In-app toggle** — you explicitly enable the capability in the app. Off by default.
2. **OS runtime permission** — the backing `android.permission.*` is currently granted.
3. **Situational** — special access (Notification Listener, all-files), a live
   MediaProjection session, the app being foregrounded, or **per-call approval**.

> **Per-call approval is required for high-impact tools.** The toggle is a *setup-time*
> control; it does nothing to stop a prompt-injected LLM abusing an *already-enabled*
> capability. Camera, mic, screenshot, location, SMS/call-log, clipboard-read and
> run-shortcut therefore raise an **Allow / Deny notification** the human must approve
> (60 s timeout → deny), or an "armed for N minutes" window.

### When a call is blocked

Tools are **always listed** (never hidden) so the model can discover a capability and
explain the fix. A blocked call returns a normal result with `isError: true` plus a
machine-readable `structuredContent` naming the exact toggle, permission, and fix.
Example (camera disabled):

```json
{
  "content": [{ "type": "text", "text": "Camera is disabled. Enable ‘Capabilities → Camera’ and grant the Android CAMERA permission, then retry." }],
  "structuredContent": {
    "status": "capability_disabled", "capability": "camera",
    "gate_failed": "app_toggle", "app_toggle": "Capabilities → Camera", "app_toggle_enabled": false,
    "os_permission": "android.permission.CAMERA", "os_permission_granted": false, "retriable": true
  },
  "isError": true
}
```

Reason codes: `FEATURE_DISABLED_IN_APP`, `OS_PERMISSION_NOT_GRANTED`,
`OS_PERMISSION_PERMANENTLY_DENIED`, `SPECIAL_ACCESS_NOT_ENABLED`,
`RESTRICTED_SETTINGS_BLOCK`, `REQUIRES_FOREGROUND`, `REQUIRES_PER_SESSION_CONSENT`,
`REQUIRES_USER_APPROVAL`, `HARDWARE_UNAVAILABLE`, `NOT_SUPPORTED_WITHOUT_ROOT`.

---

## Capabilities

All default-OFF except `list_capabilities`. The 16 below need **no root**; two optional
**elevated** tools (Shizuku *or* root) are covered under
[Root vs non-root](#root-vs-non-root). All device-verified.

| Tool | Does | Backing permission / access | High-impact |
|---|---|---|:---:|
| `list_capabilities` | Report every capability's gate state | none | |
| `device_info` | Model, OS, RAM, uptime (no IMEI/serial) | none | |
| `battery_status` | Level, charging, health, temperature | none | |
| `read_sensors` | Accelerometer, light, proximity, magnetometer | none | |
| `get_location` | Current / last-known location | `ACCESS_FINE/COARSE_LOCATION` | ✓ |
| `post_notification` | Post to the shade | `POST_NOTIFICATIONS` | |
| `read_notifications` | List active notifications | Notification Listener access | ✓ |
| `list_files` | Browse + read within granted folders (list, or read by URI) | SAF grant | ✓ |
| `take_photo` | Headless still, front/rear (Camera2) | `CAMERA` | ✓ |
| `record_audio` | Short mic clip (MediaRecorder) | `RECORD_AUDIO` | ✓ |
| `capture_screenshot` | Screen frame (MediaProjection) | screen-share consent | ✓ |
| `read_sms` | Recent received texts | `READ_SMS` | ✓ |
| `read_call_log` | Recent call history | `READ_CALL_LOG` | ✓ |
| `read_clipboard` / `write_clipboard` | Get / set clipboard | none | read ✓ |
| `run_shortcut` | Launch an app by package | none | ✓ |

Photos/audio/screenshots return proper MCP `image`/`audio` content blocks.

---

## Root vs non-root

**Today every capability is non-root** and works on a stock, locked device. But a
non-rooted app hits a hard ceiling — these are surfaced as honest refusals, never faked:

- **Silent screenshots** — `capture_screenshot` needs a per-session consent + a visible
  cast indicator; a truly silent grab is impossible without root.
- **Background camera/mic cold-start** — the OS only allows capture while the app is
  foregrounded / a sensor foreground-service is live (`REQUIRES_FOREGROUND`).
- **Background clipboard reads** — return null unless the app is foregrounded (Android 10+).
- **System-wide input injection** (tap/type into other apps), reading other apps' private
  data, silent `dumpsys` — **not possible** for a normal app.
- **IMEI / serial** — unavailable to non-privileged apps since Android 10.

### Optional elevated tier — Shizuku (no root, no wipe) *or* root

Two opt-in elevated capabilities are implemented: **`root_screenshot`** (silent
`screencap`, no consent prompt or cast indicator) and **`root_shell`** (an arbitrary
shell command — input injection, any-file read, `dumpsys`, `pm`, `settings`, and more).
Same model as everything else: default-off, in-app toggle + **elevated-access detection**
+ per-call approval. On a device with neither Shizuku nor root they appear in `tools/list`
but return **`NOT_SUPPORTED_WITHOUT_ROOT`** (`retriable:false`) — verified, so nothing
silently changes.

The tier runs on **either** of two backends, whichever is present (checked live at call
time; Shizuku preferred):

- **Shizuku — non-destructive, no root, no bootloader unlock, no wipe.** Shizuku runs a
  privileged process as **uid 2000 (`shell`)** — the same identity `adb shell` has — that
  you start **once over ADB** (`adb shell sh .../start.sh`, or via Android 11+ wireless
  debugging with no PC at all). The app talks to it over a binder and inherits shell-level
  power. It survives until reboot; re-run the one-liner after each boot (or automate it).
  This is the recommended path here because it needs **no unlock and destroys no data.**
- **Magisk root — full uid 0.** If the device is actually rooted, the same two tools use
  `su -c` instead. The first call triggers Magisk's one-time superuser prompt.

**Grant it in-app:** *Setup & reliability* shows an *Elevated tier* line — once Shizuku is
running it becomes a **"Grant Shizuku"** button; after you approve, the two tools light up.

**Does this root the phone too?** Shizuku works on the **Samsung A03s phone as well** — it
only needs USB/wireless debugging, which the locked phone has, so `root_screenshot` and
`root_shell` become available on it *without* rooting or unlocking. **But Shizuku is not
root.** It is uid 2000 (`shell`), not uid 0. It gives the phone exactly ADB's level of
access — a big step up from a normal app, but strictly below true root. So on the phone
this is "partial root" in the literal sense: shell privilege, not superuser.

#### What you still **cannot** do without *full* root (uid 0)

Even with Shizuku's shell (uid 2000) granted, these remain impossible — they need real
root, which on this hardware means unlocking the bootloader (**a full data wipe**) and
flashing Magisk:

- **Read or write another app's private data** (`/data/data/<pkg>/…`) — shell can't enter
  other apps' sandboxes; only uid 0 (or the app itself) can.
- **Read protected partitions / raw storage** — `/data` userdata, another app's
  `databases/`, keystore-backed material, most of `/proc/<pid>` for other apps.
- **Remount `/system` or modify system/vendor partitions**, install a system (privileged)
  app, or change SELinux enforcing state.
- **`IMEI` / hardware serial** — gated behind `READ_PRIVILEGED_PHONE_STATE`, a
  signature/privileged permission; shell can't hold it, so even Shizuku can't read them.
- **Truly persistent, boot-surviving elevation** — Shizuku itself dies on reboot and must
  be restarted over ADB; only a rooted `su` daemon comes back automatically.
- **Grant itself arbitrary runtime/special permissions beyond what `shell` may grant** —
  `pm grant` works for normal dangerous perms, but not for signature/privileged ones.

In short: **Shizuku ≈ everything `adb shell` can do, forever-until-reboot, with no PC and
no wipe. Full root ≈ everything, full stop — but on locked US/Canada Samsungs it's
unavailable at all, and on unlockable devices it costs a factory reset.** For this project
the Shizuku path deliberately trades that last increment of power for keeping the device
and its data intact.

---

## Build & install

```bash
# build a debug APK
./gradlew assembleDebug

# install to a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or the one-shot installer (checks prerequisites, builds, installs to every connected
device; `-s <serial>` for one, `-r` for a release build, `-b` to build only):

```bash
./scripts/build-and-install.sh
```

---

## Connecting a client

In the app: flip **Server** on, pick a **bind** (loopback / lan / tailscale), press
**Generate token**, and enable the capabilities you want. Then point a client at
`http://<host>:8765/mcp` with the bearer token. Three verified paths:

**Local (bind: loopback) — a laptop test with the device on USB:**
```bash
adb forward tcp:8765 tcp:8765
claude mcp add --transport http phone http://127.0.0.1:8765/mcp --header "Authorization: Bearer <TOKEN>"
```

**LAN (bind: lan) — client and device on the same network:**
```bash
# app shows e.g. "listening on 192.168.15.123:8765"
claude mcp add --transport http tablet http://192.168.15.123:8765/mcp --header "Authorization: Bearer <TOKEN>"
```

**Tailscale (bind: tailscale) — from anywhere, WireGuard-encrypted (recommended):**
```bash
# app shows the device's 100.x tailnet address
claude mcp add --transport http tablet http://100.127.216.3:8765/mcp --header "Authorization: Bearer <TOKEN>"
```

```jsonc
// opencode  (discriminator is "remote")
{ "mcp": { "tablet": { "type": "remote", "url": "http://<host>:8765/mcp",
                       "headers": { "Authorization": "Bearer <TOKEN>" } } } }
```

Claude Desktop / claude.ai cloud connectors dial from Anthropic's servers and need a
public URL, so reach the device through a **local `mcp-remote` stdio bridge** on a
tailnet-connected machine rather than exposing it publicly.

---

## Security notes

- **Default-deny**, gate re-checked at call time; config lives only in the local UI —
  no MCP tool can enable a capability, mint a token, or widen the bind interface.
- Bind to **loopback** or the **Tailscale** interface; `lan` binds `0.0.0.0` and is the
  warned option. On Tailscale the hop is already WireGuard-encrypted; the bearer token is
  defence-in-depth + client attribution. (Optional TLS is designed but not yet wired.)
- Tokens are stored **hashed** (SHA-256, never plaintext); `allowBackup=false`;
  CSPRNG-generated. Every tool call is written to an in-app **audit log**.

---

## Caveats

Not yet production-hardened: the "armed for N minutes" approval window has backend
support but no UI toggle; media returns inline base64 rather than `resource_link`; TLS
is designed but not wired; the capability registry is static (not yet auto-hidden per
hardware). Before a first real client, pin the transport to the live MCP spec at
`modelcontextprotocol.io` — the server was verified with `curl` (spec-compatible).

---

## Roadmap

- [x] Scaffold + green build; core (gate, config, hashed tokens, audit)
- [x] Ktor foreground-service server, MCP JSON-RPC over Streamable HTTP, bearer auth
- [x] All 16 capabilities (see table) — every one device-verified
- [x] Per-call approval; Compose config UI; Setup & reliability card; installer
- [x] Bind selector; verified over adb-forward, LAN, and Tailscale
- [x] Optional root tier (`root_screenshot`, `root_shell`) — gated to
      `NOT_SUPPORTED_WITHOUT_ROOT` until rooted; verified refusing on a stock device
- [ ] "Armed window" UI, `resource_link` media, optional TLS, hardware-aware registry

---

## Scope

A personal, **sideloaded** app for the maintainer's own devices. Because it's not
Play-distributed, Play policy is not a design constraint — but default-deny and honest
disclosure are kept as the actual safety story. Not intended for Play distribution as-is.
