# docs

Reference material for androidmcp. See the repo root [`README.md`](../README.md) for the
project overview, capabilities, and setup.

## Screenshots — settings layout (verified 2026-09-08)

On-device captures confirming the **settings-card split** (the old single "Server" page is
now **Server** / **Connection & transport** / **Startup**) and the **collapsible capability
list**. Captured on a Unisoc **K70 tablet** (1280×800) and a Samsung **SM-A037W phone**
(720×1600) — the layout holds on both a wide and a narrow screen.

| Screenshot | Device | Shows |
|---|---|---|
| [settings-server-card-tablet.png](screenshots/settings-server-card-tablet.png) | K70 tablet | Compact **Server** card (status + bind selector) with the **Connection & transport** card below |
| [settings-server-card-phone.png](screenshots/settings-server-card-phone.png) | SM-A037W phone | **Server** + **Connection & transport** cards on a narrow screen (toggles wrap, switches right-aligned) |
| [settings-startup-card-phone.png](screenshots/settings-startup-card-phone.png) | SM-A037W phone | **Startup** card (Start-on-boot toggle, armed/unsaved status line, Save) + Setup & reliability |
| [capabilities-collapsed-phone.png](screenshots/capabilities-collapsed-phone.png) | SM-A037W phone | Capability list collapsed into 11 categories, each with a per-group `N / M on` count, plus Expand all / Collapse all |
| [capabilities-expanded-phone.png](screenshots/capabilities-expanded-phone.png) | SM-A037W phone | A category expanded (▾) to reveal its per-tool cards |
