# Phase reviews

Each phase ends with a critique gate (docs/SPEC.md §10): build/test/lint green,
a self-check, then an independent reviewer subagent that has not seen the code
being written grades the acceptance items PASS/FAIL with evidence. FAILs and
high-severity issues are fixed and re-reviewed, up to three rounds.

## P0 — Reset

**Round 1** (independent reviewer, diff `164ebcd..101b785`)

| Item | Verdict |
|---|---|
| A1 Old repo audited, reuse, branch `v1` | PASS |
| A2 Module skeleton + stack | PASS |
| A3 CI wiring + green | FAIL — run 36890572784 failed (placeholder test), later steps never ran |
| A4 AGPL, SPDX, CLA, CONTRIBUTING | PASS |
| A5 Rename-ready | PASS |
| A6 No secrets, gitleaks recorded | PASS |
| A7 Funding, issue templates, CoC, DECISIONS | PASS |
| A8 Fonts + OFL | PASS (with issue) |

Issues and resolution:
- HIGH — CI not green → fixed the placeholder test, made instrumented tests
  compile (`kotlin-test`), CI re-run green on all steps (see P1 commit runs).
- MEDIUM — IBM Plex Mono shipped modified (subset merge) under its Reserved
  Font Name with a wrong notice → now IBM's unmodified files + IBM's license (D-005).
- LOW — dead doc links (README principles, ARCHITECTURE) → added; widget
  exception in CLAUDE.md → resolved by D-101 (exception removed in P1);
  SECURITY_SCAN stale/inconsistent → updated; SPDX on non-Kotlin files →
  added; repository name in D-001 → removed; gitleaks image → pinned by digest.

## P1 — Core

**Round 1** (independent reviewer with probe tests, commit `ac7459d`)

| Item | Verdict |
|---|---|
| C1 Event model, UUIDv7 | FAIL (minor) — envelope never validated |
| C2 State only from events | PASS |
| C3 Ordering, concurrency tests, SYNC.md | PASS |
| C4 Domain fields, auto-advance | PASS |
| C5 Game rules | PASS (with issues) |
| C6 Cap never exceeded | PASS |
| C7 Stepless only via notification Done | FAIL — widget was also exempted |
| C8 From scratch == incremental | PASS (weak) |
| C9 MCP can't change state | PASS |
| C10 Proposal validation | FAIL — duplicate park proposals freed two slots |
| C11 ≥90% coverage | PASS (99.5% lines) |
| C12 No Android deps | PASS |

Issues and resolution (all fixed in the follow-up commit):
- HIGH — a peer with a fast clock shifted everyone's step dates and broke
  streaks → completions carry the device's local day (D-108); observed
  timestamps bounded to +5 min (D-112); saturating clock.
- HIGH — duplicate park proposals → refused and counted per quest (D-113).
- MEDIUM — achievements depended on the folding replica's time zone → fixed
  by local stamps; test proves UTC/LA/Tokyo folds agree.
- MEDIUM — `set_next_step` on a quest with a focused subquest previewed the
  wrong step → refused with a pointer to the subquest; preview uses the quest's own step.
- MEDIUM — widget exemption → removed (D-101).
- MEDIUM — envelope not validated → validated (D-111).
- LOW — inbox counter farmable → once per top-level quest; `Ids.derive`
  depended on `kotlin.random` → digest bytes; proposal `client` spoofable →
  must match actor; `decide()` didn't re-validate → it does; achievement
  check reused fold counters → independent `Stats.recount`; pending
  proposals tie-break by id.
- Accepted as known: `Commits.validate` copies the folder per commit (O(state));
  fine for a personal log, revisit if logs grow past ~50k events.
  Completing a superseded step of a finished quest still earns XP (no lost XP, D-106).

**Round 2** (independent reviewer with probe tests, commit `b8dc436`)

| Round-1 item | Verdict |
|---|---|
| Fast clock / overflow | PARTIAL — cap held, but see N1; a far-future stamp still ended the current streak |
| Duplicate parks | FIXED |
| Time-zone dependence | FIXED (UTC/Tokyo/Kiritimati/Pago Pago/LA agree) |
| set_next_step with focused subquest | FIXED |
| Widget exemption | FIXED |
| Envelope validation | FIXED |
| Low items | PARTIAL — `source` still spoofable vs `client` |

