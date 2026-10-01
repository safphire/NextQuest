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
