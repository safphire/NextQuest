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

---

# v1.1 brief — fixes, essentials, and the focus layer

v1.1 was built against the brief below, kept verbatim like the v1 brief above. Every v1
rule still holds. Decisions made where it was ambiguous are D-1101 onward in
[DECISIONS.md](DECISIONS.md); reviews are in [REVIEW.md](REVIEW.md).

## Product intent for v1.1

The user procrastinates by generating ideas and losing track; v1 fixed losing track.
v1.1 targets **starting** and **returning**: make the first minute of work easy, reward
effort itself, and make coming back after a slip feel clean instead of guilty.

Evidence the design rests on (keep the copy and mechanics faithful to it):
- Procrastination is short-term mood repair, not laziness → lower the aversiveness of
  starting; never shame.
- Rewarding effort makes effort itself rewarding (learned industriousness) → XP for
  focused time on a step, not only for completion.
- Expected, controlling rewards erode intrinsic motivation; informational feedback helps
  → XP and stats are framed as feedback, never payment or punishment. No penalties.
- If-then plans ("when X, I will Y") are among the strongest goal interventions → a
  "when/where" cue on each step.
- Mental contrasting (wish → obstacle → plan) → an optional obstacle + plan on a quest.
- Self-forgiveness after procrastinating reduces later procrastination → forgive first,
  ask for the next step second.
- Fresh-start effect: temporal landmarks motivate new starts → offer clean restarts.
- Habits take a median ~66 days and one missed day barely matters → say so after a miss.
- Cyclic sighing (two inhales, long exhale) for ~5 minutes improved mood more than
  mindfulness in a 28-day trial → the Breathe reset.

**No bloat.** If a feature starts needing settings screens, onboarding, or more than one
new tab, stop and find the smaller version. Nothing adds a bottom-nav tab.

## Phase A — Fix round (from REVIEW.md known issues and device testing)

1. **Stable debug signing.** CI debug APKs must be signed with one persistent debug
   key so updates install over each other without data loss. Generate a dedicated debug
   keystore, store it base64-encoded in a GitHub Actions secret (`DEBUG_KEYSTORE_B64`
   plus password secrets), decode it in CI, and use it for `debug`. Never commit any
   keystore. Document the one-time secret setup for the maintainer in
   `docs/RELEASING.md`; if the secret is absent, CI falls back to the default debug key
   and prints a loud warning in the job summary.
2. **Alarm Back button.** Back on the full-screen alarm must stop the ringing and leave a
   normal (silent) reminder notification with Done / Snooze / Skip.
3. **Server-restore gap.** The phone remembers the highest server cursor its pushes
   reached and detects a restore that lands between its last pull and later pushes, then
   re-sends. Integration test required.
4. **Background crash paths.** Alarm-screen actions, widget-dialog actions and the sync
   queue count must catch failures and fail quietly with a calm message or log, never
   crash. Add tests that inject failures.
5. **Rotation.** Open dialogs and sheets (capture, next step, track, swap) survive
   rotation and process death with their typed text.
6. **LAN testing.** Debug builds allow cleartext HTTP so the maintainer can pair with a
   server on the local network; release builds require HTTPS. Show the active mode in
   Settings → Sync.

## Phase B — Essentials the app is missing

Compared against TickTick, Todoist, Structured, Tiimo, Llama Life, Amazing Marvin and
Goblin Tools, these are the only gaps that matter for this product. Do not add
timelines, Kanban, Eisenhower views, routines libraries, mascots, or auto-rescheduling.

### B1. Practices (recurring quests)
Many of the user's goals are ongoing practices (singing, drawing, study) that never
"finish". Add a quest kind `PRACTICE`:
- Created from capture or Board like any quest; the user picks a rhythm:
  daily, chosen weekdays, or N times per week.
- A tracked practice always shows its next session as its step (text editable, e.g.
  "20 min scales"). Done logs a session (+15 XP) and the next session appears on the
  next due day. Practices never complete; the user can park or drop them.
- Practices count against the slot cap (one thing visible still holds).
- **Missed sessions never pile up.** No backlog, no overdue count. The card shows
  "this week 2/4" as informational feedback only.
- Reminders, digest, widget, calendar and vault export all understand practices.
- MCP: `list_quests` / `get_quest` expose kind and rhythm; add
  `propose_add_practice(title, rhythm, firstStep, reason)`.

### B2. Undo a completion
- After Done (step, subquest, quest, or practice session), show a 10-second snackbar
  with **Undo**. Undo is a compensating event in `:core` that reverses XP, streak,
  achievements and focus exactly; recomputing from scratch must equal the incremental
  result (property test).
- A notification Done posts a silent "Done · Undo" confirmation that expires after
  5 minutes.
- History shows the completion and its undo; nothing is silently deleted.

### B3. Focus timer (and effort XP)
- A **Focus** button on each Today card and on Quest detail starts a countdown on that
  step: presets 10 / 25 / 50 minutes (default 25, remembered).
- Runs as a foreground service with an ongoing notification (time left, Pause, Stop).
  Full-screen focus view: the step text, the time left, nothing else.
- At the end: gentle chime + "Did you finish the step?" → Done (normal flow, including
  "What's next?"), **Keep going** (another block), or **Not yet** (no judgment).
