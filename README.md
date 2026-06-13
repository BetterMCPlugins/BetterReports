# BetterReports

[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Paper](https://img.shields.io/badge/Paper-1.21%2B-blue.svg)](https://papermc.io)
[![Folia](https://img.shields.io/badge/Folia-supported-blue.svg)](https://papermc.io/software/folia)
[![Discord](https://img.shields.io/badge/support-Discord-5865F2.svg)](https://discord.gg/UfnyJgbY4P)

**Player reports with automatic evidence capture.** The moment a player files a report, BetterReports freezes the evidence — recent chat of both players, location, gamemode, ping — so your staff never has to ask *"do you have screenshots?"* again.

## Why this exists

Report plugins are everywhere, but they are all the same: a `/report` command that produces a row of text and leaves the actual investigation to the moderator. By the time staff looks at the report, the chat has scrolled away, the player has logged off, and the evidence is gone.

BetterReports flips that around:

1. **Evidence first.** Filing a report snapshots the last chat lines of the reported player and the reporter, plus location, gamemode and ping — attached to the report forever, captured at the only moment it exists.
2. **A real workflow, not a chat log.** Reports are claimed, resolved or dismissed in a staff GUI. Everyone sees who is handling what; nothing falls through the cracks.
3. **Accountability built in.** `/reports stats` shows average time-to-claim and time-to-resolve, per staff member — the other half of the staff-accountability story that [BetterAudit](https://github.com/BetterMCPlugins/BetterAudit) tells.
4. **Reporters get closure.** When their report is handled, reporters are told — immediately if online, on next join otherwise. Players who see results keep reporting instead of giving up.

## Features

- **Frozen chat evidence**: an in-memory ring buffer (nothing touches the disk for unreported players) snapshots both players' recent chat into the report.
- **Staff GUI**: `/reports` opens the work queue (oldest first), one click opens the detail view with the evidence book and claim / teleport / resolve / dismiss buttons.
- **Anti-abuse**: report cooldown, max open reports per player, and duplicate merging — a second report against the same target within the merge window bumps the existing report (`3x` reported) instead of cluttering the queue.
- **Reporter feedback**: handled reports notify the reporter, queued across restarts if they are offline.
- **Discord webhook alerts** for new, bumped and closed reports — triage from your phone.
- **In-game alerts** for everyone with `betterreports.notify`, plus an open-report reminder for staff on join.
- **Response-time stats**: global and per-staff average time-to-claim / time-to-close.
- **Automatic retention**: closed reports are purged after N days; open reports are never touched.
- **Zero hard dependencies, async everywhere**: SQLite storage on a dedicated thread, never on the main thread. Folia-supported.

## Integrations (all optional, auto-detected)

| Plugin | What you get |
|---|---|
| **PlaceholderAPI** | `%betterreports_open%`, `%betterreports_claimed%`, `%betterreports_unresolved%` — e.g. for staff scoreboards |
| **DiscordSRV** | Alerts route through your existing DiscordSRV channels — no webhook setup needed |
| **[BetterNotes](https://github.com/BetterMCPlugins/BetterNotes)** | `/reports view` shows how many staff notes the reported player already has (clickable), and closing a report offers a one-click "add note" shortcut |

## Commands

| Command | Description | Permission |
|---|---|---|
| `/report <player> <reason>` | File a report (reasons tab-complete from the config) | `betterreports.report` |
| `/reports` | Staff GUI — the report work queue | `betterreports.staff` |
| `/reports list [page]` | Open reports as clickable chat lines | `betterreports.staff` |
| `/reports history [page]` | Closed reports | `betterreports.staff` |
| `/reports view <id>` | Full report incl. chat evidence | `betterreports.staff` |
| `/reports claim <id>` / `unclaim <id>` | Assign a report to yourself / release it | `betterreports.staff` |
| `/reports resolve <id> [note]` | Close as handled | `betterreports.staff` |
| `/reports dismiss <id> [note]` | Close as invalid | `betterreports.staff` |
| `/reports tp <id>` | Teleport to the reported location | `betterreports.staff` |
| `/reports stats [staff]` | Response-time statistics | `betterreports.staff` |
| `/reports purge <days>` | Delete closed reports older than N days | `betterreports.admin` |
| `/reports reload` | Reload the configuration | `betterreports.admin` |

## Permissions

- `betterreports.report` — file reports (default: true)
- `betterreports.staff` — view and handle reports (default: op)
- `betterreports.notify` — in-game alerts and the on-join reminder (default: op)
- `betterreports.admin` — purge and reload (default: op)
- `betterreports.immune` — can not be reported (default: false)
- `betterreports.bypass-cooldown` — no cooldown or open-report limit (default: false)

## Privacy

Chat is buffered in memory only (default: last 30 lines per player) and written to disk solely when a report references it. Players who are never reported never have chat stored. The buffer is cleared on logout, and closed reports — including their evidence — are deleted automatically after the retention period.

## Building

```
mvn package
```

Requires Java 21+. The jar lands in `target/BetterReports-<version>.jar`. Drop it into `plugins/` on any Paper 1.21+ server.

## Support

Questions, bug reports, feature ideas — join the [Discord server](https://discord.gg/UfnyJgbY4P) or open a GitHub issue.

## Part of the BetterMCPlugins suite

BetterReports is one of three free, open-source staff plugins that share a design and a planned web dashboard:

- **[BetterAudit](https://github.com/BetterMCPlugins/BetterAudit)** — staff action audit log
- **[BetterReports](https://github.com/BetterMCPlugins/BetterReports)** — player reports with automatic evidence capture *(this plugin)*
- **[BetterNotes](https://github.com/BetterMCPlugins/BetterNotes)** — staff notes and a player watchlist

Run them together and the same players, staff and timeline line up across all three.

## License

MIT — see [LICENSE](LICENSE).

## Roadmap

- Punishment quick-actions on resolve (LiteBans / AdvancedBan integration)
- Web dashboard, shared across the suite (paid tier)
- Velocity network sync (paid tier)
- Two-way Discord bot: claim and resolve reports from Discord
