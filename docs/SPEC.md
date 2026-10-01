# v1 build brief (product contract)

This is the brief v1 was built against, kept verbatim so reviewers and future
contributors can grade the implementation against it. It supersedes the M0
spec (kept for history in [SPEC_M0_LEGACY.md](SPEC_M0_LEGACY.md)). Decisions
made where the brief was ambiguous are recorded in [DECISIONS.md](DECISIONS.md).

---

# NextQuest v1 — one-shot build prompt

You are building v1 of NextQuest (working name), an open-source, local-first Android quest log
with an optional self-hostable sync + MCP server so Claude or ChatGPT can read the user's
quests and *propose* changes. Work autonomously through every phase below. Do not stop to ask
questions; when something is ambiguous, pick the option that best serves the product principles,
record the decision in `docs/DECISIONS.md`, and continue.

---

## 0. Ground rules

- **Existing repo.** A basic M0 exists (branch `claude/nextquest-android-setup-jpaq1n`).
  Read it first. Reuse anything genuinely useful (Gradle setup, CI workflow), but it is
  disposable: you are free to restructure or replace all of it. Work on a new branch `v1`.
- **License:** AGPL-3.0-or-later. Add `LICENSE`, SPDX headers on source files,
  `CONTRIBUTING.md` stating that contributions require agreeing to `CLA.md`
  (an individual CLA that lets the maintainer also license the code commercially).
- **Rename-ready.** The product name will change before going public. Keep the display name in
  exactly one string resource and one server config value. Use a neutral package id:
  `dev.dreamweave.quest`. Never hard-code "NextQuest" elsewhere.
- **No secrets** in the repo. Run gitleaks over the *full history* (including the old M0
  commits) and write findings to `docs/SECURITY_SCAN.md`. Do not make anything public.
- **Commits:** conventional commits, at least one per phase. Open a draft PR from `v1` early
  and keep its description updated with phase status.
- **Every DB schema change** ships with a Room migration, exported schema JSON, and a
  migration test. All IDs are UUIDv7 strings.

---

## 1. Product principles (these decide every judgment call)

1. **One thing visible.** Each tracked quest shows only its single next step.
2. **Capture in two seconds from anywhere**; capture never forces sorting.
3. **Hard cap of 5 tracked quests** (configurable 3–7 in settings, default 5).
4. **Every tracked quest always has a next step.** The only exception is a step completed from
   a notification; that quest shows a "Needs next step" flag until the user sets one.
5. **AI proposes, the user ratifies.** No AI or server action ever mutates quest state
   directly. It creates proposals; the user accepts, edits, or rejects.
6. **Re-entry without guilt.** No overdue walls of red. After 2+ days away, show a resume card.
7. **Local-first.** The app is fully functional offline with no account. Sync is optional.

---

## 2. Architecture

Gradle multi-module, Kotlin 2.x, JDK 21, Android SDK 35, min SDK 26.

- `:core` — pure Kotlin, zero Android deps. Domain model, event types, the state fold
  (events → state), XP, levels, streak, achievements, invariants, proposal validation.
  Exhaustive JVM unit tests.
- `:protocol` — kotlinx.serialization DTOs shared by app and server: events, proposals,
  sync payloads, MCP tool input/output shapes. Also emit JSON Schema files to `/schema`.
- `:data` — Room database, repositories, DataStore settings, sync client.
- `:app` — Jetpack Compose UI (Material 3, heavily themed), notifications, alarms,
  Glance widget, share target, vault export.
- `:server` — Ktor server: sync API + remote MCP endpoint. SQLite by default, with every table
  carrying `user_id` so hosted multi-user is a config change, not a migration.

### Event sourcing (the spine)
- State is derived from an append-only `Event` log. Events: `id`, `deviceId`, `seq`
  (per-device monotonic), `ts`, `actor` (`user` | `mcp:<client>`), `type`, `payload` (JSON).
- XP, level, streak, achievements, history and Chronicle are all computed from events in `:core`.
- Sync = exchanging events. Conflicts resolve by deterministic ordering `(ts, deviceId, seq)`;
  document the merge rules in `docs/SYNC.md` with tests for concurrent edits.

### Domain
- **Quest:** `id, title, why?, status (INBOX | TRACKED | LATER | DONE | DROPPED), parentId?,
  order, focusedChildId?, createdAt, touchedAt`. Subquests: depth 2 in UI.
- **Step:** `id, questId, text, dueAt?, alarm: Boolean, doneAt?`.
- **Proposal:** `id, source (claude | chatgpt | other), createdAt, op, payload, reason?,
  status (PENDING | ACCEPTED | REJECTED | EDITED)`.
- **Focused-child auto-advance:** completing a step/subquest moves focus to the next
  incomplete subquest; the user can override.

### Game rules (in `:core`)
- XP: +15 step, +40 subquest, +100 quest. Level curve: document it, keep it gentle
  (level 2 at ~100 XP, growing ~1.25× per level).