New issues and resolution:
- HIGH N1 — the +5 min observe cap broke causality (a local edit on a fast
  peer's quest was skipped on refold) → pure hybrid clock restored (D-112);
  `Commits.validate` refuses local events that would sort before applied ones;
  test `FoldTest.aLocalEditOnAFastPeersQuest_survivesRefold`.
- MEDIUM N2 — future-dated stamps poisoned streak liveness → days after today
  are ignored for the current streak; stamps whose day doesn't match their
  instant fall back to the event time; test
  `GameTest.aDeviceWithAFastClock_cannotBreakAnyonesStreak`.
- LOW — `source` vs `client` → enforced; client names trimmed; accept-time
  filtering by `ProblemCode` instead of message text; stale doc comments fixed.
- Accepted as known: forged stamps can unlock streak achievements (single-user
  data; an untrusted server could forge anything anyway); a device that resets
  its sequence while keeping its id loses those events (device ids are
  excluded from backup, so this needs deliberate tampering).

**Round 3** (independent reviewer with probe tests, commit `16199ba`)

| Item | Verdict |
|---|---|
| N1 causality | FIXED (peer ahead by 10 min / 1 day / 1 year; 20 shuffled refolds each) |
| N2 streak | FIXED |
| Source chip spoof | FIXED |
| Accept-time filtering by code | FIXED |
| Clock overflow / own order | FIXED (saturates, own events stay ordered under ±1 h jitter) |
| ±2 min concurrency, 3 devices × 80 rounds | Converges |

Remaining issues and resolution:
- MEDIUM — a far-future peer still skewed user-visible dates (Chronicle,
  resume card) → ingestion points refuse events > 24 h ahead of their clock
  (`Envelope.tooFarAhead`; enforced in the sync server, sync pull and import).
- MEDIUM — one event at `ts = Long.MAX_VALUE` could block a device's writes
  forever → envelope refuses timestamps beyond 9999-12-31.
- LOW — stored client name untrimmed → trimmed.

P1 closed after three rounds; no open HIGH or MEDIUM items.

## P2 — Data + UI

### Round 1 (independent reviewer, read-only) — CHANGES NEEDED
Verified compliant: product name only in `app_name`; colors only from tokens;
lime only on primary actions; slot cap 3–7; capture autofocus + Enter; FAB on
every route; non-dismissable "What's next?"; resume card; proposals
accept/edit/reject/track/accept-all; backup rules; no content in logs; exported
components limited to launcher, system receiver, widget; single-writer
repository with all-or-nothing commits.

| Finding | Severity | Resolution |
|---|---|---|
| `refold()` before first load leaves the event factory null → every save crashes | major | FIXED in round 1 (pending CI) |
| Cancellation between DB insert and in-memory update desyncs the fold | major | FIXED in round 1 (pending CI) |
| Quest whose focused subquest has no step can't be tracked (step attached to root) | major | FIXED in round 1 (pending CI) |
| Onboarding brain dump lost on configuration change; onboarding toasts dropped | major | FIXED in round 1 (pending CI) |
| Malformed `EXTRA_QUEST_ID` from another app crashes navigation | minor | FIXED in round 1 (pending CI) |
| "+15 XP" shown before anything is saved | minor | FIXED in round 1 (pending CI) |
| Accepting proposals shows no XP / achievement toasts | minor | FIXED in round 1 (pending CI) |
| Undo after dropping from Later restores to Inbox | minor | FIXED in round 1 (pending CI) |
| Touch targets under 48dp (step text, calendar day cells) | minor | FIXED in round 1 (pending CI) |
| Nits: styled share text, pairing link intent-filter + confirmation (P6), dialog state on rotation | nit | Share text and pairing link FIXED; open dialogs on rotation deferred (known issue) |

## P3 — Presence

### Round 1 (independent reviewer, read-only) — CHANGES NEEDED
Verified correct: manifest system-event filters, non-exported receivers and
activities, immutable PendingIntents, goAsync handling, exact-alarm and
full-screen checks with fallbacks, once-only catch-up firing, the next-step
rule on every Done path (only notification Done is exempt).

| Finding | Severity | Resolution |
|---|---|---|
| Commit 5cae1db doesn't compile alone (uses P4 vault classes) | major | Known issue: the P3/P4 app code was split by file after the fact. CI gates run on the pushed head (f345a22 onward), where both are present. |
| Widget shows stale state after an update (state read before `provideContent`) | major | FIXED in round 1 (pending CI) |
| Second alarm while AlarmActivity is open is ignored (no `onNewIntent`) | minor | FIXED in round 1 (pending CI) |
| Step notifications stay after the step changes elsewhere; Done on them gives XP | minor | FIXED in round 1 (pending CI) |
| PendingIntent identity rests on `hashCode` request codes | minor | FIXED in round 1 (pending CI) |
| Default-on digest / streak guard never ask for notification permission | minor | FIXED in round 1 (pending CI) |
| Alarm sounds once | minor | FIXED in round 1 (pending CI) |
| AlarmActivity: one-shot state, no rejection feedback, no Cancel | minor | FIXED in round 1 (pending CI) |
| Nits: Model.kt KDoc, fired markers for superseded steps, receiver exception handler, empty digest, lock-screen writes, DEVICE_TEST.md missing | nit | FIXED in round 1 (pending CI) |

## P4 — Durability

### Round 1 (independent reviewer, read-only) — CHANGES NEEDED

| Finding | Severity | Resolution |
|---|---|---|
| Mirror overwrites a user's own note with a quest's file name | blocker | FIXED in round 1 (pending CI) |
| Mirror deletes user copies of its notes (any file with an app `id:`) | major | FIXED in round 1 (pending CI) |
| Today's snapshot overwritten with a stale or empty log; in-place truncating write | major | FIXED in round 1 (pending CI) |
| `SnapshotCodec.decode` throws on non-primitive header fields → import crash | major | FIXED in round 1 (pending CI) |
| Debounce drops backup requests; daily and mirror workers race | minor | FIXED in round 1 (pending CI) |
| Snapshot blocked by a failing note; revoked folder grant not surfaced | minor | FIXED in round 1 (pending CI) |
| Markdown/Dataview injection through newlines in titles and steps | minor | FIXED in round 1 (pending CI) |
| `sanitize` edge cases: split surrogates, leading dot, reserved names | minor | FIXED in round 1 (pending CI) |
| Import of an own-device event with a future seq blocks local writes; no size cap; fold on Main | minor | FIXED in round 1 (pending CI) |
| Header validation nits | nit | FIXED in round 1 (pending CI) |

## P5 — Server

### Round 1 (independent security reviewer, read-only) — CHANGES NEEDED
Verified correct: parameterized SQL everywhere, HTML escaping of every
interpolation, exact redirect-URI matching, mandatory S256 with constant-time
compare, single-use codes, refresh rotation with family revocation, hashed
secrets, session cookie flags, CSRF tokens, gap-free cursors, non-root image.

| Finding | Severity | Resolution |
|---|---|---|
| No request body size limits (unauthenticated OOM) | major | FIXED in round 1 (pending CI) |
| Open DCR fills the 50-client cap permanently | major | FIXED in round 1 (pending CI) |
| Rate limits keyed on `remoteHost` (reverse DNS; one bucket behind a proxy); unbounded limiter map | major | FIXED in round 1 (pending CI) |
| Consent page can be framed (clickjacking) | major | FIXED in round 1 (pending CI) |
| Open redirect through DCR error redirects | minor | FIXED in round 1 (pending CI) |
| Connector names can impersonate Claude/ChatGPT | minor | FIXED in round 1 (pending CI) |
| Malformed MCP params → HTTP 500 for the whole batch | minor | FIXED in round 1 (pending CI) |
| Not every MCP call logged; call log never pruned | minor | FIXED in round 1 (pending CI) |
| `step_id` not checked against `quest_id` | minor | FIXED in round 1 (pending CI) |
| Proposal validation not atomic with the append | minor | FIXED in round 1 (pending CI) |
| Code-replay race skips revocation | minor | FIXED in round 1 (pending CI) |
| Compose publishes the plain-HTTP port on all interfaces | minor | FIXED in round 1 (pending CI) |
| Nits: token audience binding, constant-time CSRF compare, `next` backslash, logout cookie attributes, raw device name in logs, PushResponse.cursor docs | nit | FIXED in round 1 (pending CI) |

### P5 round 2 (fresh reviewer, head 839bab7) — CHANGES NEEDED
All 12 round-1 findings verified fixed. Server migration 2 upgrades existing
databases correctly; acceptance items (no state change without user
acceptance, cap never exceeded via MCP/sync, compose + suite) PASS.

| Finding | Severity | Resolution |
|---|---|---|
| N1 Unapproved DCR clients unbounded (disk/memory); long redirect URIs; IPv6 keyed per address | major | FIXED: cap of 200 unapproved clients (oldest >10 min evicted, else 429), 2,048-char URIs, IPv6 keyed by /64 |
| N2/N3 `TRUST_PROXY` default leaves one bucket behind the documented proxy; trusted from any peer | minor | FIXED: X-Forwarded-For honoured only from loopback/private peers; compose defaults to 1; docs say 0 for direct exposure |
| N4 Global password ceiling: owner lockout, non-atomic check | minor | FIXED: reserve-then-refund (exactly 100 under concurrency); restart resets it, documented on the page and in D-505 |
| N5 A paired device can occupy a removed device's future sequence numbers | minor | Accepted known limitation (single-user server; all paired devices are the owner's) — D-504 |
| N6 Unicode look-alike vendor names | nit | FIXED |
| N7 Tokens without `resource` not audience-bound | nit | FIXED |
| N8 Purge can drop a client mid-re-authorization | nit | FIXED |
| N9 Only tools/call rate-limited; internal errors not logged | nit | FIXED |
| N10 BIND_ADDRESS/PORT documentation | nit | FIXED |

