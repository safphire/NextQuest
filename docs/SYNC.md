# Sync and merge rules

Sync is optional (principle 7). Without a server the app is a single replica
and everything below still applies to imports.

## What is exchanged

Only events. A device pushes the events it created and pulls everyone else's
(including `proposal.created` events written by the server on behalf of AI
clients). Pushing is idempotent by event id: the server stores each id once.

## How replicas converge

1. Every replica keeps the full set of events it has seen.
2. State = the fold of that set in **canonical order** `(ts, deviceId, seq)`,
   ties broken by `id`.
3. The fold is deterministic and validates every event against the state it
   is applied to. An event that would break an invariant is skipped — on every
   replica alike — and recorded with a reason.

So two replicas with the same set of events have the same state, regardless
of the order in which the events arrived. When an event arrives that sorts
before events already applied, the replica refolds from scratch (cheap: the
log of a personal quest list is small).

`PropertyTest.randomMultiDeviceHistories_holdEveryInvariant` checks this on
random histories from up to three devices with skewed clocks, random sync
points, duplicate deliveries and deliberately invalid "rogue" events: every
replica and every shuffle of the log fold to the same state.

## Timestamps

Each device stamps events with a hybrid logical clock: `ts = max(wall clock,
last ts + 1)`, and receiving an event bumps the device's last ts. A device's
own events therefore stay in the order it wrote them, and a local edit always
sorts after the remote events it was made on top of — even when a peer's clock
is far ahead (D-112). Streaks never depend on `ts`: each completion carries the
completing device's local day (D-108).

## Concurrent edits — what wins

| Situation | Result |
|---|---|
| Two devices each track a quest into the last free slot | The earlier event (canonical order) is applied; the other is skipped and that quest stays in its previous list. |
| Two devices set a different next step for the same quest | Both are applied in order; the later one is the current step, the earlier one is kept as superseded history. |
| Both devices complete the same step | The first completion counts (+15 XP once); the second is skipped. |
| A device completes a step another device already replaced | The completion still counts (no lost XP); the newer step stays current. |
| One device lowers the cap while another tracks a quest | Whichever comes first wins; lowering the cap below the tracked count is skipped. |
| A device edits a quest another device completed | Edits to title/why still apply; new steps on a finished quest are skipped. |
| A device reuses a sequence number for a different event (e.g. a restored backup on a second phone) | The first event keeps the number; the other is skipped. Device ids are excluded from Android backup, so a restored install gets its own id. |
| A user accepts the same proposal on two devices | Ids created by an acceptance are derived from the proposal id, so the second acceptance's events collide ("already exists" / "already done" / "already decided") and are skipped: nothing is created twice. If one device rejected and the other accepted, the first decision sets the proposal's status, and the accepting device's domain events still apply (the user did accept there). |

Skipped events stay in the log (they are facts about what a device tried) and
are listed in the import/sync report; they never change state.