- Streak: current + best; a day counts if ≥1 step done. Grace: missing exactly one day is
  forgiven if a step is done before noon the next day.
- Achievements: first step; 10 / 50 steps; first / 5 quests; 3 / 7-day streak; level 5;
  10 inbox items sorted; first subquest tree; 10 subquests done; first accepted proposal.
- No penalties, no lost XP, ever.

---

## 3. Screens and UX

Bottom nav: **Today · Board · Calendar · Trophies**. A capture FAB on every screen.
Settings sit behind the level badge. Reference mockups: Today, Quest detail, Proposals
(described below; match their structure).

1. **Today.** Header: level badge (tap → Settings), XP bar, streak chip, proposals icon with
   count. Slot pips "4/5 SLOTS". Optional resume card. Tracked quest cards: title, subquest
   progress (done/total + bar), "NEXT" label, the step, due chip, and a large **Done** button.
   A quest needing a next step shows a dashed card with "Set step".
2. **Capture sheet.** One text field, autofocus, Enter saves to Inbox. Reachable from the FAB,
   widget, notification action, and Android share sheet.
3. **Board.** Inbox and Later tabs, search, sort (recently touched / created / A–Z).
   Item actions: Track, Later, Drop. If slots are full, Track opens a swap dialog:
   finish or park one first.
4. **Quest detail.** Title, "why it matters", progress, next-step card (date/time chip,
   alarm toggle, Done · +15 XP), subquest checklist with FOCUSED tag and nested items,
   history tail (last ~10 events, mono font, showing XP and proposal acceptances).
5. **Proposals.** Cards: source chip (CLAUDE / CHATGPT), age, op label, quest name,
   before → after diff, the AI's reason in a quoted block, actions Reject / Edit / Accept
   (or Reject / To Inbox / Track for new quests when slots matter). "Accept all (n)".
   Footer: sync status.
6. **Calendar.** Week view default, month toggle. Dated steps appear on their days; tap a day
   for its list; tap a step to open its quest.
7. **Trophies.** Achievements grid (locked/unlocked), Chronicle of completed quests,
   lifetime stats.
8. **Settings.** Slot cap, digest time, streak-guard time, sync server URL + pairing,
   vault folder, export/import, theme (system/light/dark), about + donate links.
9. **First launch.** Brain-dump many items → choose up to 5 to track → rest go to Later.
   Ask notification permission only when the first reminder is set.

**Completion flow:** Done → haptic + XP toast → "What's next?" sheet (required unless the
quest is finished, in which case offer "Complete quest · +100 XP" with a celebratory moment).
Achievement unlocks show a toast.

---

## 4. Visual design

Fresh, light-first, nothing like legacy dark "gamer" apps.

| Token | Light | Dark |
|---|---|---|
| background | `#F3F6F1` | `#0F1513` |
| surface | `#FFFFFF` | `#18201D` |
| surface-alt | `#EEF3EF` | `#212B27` |
| border | `#E1E8E2` | `#2C3833` |
| text | `#12201B` | `#EAF2EC` |
| text-secondary | `#56675F` | `#A3B3AB` |
| action (lime) | `#C8F135`, text `#12201B` | `#C8F135`, text `#12201B` |
| progress/XP (violet) | `#5B4BFF` | `#8C80FF` |
| violet tint | `#EEEBFF` | `#252046` |

- Lime is **only** for primary actions (Done, Accept, Track, FAB). Never lime text.
- Violet for progress bars, XP, level, labels ("NEXT", "FOCUSED"), badges.
- Fonts (bundle as resources, OFL licenses included): **Bricolage Grotesque** for titles,
  **Manrope** for body, **IBM Plex Mono** for meta labels and numbers.
- Corners 12–16dp, cards with 1dp borders, no heavy shadows. Touch targets ≥ 48dp.
  Text contrast ≥ 4.5:1 in both themes (write a unit test that checks the token pairs).

---

## 5. Presence: notifications, alarms, widget

- Channels: Digest, Streak, Reminders, Alarms.
- **Morning digest** (default 08:00): tracked quests + next steps + streak.
- **Streak guard** (default 21:00): only if no step done today.
- **Per-step reminders** from `dueAt`, actions **Done / Snooze 1h / Skip**. Done works without
  opening the app and sets the "needs next step" flag.
- **Alarms** (per-step opt-in): exact alarm with full-screen intent. Handle
  `SCHEDULE_EXACT_ALARM` permission and full-screen-intent restrictions gracefully: fall
  back to a high-priority notification and explain in Settings.
- Reschedule everything on boot, time change, and app update.
- **Glance widget:** tracked next steps with tap-to-done and a capture button.

---

## 6. Durability

- **Vault mirror:** user picks a folder via SAF; export one markdown file per quest
  (YAML front matter, next-step line, subquest checklist with wikilinks, log). One-way in v1.