### P2 + P3 round 2 (fresh reviewer, head 839bab7) — CHANGES NEEDED
All round-1 findings verified fixed except one; acceptance items (never
stepless except notification Done, cap via UI, core loop, 48dp targets,
alarms after reboot) PASS by inspection.

| Finding | Severity | Resolution |
|---|---|---|
| Round-1 cancellation fix ineffective: `withContext(NonCancellable + io)` re-dispatches and can still throw on return, skipping the in-memory update | major | FIXED: post-validation work runs in a dispatcher-free `NonCancellable` block with the IO switch inside; regression test cancels mid-insert on real IO |
| D-302 says permission is asked only when a reminder is first set, but the default-on digest now asks after first launch | minor | FIXED: D-302 records the decision |
| Undo reads the current tab at tap time | nit | FIXED: captured at drop time |
| Back on AlarmActivity leaves the insistent notification ringing | nit | Known issue (Snooze/Done on the notification still stop it) |
| Calendar ripple covers the 2dp gutter | nit | Accepted (cosmetic) |
| No confirmation when accepting a proposal without XP | nit | FIXED: "Accepted." again |

### P4 round 2 (fresh reviewer) — PASS
All ten round-1 findings verified fixed.

| Finding | Severity | Resolution |
|---|---|---|
| An unreadable note looks foreign and forks into a suffixed duplicate | minor | FIXED: the mirror run aborts (and retries later) instead |
| "Change folder" may show the old folder's error until the next run | nit | Accepted |

