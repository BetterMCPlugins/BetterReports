# Listing copy for Modrinth / Hangar

Summary line (Modrinth "description" field, max ~250 chars):

> Player reports with automatic evidence capture: filing a report freezes recent chat, location and context — staff never has to ask "do you have screenshots?". Claim/resolve workflow, GUI, response-time stats, Discord alerts. Lightweight, free.

Suggested tags: `admin-tools`, `moderation`, `utility`. Loaders: Paper, Folia. Versions: 1.21+.

---

## The evidence is gone by the time staff looks. Unless you freeze it.

Every report plugin gives you a `/report` command. Then the moderator opens the report twenty minutes later, the chat has scrolled away, the player logged off, and the "investigation" is asking the reporter for screenshots they didn't take.

BetterReports captures the evidence the only moment it exists — **when the report is filed**:

**Frozen chat evidence.** The last chat lines of the reported player *and* the reporter are attached to every report, together with the target's location, gamemode and ping. Staff opens the report and the proof is already there.

**A real workflow.** Reports are claimed, resolved or dismissed — in a GUI work queue (`/reports`) or by command. Everyone sees who handles what; duplicates against the same target merge into one bumped report (`3x reported`) instead of flooding the queue.

**Reporters get closure.** When their report is handled, the reporter is told — immediately if online, on next join otherwise. Players who see results keep reporting instead of giving up.

**Response-time stats.** `/reports stats` shows average time-to-claim and time-to-resolve, globally and per staff member. Pairs with [BetterAudit](https://modrinth.com/plugin/betteraudit) for the full staff-accountability picture.

### Anti-abuse built in

Report cooldown, max open reports per player, duplicate merging, and a `betterreports.immune` permission — report spam handled in config, not in your moderators' patience.

### Privacy by design

Chat is buffered in memory only and written to disk solely when a report references it. Players who are never reported never have chat stored; closed reports (including evidence) auto-delete after the retention period.

### Integrations (all optional, auto-detected)

PlaceholderAPI (`%betterreports_open%` for staff scoreboards) · DiscordSRV (alerts with zero setup) · raw Discord webhooks (no other plugin needed)

### Built the way you'd want it built

- Async SQLite storage, never on the main thread; ~70KB jar, zero dependencies
- Folia supported, Paper 1.21+
- Every module toggleable; evidence capture, cooldowns, merge window, retention — all config

### Commands & permissions

See the [README](https://github.com/BetterMCPlugins/BetterReports#commands) for the full reference. Quick start: install, give staff `betterreports.staff` + `betterreports.notify`, done — players can `/report` out of the box.

Support: [Discord](https://discord.gg/UfnyJgbY4P) · [Issues](https://github.com/BetterMCPlugins/BetterReports/issues)

---

Screenshot shot list (taken in-game, default font):
1. `/reports` GUI work queue with several open reports (hero image)
2. Detail view with the chat-evidence book hovered (the USP shot)
3. A player filing `/report` + the green confirmation, with the staff alert visible from a second account
4. `/reports stats` output
5. A Discord alert embed (enable webhook, file a report)
