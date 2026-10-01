# Decisions

Where the brief (docs/SPEC.md) was ambiguous or could not be followed
literally, the decision and its reason are recorded here. Each entry names the
product principle (§1) it serves where one applies.

## P0 — Reset

### D-001 · Where v1 lives
The build session was started from an unrelated repository; the brief
clearly targets this one (it names the M0 branch that exists only here). v1 is
built here on branch `v1`; nothing was changed anywhere else.

### D-002 · Fresh application id, no M0 data migration
The brief mandates the package id `dev.dreamweave.quest`. M0 used
`com.nextquest`, so on a device the two are different apps and M0 data cannot
be migrated in place. M0 was never released, so v1 starts at Room schema
version 1 with no migration from the M0 schema. All M0 source was replaced;
reused: Gradle wrapper (8.14.3), version-catalog layout, Android SDK pins,
CI shape (unit tests + debug APK + committed-schema check), backup rules.
M0's spec is kept as `docs/SPEC_M0_LEGACY.md`.

### D-003 · Android modules are verified in CI, not in the dev container
The cloud container used to write v1 has an allowlisted network that does not
include Google's Maven repository (`dl.google.com`), so AGP and AndroidX cannot
be resolved there. Consequences:
- `:core`, `:protocol` and `:server` are built and tested locally
  (`./gradlew -Pquest.jvmOnly=true …`, a flag that drops the Android modules
  and the Android Gradle plugin — also useful to contributors without the SDK).
- `:data` and `:app` are compiled, linted and tested by GitHub Actions on every
  push; CI is the source of truth for them.

### D-004 · Toolchain stays Kotlin 2.1 / AGP 8.7.3
M0's verified Android toolchain (Kotlin 2.1.0, KSP 2.1.0-1.0.29, AGP 8.7.3,
Room 2.6.1, Compose BOM 2024.12.01, compileSdk 35) is kept. The newest MCP
Kotlin SDK (0.15) is built against Kotlin 2.4 / Ktor 3.5 and would force a
simultaneous bump of every Android tool that cannot be verified locally
(D-003). How MCP is implemented is recorded in the P5 decisions.

### D-005 · License text and fonts
- `LICENSE` is the AGPL-3.0-or-later text from the SPDX license list
  (paragraph-reflowed; word-for-word identical to the FSF text).
- **IBM Plex Mono** declares the Reserved Font Name "Plex", so it ships
  unmodified: IBM's own complete (unsubsetted) WOFF 1.0 files from the
  `@ibm/plex-mono` 2.5.0 npm package, unwrapped to TTF (a lossless container
  change; every font table is byte-identical to IBM's, verified with
  fontTools), with IBM's `LICENSE.txt`.
- **Bricolage Grotesque** and **Manrope** declare no Reserved Font Name. They
  come from the Fontsource npm packages (OFL-1.1) as WOFF; the latin and
  latin-ext subsets are merged into one TTF per weight. Glyphs outside those
  subsets fall back to the system font.
- All three OFL licenses ship in `app/src/main/assets/licenses/` and are shown
  in Settings → About.

### D-006 · Product name in exactly two places
`app/src/main/res/values/strings.xml` (`app_name`) for the app and the
`APP_NAME` setting (default "NextQuest") for the server. Everything else —
docs aside — references those. Code, packages, Gradle project names, Docker
image and database names use the neutral `quest` / `dreamweave` naming.

## P1 — Core

### D-101 · Widget Done may also leave "Needs next step"
Principle 4 names only notification Done, but §5 requires tap-to-done on the
home-screen widget, which cannot show the "What's next?" sheet either. Both
use the same exception (`StepVia.NOTIFICATION` / `WIDGET`); the quest shows
the dashed "Set step" card until the user sets one. App and proposal Done
must always provide the next step (or finish the quest).

### D-102 · Atomic events carry the next step
"Done → What's next?" is one `step.completed` event with an optional `next`
step (and `finishQuest`). `quest.completed`, `quest.status_set` and
`quest.focus_set` carry an optional `next` too. Because every event is
atomic, the fold checks the next-step invariant after *every* event — the
same check guards UI writes, imports, sync merges and accepted proposals.

### D-103 · Invalid events are skipped deterministically, never "fixed"
The fold is a pure function of the ordered log. An event that would break an
invariant (cap, next step, actor rules) or references something missing is
skipped with a recorded reason, identically on every replica. Local writes are
validated as an all-or-nothing commit *before* they are stored
(`Commits.validate`), so the UI never writes an event that would be skipped.
For concurrent offline edits this means "first in canonical order wins": two
devices tracking a fifth quest each → the earlier one is tracked, the other
stays in its previous list (tests: `FoldTest.cap_concurrentTracks…`,
`PropertyTest`).

### D-104 · Hybrid-clock timestamps
Event `ts` is never earlier than the wall clock, strictly increasing per
device, and later than any event the device has already seen. A device's own
commit therefore can't be reordered by its clock jumping back, and a local
edit always sorts after the remote state it was made on.

### D-105 · Focus rules
- Adding a subquest never moves focus: auto-focusing a new, stepless subquest
  would hide the quest's visible step.
- Focus auto-advances only when the focused subquest is finished or dropped
  (to the next open subquest by order, wrapping to the first; otherwise back
  to the quest's own step). Completing a *step* of the focused subquest
  doesn't move focus unless the user also finishes the subquest.
- The user can focus any open subquest (or the quest itself) at any time,
  supplying a next step if the target has none.

### D-106 · No lost XP in edge cases
A step replaced by a newer one ("superseded") can still be completed and
earns its +15 XP; completing an already-done step is ignored (no double XP
after concurrent completion on two devices). Parking, dropping or
re-tracking never removes XP.

### D-107 · The slot cap is an event, not a device setting
The cap is part of the synced state (`settings.slot_cap_set`), so every
replica enforces the same cap. Lowering it below the number of tracked
quests is refused ("park quests first").

### D-108 · Streak details
A day counts when ≥1 step is completed, in the device's time zone (the
server uses its `TZ` setting). A one-day gap is forgiven when the first step
of the next active day is before 12:00. The displayed current streak stays
alive through "today", "tomorrow", and the grace morning after a missed day,
so nobody sees a zero before they had a chance to act (principle 6).

### D-109 · Counting rules for achievements
"Inbox items sorted" counts moves out of the inbox (to Tracked, Later or
Dropped). "Quests" are top-level quests; subquests count separately.
"First accepted proposal" counts accepted and edited-then-accepted proposals.

### D-110 · AI tracking needs a free slot or a pending park
`propose_add_quest` with `track=true` is refused unless a slot is free or a
`propose_park_quest` for a tracked quest is pending and not already claimed by
another tracking proposal. On accept, the user still chooses Track (with a
swap dialog if needed) or To Inbox; the fold enforces the hard cap.