## P6 — Integration

### Round 1 (independent reviewer) — CHANGES NEEDED
Verified: migration matches the entity; push paging can't loop; pull never
skips past future events; lock order can't deadlock; pulled events marked
pushed; `mcp:` events never pushed; token excluded from backup; pairing links
only pre-fill (https, explicit confirmation). The integration test really
runs pair → push → MCP OAuth + `propose_add_quest` → pull → accept → server
and second phone agree. Acceptance: no state change without acceptance PASS;
cap via sync/MCP PASS by design.

| Finding | Severity | Resolution |
|---|---|---|
| Room schema `2.json` not committed | major | Taken from CI's generated output and committed |
| Restored install keeps server settings but not the token: looks paired, never syncs | major | FIXED: `SyncEngine.reconcile()` at startup forgets the pairing and says "pair again" |
| Reset detection only when pushing; never re-uploads | minor | FIXED: empty push probes the server's latest cursor every round; reset → pull from 0 and mark the log unpushed |
| Non-retryable push error (e.g. 413) blocks pulls forever | minor | FIXED: batches limited by size as well as count; pull runs even when the push failed |
| 401 doesn't actually stop retrying | minor | FIXED: the engine forgets the token and the worker cancels all sync work |
| REPLACE cancels a running sync on every edit | minor | FIXED: KEEP, plus extra push passes at the end of a round |
| Pairing in the UI's coroutine scope | minor | FIXED: app scope, local writes NonCancellable |
| Integration test doesn't cover WorkManager, the cap via sync, future refusals | minor | Known gap (cap via sync is covered by the core property test) |
| Nits: substring match on "future", pair link while paired, `commit()` on main, imports don't trigger sync | nit | Pair link while paired FIXED (ignored with a message); others accepted |

### Round 2 (fresh reviewer, head 5d2e244) — CHANGES NEEDED
All nine round-1 findings verified fixed (schema committed and regenerated in
CI, migration matches the entity, reconcile at startup, reset probe, size
limited batches, pull after a failed push, 401 forgets and cancels, KEEP,
pairing NonCancellable, token excluded from backups).