- **Effort XP:** +5 XP per completed block of ≥10 minutes, capped at 60 XP per day so it
  can't be farmed. Stopping early credits nothing and says nothing negative.
- Event `focus.block_completed` (questId, stepId, minutes). Lifetime focus minutes on
  Trophies. Add achievements: first focus block; 10 hours focused.

### B4. "Stuck?" nudge (stall detection without guilt)
- A tracked quest whose step hasn't been touched for 3 days (setting: 2–7) shows a small
  neutral **Stuck?** chip on its card. No red, no counts, no "overdue".
- Tapping it offers: **Shrink** (rewrite as a 2-minute version), **Focus 10 min**,
  **Park it**, or **Ask Claude to break it down** (B5).
- The morning digest mentions at most one stuck quest.

### B5. Ask Claude / ChatGPT to break a quest down
- On Quest detail and the Stuck sheet: **Ask AI to break this down** opens the Android
  share sheet with a ready prompt containing the quest title, why, obstacle, current
  step and subquests, asking for 3–7 small concrete subquests, the first doable in under
  10 minutes.
- If a sync server is paired, the prompt also tells the assistant to submit them with
  `propose_add_subquest` so they arrive in Proposals; otherwise the user pastes the reply
  into capture, where each line becomes an Inbox item.
- No API keys, no in-app AI calls.

## Phase C — The focus layer (research-backed)

### C1. Reset button
A **Reset** button in the Today header opens a sheet with three choices:
- **Breathe:** guided cyclic sighing — two inhales, one long exhale — for 1, 3 or
  5 minutes. A slow expanding/contracting shape and haptic cues; works with the screen
  off via haptics. No XP (rewards stay tied to work). Copy: one line on why it works.
- **Shrink:** pick a tracked quest and rewrite its step as a 2-minute version, with the
  current step shown above for reference.
- **Fresh start:** "Clean slate. Nothing you missed carries forward." Clears snoozes and
  past due times on tracked steps (they become undated), optionally parks all but the one
  quest the user picks, and logs `reset.fresh_start`. XP, level, achievements and best
  streak are untouched.

### C2. Automatic fresh-start moments
On the first open after 2+ days away, and on the first open of a week (Monday) or month,
the resume card offers **Fresh start** alongside "pick up where you left off". Respect
the user's dismissals: if dismissed twice in a row, stop offering it for 30 days.

### C3. When/where cues (if-then plans)
- Steps get an optional `cue` ("after lunch, at my desk"). The "What's next?" sheet asks
  **When/where?** with quick chips (a time, "after ___", "at ___") and a free-text field.
  A time cue sets `dueAt` as today; a text-only cue just displays.
- Cards show the cue under the step. Calendar and digest show it too.
- MCP: cue readable and settable via `propose_set_next_step` / `propose_schedule_step`.

### C4. Obstacle and plan (mental contrasting)
- Quests get two optional fields on Quest detail: **What's in the way?** and
  **If that happens, I will…**. Shown collapsed under "Why it matters".
- Included in the vault export, the AI breakdown prompt (B5), and MCP `get_quest`.

### C5. Forgiveness after a miss
- When a day passes with no step done, the next open shows one calm line on Today, e.g.
  "Yesterday didn't happen. That's normal — one missed day barely affects a habit.
  What's one small step now?" with **Set step** / **Dismiss**. Rotate between 3–4
  variants; never mention streak loss.
- If the streak breaks, Trophies keeps and highlights the best streak.
- Write all copy to a single strings file section so it can be reviewed in one place.
  Banned words in user-facing copy: overdue, failed, missed (except in C5's line),
  lazy, behind, again.

## Phase D — Polish, docs, and review

- Update `README.md` (features, screenshots placeholders), `docs/MCP_TOOLS.md`,
  `docs/SPEC.md` (append a v1.1 section), and `docs/DEVICE_TEST.md` with checks for
  every new feature: signing (install update over previous build without data loss),
  focus timer with screen off, Breathe haptics, practice reminders on weekdays, Undo from
  a notification, LAN pairing over HTTP in debug.
- Accessibility: TalkBack labels on Reset, Focus, Stuck, Breathe; Breathe must not rely
  on animation alone (haptics + text count).

## Critique mechanism and acceptance highlights

After each phase: build, test and lint green → self-check against the acceptance list →
an independent reviewer subagent that has not seen the work grades each item PASS/FAIL
with evidence → fix every FAIL and high-severity issue → up to 3 rounds → log to
`docs/REVIEW.md`. Finish with one fresh full review across all phases.

- A CI debug APK installs over the previous CI debug APK with data intact (when the
  secret is set).
- Undo of any completion restores exactly the prior XP, streak, achievements and focus;
  a fold from scratch matches.
- Practices never create a backlog; missing a week of a practice changes nothing but
  "this week x/N".
- The slot cap still can't be exceeded by any path, including practices, Fresh start,
  imports, sync and MCP.
- No MCP tool mutates state without a user acceptance event (including the new
  practice, cue and obstacle fields).
- Effort XP can't exceed 60/day; stopping a focus block early grants nothing and shows no
  negative copy.
- No banned word appears in user-facing strings (add a unit test that scans them).
- Breathe works with the screen off; the focus timer survives app backgrounding and
  doze.
- Every Phase A known issue is closed with a test or a DEVICE_TEST entry.
