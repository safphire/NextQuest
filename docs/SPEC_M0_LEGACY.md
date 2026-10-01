# NextQuest v1 — Native Android Spec

Personal quest log for capture → focus → completion. Successor to the web v0
(React artifact). Single user, local-first, Obsidian as the knowledge layer.

---

## 1. Product model

**Everything is a quest. Nothing gets dropped.**

- Capturing a thought creates an **untracked quest** on the Board.
- **Tracking** a quest moves it into the Tracked view. Hard cap: **5 tracked**
  root quests (configurable 3–5). To track a new one, finish or untrack one.
- Untracking is the default "not now" action. Deleting exists but is rare and
  requires confirm.

**Subquests**

- A quest may have subquests (UI supports depth 2: quest → subquests; DB
  allows deeper via `parentId`, UI v1 does not).
- Example: *Diarytale* → improve prompts, approve memory design, build memory
  architecture, code it.
- Parent quest shows progress: `3/7 subquests done` + progress bar.
- Subquests exist on tracked and untracked quests alike.

**The next-step invariant (do not lose this)**

- Every **tracked** quest surfaces exactly **one** current next step at the
  top level of its card, regardless of hierarchy.
- If the quest has subquests, the next step belongs to one designated
  **focused subquest** (user-pickable; defaults to first incomplete).
- A quest/subquest with no next step shows the amber "define the next step"
  state. A quest isn't real until it has a next step.

**Rewards (event-driven)**

| Event              | XP   |
|--------------------|------|
| Step done          | +15  |
| Subquest complete  | +40  |
| Quest complete     | +100 |

- Levels: level n→n+1 requires `100 + 50·(n−1)` XP (same curve as v0).
- Streak: any step/completion counts for the day; streak guard notification
  (see §5). Track current + best.
- Achievements: port the v0 set (first step, 10/50 steps, first/5 quests,
  3/7-day streak, level 5); add: first subquest tree, 10 subquests done.
- Chronicle: completed quests list with steps + date.

---

## 2. Data model (Room / SQLite)

```
Quest
  id            UUID PK
  parentId      UUID nullable (null = root)
  title         text
  nextStep      text nullable
  notes         text (markdown)
  status        enum { OPEN, DONE }
  tracked       bool (meaningful on roots only)
  focusedChild  UUID nullable (roots only)
  orderIndex    int
  createdAt / completedAt / touchedAt   timestamps

EventLog                      -- source of truth for stats
  id, questId, type { CREATED, STEP_DONE, SUBQUEST_DONE, QUEST_DONE,
  TRACKED, UNTRACKED }, payload (e.g. the step text), at

Derived (computed or cached): xp, level, streak, achievements, chronicle.
Settings (DataStore): trackedCap, vaultUri, notifTimes, aiProvider, aiKey.
```

`touchedAt` updates on any interaction → powers Board sort. Board needs
**search + sort (recently touched / created / A-Z)** from M0.

Optional: JSON import screen to seed from the web v0 state dump.

---

## 3. Screens

1. **Tracked** (home): capped quest cards — title, progress bar if subquests,
   NEXT + big Done button, expandable subquest checklist + notes.
2. **Board**: all untracked quests, search bar, sort toggle. Track / open /
   add-subquest actions.
3. **Quest detail**: notes editor, subquest list (add, reorder, complete,
   set focused), event log tail, Send to Obsidian, track/untrack, delete.
4. **Trophies**: achievements grid + chronicle + lifetime stats.
5. **Settings**: tracked cap, vault folder picker, notification times,
   AI provider/base-url/key, reset.

Keep the v0 visual identity: dark warm ink `#0e0c09`, gold `#e6b455`,
Chakra Petch display / IBM Plex Sans body / IBM Plex Mono meta, slot pips,
gold Done button as the loot moment, XP toasts.

---

## 4. Obsidian mirror (one-way, app → vault)

- User picks vault folder once via SAF (`ACTION_OPEN_DOCUMENT_TREE`,
  persisted permission). All notes live under `<vault>/NextQuest/`.
- Filename = sanitized quest title (`.md`), collision → append short id.
- **Mirror on demand** (per-quest button) **and** optional auto-mirror on
  quest/subquest completion. Full re-export action in Settings.
- v1 is one-way. Two-way sync (reading edits back) is v2 — real conflict
  problem, out of scope now.

**Note format** (Dataview-friendly, graph-visible relations):

```markdown
---
type: quest
status: open          # open | done
tracked: true
parent: "[[Diarytale]]"        # subquest notes only
created: 2026-07-23
completed:
tags: [sidequest]
---

# Build memory architecture

next-step:: Draft the declarative vs procedural layer split

## Notes
(freeform markdown from the app)

## Subquests
- [x] [[Approve memory design]]
- [ ] [[Improve prompts]]
- [ ] Code it            # plain item if no child note exists

## Log
- 2026-07-23 · step done — "Sketched layer diagram"
- 2026-07-22 · created
```

Rules:
- Parent → children as wikilinked checklist; child → parent via `parent`
  property. Both directions appear in graph/backlinks.
- A subquest gets its **own note** only when it has notes or its own
  subquests; otherwise it stays a checklist line in the parent.
- Weekly review note (see §6) links every quest touched that week.