| Finding | Severity | Resolution |
|---|---|---|
| Reset handling saves cursor 0 before marking the log unpushed; a round cancelled in between never detects the reset again | major | FIXED: unpushed first, then cursor, both NonCancellable |
| A server restored from an older backup and overtaken by other devices' pushes is not detected | minor | FIXED: the phone remembers the id of the event at its cursor and checks it each round; integration test for restored-and-overtaken |
| `return@repeat` doesn't stop push passes; a "future" refusal is counted three times | minor | FIXED: loop breaks when a pass sends nothing; refusals keyed by event id |
| With KEEP, an edit made during the pull waits for the periodic sync | minor | FIXED: one more push after the pull when anything is queued |
| D-601/D-602 don't describe KEEP and the reset rule | nit | FIXED |
| An undecodable reply reads "Can't reach the server" and is retried | nit | FIXED: reported as a version mismatch, not retried |

## P7 — Polish

### Round 1 (independent reviewer, head 5d2e244) — CHANGES NEEDED
Verified: SPDX headers on every source file; CONTRIBUTING requires the CLA;
touch targets and labels; empty states on every screen; MCP_TOOLS.md matches
the tool specs; the release workflow leaks nothing and no-ops without secrets;
DEVICE_TEST covers the spec's acceptance items.

| Finding | Severity | Resolution |
|---|---|---|
| A failed load (or a failed action) crashes the app instead of showing an error state | major | FIXED: "Try again" screen for load failures; failed actions show a calm message |
| `ALLOWED_ORIGINS` isn't passed through by compose | minor | FIXED, plus blank now means the defaults (an empty value would have blocked Claude and ChatGPT); ConfigTest |
| Capture/next-step fields accept text past the 500/2,000 limits, which is then refused after the panel closed | minor | FIXED: capped while typing |
| DEVICE_TEST misses the D-201 panel checks, Android restore and server restore | minor | FIXED |
| Product name literals outside `app_name` / `APP_NAME` | nit | FIXED: one `Config.APP_NAME_DEFAULT` |
| BottomPanel has no pane title for TalkBack | nit | FIXED |
| Unreachable "Complete quest?" branch in What's next | nit | FIXED: removed |
| Release job skips wrapper validation; actions pinned by tag | nit | Wrapper validation added; tag pinning accepted (same as CI) |

### Found by CI on the emulator and Robolectric
| Finding | Resolution |
|---|---|
| Capture sheet stayed on screen after a quick save (API 34 emulator, core-loop test) | FIXED: capture and What's next are a plain bottom dialog (D-201); the test asserts both close |
| A fired step reminder was taken down by a stale snapshot | FIXED: stale cleanup judges against the latest state (e7d2be3) |
| ReminderIntegrityTest failed at random | Root cause: Robolectric keeps notifications in static state, and the previous test's app scope was still refreshing against its own repository. Test apps now stop their background work in @After |
| First-step dialog kept an empty field on the emulator, so Track stayed disabled | FIXED: TextPromptDialog requests focus from inside the dialog's own composition; the core-loop test waits for typed text and enabled buttons. Green on the emulator at 5d88d2e |

## Final verification (fresh reviewer, head 5d88d2e)
**P6 round 3: PASS. P7 round 2: PASS. P2, P3, P5: PASS** (no regressions).
Every P6 round-2 and P7 round-1 fix was verified in code. The reviewer traced the
restored-and-overtaken integration test: it fails before the fix and passes after.

| Finding | Severity | Resolution |
|---|---|---|
| A restore that lands between the last pull and later pushes isn't detected, so those events stay marked pushed | minor | Known issue (next: a pushed-through watermark from `PushResponse.cursor`) |
| Unguarded launches: settings flow on a corrupt DataStore, SyncSection's count, AlarmActivity / WidgetDoneActivity actions | minor | Corrupt DataStore FIXED (`ReplaceFileCorruptionHandler`); the others are a known issue |
| `pair()` saved the token before marking the log unpushed | nit | FIXED: unpushed first |
| Failure message says "nothing was changed" even after a commit | nit | FIXED: neutral wording |
| A restored "finishing" flag for a step that now belongs to a top-level quest | nit | FIXED: ignored for top-level quests |
| `ALLOWED_ORIGINS=" , "` yields an empty allow-list | nit | FIXED: falls back to the defaults; ConfigTest |
