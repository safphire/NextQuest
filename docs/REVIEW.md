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