---

## 5. Notifications (local, no server)

- **Daily quest call** — user-set time (default 08:00): tracked quests +
  their next steps + streak. Tap → Tracked screen.
- **Streak guard** — evening (default 21:00), only if no step done today:
  "Streak at risk — one step keeps it."
- **Per-quest reminder** — optional date/time on a quest.
- Implementation: WorkManager periodic for daily digests; AlarmManager
  `setExactAndAllowWhileIdle` + `SCHEDULE_EXACT_ALARM` permission for
  per-quest reminders; `POST_NOTIFICATIONS` runtime permission (API 33+);
  separate channels (digest / streak / reminders) so each is mutable.
- Reschedule everything on `BOOT_COMPLETED`.

---

## 6. AI assist (optional layer, offline-safe)

Provider-agnostic client: Anthropic Messages API by default, or any
OpenAI-compatible base URL (points at a self-hosted endpoint on the LAN just
as well). Key + base URL in Settings (EncryptedSharedPreferences). Every
feature is a button, never automatic; app fully works with AI off.

1. **Break it down**: quest title + notes → proposed subquest list →
   user edits/confirms before insert.
2. **Suggest next step**: quest context (title, notes, recent log, subquests)
   → one concrete next step → user accepts/edits.
3. **Weekly review**: Sunday evening job summarizes the week's EventLog →
   markdown note `NextQuest/Reviews/2026-W30.md` in the vault, wikilinked to
   touched quests.

Prompting note: responses must be JSON (subquest arrays) — parse defensively.

---

## 7. Stack

- **Kotlin 2.x + Jetpack Compose (Material 3, dark-only custom theme)**
- Room (DB) · DataStore (settings) · WorkManager + AlarmManager (notifs)
- SAF/DocumentFile (vault writes) · kotlinx-serialization · Ktor or OkHttp
  (AI client)
- minSdk 26, targetSdk current. Single module. No backend, no accounts.
- Sideload via APK; Play Store optional later.

Rationale: Java → Kotlin is a short hop; native gives first-class
notifications + vault file access, which are the two features that killed
the web version. (Fallback if speed ever trumps learning: Capacitor wrap of
v0 — weaker SAF/background story, not recommended.)

Future-proofing: keep domain logic (XP, streak, next-step invariant,
snapshot codec) in a pure Kotlin `core` package with zero Android imports —
KMP-ready if iOS ever calls, mechanical to port anywhere else. The versioned
JSON snapshot + vault markdown are public contracts: any future
implementation that reads them inherits every user's data.

---

## 8. Milestones

- **M0 — Loop**: scaffold, Room, capture → Board → track (cap 5), next-step
  invariant, Done +XP, level bar, toasts. *Usable daily from here.*
- **M1 — Depth**: subquests + focused child + progress bars, streak,
  achievements, chronicle, search/sort, v0 JSON import.
- **M2 — Vault**: SAF picker + markdown mirror per §4.
- **M3 — Notifications**: §5 complete, incl. boot reschedule.
- **M4 — AI**: §6 features + weekly review job.

Rough effort with Claude Code: M0–M1 ≈ two weekends, M2/M3 ≈ a day each,
M4 ≈ a day+. Order is deliberate: the loop must be in daily use before the
integrations earn their keep.

## 9. Data durability (upgrades & device loss)

App updates never touch app data by themselves — Android preserves internal
storage across upgrades. The real risks are a bad schema migration, an
uninstall, or a lost device. Layered defense:

1. **Migrations, disciplined.** Room `exportSchema = true`, commit
   `schemas/*.json` to the repo. Auto-migrations for additive changes,
   hand-written `Migration(n, n+1)` for renames/splits. One
   `MigrationTestHelper` test per migration — every historical schema must
   open green before release. `fallbackToDestructiveMigration()` is banned;
   it silently wipes the DB.
2. **Pre-flight snapshot.** On first launch after a `versionCode` change,
   copy the raw `.db` file aside *before* Room opens it with the new schema.
   If a migration crashes, the previous file is intact.
3. **JSON snapshots to the vault.** EventLog + Quests is the complete truth,
   so one JSON dump is a full backup. Weekly WorkManager job plus a
   "Back up now" button → `NextQuest/.backups/nextquest-YYYY-MM-DD.json`,
   rotate last 8. Whatever syncs the vault (Syncthing / Obsidian Sync / git)
   becomes off-device backup for free. Embed `schemaVersion` in the JSON so
   old exports import cleanly into future app versions.
4. **Android Auto Backup.** `dataExtractionRules` covering DB + DataStore
   (≤25 MB, Google Drive, restores on reinstall or new device). Exclude the
   encrypted key prefs — Keystore keys are device-bound and restore as
   garbage; the user re-enters the API key once on a new device.
5. **Restore path.** Settings → Restore: pick a snapshot JSON, wipe, replay.
   The markdown mirror doubles as a human-readable last resort.

Timing: 1–2 from M0, Auto Backup manifest rules day one, 3 + 5 land with M2
(they need SAF vault access).

## Non-goals (v1)

Two-way Obsidian sync · iOS · cloud sync/accounts · collaboration ·
UI nesting beyond depth 2 · calendar views · time tracking.
