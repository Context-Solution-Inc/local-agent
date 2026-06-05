# Headless / startup service templates

Operator templates for running the desktop agent (`:desktopApp`) as a background
service via `MOBILEAGENT_HEADLESS=1`. These are **not** built into the app — copy and
edit the paths to match your install.

| File | Platform | Installs to |
|---|---|---|
| `mobileagent.user.service` | Linux systemd (per-user) | `~/.config/systemd/user/mobileagent.service` |
| `mobileagent.system.service` | Linux systemd (system-wide) | `/etc/systemd/system/mobileagent.service` |
| `com.contextsolutions.mobileagent.plist` | macOS launchd | `~/Library/LaunchAgents/` |

Windows uses Task Scheduler / the startup folder — no template file; see
`docs/DESKTOP_PACKAGING.md` → "Headless / standalone deployment".

Full step-by-step (build → install → provision models → enable service) lives in
`docs/DESKTOP_PACKAGING.md`.
