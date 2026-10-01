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
