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

> **Status: feature-complete and device-verified; transport pinned to the MCP spec.** All
> capabilities, the double gate, per-call approval, token auth, the config UI, and the
> installer are built and tested on real hardware (a Samsung Galaxy A03s and a Unisoc tablet),
> including live cross-machine connections over **LAN** and **Tailscale**. The JSON-RPC and
> HTTP layers are covered by **151 JVM unit tests**, and it is **driven end to end by two real MCP
> clients** — the official MCP Python SDK and Claude Code itself. See [Caveats](#caveats).

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

All default-OFF except `list_capabilities`. The 35 below need **no root**; five optional
**elevated** tools (Shizuku *or* root) are covered under
[Root vs non-root](#root-vs-non-root). All device-verified. Tools whose hardware is
absent (e.g. `dial`/`vibrate` on a Wi-Fi-only tablet) are auto-marked unavailable and
refuse with `HARDWARE_UNAVAILABLE`.

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
| `wifi_info` | Wi‑Fi signal (RSSI/level), link speed, frequency, SSID | `ACCESS_WIFI_STATE` (install-time) | |
| `network_info` | Active transport, connected/metered, carrier | none | |
| `storage_info` | Internal/external total, free, used | none | |
| `thermal_status` | Thermal status + headroom | none | |
| `screen_info` | Resolution, density, refresh, rotation, timeout | none | |
| `volume_info` | Per-stream volumes + ringer mode | none | |
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
| `share_text` | Open the share sheet with text | none | ✓ |
| `open_settings` | Open a Settings screen | none | |

Photos/audio/screenshots return proper MCP `image`/`audio` content blocks — or, with
**Media as links** on, a short-lived `resource_link` URL the client fetches (an unguessable
one-time key, 10-minute TTL) instead of multi-MB inline base64.

> **Approvals need notifications.** The high-impact tools (✓) prompt for per-call
> approval via a notification. On Android 13+ that requires the `POST_NOTIFICATIONS`
> runtime permission — the app requests it on launch; if you decline, high-impact calls
> block until they time out (deny). Re-enable it from the app's *Setup & reliability* card
> or Android's App info → Notifications.

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

Three opt-in elevated capabilities are implemented: **`root_screenshot`** (silent
`screencap`, no consent prompt or cast indicator), **`root_shell`** (an arbitrary
shell command — any-file read, `dumpsys`, `pm`, `settings`, and more), and
**`elevated_input`** (system-wide `input` injection — tap / swipe / text / keyevent into
*any* app, which a normal app cannot do), **`elevated_current_app`** (the true foreground
app/activity via `dumpsys`), and **`elevated_settings`** (read/write `system`/`secure`/`global`
settings via `settings get`/`put`, with a read-back confirmation).
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

**Prefer `-r` for anything left running.** A debug build is `debuggable`, so the process is
jdwp-attachable by anything with adb — and these devices keep USB debugging enabled because
Shizuku needs it, which means the debug build lets adb drive the app straight past its own
gate. The release variant is not debuggable. It is signed with the debug key unless you
configure a real one (`ANDROIDMCP_KEYSTORE`, `ANDROIDMCP_KEYSTORE_PASSWORD`,
`ANDROIDMCP_KEY_ALIAS`, `ANDROIDMCP_KEY_PASSWORD` in `~/.gradle/gradle.properties` or the
environment) — keeping the debug signature means it installs over an existing debug build
without an uninstall, which would wipe tokens, folder grants and special-access approvals.

### Tests

The protocol, the HTTP layer, SAF containment, the TLS cert and the tool schemas all run on a
plain JVM — no device or emulator needed:

```bash
./gradlew :app:testDebugUnitTest
```

Anything that needs real hardware (camera, mic, screen capture, the ContentProvider-backed
readers, Shizuku/root) is deliberately **not** in that suite and stays on manual on-device
verification — see [scripts/verify/](scripts/verify/) for the tools that do it: driving the server
with the official MCP SDK, tapping the wire to see what a client really sends, and serving the
dashboard against a device.

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
- **`list_files` is confined to the folders you shared.** `file://` and every other scheme are
  refused outright, and for a `content://` document the **owning provider** decides containment
  (`DocumentsContract.isChildDocument`) rather than this app guessing from the document id — its
  "no" is final. The grant must also still be held: the tree is re-checked against the persisted
  URI permissions on every read, so revoking it in Settings takes effect immediately.
- Bind to **loopback** or the **Tailscale** interface; `lan` binds `0.0.0.0` and is the
  warned option. On Tailscale the hop is already WireGuard-encrypted; the bearer token is
  defence-in-depth + client attribution. **Optional HTTPS** (self-signed, via the Netty
  engine) can be toggled on — mostly useful for a bare-LAN bind, since Tailscale already
  encrypts. The cert is **persistent, RSA-2048/SHA-256, valid 10 years**, and carries an IP
  SAN for every address the device is reachable on (loopback, LAN, tailnet), so pin the
  stable SHA-256 the app shows (or skip verification).
- Tokens are stored **hashed** (SHA-256, never plaintext); `allowBackup=false`;
  CSPRNG-generated. Every tool call is written to an in-app **audit log**.

---

## Caveats

The v1 roadmap is done and the transport has since been pinned to the MCP spec and covered
by **151 JVM unit tests** (`./gradlew :app:testDebugUnitTest`), plus **four instrumentation tests**
for the two things a device-free JVM cannot reach — a main-thread DataStore read and the camera and
mic (`./gradlew :app:connectedDebugAndroidTest`, never wired into `check`). Remaining rough edges:

- **Two real clients have connected**: the official MCP Python SDK 2.2.0 and Claude Code 2.1.251
  (which negotiates down from its own newer revision). Claude Desktop and the MCP Inspector have
  not been tried.
- **TLS pulls in the Netty engine**, because CIO cannot serve HTTPS at all — and there is no
  alternative: the Ktor issue is open since 2019, and the servlet-container engines are not
  viable on Android. Netty is ~2.0 MB, about 7% of the APK; the caveat here used to blame it for
  the APK size, which was wrong by more than 4× — the actual bulk was an unused
  `material-icons-extended` dependency (32%), now removed. The cert is self-signed, so a client
  must pin the SHA-256 the app shows or skip verification; Node-based clients have no pinning
  knob and need the cert as a trusted CA instead.
- **The server implements protocol revision `2025-06-18` only.** An unsupported
  `MCP-Protocol-Version` header is answered with a 400 naming what is supported.
- **`resource_link` media is served both ways** — through `resources/read` and as a plain HTTP GET
  on the link. Bytes live in memory with a 10-minute TTL and are consumed on first fetch by either
  path.
- **No server-initiated SSE stream** (`GET /mcp` returns 405, which the spec permits), so there is
  no channel for `tools/list_changed` — tool descriptions are deliberately static and live state
  comes from `list_capabilities`. A `tools/call` *response* can still stream: see below.
- **`notifications/progress` on the call's own response.** When a client sends both a
  `_meta.progressToken` and `Accept: text/event-stream`, that POST is answered as an SSE stream
  carrying a progress notification every 2 s and the JSON-RPC response last. It exists because 22
  of the 40 tools block on a human tapping "Allow" for up to 25 s, which is otherwise
  indistinguishable from a hung server. Both signals are required, so a client that sends neither —
  or only one — gets exactly the single-JSON response it always did.

---

## Roadmap

### v2 — spec conformance, schema quality, and a test harness

Written after auditing the transport against the live MCP spec and the code against itself.
The v1 list below was fully checked off; this is its successor.

**Done:**

- [x] **JSON-RPC conformance.** Notifications are never answered (any method, not just two
      hardcoded names — `notifications/roots/list_changed` used to get back a `-32601` carrying
      `"id": null`). Malformed envelopes answer **400** instead of a 200-with-error, and omit
      `id` rather than sending `null`. Unknown tools are a `-32602` protocol error, not a tool
      result. Type-confused `method`/`name`/`arguments` return JSON-RPC errors instead of a 500.
- [x] **Real version negotiation.** `initialize` echoes the client's requested revision when
      supported instead of hardcoding its own, and an unsupported `MCP-Protocol-Version` header
      is a 400 naming `data.supported`.
- [x] **Honest failures.** A tool that throws now returns `isError: true` and is audited as
      `EXECUTION_ERROR` — it used to be reported to the client as a success and written to the
      audit log as `"ok"`. `AuditLog.record` is atomic, so concurrent calls stop dropping entries.
- [x] **Auth hardening.** The `Bearer` scheme is matched case-insensitively, and a bare
      schemeless token no longer authenticates (`removePrefix` returned the string unchanged when
      the prefix was absent). 401s carry `WWW-Authenticate`. Request bodies are capped at 512 KB.
- [x] **`serverInfo.title` + `instructions`**, so a client is told the default-deny contract and
      the `structuredContent` refusal shape up front rather than discovering it by trial.
- [x] **`list_files` is actually confined to granted folders.** `read` passed the client's string
      straight to `ContentResolver`, which resolves `file://` to a plain `FileInputStream` — so
      `file:///proc/self/status` was readable. Non-`content://` schemes are now refused outright,
      and containment is decided by the **owning provider** via
      `DocumentsContract.isChildDocument`, not by this app guessing from the document id (they are
      opaque provider strings — a first attempt at prefix matching refused perfectly good URIs
      from Drive-style providers and from whole-volume grants). The provider's "no" is final; the
      prefix rule survives only as a fallback for `externalstorage`, whose id layout is documented,
      and everything else fails closed. The grant is re-checked against the persisted URI
      permissions on every read, so revoking it in Settings takes effect at once.
      Verified on-device across a granted read, `file://`, a doc outside the tree, a sibling tree,
      and a foreign authority.
- [x] **Media capability URLs are genuinely single-use.** The link was replayable for the full
      10-minute TTL, while a *wrong* nonce evicted the entry — and ids were a sequential base36
      counter on a route that needs no bearer token, so pending blobs could be enumerated and
      deleted. Ids are now random, the entry is consumed on success, and a bad nonce evicts
      nothing. Responses carry `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.
- [x] **Captures are no longer written to disk.** Every `take_photo` / `record_audio` /
      `capture_screenshot` also wrote `filesDir/{photos,audio,screens}/last.*`, which nothing
      read — so the last camera frame and mic clip outlived turning the capability off and
      revoking the OS permission. The writes are gone and existing installs are purged on launch.
- [x] **Elevated commands can no longer wedge a worker.** `root_shell` read only stdout, so a
      command that filled the stderr pipe deadlocked; `waitFor()` was unbounded, so `logcat`
      never returned; and `readBytes()` was uncapped. Output is now capped at 1 MB, stderr is
      merged, and the command is killed after 20 s.
- [x] **The TLS cert is usable by a verifying client.** ktor's `CertificateBuilder` defaults were
      being taken wholesale: the cert measured on-device was **SHA-1, RSA-1024, valid three days**,
      with no SAN for the LAN or tailnet address. Now RSA-2048 / SHA-256 / 10 years, with an IP
      SAN per bind address, regenerated when it expires or the device's address changes.
- [x] **Tool schemas a model can actually use.** 47 properties across 25 tools were bare
      `{"type":"string"}` with no `required`, no descriptions and no bounds. Now a single
      `ToolSchemas` table carries usage text, per-argument descriptions with units and ranges,
      `required`, enums, `minimum`/`maximum`/`default` mirroring the handler clamps, and MCP
      `annotations` (`readOnlyHint` / `destructiveHint` / `openWorldHint`). Descriptions no longer
      embed `[currently enabled/disabled]`, which went stale the moment a toggle changed because
      clients cache `tools/list` and there is no `listChanged` channel.
- [x] **Behavioural fixes surfaced by writing those schemas:** `torch` with no arguments turned
      the light **off**; `read_sms`/`read_call_log`/`read_notifications` were unclamped, so
      `limit:-1` made a full inbox report "no messages" and made `read_notifications` throw;
      `set_volume` rejected the `voice_call` stream that `volume_info` advertises; `take_photo`
      echoed a camera it had not used; `post_notification` silently posted `"(no text)"`.
- [x] **151 JVM unit tests** — the first in the project. Protocol conformance, the HTTP layer
      (auth, DNS-rebinding guard, CORS, version header, body cap, media nonce), SAF containment,
      TLS cert properties, registry invariants, and schema quality gates. `installRoutes` takes
      the handler as a lambda so the whole HTTP layer runs under `testApplication` with no device.

- [x] **Driven by two real MCP clients.** The official **MCP Python SDK 2.2.0** completed the full
      lifecycle against the K70 over LAN: `initialize` (version negotiated, `title` and
      `instructions` parsed), `notifications/initialized`, `ping`, `tools/list` (40 tools, with
      `required` and `annotations` deserialised into the SDK's own types), `tools/call`, and an
      unknown tool surfacing as an `MCPError` rather than a tool result. This is what every fix
      above was for, and it immediately found two defects no unit test could — see below.
- [x] **Argument errors were reported as successes.** ~27 handlers `return`ed their complaint as
      an ordinary string, which became a **successful** tool result whose text merely read like an
      error: `torch {}` came back `isError: false` with the body "provide 'on': …". Only visible
      by watching a real client parse the reply. Now a typed `ToolArgError`, audited as
      `INVALID_ARGUMENT`, with a test that fails if any handler goes back to returning one.
- [x] **`gate_failed` contradicted `retriable`.** It was inferred from the toggle/permission
      booleans while ignoring the reason code, so a capability refused because the hardware is
      absent reported `gate_failed: "app_toggle"` — telling a model to flip a switch that cannot
      help. Now derived from the reason code: `hardware` / `elevated_access` / `special_access` /
      `app_toggle` / `os_permission`. Verified on the K70 across all three.

- [x] **`resources/list` + `resources/read`.** `resource_link` media is now dereferenceable
      through the protocol, not only over plain HTTP: the server declares the `resources`
      capability and serves the links it minted. Verified on the K70 end to end — a real
      `take_photo` returned a link, `resources/read` returned the identical 310,936 bytes as
      base64, and the second read answered `-32002` because the entry is single-use (the HTTP
      route 404s too — both paths consume the same entry). `resources/list` is legitimately empty:
      media is transient and per-call, not enumerable.
- [x] **Origin allowlist.** Turning the dashboard on used to admit *any* browser origin — CORS
      simply echoed whatever was sent, so the DNS-rebinding guard went silent exactly when it was
      in use. Measured on the live server before the change: `Origin: https://evil.example` passed
      the guard and failed only at auth. Now `localhost` / `127.0.0.1` / `[::1]` / a `file://`
      dashboard (`Origin: null`) are accepted by default, anything else must be added in the app,
      and the rest are refused. Verified live: `evil.example` → **403**, `localhost:3000` → 200,
      `null` → 200.
- [x] **Rejected connections are audited and rate-limited.** Neither a 401 nor an Origin 403 wrote
      anything, so a `lan` bind could be probed indefinitely leaving no trace. Now a per-remote-host
      token bucket (burst 20, refill 10/min, **failures only**, so a busy legitimate client cannot
      throttle itself) answers 429 with `Retry-After`, and audit lines are **coalesced to one per
      host per minute** carrying the count they stand for — logging every rejection would still
      have let a patient prober walk the 200-entry ring clean in about twenty minutes. Verified on
      the K70: 20 × 401 then 429s, a valid token still 200, and 25 rejections producing exactly
      **one** audit line.
- [x] **Approval no longer races the client's timeout.** `TIMEOUT_MS` was 60 s — exactly the
      reference SDKs' default request timeout, with the client's clock starting first, so "phone in
      a pocket" surfaced as an opaque transport timeout instead of the structured refusal this app
      builds. Now 25 s. `notifications/cancelled` withdraws a pending approval (keyed by JSON-RPC
      request id) so a late "Allow" tap cannot fire the camera for an abandoned call, and cleanup
      moved into a `finally` so cancellation no longer orphans a live consent prompt in the shade.
- [x] **Media links are built from the config the listener actually bound**, not live config —
      bind and TLS can be toggled without restarting the server, which produced links pointing at
      an address the running listener never bound.
- [x] **Claude Code drives it.** `claude mcp add --transport http phone http://<host>:8765/mcp
      --header "Authorization: Bearer <TOKEN>"` → **✔ Connected**. Its handshake, captured with a
      logging proxy in front of the device:
      1. `POST server/discover` with `mcp-protocol-version: 2026-07-28` — a **dual-era probe**.
         This server answers **400** naming `data.supported`, and Claude Code falls back.
      2. `POST initialize` with `protocolVersion: "2025-11-25"` and **no version header** — the
         negotiation is in the body. Negotiated down to `2025-06-18` and accepted.
      3. `notifications/initialized` → 202 · `GET /mcp` (`Accept: text/event-stream`) → 405,
         tolerated · `tools/list` → 24 KB of schemas, parsed.
      Answering the probe with **400 rather than 404** is what makes the fallback work; a 404
      would also have contradicted the revision this server does declare.
- [x] **The browser dashboard still works under the Origin allowlist.** Verified the full CORS
      handshake a `file://` page needs: preflight → 204 with `Access-Control-Allow-Origin: null`,
      the POST → 200 with a matching header and `Vary: Origin`, and `https://evil.example` → 403.
      (The in-app preview pane cannot itself reach the LAN — `ERR_BLOCKED_BY_CLIENT` — so the
      handshake was verified directly rather than through that sandbox.)
- [x] **Structured results carry the serialized JSON too.** The spec: *"a tool that returns
      structured content SHOULD also return the serialized JSON in a TextContent block."* Both
      refusal envelopes returned `structuredContent` beside only a human sentence, so a client
      reading just `content` — every client predating structured content — got the remediation but
      none of the machine-readable gate detail. Both now go through one `structuredResult` helper.

**Open, and decisions taken:**

- [ ] **Claude Desktop and the MCP Inspector.** The Inspector needs `npx`; this machine has bare
      `node` with no npm, and Debian's `corepack` is broken (`MODULE_NOT_FOUND`), so trying it
      needs `sudo apt install npm` first. Claude Desktop dials from Anthropic's servers and would
      need the `mcp-remote` bridge.
- [x] **`outputSchema` — deliberately NOT declared, on the spec's own terms.** The rule is
      unconditional: *"If an output schema is provided: Servers MUST provide structured results
      that conform to this schema."* There is no carve-out for errors. This server's **most common
      result is a refusal**, whose `structuredContent` is the gate diagnostic (`reason_code`,
      `gate_failed`, `remediation`, …) rather than the tool's output — so declaring an
      `outputSchema` anywhere would put the server in breach on nearly every call. Revisit only by
      first moving the refusal payload out of `structuredContent`, which would break the documented
      contract for a marginal gain. A test asserts no `outputSchema` appears while that holds.
- [ ] **No server-initiated SSE stream** (`GET /mcp` is a 405, which the spec permits), so no
      channel for `tools/list_changed`. Claude Code tolerates the 405; revisit only if a real client
      demands it. Progress notifications no longer need it — they ride the call's own response
      (v3).

### v3 — the release build, and three things deliberately not done

Written after investigating five candidate milestones. Three came back **skip**, and that half
matters as much as the doing half: with v1 and v2 both complete, the temptation is to invent work.

- [x] **A release build that is not `debuggable`.** The app in daily use was a *debug* build —
      `dumpsys package` reported `pkgFlags=[ DEBUGGABLE … ]`, so the process was jdwp-attachable,
      and both devices keep USB debugging on *because Shizuku requires it*. Anything with adb could
      drive the app past its own double gate. `-r` was also a documented flag that could not work:
      with no signing config it produced an unsigned APK that the installer handed to adb anyway.
      Signing falls back to the debug key so the signature stays stable — changing keys forces an
      uninstall, wiping tokens and grants — and costs nothing, since `debuggable` comes from the
      build *type*.
- [x] **Minification investigated and deliberately left OFF.** R8 silently breaks HTTPS: the first
      TLS request succeeds and every one after it hangs, while the UI and audit log both insist the
      server is running. `NettyChannelInitializer` loses its `@Sharable` marker. Five fixes failed,
      including `-dontoptimize` (so it is not the optimizer) and `-keepattributes *Annotation*`,
      which produced a byte-identical APK and thereby revealed itself as redundant. 14.6 MB → 2.9 MB
      is not worth an advertised feature breaking after exactly one request. Full investigation in
      [app/proguard-rules.pro](app/proguard-rules.pro) — including that **one request always
      works**, so a single smoke test proves nothing.
- [x] **Dropped `material-icons-extended`** — declared, never used, and **32% of the APK**. The
      caveat above used to blame Netty for the size; Netty is ~7%.
- [x] **`TlsKeystore` moved into the JVM suite.** It needed `Context` for one thing, `filesDir`.
      The first run found a real bug: nothing checked that the stored cert and key belong together,
      so a mismatched pair loaded cleanly and was served — every handshake failing while the app
      reported success. Now compared by RSA modulus, with eleven tests covering fingerprint
      stability, re-issue, corrupt material and the atomic commit.

**Deliberately not done**

- [ ] **Do NOT raise `targetSdk` 33 → 34/35.** It opts into *restrictions*, not APIs (`compileSdk`
      is already 35), and Play policy is not a constraint here. Meanwhile 33 is buying three
      behaviours this design depends on: an unlimited-duration `dataSync` foreground service (capped
      at 6h per 24 at target 35, then killed), the ability to start that service from
      `BOOT_COMPLETED` (forbidden at 35), and a **reusable `MediaProjection`** — which breaks at
      **34**, where each projection is single-use, so `capture_screenshot` would work exactly once
      and then throw forever. Revisit only if an OS update blocks installation.
- [ ] **Do NOT treat APK size as a milestone.** Measured: the entire prize for an 87% cut is ~1.4
      seconds per `adb install`.
- [ ] **Do NOT add `logging`, `completions` or `prompts`.** `logging` is deprecated as of the
      2026-07-28 revision; `completions` is structurally impossible for tool arguments
      (`completion/complete` takes only `ref/prompt` and `ref/resource` — there is no `ref/tool`),
      and serving package names through it would bypass the gate on `list_packages`; `prompts`
      duplicates what a local `.claude/commands/*.md` does better.

**Done since, with what checking the claim revealed**

- [x] **Four instrumentation tests** (`app/src/androidTest`, run with
      `./gradlew :app:connectedDebugAndroidTest`). Deliberately NOT wired into `check` — the
      device-free JVM loop must keep working unplugged.

      > **Running these UNINSTALLS the app and wipes its data.** AGP removes both APKs when the
      > run finishes, so minted tokens, SAF grants, the enabled-capability set and the port/bind
      > settings all go with it — and it installs a *debuggable* build over your release one along
      > the way. Reinstall after every run:
      > `adb install -r app/build/outputs/apk/release/app-release.apk`, then confirm
      > `dumpsys package com.sixoffive.androidmcp | grep pkgFlags` shows **no** `DEBUGGABLE`.
      > Learned the hard way, on a device that had three live tokens on it.

      **`ConfigStore.currentBlocking()` on the main thread — the one that earns its keep.** It is
      `runBlocking { flow.first() }` over a DataStore, and `BootReceiver` calls it from `onReceive`,
      on the main thread, inside the 10 s broadcast window. A JVM test cannot see this at all:
      there is no Looper to deadlock against. Confirmed falsifying — rewritten as
      `runBlocking { withContext(Dispatchers.Main) { … } }`, a plausible refactor, the run goes red
      (as a process crash after the ANR watchdog, not a tidy assertion message).

      **The two capture tests are smoke tests, and the roadmap's claim for them was wrong.** They
      were listed here as "guarding the camera-release fix". Checking that claim killed it: run
      against the **actual pre-fix code** (`git show 0709889^`), `take_photo` twice in a row
      *passes*, completing in ~2.4 s instead of hanging. The audio equivalent passes with
      `rec.release()` deleted outright. Neither leak is observable from an instrumented process on
      the K70 — the reader and handler thread are still torn down, the leaked object is
      unreferenced immediately, and the camera service hands the same process a fresh open. The
      original failure was seen through the long-lived foreground service, a different environment
      with a different lifetime and different GC pressure.

      So they assert what they can prove: both paths return well-formed data on a repeat call,
      permissions plumb through, and neither hangs. A pass says nothing about whether the camera or
      recorder was released. The pre-existing ceiling still applies too — an instrumented process
      has foreground importance, so none of this reproduces the background-restriction failures the
      tools' own error strings describe.
- [x] **`notifications/progress` during the approval wait.** 22 of 40 tools block on a human tap for
      up to 25 s, and the client saw a silent stall indistinguishable from a hung server. A
      `tools/call` is now answered as SSE on its **own POST response** — no `GET /mcp` stream
      needed — emitting progress every 2 s and the JSON-RPC response last, but **only** when the
      client sends both a `_meta.progressToken` and `Accept: text/event-stream`. Requiring both
      means asking for progress can never change the response shape under a client that cannot read
      it, and Claude Code's blanket `Accept: application/json, text/event-stream` does not
      accidentally opt every call in.

      The prerequisite was checked first, not assumed: `StreamingFlushTest` stands both engines up
      on a real socket and times two writes held 700 ms apart. Both flush per-write — and the test
      fails on both when the handler is made to buffer, so it is falsifying rather than merely
      green. Without that, this feature would have replaced a silent stall with a silent stall that
      had changed its MIME type, and no `testApplication` test could have seen the difference,
      because the test engine never goes through a socket.

      Verified end to end by the reference MCP Python SDK against the real engine
      (`SdkProgressHarnessTest`, opt-in via `-Dandroidmcp.sdk=1`). That harness immediately earned
      its keep: with a hardcoded progress token the SDK still read the result off the stream but
      **silently dropped every notification**, because it correlates strictly on the token *it*
      generated. A framing assertion cannot see that failure — the bytes are well-formed and the
      call succeeds.

### v1 — feature completeness *(done)*

- [x] Scaffold + green build; core (gate, config, hashed tokens, audit)
- [x] Ktor foreground-service server, MCP JSON-RPC over Streamable HTTP, bearer auth
- [x] All 16 capabilities (see table) — every one device-verified
- [x] Per-call approval; Compose config UI; Setup & reliability card; installer
- [x] Bind selector; verified over adb-forward, LAN, and Tailscale
- [x] Optional root tier (`root_screenshot`, `root_shell`) — gated to
      `NOT_SUPPORTED_WITHOUT_ROOT` until rooted; verified refusing on a stock device
- [x] Elevated tier generalised to **Shizuku** (non-destructive) *or* root
- [x] **"Armed window" UI** — per-capability "Arm 10 min" skips approval for a window
- [x] **`resource_link` media** — opt-in fetchable URL (capability nonce, 10-min TTL)
- [x] **Optional TLS** — self-signed HTTPS via the Netty engine (CIO stays HTTP);
      verified `https://` initialize on-device
- [x] **Hardware-aware registry** — absent-hardware tools auto-marked `HARDWARE_UNAVAILABLE`
- [x] **Save/load on startup** — options persist; the server auto-resumes on boot / app launch
      only after an explicit, **warned** "Start on boot" is **Saved** (verified via a real reboot)
- [x] **Grouped capability list** — 40 tools in 11 **collapsible** categories (expanded state persists)
- [x] **`resource_link` auto-used for very large media** (>4 MB) even with the toggle off
- [x] **Persistent, pinnable TLS cert** — generated once, stored as DER, stable SHA-256 shown in
      the app (verified identical across restarts) so a client can pin it
- [x] **Settings UI grouped** — the one long Server page is split into **Server** /
      **Connection & transport** / **Startup** cards. Layout verified on-device via
      screenshots on both the K70 tablet (1280×800) and the SM‑A037W phone (720×1600):
      the cards render cleanly on a narrow screen and the collapsible capability list
      (11 categories, per-group counts, expand/collapse) works on the phone. Screenshots: [docs/](docs/).

---

## Scope

A personal, **sideloaded** app for the maintainer's own devices. Because it's not
Play-distributed, Play policy is not a design constraint — but default-deny and honest
disclosure are kept as the actual safety story. Not intended for Play distribution as-is.
