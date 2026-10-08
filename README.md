# NextQuest

*(working name: the product will be renamed before going public)*

A local-first Android quest log that keeps exactly one next step in view for
each quest. An optional self-hosted sync + MCP server lets Claude or ChatGPT
read your quests and **propose** changes, which you approve in the app.

> Screenshots: *(placeholder: Today, Board, Quest, Proposals, widget)*
>
> v1.1 screenshots: *(placeholder: Reset sheet with Breathe, focus timer, a practice
> card with "this week 2/4", Stuck? sheet, What's next with When/where?)*

## Principles

These decide every judgment call:

1. **One thing visible.** Each tracked quest shows only its single next step.
2. **Capture in two seconds from anywhere**; capture never forces sorting.
3. **Hard cap of 5 tracked quests** (configurable 3–7, default 5).
4. **Every tracked quest always has a next step.** The only exception is a step
   completed from a notification. That quest shows a "Needs next step" flag
   until you set one.
5. **AI proposes, you ratify.** No AI or server action ever changes your quests
   directly. It creates proposals; you accept, edit, or reject them.
6. **Re-entry without guilt.** No overdue walls of red. After 2+ days away, a
   resume card helps you pick up again.
7. **Local-first.** Fully functional offline with no account. Sync is optional.

## What's in the app

| | |
|---|---|
| **Today** | Your tracked quests, each with its one next step. Done → +XP → "What's next?". |
| **Board** | Inbox (everything captured, unsorted) and Later (parked). Track, park, drop, search. |
| **Calendar** | Steps with a date, by week or month. |
| **Trophies** | Level, XP, streak, 12 achievements. |
| **Capture** | The + button on every screen, the Android share sheet, the widget. |
| **Proposals** | What Claude or ChatGPT suggested, with their reason. Accept, edit or reject. |
| **Presence** | Morning digest, streak guard, step reminders and alarms (they survive reboot), home-screen widget. |
| **Durability** | Mirror into an Obsidian/Syncthing folder as Markdown, daily snapshots, export/import. |

### New in v1.1: starting and returning

| | |
|---|---|
| **Practices** | Ongoing quests with a rhythm (daily, chosen days, or N a week): singing, drawing, study. Each session is the step; the next one appears by itself. They never complete and never pile up: the card only says "this week 2/4". |
| **Undo** | 10 seconds to undo any completion in the app, 5 minutes from a notification. XP, streak, achievements and focus go back exactly. |
| **Focus timer** | 10 / 25 / 50 minutes on one step, full screen or in the shade. +5 XP for every block you finish (at most 60 a day), so effort itself counts. Stopping early is fine. |
| **Stuck?** | A step untouched for a few days gets a quiet chip: shrink it, focus 10 minutes, park it, or ask Claude or ChatGPT to break it down (share sheet, no API key). |
| **Reset** | Breathe (cyclic sighing, guided by haptics even with the screen off), Shrink a step to 2 minutes, or a Fresh start: nothing you missed carries forward. |
| **When/where** | An if-then cue on each step ("after lunch, at my desk"), and an obstacle + plan on each quest. |
| **Coming back** | After time away, a new week or a new month: pick up where you left off, or start clean. After a day without a step, one kind line, never about the streak. |

Game rules: a step (or practice session) is +15 XP, a subquest +40, a quest +100,
a finished focus block +5 (60 a day at most). Each level needs
about 1.25× the XP of the previous one. The streak counts days with at least
one completed step. Missing a single day is forgiven if you finish a step
before noon on the next day.

## Building

Requirements: JDK 21 and the Android SDK (compileSdk 35).

```bash
./gradlew :app:assembleDebug     # debug APK → app/build/outputs/apk/debug/
./gradlew test                   # every JVM test, including Robolectric
./gradlew -Pquest.jvmOnly=true :server:installDist   # server only, no Android SDK needed
```

CI builds a debug APK on every push. You can download it from the workflow
run's artifacts. With the one-time secret setup in
[docs/RELEASING.md](docs/RELEASING.md), each CI APK installs over the previous one
and keeps your data.

## Self-hosting the sync + AI server

The server is one small JVM process with a SQLite file. It holds your event
log, lets your devices sync, and is the MCP endpoint for Claude and ChatGPT.

```bash
cp .env.example .env      # set ADMIN_PASSWORD (12+ characters) and PUBLIC_URL
docker compose up -d
curl http://localhost:8080/health
```

- Put it behind **HTTPS** before connecting phones or AI clients. Use any
  reverse proxy (Caddy, Traefik, nginx) or a tunnel, and set `PUBLIC_URL` to
  the public `https://` address. Release builds of the app only pair over HTTPS;
  debug builds also accept `http://` for testing on a local network
  ([RELEASING](docs/RELEASING.md)).
- Data lives in the `quest-data` volume (`/data/quest.db`). Back it up like
  any SQLite file. Every phone also keeps the full log.
- The server always listens on port 8080 inside the container;
  `BIND_ADDRESS` (default `127.0.0.1`) and `PORT` only choose where compose
  publishes it on the host.
- `TRUST_PROXY=1` (the default) is right behind a reverse proxy on the same
  host: rate limits then see client IPs from `X-Forwarded-For`, which is only
  honoured from loopback or private/Docker addresses. Set it to `0` if clients
  connect to the published port directly.
- All settings (rate limits, allowed origins, proxy trust, bind address) are
  environment variables. See [`docker-compose.yml`](docker-compose.yml) and
  [`.env.example`](.env.example).

### Pairing a phone

1. Open `PUBLIC_URL` in a browser and sign in with the admin password.
2. Choose **Pair a device**. A QR code and an 8-character code appear, valid
   for 10 minutes.
3. Scan the QR code with the phone's camera, or in the app go to
   **Settings → Sync & AI** and enter the address and code.
4. Check the server address in the app and tap **Pair**.

Changes then sync in the background. You can remove a device on the server
page at any time.

## Connecting Claude and ChatGPT

Both connect to `https://your-server/mcp` with OAuth. You approve each
connector once with the admin password.

- **Claude** (web, desktop, mobile): *Settings → Connectors → Add custom
  connector*, URL `https://your-server/mcp`.
- **ChatGPT**: *Settings → Connectors → Create* (developer mode), same URL.

Then ask things like *"What's on my plate today?"* or *"Break my 'Move flat'
quest into subquests."* Read tools answer directly. Every change arrives in
the app's **Proposals** screen with the assistant's reason, and nothing changes
until you accept it. The tools and their limits are in
[docs/MCP_TOOLS.md](docs/MCP_TOOLS.md).

## Documentation

- [ARCHITECTURE](docs/ARCHITECTURE.md): modules, event sourcing, the fold.
- [SYNC](docs/SYNC.md): the sync API, merge rules, what wins when.
- [MCP_TOOLS](docs/MCP_TOOLS.md): the AI tools and their rules.
- [DECISIONS](docs/DECISIONS.md): every judgment call, with reasons.
- [REVIEW](docs/REVIEW.md): independent review results per phase.
- [DEVICE_TEST](docs/DEVICE_TEST.md): the on-device checklist for what CI can't prove.
- [RELEASING](docs/RELEASING.md): stable debug signing, release signing, network security.
- [SPEC](docs/SPEC.md): the v1 and v1.1 briefs.

## Roadmap

- Rename and public release (F-Droid, Play).
- Two-way vault sync: edits in Obsidian flow back as proposals.
- Hosted multi-user server mode (the tables are already per-user).
- Wear OS tile for the next step.
- End-to-end encrypted sync.

## License and contributing

[AGPL-3.0-or-later](LICENSE). Contributions are welcome under the
[CLA](CLA.md); see [CONTRIBUTING](CONTRIBUTING.md) and the
[Code of Conduct](CODE_OF_CONDUCT.md).
