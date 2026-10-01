# Architecture

```
            ┌──────────────── :app (Compose, notifications, widget, vault) ───────────────┐
            │                                                                              │
            │   :data  — Room event store · repositories · DataStore settings · sync client │
            └────────────────────────────────┬─────────────────────────────────────────────┘
                                             │ events (JSON)
 :protocol — wire DTOs + JSON Schema ────────┤
                                             │ POST/GET /v1/events
            ┌────────────────────────────────┴─────────────────────────────────────────────┐
            │  :server — Ktor · SQLite · sync API · OAuth 2.1 · MCP endpoint (/mcp)         │
            └────────────────────────────────▲─────────────────────────────────────────────┘
                                             │ MCP tools (read + propose_*)
                                     Claude / ChatGPT
          :core — pure Kotlin: events · fold · invariants · game rules · proposals
          (used by :data, :app and :server, so every party computes the same state)
```

## Modules

| Module | Responsibility | Depends on |
|---|---|---|
| `:core` | Event envelope and payloads, the fold (events → state), invariants, XP/levels/streak/achievements, proposal validation and acceptance, read models (Today, Board, Calendar, Chronicle, stats). Pure Kotlin, no Android. | kotlinx.serialization, kotlinx.datetime |
| `:protocol` | DTOs that cross the network: sync push/pull, pairing, MCP tool inputs/outputs. Emits JSON Schema to `/schema`. | `:core` |
| `:data` | Room event store, the in-memory folder kept in step with it, repositories used by the UI, DataStore settings, WorkManager sync client. | `:core`, `:protocol` |
| `:app` | Jetpack Compose UI, notifications and exact alarms, Glance widget, share target, vault export. | `:data` |
| `:server` | Sync hub (SQLite), pairing, OAuth 2.1 authorization server, MCP Streamable HTTP endpoint. | `:core`, `:protocol` |

## Event sourcing

The only stored truth is an append-only log of events:

```json
{ "id": "0199…", "deviceId": "8f3…", "seq": 42, "ts": 1790870000000,
  "actor": "user", "type": "step.completed",
  "payload": { "stepId": "0199…", "via": "app", "next": { "stepId": "0199…", "questId": "0199…", "text": "Draft chapter 2" } } }
```

- `id` — UUIDv7. `seq` — per-device counter. `ts` — hybrid clock (never before
  the wall clock, strictly increasing per device, after anything the device has
  seen). `actor` — `user` or `mcp:<client>`.
- **Canonical order** is `(ts, deviceId, seq)`, then `id`. Every replica folds
  the same events in the same order with the same rules, so every replica
  computes the same state (see [SYNC.md](SYNC.md)).
- XP, level, streak, achievements, per-quest history and the Chronicle are
  derived by the fold; none of them is stored.

### Event catalog

| Type | Payload | Rules (an event breaking them is skipped) |
|---|---|---|
| `quest.created` | `questId, title, why?, parentId?, status (INBOX\|LATER), order?` | new UUIDv7; non-empty title ≤ 500; parent must be open; subquests start in INBOX. |
| `quest.edited` | `questId, title?, why?, clearWhy` | |
| `quest.status_set` | `questId, status (INBOX\|TRACKED\|LATER\|DROPPED), next?` | not on DONE quests; TRACKED only for top-level quests and only with a free slot; subquests can only be dropped/restored; dropping the focused subquest advances focus. |
| `quest.completed` | `questId, next?` | quest must be open. +100 XP (top-level) or +40 XP (subquest). Finishing the focused subquest advances focus. |
| `quest.focus_set` | `questId, childId?, next?` | top-level, open; child must be an open subquest of it. |
| `quest.reordered` | `questId, order` | |
| `step.set` | `stepId, questId, text, dueAt?, alarm` | quest must be open; replaces (supersedes) the quest's current step. |
| `step.edited` | `stepId, text?, dueAt?, clearDue, alarm?` | only the current step; rescheduling re-arms its reminder. |
| `step.completed` | `stepId, via (app\|notification\|widget\|proposal), finishQuest, next?` | step not already done. +15 XP. |
| `step.snoozed` / `step.reminder_dismissed` | `stepId, until` / `stepId` | step not done. |
| `settings.slot_cap_set` | `cap` | 3–7; not below the current number of tracked quests. |
| `proposal.created` | `proposalId, source, client, op, payload, reason?` | **only** from `mcp:*` actors. |
| `proposal.accepted` / `rejected` / `edited` | `proposalId` (+ `payload` for edited) | only from `user`; proposal must be pending. |

Unknown event types are skipped (and kept in the log), so an older app never
breaks on a newer device's events.

### Invariants enforced by the fold

After every single event:

1. **Cap** — tracked top-level quests ≤ slot cap.
2. **Next step** — every tracked quest has a visible next step, unless the
   event that removed it was a `step.completed` with `via` notification or
   widget (the quest then shows "Needs next step"). A quest already in that
   state may be edited without being fixed, but nothing else can put it there.
3. **Actors** — `mcp:*` actors can only create proposals. Proposals are inert
   data until a `user` event accepts them; acceptance produces ordinary user
   events that pass the same checks.

Each event is applied as a transaction with an undo log: a rejected event leaves
no trace. Local writes are validated as all-or-nothing commits before they are
stored (`Commits.validate`).

### The visible next step

A tracked quest shows exactly one step: the **focused subquest's** current step
when a subquest is focused, otherwise the quest's own current step.
`Completion.plan` decides, for the "What's next?" sheet, which quest receives
the next step after a completion and whether the user must type one.

## Game rules

| | XP |
|---|---|
| Step done | +15 |
| Subquest done | +40 |
| Quest done | +100 |

There are no penalties and XP never decreases.

**Levels.** Level 1 → 2 costs 100 XP; each further level costs 1.25× the
previous (rounded):

| Level | Cost to next | Total to reach |
|---|---|---|
| 1 | 100 | 0 |
| 2 | 125 | 100 |
| 3 | 156 | 225 |
| 4 | 195 | 381 |
| 5 | 244 | 576 |
| 6 | 305 | 820 |
| 10 | 745 | 2,579 |

**Streak.** A day counts when at least one step is done (device time zone).
Missing exactly one day is forgiven if a step is done before noon on the next
day. Current and best are tracked; the incremental tracker and the
from-scratch computation are tested against each other on random histories.

**Achievements.** First step · 10 / 50 steps · first / 5 quests · 3 / 7-day
streak · level 5 · 10 inbox items sorted · first subquest · 10 subquests done ·
first accepted proposal. Each records the timestamp of the event that unlocked it.

## Proposals (AI proposes, the user ratifies)

Ops: `add_quest`, `set_next_step`, `complete_step`, `add_subquest`,
`schedule_step`, `park_quest`.

1. The MCP server validates a proposal against the current folded state
   (`ProposalRules.validate`) and stores it as a `proposal.created` event.
2. The event syncs to the app; the Proposals screen shows a preview
   (`ProposalRules.preview`: op, quest, before → after, staleness).
3. The user's decision becomes events (`ProposalRules.decide`): the resulting
   domain events followed by `proposal.accepted` / `edited` / `rejected`,
   committed through the same validation as any other edit.