- **Snapshots:** JSON export/import of the full event log; import validates and merges.
- Daily automatic snapshot to the vault folder if set.

---

## 7. Sync + MCP server (`:server`)

- Ktor, SQLite, Dockerfile + `docker-compose.yml`, one-command self-host.
- **Sync API:** `POST /v1/events` (push batch), `GET /v1/events?after=<cursor>` (pull).
  Idempotent by event id.
- **Auth:** single-user mode for v1. Admin password via env. The app pairs by scanning or
  entering a pairing code from the server's web page, receiving a device token.
- **Remote MCP endpoint** at `/mcp` using Streamable HTTP and the official MCP Kotlin SDK
  (if it blocks you, implement the protocol directly; document why). Implement the MCP
  authorization flow (OAuth 2.1 with dynamic client registration, PKCE) backed by the
  admin login, so the server can be added as a custom connector in Claude and in ChatGPT.
- **Read tools:** `get_today`, `list_quests(status?)`, `get_quest(id)`, `get_history(days)`,
  `get_stats`, `list_proposals(status?)`.
- **Write tools create proposals only:** `propose_add_quest`, `propose_set_next_step`,
  `propose_complete_step`, `propose_add_subquest`, `propose_schedule_step`,
  `propose_park_quest`. Each takes a required `reason`. Validate against `:core` rules
  (e.g. reject a proposal that would exceed the cap without a park). Tool descriptions must
  tell the model that the user approves everything in the app.
- Proposals travel to the app as `proposal.created` events; the user's decision returns as
  `proposal.accepted` / `rejected` / `edited` plus the resulting domain events.
- Rate-limit tool calls; log every MCP call with client name.

**App side:** sync client with WorkManager (periodic + on app open + after local change,
debounced), exponential backoff, offline queue, sync status in Settings and the Proposals
footer.

---

## 8. Repo, docs, release

- `README.md`: what it is, the principles, screenshots placeholder, self-hosting guide,
  connecting Claude and ChatGPT, roadmap. `docs/`: ARCHITECTURE, SYNC, MCP_TOOLS, DECISIONS.
- `.github/FUNDING.yml` with placeholder entries (GitHub Sponsors, Ko-fi) commented for
  the maintainer to fill in.
- CI (GitHub Actions): build, unit tests, lint, Room migration tests, server tests,
  debug APK artifact on every push, signed-release workflow stub (keystore from secrets).
- Issue templates (bug, feature), `CODE_OF_CONDUCT.md`.

---

## 9. Phases

Do them in order. Each ends with the critique gate (§10).

- **P0 Reset:** audit old repo, new branch, module skeleton, CI green, license files, gitleaks.
- **P1 Core:** domain, events, fold, game rules, invariants, proposal validation. ≥90% line
  coverage on `:core`.
- **P2 Data + UI:** Room, repos, all screens, theming, completion flow, first launch.
- **P3 Presence:** notifications, alarms, boot rescheduling, widget, share target, calendar.
- **P4 Durability:** vault mirror, snapshots, import.
- **P5 Server:** sync API, auth, MCP endpoint and tools, Docker, server tests.
- **P6 Integration:** app sync client, proposals end to end, an integration test that runs
  the server, pushes a proposal via MCP, syncs, accepts it, and verifies state.
- **P7 Polish:** accessibility pass, empty states, error states, docs, release workflow.

---

## 10. Critique mechanism

After each phase:

1. Build, test, lint must be green.
2. **Self-check** the phase against its acceptance list below and the principles in §1.
3. **Independent review:** spawn a fresh reviewer subagent that has not seen you write the
   code. Give it only this prompt and the diff. It grades each acceptance item PASS/FAIL with
   evidence and lists the top issues by severity.
4. Fix every FAIL and every high-severity issue. Repeat review up to **3 rounds** per phase.
   If something still fails, record it as a known issue and move on.
5. Append the results to `docs/REVIEW.md`.

**Acceptance highlights (non-exhaustive; reviewers may add):**
- The cap can never be exceeded by any path (UI, import, sync, MCP).
- A tracked quest can never be left without a next step except via notification Done.
- XP/streak/achievements recomputed from scratch equal the incremental values.
- No MCP tool can change quest state without a user acceptance event.
- Fresh install → capture → track → done → next step works offline in under 30 seconds.
- Both themes pass the contrast test; all touch targets ≥ 48dp.
- Alarms and reminders survive reboot (instrumented test where possible).
- Server runs with `docker compose up` and passes its test suite.

At the end, write **`docs/DEVICE_TEST.md`**: a checklist the maintainer runs on a real phone
for what CI cannot prove (alarms at the exact time, lock-screen full-screen alarm, boot
reschedule, widget, share target, pairing, Claude and ChatGPT connector setup).

## 11. Final report

Finish with a PR description that lists: what shipped per phase, known issues, decisions
made (link DECISIONS.md), review results, and the device test checklist.
